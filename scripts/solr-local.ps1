<#
    Sobe o Solr 10 a partir do zip instalado em tools\ — sem container e sem admin.
    E o caminho usado enquanto nao houver runtime de container nesta maquina.
    Mesma porta e mesma colecao do compose.yaml, entao o resto do projeto nao muda.

        .\scripts\solr-local.ps1 start
        .\scripts\solr-local.ps1 status
        .\scripts\solr-local.ps1 stop

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

# Nada aqui tem caminho padrao: um default apontando para a maquina de quem
# escreveu o script falha para todo mundo que clona, e falha reclamando de uma
# pasta que o leitor nunca viu.
if (-not $SolrHome) {
    if ($env:SOLR_INSTALL) { $SolrHome = $env:SOLR_INSTALL }
    else {
        throw "Nao sei onde o Solr esta instalado. Aponte -SolrHome ou a variavel SOLR_INSTALL para a pasta do solr-10.0.0. Ou use 'docker compose up -d', que dispensa instalar o Solr."
    }
}
if (-not $JavaHome) {
    if     ($env:JAVA_25_HOME) { $JavaHome = $env:JAVA_25_HOME }
    elseif ($env:JAVA_HOME)    { $JavaHome = $env:JAVA_HOME }
    else {
        throw "Nao sei qual JDK usar. Aponte -JavaHome, ou defina JAVA_25_HOME ou JAVA_HOME."
    }
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
