<#
    Portao de publicacao da colecao 'sugestoes'.

        .\scripts\conferir-piso.ps1

    Sai com codigo 1 se qualquer invariante for violada, para servir de passo de
    build. Sao dois alarmes, e nenhum deles tem faixa aceitavel: qualquer valor
    acima de zero e defeito de construcao, nao de relevancia.

      1. K-ANONIMATO   Nenhum termo DE LOG abaixo do piso de clientes distintos
                       pode aparecer em nenhuma consulta, para nenhum prefixo. O
                       teste usa exatamente os mesmos fq da producao - se o
                       filtro nao for o mesmo, o portao nao prova nada.

                       "De log" quer dizer tipo:consulta, e a restricao e o
                       ponto. O piso protege termo digitado por gente: um termo
                       que so tres pessoas buscaram, devolvido a um quarto,
                       conta algo sobre aquelas tres. Nome de produto que o
                       banco oferece nao conta nada sobre ninguem, e oferta
                       recem-cadastrada tem zero clientes distintos porque
                       ninguem a buscou ainda.

                       Enquanto este alarme olhava todos os tipos, ele acusava
                       cada item de catalogo como vazamento assim que o contador
                       falso saiu do semeador - 44 alarmes falsos, que e o
                       numero exato de alarmes que fazem um portao ser ignorado.

      2. ZERO-RESULTADO
                       Todo termo publicado precisa ser alcancavel por alguma
                       coisa que uma pessoa digitaria. No desenho este alarme e
                       mais forte: o termo e reexecutado contra o indice de
                       CONTEUDO e precisa devolver resultado hoje. Aqui nao ha
                       indice de conteudo, entao o que se verifica e a versao
                       fraca - se a analise de prefixo ou um fq estiver
                       derrubando o termo em silencio, aparece aqui.

                       Alcancavel quer dizer: o inicio da frase OU o inicio de
                       qualquer palavra dela. Exigir so o inicio da frase
                       reprovaria "o que e CET", que comeca com uma palavra de
                       uma letra - o edismax quebra a consulta no espaco antes
                       da analise de campo, entao o prefixo "o " vira o termo
                       "o", abaixo do minGram de 2. Ninguem digita so "o" para
                       chegar nele; digita "que" ou "cet", e por esses ele
                       aparece.

    Nota de codificacao: este arquivo e ASCII puro, como os outros scripts do
    repositorio. O Windows PowerShell 5.1 le .ps1 sem BOM como ANSI, e um
    travessao dentro de uma string vira tres bytes cujo ultimo o parser entende
    como aspa de fechamento. A string termina cedo e o resto do arquivo passa a
    ser lido como codigo.
