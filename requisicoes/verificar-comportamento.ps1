<#
    Verifica os tres sinais de comportamento de ponta a ponta, contra a
    aplicacao de pe: afinidade por produto, fadiga e o zeramento por clique.

        .\requisicoes\verificar-comportamento.ps1

    POR QUE ESTE SCRIPT EXISTE SEPARADO DOS TESTES DE UNIDADE

    Os testes de unidade provam a matematica: PesosDeFadiga.fadigaDe devolve 1/3
    com dez impressoes, o Rankeador aplica o teto antes da fadiga. O que eles NAO
    provam e que o sinal atravessa o sistema inteiro — ingestao, saneamento,
    fila, agregacao, escrita de perfil, leitura de perfil, ranking — e chega a
    mudar a resposta HTTP.

    Ja aconteceu duas vezes neste projeto um sinal estar matematicamente correto
    e nunca chegar ao ranking, as duas por chave que nao casava:

      - a afinidade era contada com a jornada crua ("Credito") e comparada com a
        do indice ("credito");
      - a fadiga era guardada com o termo normalizado ("antecipacao de limite") e
        consultada com o termo cru do indice ("antecipacao de limite", com
        acento).

    Nos dois casos o teste de unidade passava. Este tipo de script e o que pega.

    O PERFIL ESCOLHIDO NAO E ARBITRARIO

    Precisa de um perfil da demonstracao (cliente desconhecido cai em
    Perfil.NEUTRO com conta DESCONHECIDA, e falha fechada veta toda oferta
    condicionada) que tenha DOIS OU MAIS produtos elegiveis na MESMA jornada —
    senao nao ha o que a afinidade por produto desempatar.

    pf-novo nao serve: sem elegibilidade nenhuma, ele so alcanca
    OFR-CREDITO-EMPRESTIMO em credito. pf-uniclass alcanca CDB, LCI e
    CONSULTORIA em investimento.

    Nota de codificacao: ASCII puro. O PowerShell 5.1 le .ps1 sem BOM como ANSI.
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:8080',
    [string] $Cliente = 'pf-uniclass',
    [string] $Relacionamento = 'UNICLASS',

    # O prefixo tem de trazer os dois produtos na mesma lista.
    [string] $Prefixo = 'invest',

    # O produto que recebe os cliques, e o que serve de controle.
    [string] $Alvo = 'OFR-INVESTIMENTO-LCI',
    [string] $TermoDoAlvo = 'LCI e LCA',
    [string] $ProdutoDoAlvo = 'LCI',
    [string] $Controle = 'OFR-INVESTIMENTO-CDB',
    [string] $TermoDoControle = 'CDB'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot

$token = & "$raiz\scripts\gerar-token.ps1" -Tipo cliente -ClienteId $Cliente `
    -Relacionamento $Relacionamento -Silencioso
$operacao = & "$raiz\scripts\gerar-token.ps1" -Tipo operacao -Silencioso

$comoCliente  = @{ Authorization = "Bearer $token" }
$comoOperacao = @{ Authorization = "Bearer $operacao" }

$script:falhas = 0

function Afirmar($condicao, $texto, $visto) {
    if ($condicao) {
        Write-Host "  OK    $texto" -ForegroundColor Green
    } else {
        Write-Host "  FALHA $texto" -ForegroundColor Red
        Write-Host "        visto: $visto" -ForegroundColor Red
        $script:falhas++
    }
}

function Buscar {
    Invoke-RestMethod -Uri "$BaseUrl/v1/sugestoes?q=$Prefixo&explicar=true&limite=20" `
        -Headers $comoCliente -TimeoutSec 15
}

function Enviar($eventos) {
    # @() em volta do array garante que ConvertTo-Json produza LISTA mesmo com um
    # elemento so. Sem isso, um evento sozinho vira objeto e a API devolve 400 -
    # armadilha ja documentada neste repositorio.
    $corpo = ConvertTo-Json -InputObject @($eventos) -Depth 6
    Invoke-RestMethod -Uri "$BaseUrl/v1/eventos" -Method Post -TimeoutSec 15 `
        -Headers $comoCliente -ContentType 'application/json' `
        -Body ([Text.Encoding]::UTF8.GetBytes($corpo)) | Out-Null
}

