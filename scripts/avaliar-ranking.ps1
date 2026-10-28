<#
    Mede a ORDEM da lista, que e a unica coisa que este projeto nunca mediu.

        .\scripts\avaliar-ranking.ps1 -Autoteste          # so a aritmetica, sem rede
        .\scripts\avaliar-ranking.ps1                     # mede e compara com a linha de base
        .\scripts\avaliar-ranking.ps1 -SalvarLinhaDeBase  # grava a referencia atual
        .\scripts\avaliar-ranking.ps1 -Detalhado          # mostra a lista devolvida por consulta

    POR QUE ISTO EXISTE

    Ha onze numeros calibrados a mao no ranking - 6, 2 e 3 nos campos do Solr;
    1,6, 1,25, 1,5, 1,2, 1,15, 3,6, 0,6 e 0,15 no dominio - e NENHUMA forma de
    saber se mexer em qualquer um deles melhorou ou piorou a busca.

    O projeto tem boas metricas de processo: taxa de zero-resultado, desfecho em
    cinco valores, posicao clicada, motivo de descarte. Nenhuma delas mede ORDEM.
    Sem medir ordem, mexer no ranking e fe - e no escuro ninguem mexe, entao o
    ranking congela.

    O ENTREGAVEL NAO E O NUMERO, E O PORTAO. Qualquer mudanca nos pesos roda isto
    e reporta o delta. Sai com codigo 1 quando a media cai mais que a tolerancia,
    para servir de passo de build.

    O QUE ELE MEDE, E O QUE NAO

    Mede a cadeia inteira: o qf e o boost do Solr, a peneira, o Rankeador e o
    Misturador - porque bate no endpoint, e nao no indice. E isso importa: uma
    lista que remove corretamente uma oferta inelegivel e poe um artigo no lugar
    E a lista melhor, e so o endpoint sabe disso.

    NAO mede personalizacao. NDCG compara a ordem devolvida com uma ordem julgada
    por uma pessoa, e uma pessoa nao tem como julgar o que ESTE cliente quer.
    Afinidade, tendencia e fadiga continuam sem medicao de qualidade: aqueles
    precisam de experimento online ou avaliacao contrafactual sobre log. Sao os
    dois rankings do desenho outra vez, e so um deles e mensuravel offline.

    POR QUE HA TOLERANCIA NUM TESTE DETERMINISTICO

    Porque ele nao e deterministico entre horas. O boost multiplicativo do indice
    tem recip(ms(NOW/HOUR, visto_em_dt), ...): o NOW/HOUR existe para a chave do
    cache de filtro ser estavel dentro da hora, e a consequencia e que o score
    muda na virada dela. A tolerancia cobre isso. Baixa-la para zero produz um
    portao que fecha sozinho de hora em hora.

    Nota de codificacao: ASCII puro, como os outros scripts. O Windows PowerShell
    5.1 le .ps1 sem BOM como ANSI, e um travessao dentro de uma string vira tres
    bytes cujo ultimo o parser entende como aspa de fechamento.
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:8080',
    [string] $Julgamentos = 'avaliacao\julgamentos.json',
    [string] $LinhaDeBase = 'avaliacao\linha-de-base.json',

    # So para a impressao digital do corpus. Ver ImpressaoDoCorpus.
    [string] $SolrUrl = 'http://localhost:8983/solr',
    [string] $Colecao = 'sugestoes',

    # Quanto a media pode cair sem o portao fechar. Ver o cabecalho.
    [double] $Tolerancia = 0.01,

    [int]    $Limite = 8,

    [switch] $SalvarLinhaDeBase,
    [switch] $Detalhado,
    [switch] $Autoteste
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# ---------------------------------------------------------------------------
# A aritmetica
# ---------------------------------------------------------------------------
# Ganho exponencial (2^g - 1) e desconto log2(i+2), que e o NDCG padrao de
# recuperacao de informacao. O ganho exponencial e o que faz a nota 3 valer
# SETE e a nota 1 valer UM: a diferenca entre "e isto" e "e parecido" tem de ser
# grande, senao trocar o primeiro lugar por um vizinho quase nao mexe no numero
# e o portao deixa de ver a regressao que mais importa.
function Dcg([double[]] $notas) {
    $soma = 0.0
    for ($i = 0; $i -lt $notas.Count; $i++) {
        $ganho = [Math]::Pow(2, $notas[$i]) - 1
        $soma += $ganho / [Math]::Log($i + 2, 2)
    }
    return $soma
}

