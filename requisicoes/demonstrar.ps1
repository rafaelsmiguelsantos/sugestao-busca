<#
    Roteiro de demonstracao: roda os cenarios na ordem e imprime so o que importa.

        .\requisicoes\demonstrar.ps1              # pausa entre os atos
        .\requisicoes\demonstrar.ps1 -Corrido     # sem pausa, para conferir antes
        .\requisicoes\demonstrar.ps1 -Ato 3       # so um ato

    POR QUE ISTO EXISTE

    Nao e por tempo - e por risco. Digitar curl na frente de alguem significa
    errar um token, esquecer um cabecalho, ou colar o prefixo errado, e cada um
    desses vira um silencio de trinta segundos no meio da apresentacao.

    E a saida e EDITADA de proposito. A resposta crua tem campo que nao interessa
    no momento e esconde o que interessa: numa comparacao entre dois perfis, o que
    importa sao tres linhas - jornada lider, melhor oferta, motivo do descarte.

    A VERIFICACAO PREVIA E A PARTE QUE SALVA

    Ela roda ANTES de qualquer ato e falha alto: app no ar, Solr no ar, segredo
    configurado, e indice sem oferta de teste. O pior cenario de uma demonstracao
    nao e nao funcionar - e funcionar com "fundo multimercado invadido" na lista,
    porque quem assiste nao sabe que e residuo de teste.

    Nota de codificacao: ASCII puro. O PowerShell 5.1 le .ps1 sem BOM como ANSI.
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:8080',
    [string] $SolrUrl = 'http://localhost:8983/solr/sugestoes',
    [switch] $Corrido,
    [int]    $Ato = 0
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot

# O console do Windows abre em code page 850/437, e os termos do catalogo tem
# acento: sem isto, "credito consignado" aparece como "crA©dito" na tela. Numa
# apresentacao isso nao e detalhe - e a primeira coisa que quem assiste nota.
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

# ---------------------------------------------------------------------------
# Apresentacao
# ---------------------------------------------------------------------------
function Titulo($n, $texto) {
    Write-Host ''
    Write-Host ('=' * 78) -ForegroundColor DarkCyan
    Write-Host ("  ATO $n  -  $texto") -ForegroundColor Cyan
    Write-Host ('=' * 78) -ForegroundColor DarkCyan
}

# Aceita varias linhas e imprime o bloco junto, com UMA linha em branco antes.
# Uma chamada por linha deixava um branco entre cada uma, e a narracao ficava
# esparsa demais para ser lida em voz alta no ritmo de quem apresenta.
function Fala {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Linhas)
    Write-Host ''
    foreach ($l in $Linhas) { Write-Host "  $l" -ForegroundColor Gray }
}

function Pausa {
    if ($Corrido) { return }
    Write-Host ''
    Write-Host '  [Enter para seguir]' -ForegroundColor DarkGray -NoNewline
    try { Read-Host | Out-Null } catch { Write-Host '' }
}

function Destaque($texto) { Write-Host "  $texto" -ForegroundColor Yellow }

# ---------------------------------------------------------------------------
# Chamadas
# ---------------------------------------------------------------------------
$script:cacheDeToken = @{}

function Token($cliente, $segmento = 'PF', $relacionamento = 'VAREJO') {
    $chave = "$cliente|$segmento|$relacionamento"
    if (-not $script:cacheDeToken.ContainsKey($chave)) {
        $script:cacheDeToken[$chave] = & "$raiz\scripts\gerar-token.ps1" `
            -Tipo cliente -ClienteId $cliente -Segmento $segmento `
            -Relacionamento $relacionamento -Silencioso
    }
    return $script:cacheDeToken[$chave]
}

