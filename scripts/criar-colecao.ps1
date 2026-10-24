<#
    Cria a colecao 'sugestoes' e aplica solr\schema-sugestoes.json.

    Funciona igual contra o Solr do zip (scripts\solr-local.ps1 start) e contra o
    do container (docker compose up -d), porque os dois partem do configset _default.

        pwsh scripts\criar-colecao.ps1

    Rodar de novo depois de ter dado certo reporta "already exists" campo a campo.
    Isso e esperado: a Schema API nao e idempotente. Para comecar do zero, apague
    o core (zip: tools\solr-10.0.0\server\solr\sugestoes; container: docker compose down -v).
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:8983/solr',
    [string] $Colecao = 'sugestoes',
    [int]    $EsperaSegundos = 60
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$raiz    = Split-Path -Parent $PSScriptRoot
$arquivo = Join-Path $raiz 'solr\schema-sugestoes.json'
if (-not (Test-Path $arquivo)) { throw "Schema nao encontrado: $arquivo" }

# --- 1. esperar o Solr responder -------------------------------------------
Write-Host "Aguardando o Solr em $BaseUrl ..." -NoNewline
$pronto = $false
for ($i = 0; $i -lt $EsperaSegundos; $i++) {
    try {
        Invoke-RestMethod -Uri "$BaseUrl/admin/info/system?wt=json" -TimeoutSec 3 | Out-Null
        $pronto = $true
        break
    } catch {
        Start-Sleep -Seconds 1
        Write-Host '.' -NoNewline
    }
}
Write-Host ''
if (-not $pronto) {
    throw "Solr nao respondeu em $EsperaSegundos s. Suba com 'pwsh scripts\solr-local.ps1 start' ou 'docker compose up -d'."
}

# --- 2. criar o core, se ainda nao existir ---------------------------------
$existe = $false
try {
    $st = Invoke-RestMethod -Uri "$BaseUrl/admin/cores?action=STATUS&core=$Colecao&wt=json" -TimeoutSec 15
    if ($st.status.$Colecao.name) { $existe = $true }
} catch {
    # Em modo cloud a API de cores responde diferente; tratamos como inexistente
    # e deixamos o CREATE decidir.
}

if ($existe) {
    Write-Host "Colecao '$Colecao' ja existe."
} else {
    Write-Host "Criando a colecao '$Colecao' a partir do configset _default..."
    $url = "$BaseUrl/admin/cores?action=CREATE&name=$Colecao&configSet=_default&wt=json"
    try {
        Invoke-RestMethod -Uri $url -TimeoutSec 60 | Out-Null
        Write-Host "Criada."
    } catch {
        throw "Falhou ao criar a colecao: $($_.Exception.Message)"
    }
}

# --- 3. aplicar o schema ----------------------------------------------------
Write-Host "Aplicando $arquivo ..."
$corpo = Get-Content $arquivo -Raw -Encoding UTF8
try {
    $r = Invoke-RestMethod -Uri "$BaseUrl/$Colecao/schema" -Method Post `
                           -ContentType 'application/json; charset=utf-8' `
                           -Body ([Text.Encoding]::UTF8.GetBytes($corpo)) -TimeoutSec 120
    if ($r.errors) {
        Write-Warning 'O Solr recusou parte do schema:'
        $r.errors | ConvertTo-Json -Depth 6 | Write-Output
        exit 1
    }
    Write-Host 'Schema aplicado.'
} catch {
    Write-Warning "Resposta do Solr: $($_.Exception.Message)"
    if ($_.ErrorDetails.Message) { Write-Output $_.ErrorDetails.Message }
    exit 1
}

# --- 4. conferir ------------------------------------------------------------
$campos = (Invoke-RestMethod -Uri "$BaseUrl/$Colecao/schema/fields?wt=json" -TimeoutSec 30).fields
$meus = @('termo','termo_prefixo','termo_infixo','sinonimo','clientes_distintos_i','taxa_adesao_f','gatilho_ss')
Write-Host ''
Write-Host 'Campos que importam:'
foreach ($c in $meus) {
    $achou = $campos | Where-Object { $_.name -eq $c }
    if ($achou) { Write-Host ("  ok     {0,-24} {1}" -f $c, $achou.type) }
    else        { Write-Host ("  FALTA  {0}" -f $c) }
}
Write-Host ''
Write-Host "Painel: $($BaseUrl.Replace('/solr','/solr/#/'))$Colecao"
