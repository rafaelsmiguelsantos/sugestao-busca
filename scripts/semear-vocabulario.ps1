<#
    Preenche a colecao 'sugestoes' com vocabulario bancario sintetico.

        .\scripts\semear-vocabulario.ps1
        .\scripts\semear-vocabulario.ps1 -Limpar

    Tres blocos entram no indice:

      1. CATALOGO   solr\vocabulario-catalogo.json, curado a mao. Produto, acao e
                    artigo de ajuda, cada um com a redacao alternativa do cliente no
                    campo sinonimo. Nao passa pelo piso de k-anonimato: e catalogo,
                    nao tem cliente por tras.

      2. CONSULTAS  geradas de verbo x objeto, como se tivessem vindo do log de
                    consulta. Contadores plausiveis e deterministicos — a mesma
                    semente devolve sempre os mesmos numeros, entao comparar duas
                    rodadas tem sentido.

      3. BLOQUEADOS consultas abaixo do piso de clientes distintos, incluindo duas
                    que parecem dado pessoal. Entram no indice de proposito: sao a
                    prova de que o fq de k-anonimato funciona. Se alguma delas
                    aparecer numa sugestao, o filtro esta quebrado.

    Nenhum dado real. Os numeros sao inventados para mostrar o mecanismo.
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:8983/solr',
    [string] $Colecao = 'sugestoes',
    [switch] $Limpar
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$raiz = Split-Path -Parent $PSScriptRoot
$agora = Get-Date

function ConverterParaChave([string] $texto) {
    $limpo = ($texto.Normalize([Text.NormalizationForm]::FormD).ToCharArray() |
        Where-Object { [Globalization.CharUnicodeInfo]::GetUnicodeCategory($_) -ne 'NonSpacingMark' }) -join ''
    return ($limpo.ToLower() -replace '[^a-z0-9 ]', ' ' -replace '\s+', ' ').Trim()
}

# Devolve sempre um array de verdade, inclusive vazio.
#
# Sem isto, 'gatilho_ss = if ($x) { @($x) } else { @() }' chega ao JSON como
# null: o PowerShell desenrola o @() de um ramo de if e sobra $null. E o Solr
# le campo nulo como documento filho anonimo — cada item de catalogo sem gatilho
# criava um documento fantasma 'cat-xxx/gatilho_ss#' no indice.
function ComoLista($valor) {
    if ($null -eq $valor) { return , @() }
    return , @($valor)
}

# Numeros estaveis a partir do proprio termo: rodar de novo nao muda o ranking.
function SementeDe([string] $texto) {
    $soma = 0
    foreach ($c in $texto.ToCharArray()) { $soma = ($soma * 31 + [int] $c) % 100000 }
    return $soma
}

# A identidade da oferta.
#
# Item de catalogo sem produto nao e oferta: acao ('segunda via do cartao') e
# navegacao, entidade sem produto e vocabulario. Esses ficam com oferta_id_s
# vazio de proposito, e a API nunca os devolve como oferta acionavel.
#
# Para quem tem produto, o id e derivado de (jornada, produto) em vez de
# sorteado, por um motivo so: semear de novo nao pode trocar a identidade das
# ofertas. Se o id mudasse a cada execucao, toda impressao e todo clique
# registrados antes apontariam para oferta que nao existe mais, e o caminho de
# aprendizado perderia o historico a cada reindexacao.
#
# Em producao isto nao se deriva: o id vem do cadastro de ofertas, e o POST
# /v1/ofertas o exige no corpo. A derivacao aqui existe porque este catalogo e
# um arquivo curado a mao, e um arquivo a mao com 18 codigos digitados tem erro
# de digitacao. O campo 'ofertaId' do JSON tem precedencia quando presente.
function IdDaOferta($item) {
    if ($item.ofertaId) { return [string] $item.ofertaId }
    if (-not $item.produto) { return '' }

    $jornada = ([string] $item.jornada).ToUpperInvariant() -replace '[^A-Z0-9]', '-'
    $produto = ([string] $item.produto).ToUpperInvariant() -replace '[^A-Z0-9]', '-'
    return "OFR-$jornada-$produto"
}

# As faixas de relacionamento. Um item que nao declara faixa vale para todas, e
# isso precisa estar escrito em cada documento: o corte e um {!terms}, e campo
# vazio nao casa com nada.
$TodasAsFaixas = @('VAREJO', 'UNICLASS', 'PERSONNALITE', 'PRIVATE')

$documentos = New-Object System.Collections.ArrayList

# ---------------------------------------------------------------------------
# 1. Catalogo
# ---------------------------------------------------------------------------
$catalogo = Get-Content (Join-Path $raiz 'solr\vocabulario-catalogo.json') -Raw -Encoding UTF8 | ConvertFrom-Json