#>
[CmdletBinding()]
param(
    [string]   $BaseUrl = 'http://localhost:8983/solr',
    [string]   $Colecao = 'sugestoes',
    [int]      $Piso    = 30,
    [string[]] $TiposSugeriveis = @('consulta', 'acao', 'entidade', 'oferta'),
    [int]      $PrefixoMaximo = 12
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

function ConverterParaChave([string] $texto) {
    $limpo = ($texto.Normalize([Text.NormalizationForm]::FormD).ToCharArray() |
        Where-Object { [Globalization.CharUnicodeInfo]::GetUnicodeCategory($_) -ne 'NonSpacingMark' }) -join ''
    return ($limpo.ToLower() -replace '[^a-z0-9 ]', ' ' -replace '\s+', ' ').Trim()
}

function Escapar([string] $texto) {
    return [Uri]::EscapeDataString($texto)
}

function Selecionar([string[]] $parametros) {
    $url = "$BaseUrl/$Colecao/select?" + ($parametros -join '&')
    return (Invoke-RestMethod -Uri $url -TimeoutSec 60).response
}

# Os mesmos filtros de ConsultaDeCandidatos.java. Se um dos dois mudar sem o
# outro, este portao passa a aprovar o que a producao recusa, ou o contrario.
#
# O fq do piso e disjuntivo, e lido em voz alta e a regra: "ou nao e termo do
# log, ou limpa o piso". Precisa ser identico ao de ConsultaDeCandidatos.java.
$FqDoPiso = "(*:* -tipo:consulta) OR clientes_distintos_i:[$Piso TO *]"

function ConsultarComoAProducao([string] $prefixo, [string] $segmento, [int] $linhas) {
    $fq = @(
        "tipo:($($TiposSugeriveis -join ' OR '))"
        'vigencia_inicio_dt:[* TO NOW]'
        'vigencia_fim_dt:[NOW TO *]'
        "{!terms f=segmento_ss}$segmento"
        $FqDoPiso
    )

    $parametros = @(
        "q=$(Escapar $prefixo)"
        'defType=edismax'
        "qf=$(Escapar 'termo_prefixo^6 termo_infixo^2 sinonimo^3')"
        "rows=$linhas"
        'fl=termo'
        'wt=json'
    ) + ($fq | ForEach-Object { "fq=$(Escapar $_)" })

    return (Selecionar $parametros).docs.termo
}

Write-Host "Portao de publicacao - colecao '$Colecao', piso de $Piso clientes distintos."
Write-Host ''

# ---------------------------------------------------------------------------
# 1. K-anonimato
# ---------------------------------------------------------------------------
# Apenas tipo:consulta. Sao estes que o piso protege, e so eles que ele deve
# acusar. Ver a nota de cabecalho.
$abaixoDoPiso = @((Selecionar @(
    'q=*:*'
    'rows=1000'
    'fl=termo'
    'wt=json'
    'fq=tipo:consulta'
    "fq=$(Escapar "clientes_distintos_i:[* TO $($Piso - 1)]")"
)).docs.termo)

Write-Host ("1. K-anonimato: {0} termos de log abaixo do piso." -f $abaixoDoPiso.Count)

$vazamentos = New-Object System.Collections.ArrayList
$prefixosTestados = 0

foreach ($termo in $abaixoDoPiso) {
    $chave = ConverterParaChave $termo
    $limite = [Math]::Min($chave.Length, $PrefixoMaximo)

    for ($n = 2; $n -le $limite; $n++) {
        $prefixo = $chave.Substring(0, $n).Trim()
        if ($prefixo.Length -lt 2) { continue }
        $prefixosTestados++

        foreach ($segmento in @('PF', 'PJ')) {
            $devolvidos = ConsultarComoAProducao $prefixo $segmento 50
            if ($devolvidos -contains $termo) {
                [void] $vazamentos.Add([pscustomobject]@{
                    Termo = $termo; Prefixo = $prefixo; Segmento = $segmento
                })
            }
        }
    }
}

Write-Host ("   {0} prefixos testados nos dois segmentos." -f $prefixosTestados)
if ($vazamentos.Count -gt 0) {
    Write-Host ''
    Write-Host 'VAZAMENTO: termo abaixo do piso apareceu numa consulta de producao.' -ForegroundColor Red
    $vazamentos | Format-Table -AutoSize | Out-String | Write-Host
} else {
    Write-Host '   ok - nenhum deles aparece para nenhum prefixo.' -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# 2. Zero-resultado
# ---------------------------------------------------------------------------
Write-Host ''
# O mesmo fq do piso que a producao usa, e nao 'clientes_distintos_i >= piso'.
#
# A diferenca nao e cosmetica. Com o filtro antigo, assim que o contador falso
# saiu do catalogo este alarme passou a enxergar 49 documentos em vez de 93:
# todos os itens de catalogo e TODAS as ofertas cadastradas saiam da amostra, em
# silencio. O portao continuava verde porque estava conferindo menos, nao porque
# estava tudo certo - um alarme que encolhe a propria cobertura e pior que um
# alarme ausente, porque ele passa a dar garantia de algo que nao verifica.
$publicados = @((Selecionar @(
    'q=*:*'
    'rows=5000'
    'fl=termo,segmento_ss'
    'wt=json'
    "fq=$(Escapar $FqDoPiso)"
    "fq=$(Escapar "tipo:($($TiposSugeriveis -join ' OR '))")"
)).docs)

Write-Host ("2. Zero-resultado: {0} termos sugeriveis a conferir." -f $publicados.Count)

# O inicio da frase e o inicio de cada palavra: os prefixos que alguem digitaria
# para chegar neste termo. Dois caracteres, que e o minGram dos dois campos.
function PrefixosPlausiveis([string] $chave) {
    $candidatos = New-Object System.Collections.ArrayList

    foreach ($pedaco in @($chave) + @($chave -split ' ')) {
        $limpo = $pedaco.Trim()
        if ($limpo.Length -ge 2) {
            [void] $candidatos.Add($limpo.Substring(0, 2))
        }
    }
    return $candidatos | Select-Object -Unique
}

$inalcancaveis = New-Object System.Collections.ArrayList
foreach ($doc in $publicados) {
    $chave = ConverterParaChave $doc.termo
    if ($chave.Length -lt 2) { continue }

    $segmento = @($doc.segmento_ss)[0]
    $achou = $false

    foreach ($prefixo in PrefixosPlausiveis $chave) {
        if ((ConsultarComoAProducao $prefixo $segmento 500) -contains $doc.termo) {
            $achou = $true
            break
        }
    }

    if (-not $achou) {
        [void] $inalcancaveis.Add([pscustomobject]@{ Termo = $doc.termo; Segmento = $segmento })
    }
}

if ($inalcancaveis.Count -gt 0) {
    Write-Host ''
    Write-Host 'INALCANCAVEL: termo publicado que o proprio prefixo nao encontra.' -ForegroundColor Red
    $inalcancaveis | Select-Object -First 20 | Format-Table -AutoSize | Out-String | Write-Host
} else {
    Write-Host '   ok - todo termo publicado e alcancavel pelo proprio prefixo.' -ForegroundColor Green
}

# ---------------------------------------------------------------------------
Write-Host ''
if ($vazamentos.Count -gt 0 -or $inalcancaveis.Count -gt 0) {
    Write-Host 'PORTAO FECHADO. A colecao nao deve ser publicada.' -ForegroundColor Red
    exit 1
}
Write-Host 'PORTAO ABERTO. As duas invariantes estao de pe.' -ForegroundColor Green