# $null quando nao ha nada julgado para encontrar.
#
# Devolver 1,0 nesse caso seria pior que nao devolver nada: a consulta entraria
# na media como acerto perfeito, e um conjunto de julgamento que envelheceu -
# termos renomeados no catalogo, por exemplo - faria a media SUBIR enquanto a
# cobertura desaba. Fora da media, ela aparece na contagem de nao avaliaveis.
function Ndcg([double[]] $notasDevolvidas, [double[]] $todasAsNotas, [int] $k) {
    $ideal = @($todasAsNotas | Sort-Object -Descending | Select-Object -First $k)
    $idcg = Dcg $ideal
    if ($idcg -le 0) { return $null }

    $obtidas = @($notasDevolvidas | Select-Object -First $k)
    return (Dcg $obtidas) / $idcg
}

# ---------------------------------------------------------------------------
# Autoteste: a aritmetica contra casos calculados a mao
# ---------------------------------------------------------------------------
# Um avaliador com a conta errada e pior que avaliador nenhum, porque ele produz
# um numero e ninguem desconfia de numero. Estes cinco casos rodam sem rede, sem
# indice e sem aplicacao.
function Autotestar {
    $falhas = New-Object System.Collections.ArrayList

    function Conferir([string] $nome, $obtido, $esperado, [double] $margem = 1e-6) {
        if ($null -eq $esperado) {
            if ($null -ne $obtido) { [void] $falhas.Add("$nome : esperava `$null, veio $obtido") }
            return
        }
        if ($null -eq $obtido) { [void] $falhas.Add("$nome : esperava $esperado, veio `$null"); return }
        if ([Math]::Abs($obtido - $esperado) -gt $margem) {
            [void] $falhas.Add("$nome : esperava $esperado, veio $obtido")
        }
    }

    # Ordem perfeita.
    Conferir 'ordem perfeita' (Ndcg @(3,2,1) @(3,2,1) 3) 1.0

    # Ordem invertida. A conta, com log2(3) = 1,584962500721156:
    #   DCG  = 1/1 + 3/1,5849625 + 7/2 = 6,392789260714372
    #   IDCG = 7/1 + 3/1,5849625 + 1/2 = 9,392789260714372
    Conferir 'ordem invertida' (Ndcg @(1,2,3) @(3,2,1) 3) 0.680606 1e-5

    # Lista vazia: nao encontrou nada do que havia para encontrar.
    Conferir 'lista vazia' (Ndcg @() @(3,2,1) 5) 0.0

    # Tudo irrelevante nas cinco primeiras, o acerto na sexta: o corte em k e o
    # que faz esta consulta valer zero, e e isso que se quer de um typeahead -
    # a linha que ninguem ve nao conta.
    Conferir 'acerto fora do k' (Ndcg @(0,0,0,0,0,3) @(3) 5) 0.0

    # Nada julgado: nao avaliavel, e nao acerto perfeito.
    Conferir 'nada julgado' (Ndcg @(0,0) @() 5) $null

    # Primeiro lugar trocado por um vizinho de nota 2: a queda tem de ser
    # visivel, senao o portao nao serve para o que ele existe.
    $trocado = Ndcg @(2,3) @(3,2) 2
    if ($trocado -ge 0.95) {
        [void] $falhas.Add("troca de primeiro lugar quase nao mexeu no numero: $trocado")
    }

    Write-Host ''
    if ($falhas.Count -gt 0) {
        Write-Host 'AUTOTESTE REPROVADO. A conta do NDCG esta errada:' -ForegroundColor Red
        $falhas | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
        exit 1
    }
    Write-Host ("AUTOTESTE OK - 6 casos. Troca de primeiro lugar derruba para {0:N4}." -f $trocado) -ForegroundColor Green
    Write-Host ''
}

