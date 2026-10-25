<#
    Simulacao de ponta a ponta: um cliente clica, o comportamento e mapeado, e a
    proxima busca muda. Com estado de conta variando e com inspecao do que o
    Solr de fato guardou.

        .\requisicoes\simular.ps1

    Responde quatro perguntas, em ordem:

      1. O estado da conta muda a melhor oferta?            (passos 1 a 3)
      2. Clicar muda a busca SEGUINTE?                      (passos 4 a 6)
      3. A afinidade de uma jornada contamina as outras?    (passo 7)
      4. O que o Solr guarda, e o que ele NAO guarda?       (passos 8 a 10)

    Precisa do app e do Solr no ar, com o perfil 'local':
        .\mvnw spring-boot:run "-Dspring-boot.run.profiles=local"

    Nota de codificacao: ASCII puro nos comentarios e strings, como os outros
    scripts. O PowerShell 5.1 le .ps1 sem BOM como ANSI.
#>
[CmdletBinding()]
param(
    [string] $App  = 'http://localhost:8080',
    [string] $Solr = 'http://localhost:8983/solr/sugestoes',
    [switch] $PularPiso
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$gerador = Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts\gerar-token.ps1'

# ---------------------------------------------------------------------------
# Plumbing
# ---------------------------------------------------------------------------

function Titulo($n, $texto) {
    Write-Host ''
    Write-Host ('=' * 78) -ForegroundColor DarkGray
    Write-Host ("  PASSO {0} - {1}" -f $n, $texto) -ForegroundColor Cyan
    Write-Host ('=' * 78) -ForegroundColor DarkGray
}

function Nota($texto) { Write-Host "   $texto" -ForegroundColor DarkGray }

function TokenCliente($cliente, $segmento = 'PF', $relacionamento = 'VAREJO') {
    return (& $gerador -Silencioso -Tipo cliente -ClienteId $cliente `
            -Segmento $segmento -Relacionamento $relacionamento)
}

# Le o corpo como UTF-8 a partir dos bytes: o PowerShell 5.1 decide a
# codificacao pelo charset do Content-Type e cai em ISO-8859-1 quando ele nao
# vem, o que transforma "emprestimo" em "emprAstimo" na saida.
function LerJson($resposta) {
    $fluxo = $resposta.RawContentStream
    $bytes = New-Object byte[] $fluxo.Length
    [void] $fluxo.Seek(0, 'Begin')
    [void] $fluxo.Read($bytes, 0, $bytes.Length)
    return ([Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json)
}

function Buscar($cliente, $q, $relacionamento = 'VAREJO', $segmento = 'PF') {
    $t = TokenCliente $cliente $segmento $relacionamento
    $u = "$App/v1/sugestoes?explicar=true&q=" + [uri]::EscapeDataString($q)
    return LerJson (Invoke-WebRequest -Uri $u -Headers @{ Authorization = "Bearer $t" } `
                        -UseBasicParsing -TimeoutSec 20)
}

function MostrarLista($r, $rotulo) {
    Write-Host ("   [{0}]" -f $rotulo) -ForegroundColor White
    if (-not $r.itens -or $r.itens.Count -eq 0) {
        Nota '(lista vazia)'
    } else {
        foreach ($i in $r.itens) {
            $id = if ($i.ofertaId) { $i.ofertaId } else { '' }
            Write-Host ("     {0} {1,-9} {2,-30} {3,-28} r={4}" -f `
                $i.posicao, $i.origem, $i.termo, $id, $i.relevancia)
        }
    }
    if ($r.melhorOferta) {
        Write-Host ("     MELHOR OFERTA -> {0}  (produto {1}, jornada {2}, posicao {3})" -f `
            $r.melhorOferta.ofertaId, $r.melhorOferta.produtoAlvo,
            $r.melhorOferta.jornada, $r.melhorOferta.posicao) -ForegroundColor Green
    } else {
        Write-Host '     MELHOR OFERTA -> nenhuma' -ForegroundColor DarkYellow
    }
    if ($r.explicacao) {
        Nota ("jornadaLider={0}  perfil={1}  degradada={2}  {3} ms" -f `
            $(if ($r.explicacao.jornadaLider) { $r.explicacao.jornadaLider } else { '-' }),
            $r.explicacao.perfil, $r.degradada, $r.explicacao.msTotal)
        foreach ($d in $r.explicacao.descartes) {
            Write-Host ("     REMOVIDO  {0,-24} {1}" -f $d.motivo, $d.termo) -ForegroundColor Red
        }
    }
}

function Eventos($cliente, $corpo) {
    $t = TokenCliente $cliente

    # O endpoint recebe @RequestBody List<EventoRecebido>, ou seja um array.
    #
    # E o ConvertTo-Json do PowerShell 5.1 desenrola array de UM elemento num
    # objeto: @(@{a=1}) | ConvertTo-Json devolve {"a":1}, nao [{"a":1}]. O
    # Jackson recusa com 400, e a mensagem fala de ArrayList, o que manda olhar
    # o Java quando o problema esta no cliente.
    #
    # Montar o array a mao e feio e e o unico jeito que nao depende da contagem.
    # A alternativa, a virgula a frente, resolve o caso de um elemento e QUEBRA
    # o de varios: @(a,b) com virgula vira {"value":[...],"Count":2}.
    $lista = @($corpo)
    $itens = $lista | ForEach-Object { $_ | ConvertTo-Json -Depth 8 -Compress }
    $json = '[' + ($itens -join ',') + ']'
    $r = Invoke-WebRequest -Uri "$App/v1/eventos" -Method Post `
            -Headers @{ Authorization = "Bearer $t" } `
            -ContentType 'application/json; charset=utf-8' `
            -Body ([Text.Encoding]::UTF8.GetBytes($json)) -UseBasicParsing -TimeoutSec 20
    return LerJson $r
}

function RodarAprendizado {
    $t = & $gerador -Silencioso -Tipo operacao
    $r = Invoke-WebRequest -Uri "$App/v1/aprendizado/rodar" -Method Post `
            -Headers @{ Authorization = "Bearer $t" } -UseBasicParsing -TimeoutSec 60
    return LerJson $r
}

function SolrConta([string[]] $Fqs, [string] $Q = '*:*') {
    $pares = @("q=$([uri]::EscapeDataString($Q))", 'rows=0', 'wt=json')
    foreach ($f in $Fqs) { $pares += 'fq=' + [uri]::EscapeDataString($f) }
    $r = Invoke-RestMethod -Method Post -Uri "$Solr/select" `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body ($pares -join '&') -TimeoutSec 20
    return $r.response.numFound
}

function SolrDocs([string] $Q, [string] $Fl, [int] $Linhas = 5) {
    $pares = @("q=$([uri]::EscapeDataString($Q))", "rows=$Linhas", 'wt=json', "fl=$Fl")
    $r = Invoke-RestMethod -Method Post -Uri "$Solr/select" `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body ($pares -join '&') -TimeoutSec 20
    return $r.response
}

# ---------------------------------------------------------------------------
# Pre-voo
# ---------------------------------------------------------------------------
try { [void] (Invoke-RestMethod -Uri "$App/actuator/health" -TimeoutSec 5) }
catch { Write-Host "App nao responde em $App" -ForegroundColor Red; exit 1 }
try { [void] (Invoke-RestMethod -Uri "$Solr/select?q=*:*&rows=0&wt=json" -TimeoutSec 5) }
catch { Write-Host "Solr nao responde em $Solr" -ForegroundColor Red; exit 1 }

Write-Host ''
Write-Host '  SIMULACAO: um cliente clica, o comportamento e mapeado, a proxima busca muda.' -ForegroundColor Cyan
Write-Host '  Com estado de conta variando e inspecao do que o Solr guardou.' -ForegroundColor DarkGray

# Cliente novo a cada execucao, para a simulacao nao depender de rodadas
# anteriores. O estado de conta dele e DESCONHECIDA, porque so a busca o viu.
$novo = "sim-$(Get-Random -Minimum 10000 -Maximum 99999)"

# ===========================================================================
Titulo 1 'Conta ATIVA, sem comportamento: a linha de base'
# ===========================================================================
Nota 'pf-novo tem conta ATIVA e nenhuma afinidade. E o controle da simulacao.'
$base = Buscar 'pf-novo' 'emprest'
MostrarLista $base 'pf-novo / conta ATIVA'

# ===========================================================================
Titulo 2 'Conta INADIMPLENTE: a mesma busca, outra resposta'
# ===========================================================================
Nota 'pf-negativado tem fatura vencida. O estado NAO e aprendido: ele e lido do'
Nota 'perfil, publicado por outro produtor. Zero dias de observacao.'
$inad = Buscar 'pf-negativado' 'emprest'
MostrarLista $inad 'pf-negativado / conta INADIMPLENTE'

Write-Host ''
if ($base.melhorOferta -and -not $inad.melhorOferta) {
    Write-Host '   OK  A conta operante recebe oferta de credito; a inadimplente nao recebe.' -ForegroundColor Green
    Write-Host '       E o motivo de cada ausencia esta registrado acima, nao inferido.' -ForegroundColor Green
} else {
    Write-Host '   INESPERADO  As duas respostas deveriam diferir.' -ForegroundColor Red
}

# ===========================================================================
Titulo 3 'Conta BLOQUEADA: terceiro estado, terceira resposta'
# ===========================================================================
Nota 'pf-bloqueado tem a conta travada por suspeita de fraude. Operacao que'
Nota 'exige conta operante sai; desbloquear e contestar continuam.'
MostrarLista (Buscar 'pf-bloqueado' 'transfer') 'pf-bloqueado / conta BLOQUEADA'

# ===========================================================================
Titulo 4 'Um cliente NOVO busca pela primeira vez'
# ===========================================================================
Nota "cliente: $novo  (nao existe em perfil nenhum)"
$antes = Buscar $novo 'emprest'
MostrarLista $antes 'primeiro acesso'
Nota ''
Nota 'degradada=true e perfil=SEM_PERFIL: nao ha comportamento nem estado de'
Nota 'conta conhecidos. A lista e a popular do segmento, e o app usa a marca'
Nota 'para nao registrar a impressao como se fosse personalizada.'

# ===========================================================================
Titulo 5 'O cliente clica. E ISTO que vira comportamento'
# ===========================================================================
$impressao = @(
    @{ tipo = 'SUGESTAO_EXIBIDA'
       termosExibidos = @($antes.itens | ForEach-Object { $_.termo })
       listaDegradada = $antes.degradada }
)
# Tres cliques na jornada de credito. Tres porque o piso de confianca e 6 pontos
# e clique vale 2: e o que basta para afinidade 1,0. UM clique daria 0,33 - o
# sistema deliberadamente nao acredita no primeiro clique.
$cliques = @()
1..3 | ForEach-Object {
    $cliques += @{ tipo = 'SUGESTAO_CLICADA'; termo = 'emprestimo pessoal'
                   posicao = 0; jornada = 'credito'; produto = 'EMPRESTIMO' }
}
# A busca digitada e um evento SEPARADO, e o app tem de mandar: o
# GET /v1/sugestoes nao registra nada por conta propria. Essa separacao e
# deliberada - a leitura nunca escreve, e o compilador garante, porque o caso de
# uso de sugestao recebe so RepositorioDePerfil, que nao tem metodo de escrita.
#
# E e dela que o HISTORICO nasce (passo 10).
$buscas = @(
    @{ tipo = 'BUSCA_DIGITADA'; termo = 'emprestimo pessoal' },
    @{ tipo = 'BUSCA_DIGITADA'; termo = 'portabilidade de salario' }
)

$r0 = Eventos $novo $buscas
$r1 = Eventos $novo $impressao
$r2 = Eventos $novo $cliques
Nota ("buscas:    aceitos={0} descartados={1}" -f $r0.aceitos, $r0.descartados)
Nota ("impressao: aceitos={0} descartados={1}" -f $r1.aceitos, $r1.descartados)
Nota ("cliques:   aceitos={0} descartados={1}" -f $r2.aceitos, $r2.descartados)
Nota ''
Nota 'A identidade NAO vem no corpo: ela vem do sub do token. Aceitar clienteId'
Nota 'no corpo deixaria qualquer chamador escrever comportamento em nome de'
Nota 'qualquer pessoa - e como e esse identificador que a agregacao conta para'
Nota 'medir clientes distintos, quem controlasse o corpo controlaria o piso de'
Nota 'k-anonimato.'

# ===========================================================================
Titulo 6 'O aprendizado roda. O PROXIMO ACESSO e diferente'
# ===========================================================================
$ap = RodarAprendizado
Nota ("rodada: eventos={0} termos={1} publicados={2} retidos={3} perfis={4}" -f `
      $ap.eventosConsumidos, $ap.termosAvaliados, $ap.termosPublicados,
      $ap.retidosPeloPiso, $ap.perfisAtualizados)

$depois = Buscar $novo 'emprest'
MostrarLista $depois 'segundo acesso, mesma busca'

$rAntes  = if ($antes.itens.Count -gt 0) { [double] ($antes.itens[0].relevancia) } else { 0 }
$rDepois = if ($depois.itens.Count -gt 0) { [double] ($depois.itens[0].relevancia) } else { 0 }
Write-Host ''
Write-Host ("   topo antes:  r={0}   degradada={1}" -f $rAntes, $antes.degradada)
Write-Host ("   topo depois: r={0}   degradada={1}" -f $rDepois, $depois.degradada)
if ($depois.degradada -eq $false -and $antes.degradada -eq $true) {
    Write-Host '   OK  O cliente deixou de ser desconhecido entre um acesso e outro.' -ForegroundColor Green
}
Nota ''
Nota 'A afinidade NAO foi calculada dentro da requisicao: o comportamento e lido'
Nota 'de um retrato pronto. Mapear acontece ENTRE uma busca e a proxima, porque'
Nota 'o sinal que mais vale - o clique - acontece depois da resposta.'

# ===========================================================================
Titulo 7 'A afinidade de uma jornada NAO contamina as outras'
# ===========================================================================
Nota 'O cliente clicou em credito. Busca em investimento nao deve ganhar impulso.'
MostrarLista (Buscar $novo 'invest') 'mesma pessoa, outra jornada'

# ===========================================================================
Titulo 8 'O QUE O SOLR GUARDOU - e o que ele NAO guarda'
# ===========================================================================
Write-Host ''
Write-Host '   A pergunta mais interessante da simulacao.' -ForegroundColor White
Write-Host ''

$total = SolrConta @('*:*')
Write-Host ("   documentos no indice: {0}" -f $total)

# 1. A identidade do cliente
$porId = SolrConta @('*:*') $novo
$porIdQualquer = SolrConta @('*:*') 'sim-*'
Write-Host ''
Write-Host '   1. A IDENTIDADE DO CLIENTE' -ForegroundColor White
Write-Host ("      documentos citando '{0}'  : {1}" -f $novo, $porId) `
    -ForegroundColor $(if ($porId -eq 0) { 'Green' } else { 'Red' })
Write-Host ("      documentos citando 'sim-*' : {0}" -f $porIdQualquer) `
    -ForegroundColor $(if ($porIdQualquer -eq 0) { 'Green' } else { 'Red' })
Nota 'ZERO, e por construcao. O identificador do cliente NUNCA chega ao indice.'
Nota 'A porta de saida da busca recebe ContextoDeBusca - segmento e faixa - e'
Nota 'nao Cliente, entao o compilador impede que a identidade passe por ali.'

# 2. O estado da conta
Write-Host ''
Write-Host '   2. O ESTADO DA CONTA (inadimplencia, bloqueio, saldo)' -ForegroundColor White
$campos = (Invoke-RestMethod -Uri "$Solr/schema/fields?wt=json" -TimeoutSec 20).fields
$suspeitos = $campos | Where-Object { $_.name -match 'inadimp|bloquei|saldo|situacao|conta_' } |
             ForEach-Object { $_.name }
if ($suspeitos) {
    Write-Host ("      campos no schema: {0}" -f ($suspeitos -join ', ')) -ForegroundColor Yellow
    Nota 'veta_inadimplencia_b e exige_conta_ativa_b sao RESTRICOES DA OFERTA -'
    Nota 'propriedades do produto, nao do cliente. "esta oferta nao serve a quem'
    Nota 'esta em atraso", nunca "este cliente esta em atraso".'
} else {
    Write-Host '      nenhum' -ForegroundColor Green
}
Nota ''
Nota 'O estado da conta NAO esta no Solr. Ele vive no perfil - em memoria aqui,'
Nota 'DynamoDB no desenho - e e cruzado com a oferta na Peneira, em CPU, depois'
Nota 'de o indice ja ter respondido.'

# 3. A afinidade
Write-Host ''
Write-Host '   3. A AFINIDADE POR JORNADA' -ForegroundColor White
$afin = $campos | Where-Object { $_.name -match 'afinidade|comportamento_cliente' }
if ($afin) {
    Write-Host ("      campos: {0}" -f (($afin | ForEach-Object { $_.name }) -join ', ')) -ForegroundColor Yellow
} else {
    Write-Host '      nenhum' -ForegroundColor Green
}
Nota 'Tambem nao. O que o indice tem de comportamento e AGREGADO da populacao:'
Nota 'ctr_f e taxa_adesao_f, que dizem o que AS PESSOAS fazem. O que ESTA pessoa'
Nota 'faz fica no perfil dela.'

# 4. O que entao esta la
Write-Host ''
Write-Host '   4. O QUE ENTAO ESTA LA' -ForegroundColor White
$amostra = SolrDocs 'tipo:oferta' 'oferta_id_s,termo,produto_alvo_s,jornada_s,veta_inadimplencia_b,clientes_distintos_i,ctr_f,taxa_adesao_f' 3
foreach ($d in $amostra.docs) {
    Write-Host ("      {0,-26} {1,-24} veta_inad={2,-5} k={3} ctr={4}" -f `
        $d.oferta_id_s, $d.termo, $d.veta_inadimplencia_b, $d.clientes_distintos_i, $d.ctr_f)
}
Nota 'Oferta: texto, identificador, produto, jornada, restricoes declaradas e'
Nota 'contadores agregados. Nada de pessoa.'

# ===========================================================================
Titulo 9 'O termo que o cliente buscou foi publicado no Solr?'
# ===========================================================================
$termoDoCliente = SolrConta @('tipo:consulta') 'termo:"emprestimo pessoal"'
Write-Host ("   documentos tipo:consulta para o termo buscado: {0}" -f $termoDoCliente)
Nota ''
Nota 'O termo deste cliente sozinho NAO vira vocabulario publicado. O piso de'
Nota 'k-anonimato exige 30 clientes DISTINTOS, e a rodada acima reportou o termo'
Nota 'como retido. Um termo que so uma pessoa buscou, devolvido a uma segunda,'
Nota 'contaria algo sobre a primeira.'

$abaixo = SolrConta @('tipo:consulta', 'clientes_distintos_i:[* TO 29]')
$fq = '(*:* -tipo:consulta) OR clientes_distintos_i:[30 TO *]'
$vazaram = SolrConta @($fq, 'tipo:consulta', 'clientes_distintos_i:[* TO 29]')
Write-Host ''
Write-Host ("   termos de log abaixo do piso no indice : {0}" -f $abaixo)
Write-Host ("   quantos deles a busca alcanca         : {0}" -f $vazaram) `
    -ForegroundColor $(if ($vazaram -eq 0) { 'Green' } else { 'Red' })
Nota 'Eles ESTAO no indice e sao inalcancaveis. O corte e fq, nao pos-filtro:'
Nota 'um termo abaixo do piso nao chega a pontuar, nem a ser contado.'

# ===========================================================================
Titulo 10 'E o historico? Ele volta para o dono, e so para ele'
# ===========================================================================
# O prefixo e 'portabil' porque o cliente buscou 'portabilidade de salario' e
# NENHUM outro perfil buscou isso. Se o teste usasse 'emprest', o termo do
# catalogo apareceria para os dois e a comparacao nao provaria nada - que era o
# defeito da primeira versao deste passo, onde os dois lados davam zero e
# passavam trivialmente.
$meu = Buscar $novo 'portabil'
$outro = Buscar 'pf-novo' 'portabil'

$meuHist = @($meu.itens | Where-Object { $_.origem -eq 'HISTORICO' })
$outroHist = @($outro.itens | Where-Object { $_.origem -eq 'HISTORICO' })

Write-Host ("   {0,-14} linhas HISTORICO: {1}" -f $novo, $meuHist.Count) `
    -ForegroundColor $(if ($meuHist.Count -gt 0) { 'Green' } else { 'Red' })
foreach ($h in $meuHist) { Write-Host ("        {0}" -f $h.termo) -ForegroundColor Green }

Write-Host ("   {0,-14} linhas HISTORICO: {1}" -f 'pf-novo', $outroHist.Count) `
    -ForegroundColor $(if ($outroHist.Count -eq 0) { 'Green' } else { 'Red' })
foreach ($h in $outroHist) { Write-Host ("        VAZOU: {0}" -f $h.termo) -ForegroundColor Red }

Write-Host ''
if ($meuHist.Count -gt 0 -and $outroHist.Count -eq 0) {
    Write-Host '   OK  O dono recebe o proprio termo; o outro cliente nao recebe nada.' -ForegroundColor Green
} elseif ($meuHist.Count -eq 0) {
    Write-Host '   INCONCLUSIVO  O dono tambem nao recebeu: nada foi provado.' -ForegroundColor Yellow
} else {
    Write-Host '   FALHA  O termo de um cliente apareceu para outro.' -ForegroundColor Red
}
Nota ''
Nota 'O historico nao passa pelo indice: ele ja esta no perfil, custa zero na'
Nota 'requisicao, e por nao sair do dono nao tem como vazar para ninguem. E a'
Nota 'unica camada da lista que nao responde pelo piso de k-anonimato, porque o'
Nota 'dono recebendo o proprio dado nao e vazamento.'

Write-Host ''
Write-Host ('=' * 78) -ForegroundColor DarkGray
Write-Host '  FIM' -ForegroundColor Cyan
Write-Host ('=' * 78) -ForegroundColor DarkGray
Write-Host ''