foreach ($item in $catalogo) {
    $chave = ConverterParaChave $item.termo
    $s = SementeDe $chave
    # Item com produto e oferta, e o tipo diz isso. Antes caia em 'entidade'
    # junto com vocabulario sem produto, e o indice nao tinha como distinguir
    # "coisa que o cliente pode contratar" de "palavra que ele pode digitar".
    # O POST /v1/ofertas grava 'oferta', e os dois caminhos precisam concordar:
    # oferta semeada e oferta cadastrada tem de ser o mesmo tipo de documento.
    $tipo = if ($item.tipo) { $item.tipo }
            elseif ($item.produto) { 'oferta' }
            else { 'entidade' }
    $segmentos = if ($item.segmento) { ComoLista $item.segmento } else { ComoLista 'PF' }

    # Item sem faixa declarada vale para todas. Precisa ser escrito por extenso:
    # o fq usa {!terms f=relacionamento_ss}, e ausencia de valor nao casa com
    # nada — um documento sem faixa ficaria invisivel para todo mundo.
    $relacionamentos = if ($item.relacionamento) {
        ComoLista $item.relacionamento
    } else {
        $TodasAsFaixas
    }

    [void] $documentos.Add([ordered] @{
        id                    = "cat-$($chave -replace ' ', '-')"
        termo                 = $item.termo
        sinonimo              = ComoLista $item.sinonimo
        tipo                  = $tipo
        jornada_s             = $item.jornada
        produto_alvo_s        = if ($item.produto) { $item.produto } else { '' }
        oferta_id_s           = IdDaOferta $item
        destino_s             = "/app/$($item.jornada)/$($chave -replace ' ', '-')"
        rotulo_s              = if ($item.rotulo) { $item.rotulo } else { '' }
        segmento_ss           = $segmentos
        relacionamento_ss     = $relacionamentos
        canal_ss              = ComoLista 'app'
        gatilho_ss            = ComoLista $item.gatilho
        exige_elegibilidade_b = [bool] $item.elegibilidade
        veta_inadimplencia_b  = [bool] $item.vetaInadimplencia
        exige_conta_ativa_b   = [bool] $item.exigeContaAtiva
        vigencia_inicio_dt    = $agora.AddDays(-60).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        vigencia_fim_dt       = $agora.AddDays(365).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        visto_em_dt           = $agora.AddHours(-($s % 48)).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        freq_30d_i            = 2000 + ($s % 40000)

        # Zero, e nao um numero alto inventado.
        #
        # Catalogo nao e agregado de log: nao existe "quantos clientes distintos
        # buscaram o produto emprestimo pessoal" medido em lugar nenhum deste
        # projeto. O numero alto estava aqui so para o item passar pelo fq do
        # piso de k-anonimato, que valia para todos os tipos. Agora o fq vale so
        # para tipo:consulta, e este campo pode dizer a verdade: nao medimos.
        clientes_distintos_i  = 0

        ctr_f                 = [double] $item.ctr
        taxa_adesao_f         = [double] $item.adesao
    })
}

# ---------------------------------------------------------------------------
# 2. Consultas do log — verbo x objeto, so combinacoes que alguem diria
# ---------------------------------------------------------------------------
$grade = [ordered] @{
    'consultar' = @('saldo', 'extrato', 'fatura', 'limite', 'tarifas', 'pontos', 'rendimento')
    'ver'       = @('extrato', 'fatura', 'comprovante', 'limite do pix', 'meus investimentos')
    'pagar'     = @('boleto', 'fatura', 'conta de luz', 'conta de agua')
    'transferir'= @('dinheiro', 'para outro banco', 'por pix', 'para minha conta')
    'simular'   = @('emprestimo', 'financiamento', 'consignado', 'investimento')
    'contratar' = @('emprestimo', 'seguro', 'cdb', 'cartao')
    'agendar'   = @('pix', 'pagamento', 'transferencia')
    'alterar'   = @('limite do pix', 'senha', 'cadastro', 'vencimento da fatura')
    'cancelar'  = @('cartao', 'seguro', 'assinatura', 'pix agendado')
    'emitir'    = @('boleto', 'comprovante', 'informe de rendimentos')
    'bloquear'  = @('cartao', 'conta', 'pix')
    'aumentar'  = @('limite do cartao', 'limite do pix')
}

