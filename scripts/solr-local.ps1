<#
    Sobe o Solr 10 a partir do zip instalado em tools\ — sem container e sem admin.
    E o caminho usado enquanto nao houver runtime de container nesta maquina.
    Mesma porta e mesma colecao do compose.yaml, entao o resto do projeto nao muda.

        pwsh scripts\solr-local.ps1 start
        pwsh scripts\solr-local.ps1 status
        pwsh scripts\solr-local.ps1 stop

    Painel: http://localhost:8983/solr
#>
[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'restart', 'status')]
    [string] $Acao = 'start',

    [string] $SolrHome,
    [string] $JavaHome,
    [int]    $Porta = 8983
)

$ErrorActionPreference = 'Stop'

if (-not $SolrHome) {
    if ($env:SOLR_INSTALL) { $SolrHome = $env:SOLR_INSTALL }
    else { $SolrHome = 'C:\Users\migue\tools\solr-10.0.0' }
}
if (-not $JavaHome) {
    if ($env:JAVA_25_HOME) { $JavaHome = $env:JAVA_25_HOME }
    else { $JavaHome = 'C:\Users\migue\tools\jdk-25' }
}

$solr = Join-Path $SolrHome 'bin\solr.cmd'
if (-not (Test-Path $solr))     { throw "Solr nao encontrado em $SolrHome. Ajuste -SolrHome ou a variavel SOLR_INSTALL." }
if (-not (Test-Path $JavaHome)) { throw "JDK nao encontrado em $JavaHome. Ajuste -JavaHome ou a variavel JAVA_25_HOME." }

# O Solr 10 exige Java 21+. O JDK 17 continua sendo o default da maquina,
# entao apontamos so para este processo.
$env:SOLR_JAVA_HOME = $JavaHome

# Escuta so em localhost. Expor 0.0.0.0 dispara o pedido de liberacao do
# firewall do Windows, que exige elevacao — e nao ha motivo para expor.
$env:SOLR_JETTY_HOST = '127.0.0.1'

Write-Host "JDK   : $JavaHome"
Write-Host "Solr  : $SolrHome"
Write-Host "Acao  : $Acao (porta $Porta)"
Write-Host ''

switch ($Acao) {
    'start'   { & $solr start   --port $Porta }
    'stop'    { & $solr stop    --port $Porta }
    'restart' { & $solr restart --port $Porta }
    'status'  { & $solr status }
}