if ($Autoteste) {
    Autotestar
    exit 0
}

Autotestar

# ---------------------------------------------------------------------------
# A mesma normalizacao do dominio
# ---------------------------------------------------------------------------
# Identica a de conferir-piso.ps1, e pelo mesmo motivo: o julgamento e escrito
# com acento e caixa naturais ("financiamento de veiculo" com acento, "Tesouro
# Direto" com maiusculas) e o indice devolve exatamente isso. Comparar texto cru
# faria uma correcao de copy no catalogo zerar a nota de uma consulta sem que
# nada de relevancia tivesse mudado.
function ConverterParaChave([string] $texto) {
    if (-not $texto) { return '' }
    $limpo = ($texto.Normalize([Text.NormalizationForm]::FormD).ToCharArray() |
        Where-Object { [Globalization.CharUnicodeInfo]::GetUnicodeCategory($_) -ne 'NonSpacingMark' }) -join ''
    return ($limpo.ToLower() -replace '[^a-z0-9 ]', ' ' -replace '\s+', ' ').Trim()
}

# ---------------------------------------------------------------------------
# A impressao digital do corpus
# ---------------------------------------------------------------------------
# NDCG SO E COMPARAVEL CONTRA O MESMO CORPUS, e esta funcao existe porque isso
# me pegou: numa rodada, a consulta 'pix' caiu 0,1753 sem nenhuma mudanca de
# codigo. A causa eram duas, e as duas eram as proprias ferramentas de teste
# deste repositorio:
#
#   - a collection de cadastro grava ofertas de teste, e o indice passou de 99
#     para 104 documentos;
#   - as sondas e a collection de consumo postam BUSCA_DIGITADA, e a rodada de
#     aprendizado escreveu 'pix' no HISTORICO do pf-novo. Uma linha de historico
#     nao julgada entrou na posicao 1 e empurrou tres linhas julgadas para baixo.
#
# Nenhuma das duas e regressao de ranking, e sem esta impressao digital as duas
# aparecem como se fossem - o que gasta uma tarde e, pior, ensina a ignorar o
# portao. A contagem por tipo nao prova que o corpus e identico; ela prova que
# ele MUDOU, que e o que falta para atribuir a diferenca.
#
# Falha macia de proposito: o trabalho deste script e NDCG, nao Solr. Sem acesso
# ao indice ele mede igual e so nao consegue atribuir.
# Devolve a impressao digital JA COMO TEXTO CANONICO, e nao como dicionario.
#
# A primeira versao guardava um [ordered]@{} e montava a descricao na hora de
# comparar. Nao funcionou, e o modo de falha vale registrar: enumerar
# PSObject.Properties de um OrderedDictionary devolve as propriedades do objeto
# .NET - Count, IsReadOnly, Keys, Values - e nao as entradas. A descricao saia
# como 'Count=4 IsReadOnly=False...', diferente da que voltava do JSON, e o
# alarme disparava SEMPRE, dizendo que o corpus mudou quando ele estava
# identico.
#
# Um alarme que grita em toda rodada e pior que alarme nenhum: ele ensina quem
# le a ignora-lo, e ai ele nao serve no dia em que o corpus mudar de verdade.
# Com texto canonico - ordenado por nome, montado uma vez - a ida e volta pelo
# JSON e sem perda e a comparacao e de string.
function ImpressaoDoCorpus {
    try {
        $url = "$SolrUrl/$Colecao/select?q=*:*&rows=0&facet=true&facet.field=tipo&facet.mincount=1&wt=json"
        $r = Invoke-RestMethod -Uri $url -TimeoutSec 15

        $pares = New-Object System.Collections.ArrayList
        $f = $r.facet_counts.facet_fields.tipo
        for ($i = 0; $i -lt $f.Count; $i += 2) {
            [void] $pares.Add([pscustomobject] @{ tipo = [string] $f[$i]; n = [int] $f[$i + 1] })
        }

        # Ordenado por nome: a ordem do facet do Solr e por contagem, e ela muda
        # quando um tipo passa o outro. Sem a ordenacao, o alarme dispararia por
        # reordenacao sem que nenhum numero tivesse mudado.
        $texto = ($pares | Sort-Object tipo | ForEach-Object { "$($_.tipo)=$($_.n)" }) -join ' '

        return [pscustomobject] @{
            total     = [int] $r.response.numFound
            descricao = "$([int] $r.response.numFound) docs [$texto]"
        }
    } catch {
        return $null
    }
}

