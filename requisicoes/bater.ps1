<#
    Bate nos endpoints e imprime a evidencia, sem precisar de extensao nenhuma.

    As mesmas chamadas de requisicoes\sugestoes.http, agrupadas por pergunta e
    com a resposta resumida no que importa para aquela pergunta. Serve para
    conferir no terminal e para colar num relato.

        .\requisicoes\bater.ps1                      # todos os cenarios
        .\requisicoes\bater.ps1 -Cenario oferta      # um cenario
        .\requisicoes\bater.ps1 -Lista               # quais existem

    Precisa do app no ar com o perfil 'local':
        $env:JAVA_HOME='<caminho do seu JDK 25>'
        .\mvnw spring-boot:run "-Dspring-boot.run.profiles=local"
#>
[CmdletBinding()]
param(
    [string] $Host_ = 'http://localhost:8080',
    [string] $Solr  = 'http://localhost:8983/solr/sugestoes',
    [string] $Cenario = 'todos',
    [switch] $Lista
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# O PowerShell 5.1 decide a codificacao da resposta HTTP pelo charset do
# Content-Type, e quando ele nao vem — 'application/json' puro, que e o que o
# Spring manda — cai em ISO-8859-1. O resultado e 'emprÃ©stimo' no lugar de
# 'emprestimo' em toda sugestao com acento, o que faz o teste parecer quebrado
# quando quem esta quebrado e o leitor. Decodificamos os bytes a mao em
# LerJson, e ajustamos a saida do console para o acento tambem sair inteiro.
[Console]::OutputEncoding = [Text.Encoding]::UTF8

# ---------------------------------------------------------------------------
# Tokens
#
# As rotas sao protegidas. Os tokens sao gerados aqui, na hora, pelo mesmo
# scripts\gerar-token.ps1 que uma pessoa usaria - nao ha token embutido neste
# arquivo, porque token em arquivo versionado e token que expira e passa a
# quebrar o teste por motivo que nao e o codigo.
#
# Tres chamadores diferentes, porque sao tres permissoes diferentes:
#   cliente  -> sugestao:ler, eventos:escrever
#   jornada  -> ofertas:escrever, ofertas:ler  (so da jornada de credito)
#   operacao -> ofertas:todas                  (qualquer jornada)
# ---------------------------------------------------------------------------
function Token {
    param([string] $Tipo, [string] $ClienteId, [string] $Segmento = 'PF',
          [string] $Relacionamento = 'VAREJO', [string] $Jornada = 'credito')

    $gerador = Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts\gerar-token.ps1'
    $saida = switch ($Tipo) {
        'cliente'  { & $gerador -Silencioso -Tipo cliente -ClienteId $ClienteId -Segmento $Segmento -Relacionamento $Relacionamento }
        'jornada'  { & $gerador -Silencioso -Tipo jornada -Jornada $Jornada }
        'operacao' { & $gerador -Silencioso -Tipo operacao }
    }
    return ($saida | Where-Object { $_ -match '^eyJ' } | Select-Object -First 1)
}

$TokenDeOperacao = $null
function TokenDeOperacao {
    if (-not $script:TokenDeOperacao) { $script:TokenDeOperacao = Token -Tipo operacao }
    return $script:TokenDeOperacao
}

$CENARIOS = @('oferta', 'conta', 'cadastro', 'validacao', 'privacidade', 'entrada', 'latencia')

if ($Lista) {
    Write-Host ''
    Write-Host 'Cenarios disponiveis:' -ForegroundColor Cyan
    foreach ($c in $CENARIOS) { Write-Host "  $c" }
    Write-Host ''
    exit 0
}

# ---------------------------------------------------------------------------
# Plumbing
# ---------------------------------------------------------------------------

function Titulo($texto) {
    Write-Host ''
    Write-Host ('=' * 78) -ForegroundColor DarkGray
    Write-Host "  $texto" -ForegroundColor Cyan
    Write-Host ('=' * 78) -ForegroundColor DarkGray
}

function Passo($texto) {
    Write-Host ''
    Write-Host "-- $texto" -ForegroundColor Yellow
}

function Nota($texto) {
    Write-Host "   $texto" -ForegroundColor DarkGray
}

function Buscar {
    param(
        [string] $Q,
        [string] $ClienteId = 'pf-novo',
        [string] $Segmento = 'PF',
        [string] $Relacionamento = 'VAREJO',
        [int]    $Limite = 0,
        [switch] $SemCliente
    )
    $url = "$Host_/v1/sugestoes?explicar=true&q=" + [uri]::EscapeDataString($Q)
    if ($Limite -gt 0) { $url += "&limite=$Limite" }

    $cab = @{}
    if (-not $SemCliente) {
        # A identidade vai no token, assinada - nao em cabecalho, que e dito
        # pelo cliente e nao provado. Os X-Cliente-* continuam indo para o caso
        # de a seguranca estar desligada; com ela ligada o servico os ignora.
        $t = Token -Tipo cliente -ClienteId $ClienteId -Segmento $Segmento -Relacionamento $Relacionamento
        $cab['Authorization']            = "Bearer $t"
        $cab['X-Cliente-Id']             = $ClienteId
        $cab['X-Cliente-Segmento']       = $Segmento
        $cab['X-Cliente-Relacionamento'] = $Relacionamento
    }
    return (Chamar 'GET' $url $null $cab).Corpo
}

# Le o corpo como UTF-8 a partir dos bytes, e nao da string que o PowerShell
# 5.1 ja decodificou errado. Ver a nota sobre codificacao no topo.
function LerJson($resposta) {
    if (-not $resposta) { return $null }
    try {
        $fluxo = $resposta.RawContentStream
        if ($fluxo -and $fluxo.Length -gt 0) {
            $bytes = New-Object byte[] $fluxo.Length
            [void] $fluxo.Seek(0, 'Begin')
            [void] $fluxo.Read($bytes, 0, $fluxo.Length)
            return ([Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json)
        }
    } catch { }
    return ComoJson $resposta.Content
}

# Devolve um objeto com status, corpo e cabecalhos, inclusive para 4xx. Sem
# isto, cada 422 esperado viraria excecao e o script pararia num cenario que
# esta passando.
#
# Objeto e nao array de tres: multi-assignment de array com $null no meio e
# fragil no PowerShell, porque o array e desenrolado no pipeline e um $null
# desloca todas as variaveis seguintes em silencio.
function Chamar {
    param([string] $Metodo, [string] $Url, $Corpo, [hashtable] $Cabecalhos = @{})

    # Nao se chama $args: $args e variavel automatica do PowerShell.
    $req = @{
        Uri             = $Url
        Method          = $Metodo
        Headers         = $Cabecalhos
        TimeoutSec      = 30
        UseBasicParsing = $true
        ErrorAction     = 'Stop'
    }
    if ($null -ne $Corpo) {
        $req['Body']        = [Text.Encoding]::UTF8.GetBytes(($Corpo | ConvertTo-Json -Depth 8))
        $req['ContentType'] = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @req
        return [pscustomobject] @{
            Status     = [int] $r.StatusCode
            Corpo      = LerJson $r
            Cabecalhos = $r.Headers
        }
    } catch {
        $resp = $_.Exception.Response
        return [pscustomobject] @{
            Status     = if ($resp) { [int] $resp.StatusCode } else { 0 }
            Corpo      = LerCorpoDeErro $_
            Cabecalhos = $null
        }
    }
}

function ComoJson($texto) {
    if (-not $texto) { return $null }
    try { return $texto | ConvertFrom-Json } catch { return $null }
}

# O corpo de uma resposta 4xx.
#
# $_.ErrorDetails.Message seria o caminho obvio e vem VAZIO no PowerShell 5.1
# quando o Content-Type nao e um dos que ele reconhece — e
# 'application/problem+json' nao e. Sem isto, todo 422 aparecia como "status
# 422" sem nenhuma das violacoes, e a API parecia nao estar explicando o que
# recusou quando ela estava explicando perfeitamente.
#
# Lemos o stream da resposta direto, em UTF-8. ErrorDetails fica como segunda
# tentativa para o caso de o stream ja ter sido consumido.
function LerCorpoDeErro($erro) {
    try {
        $resp = $erro.Exception.Response
        if ($resp) {
            $fluxo = $resp.GetResponseStream()
            if ($fluxo) {
                if ($fluxo.CanSeek) { [void] $fluxo.Seek(0, 'Begin') }
                $leitor = New-Object IO.StreamReader($fluxo, [Text.Encoding]::UTF8)
                $texto = $leitor.ReadToEnd()
                $leitor.Dispose()
                if ($texto) { return ComoJson $texto }
            }
        }
    } catch { }
    return ComoJson $erro.ErrorDetails.Message
}

function MostrarLista($resposta) {
    if (-not $resposta.itens -or $resposta.itens.Count -eq 0) {
        Nota '(lista vazia)'
    } else {
        foreach ($i in $resposta.itens) {
            $id = if ($i.ofertaId) { $i.ofertaId } else { '-' }
            Write-Host ("   {0}  {1,-9} {2,-34} {3,-24} r={4}" -f `
                $i.posicao, $i.origem, $i.termo, $id, $i.relevancia)
        }
    }
    if ($resposta.melhorOferta) {
        $m = $resposta.melhorOferta
        Write-Host ("   MELHOR OFERTA -> {0}  produto={1}  jornada={2}  posicao={3}" -f `
            $m.ofertaId, $m.produtoAlvo, $m.jornada, $m.posicao) -ForegroundColor Green
    } else {
        Write-Host '   MELHOR OFERTA -> nenhuma (nao ha oferta elegivel)' -ForegroundColor DarkYellow
    }
    if ($resposta.explicacao) {
        $e = $resposta.explicacao
        Nota ("jornadaLider={0}  perfil={1}  {2} ms  (indice {3} ms, perfil {4} ms)" -f `
            $(if ($e.jornadaLider) { $e.jornadaLider } else { '-' }), $e.perfil, `
            $e.msTotal, $e.msAteIndice, $e.msAtePerfil)
    }
}

function MostrarDescartes($resposta, [string[]] $Motivos) {
    if (-not $resposta.explicacao) { return }
    $d = $resposta.explicacao.descartes
    if (-not $d -or $d.Count -eq 0) { Nota 'sem descartes'; return }
    $filtrados = if ($Motivos) { $d | Where-Object { $Motivos -contains $_.motivo } } else { $d }
    if (-not $filtrados -or $filtrados.Count -eq 0) { Nota 'sem descartes do motivo pedido'; return }
    foreach ($x in $filtrados) {
        Write-Host ("   DESCARTE  {0,-24} {1}" -f $x.motivo, $x.termo) -ForegroundColor Red
    }
}

function SolrConta([string[]] $Fqs) {
    $pares = @('q=*:*', 'rows=0', 'wt=json')
    foreach ($f in $Fqs) { $pares += 'fq=' + [uri]::EscapeDataString($f) }
    $r = Invoke-RestMethod -Method Post -Uri "$Solr/select" `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body ($pares -join '&') -TimeoutSec 20
    return $r.response.numFound
}

# ---------------------------------------------------------------------------
# Cenarios
# ---------------------------------------------------------------------------

function Show-Oferta {
    Titulo 'A busca devolve a melhor OFERTA, identificada?'

    Passo 'q=emprestim  cliente pf-novo'
    $r = Buscar -Q 'emprestim'
    MostrarLista $r

    Passo 'q=emprestim  limite=1'
    Nota 'Deve ser exatamente o item da posicao 0 acima: o teto corta o fim, nao reordena o comeco.'
    $r1 = Buscar -Q 'emprestim' -Limite 1
    MostrarLista $r1

    if ($r.itens.Count -gt 0 -and $r1.itens.Count -eq 1) {
        if ($r.itens[0].termo -eq $r1.itens[0].termo) {
            Write-Host '   OK  limite=1 devolveu o primeiro item de limite=8' -ForegroundColor Green
        } else {
            Write-Host '   FALHA  limite=1 devolveu outro item' -ForegroundColor Red
        }
    }

    Passo 'q=segunda via  (prefixo que nao casa com oferta)'
    Nota 'melhorOferta ausente e informacao, nao erro: nao ha oferta elegivel aqui.'
    MostrarLista (Buscar -Q 'segunda via')
}

function Show-Conta {
    Titulo 'Os dados da conta mudam a resposta'

    $casos = @(
        @{ id = 'pf-novo';             rot = 'conta operante, sem restricao'; q = 'emprestim'; motivos = @() },
        @{ id = 'pf-negativado';       rot = 'conta INADIMPLENTE';            q = 'emprestim'; motivos = @('CONTA_INADIMPLENTE') },
        @{ id = 'pf-bloqueado';        rot = 'conta BLOQUEADA';               q = 'transfer';  motivos = @('CONTA_NAO_OPERANTE') },
        @{ id = 'pf-sem-elegibilidade';rot = 'sem elegibilidade concedida';   q = 'consignad'; motivos = @('SEM_ELEGIBILIDADE') },
        @{ id = 'pf-investidor';       rot = 'produto JA CONTRATADO';         q = 'invest';    motivos = @('PRODUTO_JA_CONTRATADO') }
    )
    foreach ($c in $casos) {
        Passo ("{0}  ({1})  q={2}" -f $c.id, $c.rot, $c.q)
        $r = Buscar -Q $c.q -ClienteId $c.id
        MostrarLista $r
        MostrarDescartes $r $c.motivos
    }

    Passo 'faixa de relacionamento: UNICLASS vs VAREJO, mesmo prefixo'
    Nota 'O corte de faixa e filtro no indice: item de faixa alta nao chega a pontuar.'
    Write-Host '   --- UNICLASS ---'
    MostrarLista (Buscar -Q 'invest' -ClienteId 'pf-uniclass' -Relacionamento 'UNICLASS')
    Write-Host '   --- VAREJO ---'
    MostrarLista (Buscar -Q 'invest' -ClienteId 'pf-novo' -Relacionamento 'VAREJO')

    Passo 'gatilho recente: pf-pix-recusado, q=ant'
    Nota 'Espere origem EVENTO na posicao 0. A cota garante a linha; ela nao disputa por score.'
    MostrarLista (Buscar -Q 'ant' -ClienteId 'pf-pix-recusado')
}

function Show-Cadastro {
    Titulo 'A jornada dona cadastra a propria oferta'

    $oferta = [ordered] @{
        ofertaId           = 'OFR-CREDITO-CONSORCIO'
        termo              = 'consorcio de imovel'
        sinonimos          = @('consorcio', 'carta de credito', 'consorcio imobiliario')
        rotulo             = 'sem juros, com parcelas fixas'
        destino            = '/app/credito/consorcio-imovel'
        jornada            = 'credito'
        produtoAlvo        = 'CONSORCIO_IMOVEL'
        exigeElegibilidade = $false
        vetaInadimplencia  = $true
        exigeContaAtiva    = $true
        gatilhos           = @('simulacao_abandonada_24h')
        segmentos          = @('PF')
        relacionamentos    = @('VAREJO', 'UNICLASS')
        vigenciaInicio     = '2026-10-01T00:00:00Z'
        vigenciaFim        = '2027-10-01T00:00:00Z'
    }

    # A jornada dona. Um token de cliente aqui daria 403: ele nao tem
    # ofertas:escrever. Um token de outra jornada daria 403 tambem, por outro
    # motivo - a oferta nao seria dele.
    $comoJornada = @{ Authorization = "Bearer $(Token -Tipo jornada -Jornada 'credito')" }

    Passo 'POST /v1/ofertas  (primeira vez)'
    $r = Chamar 'POST' "$Host_/v1/ofertas" $oferta $comoJornada
    Write-Host "   status $($r.Status)" -ForegroundColor $(if ($r.Status -eq 201) { 'Green' } else { 'Yellow' })
    if ($r.Cabecalhos -and $r.Cabecalhos['Location']) { Nota "Location: $($r.Cabecalhos['Location'])" }
    if ($r.Corpo) {
        $c = $r.Corpo
        Nota "gravado: termo='$($c.termo)'  ofertaId=$($c.ofertaId)"
        Nota "desempenho: ctr=$($c.desempenho.ctr) adesao=$($c.desempenho.taxaAdesao) k=$($c.desempenho.clientesDistintos)"
        Nota "            $($c.desempenho.fonte)"
    }

    Passo 'POST /v1/ofertas  (de novo, mesmo id)'
    Nota 'Idempotente: 200 e nao 201. Permite reenviar depois de timeout.'
    $r2 = Chamar 'POST' "$Host_/v1/ofertas" $oferta $comoJornada
    Write-Host "   status $($r2.Status)" -ForegroundColor $(if ($r2.Status -eq 200) { 'Green' } else { 'Yellow' })

    Passo 'POST /v1/ofertas com token de CLIENTE (escopo errado)'
    Nota 'ofertas:escrever nao esta no token de cliente.'
    $rc = Chamar 'POST' "$Host_/v1/ofertas" $oferta `
            @{ Authorization = "Bearer $(Token -Tipo cliente -ClienteId 'pf-novo')" }
    Write-Host "   status $($rc.Status) (esperado 403)" -ForegroundColor $(if ($rc.Status -eq 403) { 'Green' } else { 'Red' })

    Passo 'POST /v1/ofertas de credito com token de SEGUROS (jornada alheia)'
    Nota 'O token TEM ofertas:escrever. Quem recusa e a regra de negocio, no caso de uso.'
    $ra = Chamar 'POST' "$Host_/v1/ofertas" $oferta `
            @{ Authorization = "Bearer $(Token -Tipo jornada -Jornada 'seguros')" }
    Write-Host "   status $($ra.Status) (esperado 403)" -ForegroundColor $(if ($ra.Status -eq 403) { 'Green' } else { 'Red' })
    if ($ra.Corpo) {
        Nota "type: $($ra.Corpo.type)"
        Nota "pediu '$($ra.Corpo.jornadaPedida)', e dono de '$($ra.Corpo.jornadaDoChamador)'"
    }

    Passo 'GET /v1/ofertas/OFR-CREDITO-CONSORCIO'
    $r3 = Chamar 'GET' "$Host_/v1/ofertas/OFR-CREDITO-CONSORCIO" $null $comoJornada
    Write-Host "   status $($r3.Status)"
    if ($r3.Corpo) { Nota "termo gravado: '$($r3.Corpo.termo)'  (normalizado)" }

    Passo 'A oferta recem-cadastrada aparece na busca? q=consorc'
    Nota 'Ela tem clientes_distintos=0. Sob o piso antigo seria invisivel para sempre.'
    MostrarLista (Buscar -Q 'consorc')

    Passo 'E some para quem a restricao veta (pf-negativado, vetaInadimplencia=true)'
    $rn = Buscar -Q 'consorc' -ClienteId 'pf-negativado'
    MostrarLista $rn
    MostrarDescartes $rn @('CONTA_INADIMPLENTE')

    Passo 'E some para quem esta fora da faixa declarada (PERSONNALITE)'
    MostrarLista (Buscar -Q 'consorc' -Relacionamento 'PERSONNALITE')

    Passo 'GET /v1/ofertas/OFR-NAO-EXISTE'
    $r4 = Chamar 'GET' "$Host_/v1/ofertas/OFR-NAO-EXISTE" $null $comoJornada
    Write-Host "   status $($r4.Status)" -ForegroundColor $(if ($r4.Status -eq 404) { 'Green' } else { 'Red' })
    if ($r4.Corpo) { Nota "type: $($r4.Corpo.type)" }
}

function Show-Validacao {
    Titulo 'Validacao: todas as violacoes de uma vez'

    $comoJornada = @{ Authorization = "Bearer $(Token -Tipo jornada -Jornada 'credito')" }

    Passo 'POST com oito campos errados'
    $ruim = [ordered] @{
        ofertaId        = 'x'
        termo           = 'oferta de teste'
        destino         = 'https://site-de-phishing.example/pix'
        jornada         = 'busca'
        gatilhos        = @('GATILHO INVALIDO!')
        segmentos       = @('PESSOA_FISICA')
        relacionamentos = @('BLACK')
        vigenciaInicio  = '2027-01-01T00:00:00Z'
        vigenciaFim     = '2026-01-01T00:00:00Z'
    }
    $r = Chamar 'POST' "$Host_/v1/ofertas" $ruim $comoJornada
    Write-Host "   status $($r.Status)" -ForegroundColor $(if ($r.Status -eq 422) { 'Green' } else { 'Red' })
    if ($r.Corpo) {
        Nota "type: $($r.Corpo.type)"
        Nota "detail: $($r.Corpo.detail)"
        foreach ($v in $r.Corpo.violacoes) {
            Write-Host ("   {0,-18} {1,-16} {2}" -f $v.campo, $v.codigo, $v.mensagem) -ForegroundColor Red
        }
        Write-Host "   total: $($r.Corpo.violacoes.Count) violacoes numa resposta" -ForegroundColor Green
    }

    Passo 'Destino absoluto, sozinho'
    Nota 'Aceitar https:// faria do cadastro um redirecionador aberto com o nome do banco na frente.'
    $redir = [ordered] @{
        ofertaId = 'OFR-TESTE-REDIRECT'; termo = 'teste'; destino = '//evil.example/pix'
        jornada = 'credito'; produtoAlvo = 'TESTE'; segmentos = @('PF')
        relacionamentos = @('VAREJO')
        vigenciaInicio = '2026-10-01T00:00:00Z'; vigenciaFim = '2027-10-01T00:00:00Z'
    }
    $rr = Chamar 'POST' "$Host_/v1/ofertas" $redir $comoJornada
    Write-Host "   status $($rr.Status)" -ForegroundColor $(if ($rr.Status -eq 422) { 'Green' } else { 'Red' })
    if ($rr.Corpo) {
        foreach ($v in $rr.Corpo.violacoes) {
            Write-Host ("   {0,-18} {1}" -f $v.campo, $v.mensagem) -ForegroundColor Red
        }
    }

    Passo 'Erro de protocolo: a SEGURANCA responde antes do MVC'
    Nota 'Com anyRequest().denyAll(), metodo nao mapeado e caminho inexistente dao 403'
    Nota 'e nao 405/404 - a cadeia de autorizacao decide antes de o Spring MVC'
    Nota 'descobrir que a rota nao existe. Rota nova nasce FECHADA, e de passagem'
    Nota 'nao se conta a quem sonda quais metodos e caminhos existem.'
    foreach ($caso in @(
        @{ m = 'DELETE'; u = "$Host_/v1/ofertas/OFR-CREDITO-CONSORCIO"; esperado = 403; rot = 'metodo nao mapeado' },
        @{ m = 'GET';    u = "$Host_/v1/nao-existe";                    esperado = 403; rot = 'caminho inexistente' }
    )) {
        $rp = Chamar $caso.m $caso.u $null $comoJornada
        $cor = if ($rp.Status -eq $caso.esperado) { 'Green' } else { 'Red' }
        Write-Host ("   {0,-22} {1} {2} (esperado {3})" -f $caso.rot, $caso.m, $rp.Status, $caso.esperado) -ForegroundColor $cor
    }

    Passo 'Sem credencial nenhuma'
    $rs = Chamar 'GET' "$Host_/v1/sugestoes?q=emprestim" $null
    Write-Host ("   status {0} (esperado 401)" -f $rs.Status) -ForegroundColor $(if ($rs.Status -eq 401) { 'Green' } else { 'Red' })
    if ($rs.Corpo) { Nota "type: $($rs.Corpo.type)" }
}

function Show-Privacidade {
    Titulo 'Privacidade: o piso de k-anonimato e o saneamento de PII'

    $novo   = '(*:* -tipo:consulta) OR clientes_distintos_i:[30 TO *]'
    $antigo = 'clientes_distintos_i:[30 TO *]'

    Passo 'O piso no indice, medido'
    $total     = SolrConta @('*:*')
    $comAntigo = SolrConta @($antigo)
    $comNovo   = SolrConta @($novo)
    $ofertas   = SolrConta @($novo, 'tipo:oferta')
    $fugaram   = SolrConta @($novo, 'tipo:consulta', 'clientes_distintos_i:[* TO 29]')
    $existem   = SolrConta @('tipo:consulta', 'clientes_distintos_i:[* TO 29]')

    Write-Host ("   total no indice                      : {0}" -f $total)
    Write-Host ("   fq ANTIGO (piso para todos os tipos) : {0}" -f $comAntigo)
    Write-Host ("   fq NOVO (piso so para tipo:consulta) : {0}" -f $comNovo)
    Write-Host ("   ofertas visiveis com k=0             : {0}" -f $ofertas) -ForegroundColor Green
    Write-Host ("   termos de log abaixo do piso: existem {0}, passam {1}" -f $existem, $fugaram) `
        -ForegroundColor $(if ($fugaram -eq 0) { 'Green' } else { 'Red' })
    if ($fugaram -ne 0) {
        Write-Host '   VIOLACAO DE PRIVACIDADE: termo abaixo do piso alcancavel.' -ForegroundColor Red
    }

    Passo 'PII barrada na entrada do caminho de escrita'
    Nota 'A resposta diz quantos e por que, e NUNCA devolve o termo barrado.'
    # Array direto, nao { "eventos": [...] }: o endpoint recebe
    # @RequestBody List<EventoRecebido>.
    #
    # Sem virgula a frente. A virgula seria o jeito de impedir o PowerShell de
    # desenrolar um array de UM elemento, mas num array de varios ela embrulha
    # tudo de novo e o ConvertTo-Json produz {"value":[...],"Count":5} — que o
    # Jackson recusa com 400, porque nao e uma lista.
    $corpo = @(
        @{ tipo = 'BUSCA_DIGITADA'; termo = 'pagar boleto 529.982.247-25' },
        @{ tipo = 'BUSCA_DIGITADA'; termo = 'cartao 4539 1488 0343 6467' },
        @{ tipo = 'BUSCA_DIGITADA'; termo = 'pix para joao@exemplo.com' },
        @{ tipo = 'BUSCA_DIGITADA'; termo = 'chave 123e4567-e89b-12d3-a456-426614174000' },
        @{ tipo = 'BUSCA_DIGITADA'; termo = 'emprestimo pessoal' }
    )
    # eventos:escrever esta no token de cliente. A identidade de quem gerou o
    # evento vem do 'sub' do token, nao do corpo: aceitar clienteId no corpo
    # deixaria qualquer chamador escrever comportamento em nome de qualquer
    # pessoa - e como e esse identificador que a agregacao conta para medir
    # clientes distintos, quem controlasse o corpo controlaria o piso de
    # k-anonimato.
    $cab = @{
        'Authorization'            = "Bearer $(Token -Tipo cliente -ClienteId 'pf-novo')"
        'X-Cliente-Id'             = 'pf-novo'
        'X-Cliente-Segmento'       = 'PF'
        'X-Cliente-Relacionamento' = 'VAREJO'
    }
    $re = Chamar 'POST' "$Host_/v1/eventos" $corpo $cab
    Write-Host "   status $($re.Status)"
    if ($re.Corpo) {
        $c = $re.Corpo
        Write-Host ("   aceitos={0}  descartados={1}" -f $c.aceitos, $c.descartados)
        if ($c.motivos) {
            $c.motivos.PSObject.Properties | ForEach-Object {
                Write-Host ("   {0,-28} {1}" -f $_.Name, $_.Value) -ForegroundColor Yellow
            }
        }
        $json = $c | ConvertTo-Json -Depth 6
        if ($json -match '529|4539|joao@|123e4567') {
            Write-Host '   FALHA: a resposta devolveu o termo barrado.' -ForegroundColor Red
        } else {
            Write-Host '   OK: nenhum termo barrado apareceu na resposta.' -ForegroundColor Green
        }
    }
}

function Show-Entrada {
    Titulo 'Parametros de entrada: esta rota nunca devolve erro'

    $casos = @(
        @{ rot = 'prefixo curto demais';   url = "$Host_/v1/sugestoes?q=e" },
        @{ rot = 'q ausente';              url = "$Host_/v1/sugestoes" },
        @{ rot = 'prefixo longo demais';   url = "$Host_/v1/sugestoes?q=" + ('a' * 500) },
        @{ rot = 'limite ilegivel';        url = "$Host_/v1/sugestoes?q=emprestim&limite=tres" },
        @{ rot = 'limite negativo';        url = "$Host_/v1/sugestoes?q=emprestim&limite=-5" },
        @{ rot = 'limite acima do teto';   url = "$Host_/v1/sugestoes?q=emprestim&limite=100000" },
        @{ rot = 'explicar=qualquercoisa'; url = "$Host_/v1/sugestoes?q=emprestim&explicar=qualquercoisa" },
        @{ rot = 'faixa inexistente';      url = "$Host_/v1/sugestoes?q=invest" }
    )
    # A faixa inexistente vai no TOKEN, nao no cabecalho: e la que o servico a
    # le. O cabecalho continua indo e sendo ignorado, de proposito - mostrar que
    # ele nao decide mais nada e metade do ponto deste cenario.
    $cab = @{
        'Authorization'            = "Bearer $(Token -Tipo cliente -ClienteId 'pf-novo' -Relacionamento 'VAREJO')"
        'X-Cliente-Id'             = 'pf-escolhido-por-mim'
        'X-Cliente-Segmento'       = 'PJ'
        'X-Cliente-Relacionamento' = 'PRIVATE'
    }

    $todosOk = $true
    foreach ($c in $casos) {
        $rc = Chamar 'GET' $c.url $null $cab
        $n = if ($rc.Corpo -and $rc.Corpo.itens) { $rc.Corpo.itens.Count } else { 0 }
        $cor = if ($rc.Status -eq 200) { 'Green' } else { 'Red' }
        if ($rc.Status -ne 200) { $todosOk = $false }
        Write-Host ("   {0,-24} status {1}  itens {2}" -f $c.rot, $rc.Status, $n) -ForegroundColor $cor
    }

    Passo 'O cabecalho NAO decide mais nada'
    Nota 'As chamadas acima mandaram X-Cliente-Id=pf-escolhido-por-mim, Segmento=PJ e'
    Nota 'Relacionamento=PRIVATE no cabecalho, e um token de pf-novo/PF/VAREJO.'
    Nota 'Quem vale e o token. Antes da seguranca, o cabecalho escolhia o historico'
    Nota 'de qualquer pessoa e a faixa de qualquer oferta.'
    $rt = Chamar 'GET' "$Host_/v1/sugestoes?q=emprestim&explicar=true" $null $cab
    if ($rt.Corpo -and $rt.Corpo.explicacao) {
        $perfilVisto = $rt.Corpo.explicacao.perfil
        Write-Host ("   perfil resolvido: {0} (do token, nao do cabecalho)" -f $perfilVisto) -ForegroundColor Green
    }

    Passo 'Token de cliente sem sub conhecido: lista popular, degradada'
    $rs = Chamar 'GET' "$Host_/v1/sugestoes?q=emprestim&explicar=true" $null `
            @{ Authorization = "Bearer $(Token -Tipo cliente -ClienteId 'cliente-que-nao-existe')" }
    $n = if ($rs.Corpo -and $rs.Corpo.itens) { $rs.Corpo.itens.Count } else { 0 }
    Write-Host ("   status {0}  itens {1}  degradada={2}" -f $rs.Status, $n, $rs.Corpo.degradada) `
        -ForegroundColor $(if ($rs.Status -eq 200) { 'Green' } else { 'Red' })
    Nota 'degradada=true: o app usa isto para nao registrar a impressao como personalizada.'

    Write-Host ''
    if ($todosOk -and $rs.Status -eq 200) {
        Write-Host '   OK: nenhuma entrada estranha produziu status != 200.' -ForegroundColor Green
    } else {
        Write-Host '   FALHA: alguma entrada produziu erro.' -ForegroundColor Red
    }
}

function Show-Latencia {
    Titulo 'Latencia da busca: percentis, nao media'

    $prefixos = @('em', 'emp', 'empr', 'empre', 'emprest', 'ant', 'cart', 'inve', 'pix', 'transf',
                  'renego', 'consig', 'financ', 'segu', 'limit', 'extrat', 'saldo', 'boleto')
    $clientes = @('pf-novo', 'pf-pix-recusado', 'pf-investidor', 'pf-uniclass', 'pf-negativado')

    # Aquecimento: a primeira chamada paga JIT, abertura de conexao e cache frio
    # do Solr. Mede-la junto reportaria a latencia do primeiro usuario do dia
    # como se fosse a de todos.
    Write-Host '   aquecendo...' -ForegroundColor DarkGray
    foreach ($p in $prefixos) { [void] (Buscar -Q $p) }

    $n = 300
    $tempos = New-Object System.Collections.ArrayList
    Write-Host "   medindo $n requisicoes..." -ForegroundColor DarkGray

    $cronometroTotal = [Diagnostics.Stopwatch]::StartNew()
    for ($i = 0; $i -lt $n; $i++) {
        $q = $prefixos[$i % $prefixos.Count]
        $cliente = $clientes[$i % $clientes.Count]
        $sw = [Diagnostics.Stopwatch]::StartNew()
        [void] (Buscar -Q $q -ClienteId $cliente)
        $sw.Stop()
        [void] $tempos.Add($sw.Elapsed.TotalMilliseconds)
    }
    $cronometroTotal.Stop()

    $ord = $tempos | Sort-Object
    function Pct($p) { return [math]::Round($ord[[math]::Min($ord.Count - 1, [int][math]::Floor($p * $ord.Count))], 1) }

    Write-Host ''
    Write-Host ('   {0,-8} {1,8} ms' -f 'min',  [math]::Round($ord[0], 1))
    Write-Host ('   {0,-8} {1,8} ms' -f 'p50',  (Pct 0.50))
    Write-Host ('   {0,-8} {1,8} ms' -f 'p90',  (Pct 0.90))
    Write-Host ('   {0,-8} {1,8} ms' -f 'p95',  (Pct 0.95))
    Write-Host ('   {0,-8} {1,8} ms' -f 'p99',  (Pct 0.99)) -ForegroundColor Yellow
    Write-Host ('   {0,-8} {1,8} ms' -f 'max',  [math]::Round($ord[-1], 1))
    Write-Host ('   {0,-8} {1,8} req/s' -f 'vazao', [math]::Round($n / $cronometroTotal.Elapsed.TotalSeconds, 1))
    Write-Host ''
    Nota 'Medido de fora, por cliente HTTP do PowerShell, em uma thread: inclui'
    Nota 'rede local, serializacao e o custo do proprio medidor. O numero interno'
    Nota 'do servico esta em explicacao.msTotal e no /actuator/metrics.'
    Nota ''
    Nota 'ATENCAO: o perfil local afrouxa os prazos para 250/100/400 ms. Isto NAO'
    Nota 'e uma medicao do orcamento de 18/8/30 ms de producao.'
}

# ---------------------------------------------------------------------------
# Execucao
# ---------------------------------------------------------------------------

try {
    [void] (Invoke-RestMethod -Uri "$Host_/actuator/health" -TimeoutSec 5)
} catch {
    Write-Host ''
    Write-Host "App nao respondeu em $Host_" -ForegroundColor Red
    Write-Host "Suba com:  .\mvnw spring-boot:run `"-Dspring-boot.run.profiles=local`"" -ForegroundColor DarkGray
    Write-Host ''
    exit 1
}

$alvos = if ($Cenario -eq 'todos') { $CENARIOS } else { @($Cenario) }
foreach ($a in $alvos) {
    switch ($a) {
        'oferta'      { Show-Oferta }
        'conta'       { Show-Conta }
        'cadastro'    { Show-Cadastro }
        'validacao'   { Show-Validacao }
        'privacidade' { Show-Privacidade }
        'entrada'     { Show-Entrada }
        'latencia'    { Show-Latencia }
        default {
            Write-Host "Cenario desconhecido: $a" -ForegroundColor Red
            Write-Host "Use -Lista para ver os disponiveis." -ForegroundColor DarkGray
            exit 1
        }
    }
}

Write-Host ''