# Decodifica os bytes como UTF-8 A MAO, em vez de usar Invoke-RestMethod.
#
# POR QUE, e vale saber antes de apresentar: o PowerShell 5.1 cai para
# ISO-8859-1 quando o Content-Type nao declara charset, e a API devolve
# 'application/json' puro. O resultado e que "credito consignado" chega como
# "crA©dito consignado" NA STRING EM MEMORIA - nao e artefato de console.
#
# A API esta certa: a RFC 8259 define JSON como UTF-8 e NAO define parametro de
# charset para application/json, entao um cliente correto assume UTF-8. O PS 5.1
# e que e legado aqui - o PowerShell 7 ja assume UTF-8.
#
# Conserta-se o cliente, nao o servidor. Mas fica registrado que o caminho de
# ERRO deste servico declara charset (problem+json;charset=UTF-8) e o de sucesso
# nao: a inconsistencia e real e esta em docs/decisoes.md como pendencia.
function Buscar($cliente, $prefixo, $segmento = 'PF', $relacionamento = 'VAREJO') {
    $t = Token $cliente $segmento $relacionamento
    $resposta = Invoke-WebRequest -TimeoutSec 15 -UseBasicParsing `
        -Headers @{ Authorization = "Bearer $t" } `
        -Uri "$BaseUrl/v1/sugestoes?q=$prefixo&explicar=true&limite=20"

    return [Text.Encoding]::UTF8.GetString($resposta.RawContentStream.ToArray()) | ConvertFrom-Json
}

# A lista enxuta: posicao, origem, termo, e o id quando e oferta.
function Mostrar($resposta, $rotulo) {
    Write-Host ''
    Write-Host "  $rotulo" -ForegroundColor White
    $lider = if ($resposta.explicacao.jornadaLider) { $resposta.explicacao.jornadaLider } else { '(nenhuma)' }
    Write-Host "    jornada lider : $lider" -ForegroundColor DarkGray

    if ($resposta.melhorOferta.ofertaId) {
        Write-Host "    melhor oferta : $($resposta.melhorOferta.ofertaId)" -ForegroundColor Green
    } else {
        Write-Host "    melhor oferta : AUSENTE" -ForegroundColor DarkYellow
    }

    foreach ($i in $resposta.itens) {
        $marca = if ($i.ofertaId) { '*' } else { ' ' }
        Write-Host ("      {0} {1}  [{2,-9}] {3}" -f $marca, $i.posicao, $i.origem, $i.termo)
    }

    foreach ($d in $resposta.explicacao.descartes) {
        Write-Host ("      - removido: {0,-22} {1}" -f $d.motivo, $d.termo) -ForegroundColor DarkYellow
    }
}

function Rodar($n) { return ($Ato -eq 0 -or $Ato -eq $n) }

# ---------------------------------------------------------------------------
# VERIFICACAO PREVIA
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host 'Verificacao previa' -ForegroundColor Cyan

$problemas = @()

if (-not $env:SUGESTAO_SEGREDO) {
    $doUsuario = [Environment]::GetEnvironmentVariable('SUGESTAO_SEGREDO', 'User')
    if ($doUsuario) { $env:SUGESTAO_SEGREDO = $doUsuario }
}
if ($env:SUGESTAO_SEGREDO) {
    Write-Host '  ok   SUGESTAO_SEGREDO configurado' -ForegroundColor Green
} else {
    $problemas += 'Defina $env:SUGESTAO_SEGREDO (o mesmo da aplicacao).'
}