function DescreverCorpus($corpus) {
    if ($null -eq $corpus -or -not $corpus.descricao) { return '(indice nao consultado)' }
    return $corpus.descricao
}

# ---------------------------------------------------------------------------
# Entrada
# ---------------------------------------------------------------------------
if (-not (Test-Path $Julgamentos)) {
    throw "Conjunto de julgamento nao encontrado em '$Julgamentos'."
}

# -Encoding UTF8 e obrigatorio: o PowerShell 5.1 le arquivo sem BOM como ANSI, e
# os termos julgados tem acento. Sem isto, 'veiculo' com acento vira dois
# caracteres, a normalizacao os descarta de formas diferentes dos dois lados, e
# TODA consulta com acento passa a valer zero - um portao que reprova tudo e tao
# inutil quanto um que aprova tudo.
try {
    $conjunto = Get-Content -Path $Julgamentos -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    # O erro mais provavel aqui e chave duplicada, e a mensagem nativa nao diz o
    # porque: o ConvertFrom-Json do PowerShell 5.1 compara chaves SEM DISTINGUIR
    # CAIXA, entao "Tesouro Direto" e "tesouro direto" colidem. Como este script
    # compara tudo normalizado, as duas eram a mesma entrada de qualquer forma.
    throw @"
Nao foi possivel ler '$Julgamentos'.

$($_.Exception.Message)

Se a mensagem fala de chaves duplicadas: o ConvertFrom-Json do PowerShell 5.1
compara chaves sem distinguir caixa, e este script compara os termos
NORMALIZADOS - sem acento e em minusculas. Duas grafias do mesmo termo sao a
mesma entrada. Declare uma so. Ver 'comoAChaveEComparada' no proprio arquivo.
"@
}