foreach ($verbo in $grade.Keys) {
    foreach ($objeto in $grade[$verbo]) {
        $termo = "$verbo $objeto"
        $s = SementeDe $termo
        $segmentos = if ($s % 7 -eq 0) { @('PF', 'PJ') } else { @('PF') }

        [void] $documentos.Add([ordered] @{
            id                    = "log-$($termo -replace ' ', '-')"
            termo                 = $termo
            sinonimo              = @()
            tipo                  = 'consulta'
            jornada_s             = 'busca'
            produto_alvo_s        = ''
            oferta_id_s           = ''
            destino_s             = "/busca?q=$($termo -replace ' ', '+')"
            rotulo_s              = ''
            segmento_ss           = $segmentos
            relacionamento_ss     = $TodasAsFaixas
            canal_ss              = @('app')
            gatilho_ss            = @()
            exige_elegibilidade_b = $false
            veta_inadimplencia_b  = $false
            exige_conta_ativa_b   = $false
            vigencia_inicio_dt    = $agora.AddDays(-30).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
            vigencia_fim_dt       = $agora.AddDays(365).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
            visto_em_dt           = $agora.AddHours(-($s % 72)).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
            freq_30d_i            = 200 + ($s % 9000)
            clientes_distintos_i  = 60 + ($s % 4000)
            ctr_f                 = [math]::Round(0.10 + (($s % 45) / 100.0), 3)
            taxa_adesao_f         = [math]::Round(0.01 + (($s % 12) / 100.0), 3)
        })
    }
}

# ---------------------------------------------------------------------------
# 3. Bloqueados — abaixo do piso. Existem para provar o filtro.
# ---------------------------------------------------------------------------
$bloqueados = @(
    @{ termo = 'adiantamento para maria silva'; k = 2 },
    @{ termo = 'adiantar para cpf 000 000 000 00'; k = 1 },
    @{ termo = 'consultar saldo da conta 12345 6'; k = 3 },
    @{ termo = 'pagar boleto do joao'; k = 4 },
    @{ termo = 'simular emprestimo de 37 reais'; k = 11 },
    @{ termo = 'transferir para a loja do bairro'; k = 19 },
    @{ termo = 'ver extrato de 2019'; k = 24 },
    @{ termo = 'cancelar seguro da moto antiga'; k = 29 }
)

foreach ($b in $bloqueados) {
    $s = SementeDe $b.termo
    [void] $documentos.Add([ordered] @{
        id                    = "baixo-$($b.termo -replace ' ', '-')"
        termo                 = $b.termo
        sinonimo              = @()
        tipo                  = 'consulta'
        jornada_s             = 'busca'
        produto_alvo_s        = ''
        oferta_id_s           = ''
        destino_s             = "/busca?q=$($b.termo -replace ' ', '+')"
        rotulo_s              = ''
        segmento_ss           = @('PF')
        relacionamento_ss     = $TodasAsFaixas
        canal_ss              = @('app')
        gatilho_ss            = @()
        exige_elegibilidade_b = $false
        veta_inadimplencia_b  = $false
        exige_conta_ativa_b   = $false
        vigencia_inicio_dt    = $agora.AddDays(-10).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        vigencia_fim_dt       = $agora.AddDays(365).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        visto_em_dt           = $agora.AddHours(-1).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        freq_30d_i            = $b.k * 3
        clientes_distintos_i  = $b.k
        # Contadores altos de proposito: se o unico freio fosse o ranking, estes
        # termos subiriam. O que os segura e o fq, nao o score.
        ctr_f                 = 0.90
        taxa_adesao_f         = 0.80
    })
}

# ---------------------------------------------------------------------------
# Envio
# ---------------------------------------------------------------------------
$url = "$BaseUrl/$Colecao/update?commit=true"

if ($Limpar) {
    Write-Host 'Apagando o conteudo anterior da colecao...'
    $apagar = '{ "delete": { "query": "*:*" } }'
    Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' `
                      -Body ([Text.Encoding]::UTF8.GetBytes($apagar)) -TimeoutSec 60 | Out-Null
}

Write-Host ("Enviando {0} documentos para {1}..." -f $documentos.Count, $Colecao)
$json = ConvertTo-Json -InputObject @($documentos) -Depth 6 -Compress
Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' `
                  -Body ([Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 180 | Out-Null

$total = (Invoke-RestMethod -Uri "$BaseUrl/$Colecao/select?q=*:*&rows=0&wt=json" -TimeoutSec 30).response.numFound
Write-Host ("No indice agora: {0} documentos." -f $total)

# ---------------------------------------------------------------------------
# Conferencia: o piso de k-anonimato esta de pe?
# ---------------------------------------------------------------------------
$piso = 30
$fuga = Invoke-RestMethod -TimeoutSec 30 -Uri (
    "$BaseUrl/$Colecao/select?wt=json&rows=20&q=*:*" +
    "&fq=tipo:consulta&fq=clientes_distintos_i:[* TO $($piso - 1)]")

Write-Host ''
Write-Host ("Termos abaixo do piso de {0} clientes distintos: {1}" -f $piso, $fuga.response.numFound)
Write-Host 'Eles estao no indice e NAO podem aparecer em nenhuma sugestao.'
foreach ($d in $fuga.response.docs) {
    Write-Host ("  k={0,-3} {1}" -f $d.clientes_distintos_i, $d.termo)
}
Write-Host ''
Write-Host "Confira com:  .\scripts\conferir-piso.ps1"