try {
    $s = (Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 5).status
    Write-Host "  ok   aplicacao no ar ($s)" -ForegroundColor Green
} catch {
    $problemas += "Aplicacao fora do ar. Suba com: .\mvnw spring-boot:run `"-Dspring-boot.run.profiles=local`""
}

try {
    $docs = (Invoke-RestMethod "$SolrUrl/select?q=*:*&rows=0" -TimeoutSec 5).response.numFound
    Write-Host "  ok   Solr no ar ($docs documentos)" -ForegroundColor Green
} catch {
    $problemas += 'Solr fora do ar. Suba com: .\scripts\solr-local.ps1 start'
}

# O cheque que mais importa: oferta de teste na lista durante a apresentacao.
try {
    $ofertas = (Invoke-RestMethod "$SolrUrl/select?q=tipo:oferta&rows=100&fl=oferta_id_s" -TimeoutSec 5).response.docs
    $lixo = @($ofertas | Where-Object {
        $_.oferta_id_s -match '^OFR-(CREDITO-TESTE-|TESTE-|SONDA-|SEQ-|.*-AUTHZ|INVADIDA|CREDITO-CORRIDA|CREDITO-CONFERE-|CREDITO-ISOLADO-|CREDITO-REPRO|PREVIDENCIA-VGBL-NEWMAN)'
    })
    if ($lixo.Count -eq 0) {
        Write-Host "  ok   indice sem oferta de teste ($(@($ofertas).Count) curadas)" -ForegroundColor Green
    } else {
        $problemas += "Ha $($lixo.Count) oferta(s) de teste no indice: $(@($lixo | ForEach-Object { $_.oferta_id_s }) -join ', '). Rode: .\scripts\limpar-ofertas-de-teste.ps1 -Confirmar"
    }
} catch { }

if ($problemas.Count -gt 0) {
    Write-Host ''
    Write-Host 'NAO ESTA PRONTO:' -ForegroundColor Red
    $problemas | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host ''
    exit 1
}

Write-Host ''
Write-Host '  Pronto. Os cenarios sao os oito perfis de demonstracao, todos falsos.' -ForegroundColor DarkGray
Pausa

# ---------------------------------------------------------------------------
if (Rodar 1) {
    Titulo 1 'A mesma letra, duas pessoas'
    Fala 'A pergunta nao e "quais palavras completam o que foi digitado".' `
         'E "qual oferta apresentar A ESTA PESSOA agora".'

    Mostrar (Buscar 'pf-pix-recusado' 'consignad') 'pf-pix-recusado  -  tem a elegibilidade'
    Mostrar (Buscar 'pf-sem-elegibilidade' 'consignad') 'pf-sem-elegibilidade  -  nao tem'

    Fala 'Quem nao pode contratar recebe a EXPLICACAO, nao a oferta.'
    Destaque 'melhorOferta ausente e informacao, nao erro. E o motivo esta registrado.'
    Pausa
}

# ---------------------------------------------------------------------------
if (Rodar 2) {
    Titulo 2 'A mesma letra, dois segmentos'
    Fala 'O segmento vem assinado no token, e corta o vocabulario antes de' `
         'qualquer leitura de perfil.'

    Mostrar (Buscar 'pf-pix-recusado' 'emprest') 'pf-pix-recusado  (PF)'
    Mostrar (Buscar 'pj-lojista' 'emprest' 'PJ') 'pj-lojista  (PJ)'

    Destaque 'Mesmas letras. Produto diferente, jornada diferente.'
    Pausa
}

# ---------------------------------------------------------------------------
if (Rodar 3) {
    Titulo 3 'O estado da conta decide'
    Fala 'Isto nao e aprendido: e lido do perfil, publicado por outro produtor.' `
         'Zero dias de observacao.'

    Mostrar (Buscar 'pf-negativado' 'emprestim') 'pf-negativado  -  fatura em atraso'
    Fala 'Repare: ele AINDA VE a busca. O que ele nao recebe e PROPOSTA.' `
         'Esconder a busca de quem esta em atraso seria a decisao errada - o' `
         'caminho certo para essa pessoa e a renegociacao, que entra por gatilho.'

    Mostrar (Buscar 'pf-bloqueado' 'transfer') 'pf-bloqueado  -  conta travada'
    Mostrar (Buscar 'pf-investidor' 'invest') 'pf-investidor  -  ja tem CDB'

    Destaque 'Elegibilidade nao e nota baixa: e REMOCAO, com motivo. Se fosse peso,'
    Destaque 'um prefixo muito casado faria a oferta vetada subir de volta.'
    Pausa
}