Write-Host ("Avaliacao de ranking - conjunto versao {0}, {1} consultas." -f `
    $conjunto.versao, $conjunto.consultas.Count)
Write-Host "Endpoint: $BaseUrl/v1/sugestoes" -ForegroundColor DarkGray
Write-Host ''

# ---------------------------------------------------------------------------
# Tokens, um por identidade
# ---------------------------------------------------------------------------
# Gerados pelo mesmo script que a demonstracao usa, que le o mesmo segredo que a
# aplicacao le. Nao ha caminho de emissao dentro do servico, e este script nao
# cria um.
$gerador = Join-Path $PSScriptRoot 'gerar-token.ps1'
$tokens = @{}

function TokenPara($consulta) {
    $chave = '{0}|{1}|{2}' -f $consulta.cliente, $consulta.segmento, $consulta.relacionamento
    if (-not $tokens.ContainsKey($chave)) {
        $tokens[$chave] = & $gerador -Tipo cliente `
            -ClienteId $consulta.cliente `
            -Segmento $consulta.segmento `
            -Relacionamento $consulta.relacionamento `
            -Silencioso
    }
    return $tokens[$chave]
}

# ---------------------------------------------------------------------------
# Medicao
# ---------------------------------------------------------------------------
$linhas = New-Object System.Collections.ArrayList

foreach ($consulta in $conjunto.consultas) {

    $notasPorChave = @{}
    foreach ($prop in $consulta.julgamentos.PSObject.Properties) {
        $notasPorChave[(ConverterParaChave $prop.Name)] = [double] $prop.Value
    }

    $token = TokenPara $consulta
    $url = '{0}/v1/sugestoes?q={1}&limite={2}' -f $BaseUrl, [Uri]::EscapeDataString($consulta.q), $Limite

    # DECODIFICA UTF-8 EXPLICITAMENTE, e nao confia no cabecalho da resposta.
    #
    # Foi assim que o avaliador encontrou o primeiro defeito: com
    # Invoke-RestMethod, "empréstimo pessoal" chegava como "emprÃ©stimo pessoal",
    # e TODA consulta cujo melhor resultado tem acento media zero. A causa estava
    # no servidor - o caminho de sucesso nao declarava charset, inconsistencia ja
    # anotada nas limitacoes - e o Windows PowerShell 5.1 adivinha ISO-8859-1,
    # que e a escolha historicamente correta de quem precisa adivinhar.
    #
    # O servidor foi corrigido em ConfiguracaoWeb. Isto aqui fica de qualquer
    # forma, por dois motivos: o avaliador tem de medir instancia antiga tambem,
    # e um medidor que depende do cabecalho certo mede o cabecalho, nao o ranking.
    try {
        $web = Invoke-WebRequest -Uri $url -Headers @{ Authorization = "Bearer $token" } `
            -TimeoutSec 30 -UseBasicParsing
        $resposta = [Text.Encoding]::UTF8.GetString($web.RawContentStream.ToArray()) | ConvertFrom-Json
    } catch {
        throw "Falha ao consultar '$($consulta.q)': $($_.Exception.Message)`nA aplicacao esta no ar em $BaseUrl?"
    }

    $devolvidos = @($resposta.itens | ForEach-Object { $_.termo })
    $notasDevolvidas = @($devolvidos | ForEach-Object {
        $chave = ConverterParaChave $_
        if ($notasPorChave.ContainsKey($chave)) { $notasPorChave[$chave] } else { 0.0 }
    })

    $todasAsNotas = @($notasPorChave.Values)

    # Quantas linhas devolvidas ninguem julgou. E a metrica de saude DO CONJUNTO,
    # nao do ranking: alta significa que o catalogo andou e o julgamento ficou
    # para tras, e ai a media cai sem que nada de relevancia tenha piorado.
    $naoJulgados = @($notasDevolvidas | Where-Object { $_ -eq 0 }).Count

    [void] $linhas.Add([pscustomobject]@{
        q           = $consulta.q
        cliente     = $consulta.cliente
        julgados    = $todasAsNotas.Count
        devolvidos  = $devolvidos.Count
        naoJulgados = $naoJulgados
        degradada   = [bool] $resposta.degradada
        ndcg5       = Ndcg $notasDevolvidas $todasAsNotas 5
        ndcg8       = Ndcg $notasDevolvidas $todasAsNotas 8
        lista       = $devolvidos
    })
}

# ---------------------------------------------------------------------------
# Saida
# ---------------------------------------------------------------------------
function Formatar($valor) {
    if ($null -eq $valor) { return '  n/a' }
    return ('{0:N4}' -f $valor)
}