function Aprender {
    Invoke-RestMethod -Uri "$BaseUrl/v1/aprendizado/rodar" -Method Post `
        -Headers $comoOperacao -TimeoutSec 30 | Out-Null
}

# Casa por ofertaId, e NAO por termo.
#
# A primeira versao comparava o termo com -like "*emprestimo pessoal*" e nunca
# casava: o termo no indice tem acento e o -like do PowerShell e sensivel a
# acento. As sete verificacoes falhavam com "visto: vazio" enquanto a API
# respondia certo - o tipo de teste que acusa a aplicacao por um defeito proprio.
#
# O identificador da oferta e ASCII, estavel e exato, e e justamente o que a API
# passou a devolver em cada item para que uma jornada acione a oferta sem
# depender da redacao do termo.
function Relevancia($resposta, $ofertaId) {
    $item = @($resposta.itens | Where-Object { $_.ofertaId -eq $ofertaId })
    if ($item.Count -eq 0) { return $null }
    return [double] $item[0].relevancia
}

function Cliques($quantos, $termo, $produto, $jornada) {
    $lista = @()
    1..$quantos | ForEach-Object {
        $lista += @{ tipo = 'SUGESTAO_CLICADA'; termo = $termo
                     posicao = 1; jornada = $jornada; produto = $produto }
    }
    return $lista
}

function Impressoes($quantas, $termo) {
    $lista = @()
    1..$quantas | ForEach-Object {
        $lista += @{ tipo = 'SUGESTAO_EXIBIDA'; termosExibidos = @($termo)
                     listaDegradada = $false }
    }
    return $lista
}

Write-Host ''
Write-Host "Cliente: $Cliente ($Relacionamento)   prefixo: '$Prefixo'" -ForegroundColor Cyan
Write-Host "Alvo dos cliques: $Alvo     Controle: $Controle" -ForegroundColor Cyan
Write-Host ''

# ---------------------------------------------------------------------------
Write-Host '--- Linha de base ---' -ForegroundColor Cyan
# ---------------------------------------------------------------------------
$inicial = Buscar
$baseAlvo     = Relevancia $inicial $Alvo
$baseControle = Relevancia $inicial $Controle

Write-Host "  $Alvo : $baseAlvo"
Write-Host "  $Controle : $baseControle"

Afirmar ($inicial.degradada -eq $false) 'perfil da demonstracao: lista NAO degradada' $inicial.degradada
Afirmar ($null -ne $baseAlvo -and $null -ne $baseControle) `
    'os dois produtos da jornada estao na lista: ha o que desempatar' `
    "alvo=$baseAlvo controle=$baseControle"

# ---------------------------------------------------------------------------
Write-Host ''
Write-Host "--- B2 - afinidade por PRODUTO (tres cliques em $ProdutoDoAlvo) ---" -ForegroundColor Cyan
# ---------------------------------------------------------------------------
# O campo `produto` do evento e o que B2 usa, e ele ja existia no contrato sem
# ser lido por nada. Tres cliques: o piso de confianca do produto e 4,0 e clique
# vale 2, entao dois bastariam; tres deixam folga para o decaimento.
Enviar (Cliques 3 $TermoDoAlvo $ProdutoDoAlvo 'investimento')
Aprender

$comProduto = Buscar
$relAlvo     = Relevancia $comProduto $Alvo
$relControle = Relevancia $comProduto $Controle

Write-Host "  $Alvo : $baseAlvo -> $relAlvo"
Write-Host "  $Controle : $baseControle -> $relControle"

Afirmar ($relAlvo -gt $baseAlvo) 'o produto clicado subiu' "$baseAlvo -> $relAlvo"

# A ASSERCAO CENTRAL DE B2, e vale ler duas vezes.
#
# Os dois produtos sao da MESMA jornada, entao a afinidade de JORNADA promoveu os
# dois na mesma medida - e de fato o controle tambem pode ter subido. Se o alvo
# cresceu MAIS que o controle, o que decidiu foi o nivel do PRODUTO: era
# impossivel antes desta rodada, porque clicar em LCI promovia CDB igual.
$ganhoAlvo     = if ($baseAlvo)     { $relAlvo / $baseAlvo }         else { 0 }
$ganhoControle = if ($baseControle) { $relControle / $baseControle }  else { 0 }

Write-Host "  ganho do alvo: $([math]::Round($ganhoAlvo,4))x   ganho do controle: $([math]::Round($ganhoControle,4))x"

Afirmar ($ganhoAlvo -gt $ganhoControle) `
    'o alvo subiu MAIS que o controle: foi o nivel do PRODUTO que desempatou' `
    "alvo=${ganhoAlvo}x controle=${ganhoControle}x"

# ---------------------------------------------------------------------------
Write-Host ''
Write-Host "--- B1 - fadiga (vinte impressoes sem clique em $TermoDoControle) ---" -ForegroundColor Cyan
# ---------------------------------------------------------------------------
# O alvo da fadiga e o CONTROLE, nao o produto que acabou de receber clique:
# clique zera fadiga, e cansar a mesma linha clicada nao mediria nada.
#
# E sao VINTE impressoes, nao seis. Com seis - uma acima do piso de 5 - a fadiga
# e 1/15 e o fator e 0,973: real, correto, e invisivel no terceiro decimal.
# Vinte leva a fadiga a 1,0, o fator ao piso de 0,6, e a diferenca passa a ser
# inequivoca. Para a precisao da curva entre o piso e o teto existe o teste de
# unidade; aqui o que se verifica e que o sinal ATRAVESSA o sistema.
Enviar (Impressoes 20 $TermoDoControle)
Aprender

$cansado = Buscar
$relCansada = Relevancia $cansado $Controle

Write-Host "  $Controle : $relControle -> $relCansada"

Afirmar ($null -ne $relCansada -and $relCansada -lt $relControle) `
    'a linha exibida e ignorada perdeu relevancia' "$relControle -> $relCansada"

# Rebaixa, nao remove: a linha tem de continuar na lista.
Afirmar ($null -ne $relCansada) `
    'mas NAO desapareceu: fadiga rebaixa, remover e trabalho da Peneira' $relCansada

# Conferir a RAZAO, e nao so a direcao, e o que impede este passo de passar
# verde por qualquer outro motivo. No teto, a fadiga vale 1,0 e o fator e
# exatamente sugestao.peso.fadiga-minima = 0,6.
if ($relControle -and $relCansada) {
    $razao = $relCansada / $relControle
    Write-Host "  razao: $([math]::Round($razao,4))  (esperado 0,6 = sugestao.peso.fadiga-minima)"
    Afirmar ([math]::Abs($razao - 0.6) -lt 0.02) `
        'a razao bate com o piso do fator de fadiga, 0,6' $razao
}

# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '--- B1 - um clique zera a fadiga ---' -ForegroundColor Cyan
# ---------------------------------------------------------------------------
# ATENCAO AO QUE ESTE PASSO MEDE, porque a primeira versao dele passou verde
# testando nada.
#
# Um clique faz duas coisas ao mesmo tempo: zera a fadiga E adiciona afinidade de
# produto. Afirmar so "subiu" seria satisfeito pela segunda sozinha - foi
# exatamente o que aconteceu antes, quando a fadiga nem estava sendo aplicada (a
# chave do termo nao casava por causa do acento) e o passo passou igual.
#
# A asserçao abaixo e DISCRIMINANTE: um clique da 2 pontos sobre o piso de 4,0,
# ou 0,5 de afinidade de produto, que no teto de 1,2 vale no maximo 1,10x. Zerar
# a fadiga vale 1/0,6 = 1,67x. Exigir recuperacao acima de 1,4x torna o passo
# impossivel de satisfazer sem a fadiga ter sido realmente zerada.
Enviar (Cliques 1 $TermoDoControle 'CDB' 'investimento')
Aprender

$recuperado = Buscar
$relRecuperada = Relevancia $recuperado $Controle

Write-Host "  $Controle : $relCansada -> $relRecuperada"

if ($relCansada -and $relRecuperada) {
    $ganho = $relRecuperada / $relCansada
    Write-Host "  recuperacao: $([math]::Round($ganho,3))x  (afinidade de produto sozinha daria no maximo 1,10x)"
    Afirmar ($ganho -gt 1.4) `
        'a recuperacao e maior do que afinidade de produto explica: a fadiga foi zerada' `
        "${ganho}x"
}

# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '--- O que o Solr guarda disso: nada ---' -ForegroundColor Cyan
# ---------------------------------------------------------------------------
$noIndice = Invoke-RestMethod -TimeoutSec 15 `
    -Uri "http://localhost:8983/solr/sugestoes/select?q=$Cliente&rows=5&wt=json"
Afirmar ($noIndice.response.numFound -eq 0) `
    'o identificador do cliente nao chegou ao indice' $noIndice.response.numFound

# Afinidade, tendencia e fadiga vivem no perfil e sao cruzadas com a oferta em
# CPU, depois de o indice ja ter respondido. Nenhuma delas e campo de documento:
# o indice recebe ContextoDeBusca (segmento e faixa), nunca Cliente.
$campos = (Invoke-RestMethod -TimeoutSec 15 -Uri 'http://localhost:8983/solr/sugestoes/schema/fields').fields
$suspeitos = @($campos | Where-Object { $_.name -match 'fadiga|afinidade|tendencia|cliente_id' })
Afirmar ($suspeitos.Count -eq 0) `
    'nenhum campo de comportamento individual no schema do indice' `
    (@($suspeitos | ForEach-Object { $_.name }) -join ', ')

Write-Host ''
if ($script:falhas -eq 0) {
    Write-Host 'Todas as verificacoes passaram.' -ForegroundColor Green
    exit 0
}
Write-Host "$script:falhas verificacao(oes) falharam." -ForegroundColor Red
exit 1