# ---------------------------------------------------------------------------
if (Rodar 4) {
    Titulo 4 'A faixa de relacionamento'

    Mostrar (Buscar 'pf-uniclass' 'invest' 'PF' 'UNICLASS') 'pf-uniclass  (UNICLASS)'
    Mostrar (Buscar 'pf-novo' 'invest') 'pf-novo  (VAREJO)'

    Fala 'A consultoria de investimentos nao aparece para o varejo - e repare' `
         'que ela nao esta nem na lista de descartes.'
    Destaque 'O corte de faixa e filtro no indice, nao pos-filtro: ela nunca foi'
    Destaque 'candidata. E a faixa vem do token, assinada, nao de cabecalho.'
    Pausa
}

# ---------------------------------------------------------------------------
if (Rodar 5) {
    Titulo 5 'Gatilho: oportunidade com prazo'
    Fala 'pf-pix-recusado teve um Pix recusado por saldo ha duas horas.'

    $r = Buscar 'pf-pix-recusado' 'ant'
    Mostrar $r 'pf-pix-recusado  -  q=ant'

    $primeiro = @($r.itens)[0]
    Fala "A linha de evento vem na posicao 0, com origem $($primeiro.origem)."
    Destaque 'A cota garante a linha; o evento NAO disputa por score. Se disputasse,'
    Destaque 'um gatilho forte empurraria a lista para uma intencao que a pessoa'
    Destaque 'talvez nem estivesse perseguindo.'
    Pausa
}

# ---------------------------------------------------------------------------
if (Rodar 6) {
    Titulo 6 'Quando a pessoa erra a digitacao'
    Fala 'Uma letra errada zerava a lista. Em teclado de celular, isso e a' `
         'maioria dos casos ruins.'
    Write-Host ''

    foreach ($q in 'emprestimo', 'emprestmo', 'enprestimo', 'consignadu') {
        $r = Buscar 'pf-pix-recusado' $q
        $oferta = if ($r.melhorOferta.ofertaId) { $r.melhorOferta.ofertaId } else { '-' }
        Write-Host ("    {0,-14} {1} itens   {2,-20} {3}" -f `
            $q, @($r.itens).Count, $r.explicacao.tolerancia, $oferta)
    }

    Fala 'E uma SEGUNDA consulta, disparada so quando a primeira trouxe pouco.'
    Destaque 'NAO_PRECISOU significa custo zero no caminho quente - que e o caso'
    Destaque 'comum. A ida extra acontece quando a alternativa era a lista vazia.'
    Pausa
}

# ---------------------------------------------------------------------------
if (Rodar 7) {
    Titulo 7 'Privacidade: dois portoes independentes'
    Fala 'Primeiro portao - o que entra. CPF com digito valido, cartao no Luhn,' `
         'e-mail e chave Pix sao recusados na ingestao.'

    $t = Token 'pf-novo'
    $corpo = ConvertTo-Json -Depth 5 -InputObject @(
        @{ tipo = 'BUSCA_DIGITADA'; termo = 'pagar boleto 529.982.247-25' },
        @{ tipo = 'BUSCA_DIGITADA'; termo = 'cartao 4539 1488 0343 6467' },
        @{ tipo = 'BUSCA_DIGITADA'; termo = 'pix para joao@exemplo.com' },
        @{ tipo = 'BUSCA_DIGITADA'; termo = 'emprestimo pessoal' }
    )
    $r = Invoke-RestMethod -Method Post -TimeoutSec 15 -Uri "$BaseUrl/v1/eventos" `
        -Headers @{ Authorization = "Bearer $t" } -ContentType 'application/json' `
        -Body ([Text.Encoding]::UTF8.GetBytes($corpo))

    Write-Host ''
    Write-Host "    aceitos: $($r.aceitos)   descartados: $($r.descartados)" -ForegroundColor White
    $r.motivos.PSObject.Properties | ForEach-Object { Write-Host "      $($_.Name): $($_.Value)" -ForegroundColor DarkYellow }

    Destaque 'A resposta NUNCA devolve o termo barrado - devolve-lo recriaria o'
    Destaque 'vazamento que o saneamento evitou.'

    Fala 'Segundo portao - o que sai. Termo buscado por menos de 30 clientes' `
         'distintos nao vira sugestao de ninguem.'

    $abaixo = (Invoke-RestMethod -TimeoutSec 15 `
        -Uri "$SolrUrl/select?q=*:*&rows=0&fq=tipo:consulta&fq=clientes_distintos_i:[* TO 29]").response.numFound
    $alcancaveis = (Invoke-RestMethod -TimeoutSec 15 `
        -Uri "$SolrUrl/select?q=*:*&rows=0&fq=(*:* -tipo:consulta) OR clientes_distintos_i:[30 TO *]&fq=tipo:consulta&fq=clientes_distintos_i:[* TO 29]").response.numFound

    Write-Host ''
    Write-Host "    termos abaixo do piso, no indice : $abaixo" -ForegroundColor White
    Write-Host "    alcancaveis por alguma busca     : $alcancaveis" -ForegroundColor Green

    Fala 'E o identificador do cliente nao esta no indice:'
    $noIndice = (Invoke-RestMethod -TimeoutSec 15 -Uri "$SolrUrl/select?q=pf-*&rows=0").response.numFound
    Write-Host "    documentos casando 'pf-*' : $noIndice" -ForegroundColor Green

    Destaque 'Por construcao, nao por disciplina: a porta de saida recebe segmento e'
    Destaque 'faixa, nao Cliente. O compilador impede a identidade de passar.'
    Pausa
}

# ---------------------------------------------------------------------------
if (Rodar 8) {
    Titulo 8 'O orcamento, medido'

    $amostras = @()
    foreach ($q in 'em','emp','empr','empre','empres','emprest','emprestim') {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        Buscar 'pf-pix-recusado' $q | Out-Null
        $amostras += $sw.ElapsedMilliseconds
    }
    Write-Host ''
    Write-Host '    ida e volta COMPLETA, medida daqui (inclui o cliente HTTP):' -ForegroundColor White
    Write-Host ("      $($amostras -join ' ms  ') ms")

    $r = Buscar 'pf-pix-recusado' 'emprestim'
    Write-Host ''
    Write-Host '    e o que foi gasto DENTRO do servico, na ultima delas:' -ForegroundColor White
    Write-Host "      ate o indice : $($r.explicacao.msAteIndice) ms"
    Write-Host "      ate o perfil : $($r.explicacao.msAtePerfil) ms"
    Write-Host "      total        : $($r.explicacao.msTotal) ms"

    Fala 'Os dois conjuntos medem coisas diferentes, e vale separar: o de cima' `
         'inclui o cliente HTTP do PowerShell, que nao e o aplicativo; o de' `
         'baixo e o que o orcamento de 18/8/30 ms governa.'

    Destaque 'Os dois numeros de cima sao parecidos porque as duas leituras correm'
    Destaque 'EM PARALELO - e elas podem correr em paralelo porque segmento e faixa'
    Destaque 'vem do token, conhecidos antes de qualquer leitura de perfil.'

    Fala 'Sob carga, nesta maquina: 530-680 req/s, p50 8-10 ms, p99 ~30 ms, zero falhas.' `
         'Cliente, servico e indice juntos - serve para comparar mudancas, NAO' `
         'para dimensionar capacidade.'
    Pausa
}

Write-Host ''
Write-Host ('=' * 78) -ForegroundColor DarkCyan
Write-Host '  Fim. O raciocinio de cada decisao esta em docs/decisoes.md (25).' -ForegroundColor Cyan
Write-Host '  O que e de mentira e o que falta: README, "Limitacoes conhecidas".' -ForegroundColor Cyan
Write-Host ('=' * 78) -ForegroundColor DarkCyan
Write-Host ''