$linhas | ForEach-Object {
    $cor = 'Gray'
    if ($null -ne $_.ndcg5) {
        if ($_.ndcg5 -ge 0.9)      { $cor = 'Green' }
        elseif ($_.ndcg5 -lt 0.5)  { $cor = 'Yellow' }
    }
    Write-Host ("  {0,-13} {1,-22} dev={2,-3} nj={3,-3} @5={4} @8={5}" -f `
        $_.q, $_.cliente, $_.devolvidos, $_.naoJulgados, (Formatar $_.ndcg5), (Formatar $_.ndcg8)) `
        -ForegroundColor $cor

    if ($Detalhado) {
        if ($_.lista.Count -eq 0) {
            Write-Host '                  (lista vazia)' -ForegroundColor DarkGray
        } else {
            for ($i = 0; $i -lt $_.lista.Count; $i++) {
                Write-Host ("                  {0}. {1}" -f $i, $_.lista[$i]) -ForegroundColor DarkGray
            }
        }
    }
}

$avaliaveis = @($linhas | Where-Object { $null -ne $_.ndcg5 })
$naoAvaliaveis = $linhas.Count - $avaliaveis.Count

if ($avaliaveis.Count -eq 0) {
    Write-Host ''
    Write-Host 'Nenhuma consulta avaliavel. O conjunto de julgamento esta vazio ou invalido.' -ForegroundColor Red
    exit 1
}

$media5 = ($avaliaveis | Measure-Object -Property ndcg5 -Average).Average
$media8 = ($avaliaveis | Measure-Object -Property ndcg8 -Average).Average
$vazias = @($linhas | Where-Object { $_.devolvidos -eq 0 }).Count
$totalDevolvidos = ($linhas | Measure-Object -Property devolvidos -Sum).Sum
$totalNaoJulgados = ($linhas | Measure-Object -Property naoJulgados -Sum).Sum

Write-Host ''
Write-Host ('  NDCG@5 medio : {0:N4}' -f $media5)
Write-Host ('  NDCG@8 medio : {0:N4}' -f $media8)
Write-Host ('  listas vazias: {0} de {1}' -f $vazias, $linhas.Count)
Write-Host ('  nao julgados : {0} de {1} linhas devolvidas' -f $totalNaoJulgados, $totalDevolvidos) `
    -ForegroundColor DarkGray
if ($naoAvaliaveis -gt 0) {
    Write-Host ('  nao avaliaveis: {0} consultas sem julgamento utilizavel' -f $naoAvaliaveis) `
        -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# Linha de base
# ---------------------------------------------------------------------------
$corpus = ImpressaoDoCorpus
Write-Host ('  corpus       : {0}' -f (DescreverCorpus $corpus)) -ForegroundColor DarkGray

$atual = [pscustomobject]@{
    medidoEm       = (Get-Date).ToString('o')
    versaoConjunto = $conjunto.versao
    corpus         = $corpus
    media5         = $media5
    media8         = $media8
    porConsulta    = @($linhas | ForEach-Object {
        [pscustomobject]@{ q = $_.q; cliente = $_.cliente; ndcg5 = $_.ndcg5; ndcg8 = $_.ndcg8 }
    })
}

if ($SalvarLinhaDeBase) {
    $atual | ConvertTo-Json -Depth 5 | Out-File -FilePath $LinhaDeBase -Encoding utf8
    Write-Host ''
    Write-Host "Linha de base gravada em $LinhaDeBase." -ForegroundColor Green
    exit 0
}

if (-not (Test-Path $LinhaDeBase)) {
    Write-Host ''
    Write-Host "Sem linha de base em '$LinhaDeBase'. Rode com -SalvarLinhaDeBase para criar." -ForegroundColor Yellow
    exit 0
}

$base = Get-Content -Path $LinhaDeBase -Raw -Encoding UTF8 | ConvertFrom-Json

if ($base.versaoConjunto -ne $conjunto.versao) {
    Write-Host ''
    Write-Host ("A linha de base foi medida com o conjunto versao {0} e o atual e {1}." -f `
        $base.versaoConjunto, $conjunto.versao) -ForegroundColor Yellow
    Write-Host 'Comparar medias entre conjuntos diferentes nao diz nada. Regrave a linha de base.' -ForegroundColor Yellow
    exit 1
}

$delta5 = $media5 - $base.media5
$delta8 = $media8 - $base.media8

# O corpus mudou? Se sim, diga ANTES dos numeros. Uma diferenca de NDCG sobre
# corpus diferente nao e evidencia de nada, e descobrir isso depois de acreditar
# no numero e o caminho mais curto para alguem parar de olhar o portao.
$corpusMudou = $false
if ($null -ne $corpus -and $null -ne $base.corpus) {
    $corpusMudou = (DescreverCorpus $corpus) -ne (DescreverCorpus $base.corpus)
}

if ($corpusMudou) {
    Write-Host ''
    Write-Host 'ATENCAO: O CORPUS MUDOU desde a linha de base.' -ForegroundColor Yellow
    Write-Host ("  linha de base: {0}" -f (DescreverCorpus $base.corpus)) -ForegroundColor Yellow
    Write-Host ("  agora        : {0}" -f (DescreverCorpus $corpus)) -ForegroundColor Yellow
    Write-Host '  A diferenca de NDCG abaixo pode ser do indice, nao do ranking.' -ForegroundColor Yellow
    Write-Host '  Ofertas de teste saem com scripts\limpar-ofertas-de-teste.ps1.' -ForegroundColor DarkGray
    Write-Host ''
    Write-Host '  E o PERFIL tambem muda, sem aparecer aqui: as sondas e as collections' -ForegroundColor DarkGray
    Write-Host '  postam BUSCA_DIGITADA, e o aprendizado escreve o termo no historico do' -ForegroundColor DarkGray
    Write-Host '  cliente. Uma linha de historico nao julgada empurra as julgadas para' -ForegroundColor DarkGray
    Write-Host '  baixo. Reiniciar a aplicacao zera o perfil; o indice nao.' -ForegroundColor DarkGray
}

Write-Host ''
Write-Host ('  linha de base: @5={0:N4}  @8={1:N4}  ({2})' -f `
    $base.media5, $base.media8, $base.medidoEm) -ForegroundColor DarkGray
Write-Host ('  delta        : @5={0:+0.0000;-0.0000; 0.0000}  @8={1:+0.0000;-0.0000; 0.0000}' -f `
    $delta5, $delta8)

# As consultas que se mexeram, e nao so a media. Uma media estavel esconde duas
# consultas que trocaram de lugar - e e exatamente isso que uma mudanca de peso
# faz.
$porQ = @{}
foreach ($item in $base.porConsulta) { $porQ['{0}|{1}' -f $item.q, $item.cliente] = $item }

$mexeram = New-Object System.Collections.ArrayList
foreach ($linha in $linhas) {
    $chave = '{0}|{1}' -f $linha.q, $linha.cliente
    if (-not $porQ.ContainsKey($chave)) { continue }

    $antes = $porQ[$chave].ndcg5
    if ($null -eq $antes -or $null -eq $linha.ndcg5) { continue }

    $d = $linha.ndcg5 - $antes
    if ([Math]::Abs($d) -gt $Tolerancia) {
        [void] $mexeram.Add([pscustomobject]@{
            q = $linha.q; antes = $antes; agora = $linha.ndcg5; delta = $d
        })
    }
}

if ($mexeram.Count -gt 0) {
    Write-Host ''
    Write-Host '  consultas que mudaram de NDCG@5:'
    $mexeram | Sort-Object delta | ForEach-Object {
        $cor = if ($_.delta -lt 0) { 'Red' } else { 'Green' }
        Write-Host ('    {0,-13} {1:N4} -> {2:N4}  ({3:+0.0000;-0.0000})' -f `
            $_.q, $_.antes, $_.agora, $_.delta) -ForegroundColor $cor
    }
}

Write-Host ''
if ($delta5 -lt (-1 * $Tolerancia)) {
    Write-Host ('PORTAO FECHADO. NDCG@5 caiu {0:N4}, acima da tolerancia de {1:N4}.' -f `
        (-1 * $delta5), $Tolerancia) -ForegroundColor Red
    exit 1
}

Write-Host 'PORTAO ABERTO. A ordem da lista nao piorou.' -ForegroundColor Green
