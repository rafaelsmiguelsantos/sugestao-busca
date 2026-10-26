<#
    Remove do indice as ofertas criadas por teste manual.

        .\scripts\limpar-ofertas-de-teste.ps1              # so lista
        .\scripts\limpar-ofertas-de-teste.ps1 -Confirmar   # apaga

    POR QUE ISTO E UM SCRIPT E NAO UMA ROTA

    A API nao tem DELETE /v1/ofertas/{id}, e isso e deliberado: retirar oferta
    do ar e operacao de catalogo, com quem aprovou e quando, e nao um verbo
    HTTP exposto a qualquer jornada que tenha 'ofertas:escrever'. Uma jornada
    que pode apagar a oferta de outra jornada e o mesmo problema que o 403 de
    jornada alheia resolve na escrita, com consequencia pior.

    Enquanto nao existe esse caminho de catalogo, a collection de exploracao
    acumula uma oferta por clique na pasta 3 - cada uma com 'ofertaId' unico
    por rodada, de proposito, para o POST ser repetivel. Elas nao estragam
    nenhuma afirmacao da collection, mas enchem a lista de 'consorc' de ruido
    e deixam quem le em duvida sobre o que e dado de demonstracao.

    ESTE SCRIPT FALA COM O SOLR, NAO COM A APLICACAO

    Por isso ele nao precisa de token, e por isso ele NAO serve para producao:
    la o indice nao esta ao alcance de quem roda scripts. Aqui esta, porque o
    POC sobe os dois na mesma maquina.

    PADRAO CONSERVADOR

    Sem -Confirmar ele apenas lista. Apagar e irreversivel e o alvo e casado
    por expressao regular, que e exatamente o tipo de parametro onde um erro de
    digitacao casa mais do que se queria.

    Nota de codificacao: ASCII puro, como os outros scripts. O PowerShell 5.1
    le .ps1 sem BOM como ANSI.
#>
[CmdletBinding()]
param(
    # Tudo o que os quatro arreios de teste criam no indice.
    #
    # Era so '^OFR-CREDITO-TESTE-\d+$', o id que a collection de exploracao gera
    # na pasta 3. Nao bastava: o newman e o sondar.ps1 criam outros, e eles
    # SOBREVIVEM a rodada. Numa demonstracao, "fundo multimercado invadido" ou
    # "oferta de corrida" aparecendo na lista e pior do que parece - a pessoa
    # assistindo nao sabe que e residuo de teste, e a primeira impressao e de
    # catalogo sujo.
    #
    # Rodar isto antes de apresentar e um passo do roteiro, nao uma faxina
    # opcional.
    [string] $Padrao = '^OFR-(CREDITO-TESTE-\d+|TESTE-|SONDA-|SEQ-|.*-AUTHZ|INVADIDA|CREDITO-CORRIDA|CREDITO-CONFERE-|CREDITO-ISOLADO-|CREDITO-REPRO|PREVIDENCIA-VGBL-NEWMAN)',

    [string] $Solr = 'http://localhost:8983/solr/sugestoes',

    [switch] $Confirmar
)

$ErrorActionPreference = 'Stop'

Write-Host ''
Write-Host "Indice : $Solr"
Write-Host "Padrao : $Padrao"
Write-Host ''

try {
    $resposta = Invoke-RestMethod -TimeoutSec 15 `
        -Uri "$Solr/select?q=tipo:oferta&rows=1000&fl=oferta_id_s,termo&wt=json"
} catch {
    Write-Host "Nao consegui falar com o Solr: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Suba com: .\scripts\solr-local.ps1 start"
    exit 1
}

$total = $resposta.response.numFound

# O @() nao e decoracao: sem ele, um unico resultado vira objeto e nao array, e
# '.Count' devolve vazio em vez de 1. Foi assim que uma conferencia desta mesma
# collection acusou falha onde a API estava certa.
$alvos = @($resposta.response.docs | Where-Object { $_.oferta_id_s -match $Padrao })

Write-Host "Ofertas no indice     : $total"
Write-Host "Casam com o padrao    : $($alvos.Count)"
Write-Host ''

if ($alvos.Count -eq 0) {
    Write-Host 'Nada a remover.' -ForegroundColor Green
    exit 0
}

foreach ($a in $alvos) {
    Write-Host ("  {0,-40} {1}" -f $a.oferta_id_s, $a.termo)
}
Write-Host ''

if (-not $Confirmar) {
    Write-Host 'Nada foi apagado. Rode de novo com -Confirmar para remover.' -ForegroundColor Yellow
    exit 0
}

# Apaga por id, um por um, e nao por uma consulta so. Uma consulta de delete
# sobre o padrao apagaria o que o padrao casar NO MOMENTO DO DELETE, que pode
# nao ser o que foi listado acima - e a lista acima e o que foi conferido.
$comandos = @($alvos | ForEach-Object { @{ query = ('oferta_id_s:"' + $_.oferta_id_s + '"') } })
$corpo = @{ delete = $comandos } | ConvertTo-Json -Depth 5 -Compress

Invoke-RestMethod -TimeoutSec 30 -Method Post -ContentType 'application/json' `
    -Uri "$Solr/update?commit=true" -Body $corpo | Out-Null

$depois = Invoke-RestMethod -TimeoutSec 15 `
    -Uri "$Solr/select?q=tipo:oferta&rows=1000&fl=oferta_id_s&wt=json"
$restantes = @($depois.response.docs | Where-Object { $_.oferta_id_s -match $Padrao }).Count

Write-Host "Removidas  : $($alvos.Count)"
Write-Host "Ofertas    : $total -> $($depois.response.numFound)"

if ($restantes -eq 0) {
    Write-Host 'Nenhuma oferta de teste restante.' -ForegroundColor Green
} else {
    Write-Host "AINDA restam $restantes casando com o padrao." -ForegroundColor Red
    exit 1
}
