<#
    Sondagem adversarial: tenta quebrar a API de proposito.

        .\requisicoes\sondar.ps1
        .\requisicoes\sondar.ps1 -Grupo token

    NAO e um teste de aceitacao. Um teste de aceitacao confirma que o caminho
    feliz funciona; isto procura o caminho infeliz que ninguem escreveu. Cada
    sonda declara o que ESPERA, e o que interessa sao as divergencias.

    Grupos:
      token        assinatura forjada, alg none, expirado, emissor errado
      entrada      unicode hostil, prefixo gigante, travessia de caminho
      corpo        content-type errado, corpo gigante, json aninhado
      concorrencia cadastro simultaneo do mesmo id, carga em paralelo
      limites      valores de fronteira em limite e q

    Precisa do app e do Solr no ar com o perfil 'local'.
#>
[CmdletBinding()]
param(
    [string] $App = 'http://localhost:8080',
    [ValidateSet('todos','token','entrada','corpo','concorrencia','limites')]
    [string] $Grupo = 'todos'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$gerador = Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts\gerar-token.ps1'
$SEGREDO = 'SEGREDO-REMOVIDO-VER-SUGESTAO-SEGREDO'
$EMISSOR = 'https://sugestao-busca.dev'

$script:achados = New-Object System.Collections.ArrayList

# ---------------------------------------------------------------------------
# Plumbing
# ---------------------------------------------------------------------------

function Titulo($texto) {
    Write-Host ''
    Write-Host ('-' * 78) -ForegroundColor DarkGray
    Write-Host "  $texto" -ForegroundColor Cyan
    Write-Host ('-' * 78) -ForegroundColor DarkGray
}

function Sonda {
    param(
        [string] $Nome,
        [int[]]  $Esperado,
        [scriptblock] $Acao,
        [string] $PorQue = ''
    )
    try {
        $status = & $Acao
    } catch {
        $status = -1
        $erro = $_.Exception.Message
    }

    $ok = $Esperado -contains $status
    $cor = if ($ok) { 'Green' } else { 'Red' }
    $marca = if ($ok) { 'ok  ' } else { 'ACHADO' }

    Write-Host ("  {0,-6} {1,-46} {2,4}  esperado {3}" -f `
        $marca, $Nome, $status, ($Esperado -join '/')) -ForegroundColor $cor
    if (-not $ok) {
        if ($PorQue) { Write-Host ("         {0}" -f $PorQue) -ForegroundColor Yellow }
        if ($script:ultimoCorpo) {
            $recorte = $script:ultimoCorpo
            if ($recorte.Length -gt 300) { $recorte = $recorte.Substring(0, 300) + '...' }
            Write-Host ("         resposta: {0}" -f $recorte) -ForegroundColor DarkYellow
        }
        [void] $script:achados.Add("$Nome -> $status (esperado $($Esperado -join '/'))")
    }
}

function Status {
    param([string] $Metodo, [string] $Url, $Corpo, [hashtable] $Cabecalhos = @{},
          [string] $ContentType = 'application/json; charset=utf-8')
    # CLONE dos cabecalhos, e isto e correcao de um defeito que fazia as sondas
    # mentirem.
    #
    # O Invoke-WebRequest do PowerShell 5.1 MUTA o hashtable que recebe em
    # -Headers: ele injeta ali o Content-Type usado na chamada. Como as sondas
    # compartilham um $cab, a sonda de 'content-type text/plain' deixava
    # Content-Type: text/plain gravado no hashtable, e TODAS as seguintes
    # passavam a enviar text/plain junto - o servidor recusava com 400 por media
    # type, nao pelo que a sonda queria testar.
    #
    # O pior: duas das sondas contaminadas esperavam 400 de qualquer forma, e
    # passaram verdes pelo motivo errado. Uma suite que erra junto com o codigo
    # e pior que suite nenhuma.
    $req = @{
        Uri = $Url; Method = $Metodo; Headers = @{} + $Cabecalhos
        TimeoutSec = 30; UseBasicParsing = $true; ErrorAction = 'Stop'
    }
    if ($null -ne $Corpo) {
        # Atribuicao DENTRO de cada ramo, nunca '$x = if (...) { $array }'.
        #
        # E a armadilha que este repositorio ja documenta em
        # scripts\semear-vocabulario.ps1, noutra forma: o PowerShell DESENROLA o
        # array devolvido por um ramo de if. Aqui o byte[] virava Object[], e o
        # Invoke-WebRequest mandava so o PRIMEIRO byte.
        #
        # O sintoma foi perfeito para enganar: o servidor respondia 400 em todas
        # as sondas deste grupo, e seis das nove ESPERAVAM 400 - entao elas
        # passavam verdes sem ter testado nada. Quem revelou foi o log do app,
        # dizendo "no int-argument constructor to deserialize from Number value
        # (123)": 123 e o codigo ASCII de '{', o primeiro byte do corpo.
        #
        # Um grupo inteiro de sondas afirmando cobrir o POST e cobrindo um byte.
        $texto = if ($Corpo -is [string]) { $Corpo } else { $Corpo | ConvertTo-Json -Depth 20 }
        $bytes = [Text.Encoding]::UTF8.GetBytes($texto)
        $req['Body'] = $bytes
        $req['ContentType'] = $ContentType
    }
    try {
        $script:ultimoCorpo = ''
        return [int] (Invoke-WebRequest @req).StatusCode
    }
    catch {
        if ($_.Exception.Response) {
            # Guarda o corpo do erro para a Sonda poder mostra-lo quando o
            # status divergir. Uma sonda que diz "400" sem dizer por que custa
            # uma investigacao a cada execucao.
            try {
                $l = New-Object IO.StreamReader(
                        $_.Exception.Response.GetResponseStream(), [Text.Encoding]::UTF8)
                $script:ultimoCorpo = $l.ReadToEnd()
                $l.Dispose()
            } catch { $script:ultimoCorpo = '' }
            return [int] $_.Exception.Response.StatusCode
        }
        $script:ultimoCorpo = $_.Exception.Message
        return -1
    }
}

function TokenBom($tipo = 'cliente') {
    if ($tipo -eq 'cliente') { return (& $gerador -Silencioso -Tipo cliente -ClienteId 'pf-novo') }
    return (& $gerador -Silencioso -Tipo $tipo)
}

function Base64Url([byte[]] $b) {
    return [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_')
}
function Base64UrlTexto([string] $t) { return Base64Url ([Text.Encoding]::UTF8.GetBytes($t)) }

function MontarJwt($cabecalho, $corpo, $segredo) {
    $p1 = Base64UrlTexto ($cabecalho | ConvertTo-Json -Compress)
    $p2 = Base64UrlTexto ($corpo | ConvertTo-Json -Compress)
    $aAssinar = "$p1.$p2"
    if (-not $segredo) { return "$aAssinar." }
    $h = New-Object System.Security.Cryptography.HMACSHA256
    $h.Key = [Text.Encoding]::UTF8.GetBytes($segredo)
    $sig = Base64Url ($h.ComputeHash([Text.Encoding]::UTF8.GetBytes($aAssinar)))
    $h.Dispose()
    return "$aAssinar.$sig"
}

function CorpoDeCliente($extras = @{}) {
    $agora = [int64] ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
    $c = [ordered] @{
        iss = $EMISSOR; sub = 'pf-novo'; iat = $agora; exp = $agora + 600
        segmento = 'PF'; relacionamento = 'VAREJO'
        scope = 'sugestao:ler eventos:escrever'
    }
    foreach ($k in $extras.Keys) { $c[$k] = $extras[$k] }
    return $c
}

$BUSCA = "$App/v1/sugestoes?q=emprestim"

# ===========================================================================
function Grupo-Token {
    Titulo 'TOKEN: a parte que mais vale sondar'

    $bom = TokenBom
    Sonda 'token valido' @(200) { Status GET $BUSCA $null @{ Authorization = "Bearer $bom" } }

    # alg:none e o ataque classico contra validador mal configurado: o atacante
    # troca o algoritmo por "none" e remove a assinatura. Quem aceitar isso
    # aceita qualquer reivindicacao de qualquer pessoa.
    $none = MontarJwt @{ alg = 'none'; typ = 'JWT' } (CorpoDeCliente) $null
    Sonda 'alg:none (assinatura removida)' @(401) `
        { Status GET $BUSCA $null @{ Authorization = "Bearer $none" } } `
        'CRITICO: aceitar alg:none permite forjar qualquer identidade.'

    # Assinatura trocada por lixo.
    $partes = $bom.Split('.')
    $adulterado = "$($partes[0]).$($partes[1]).aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    Sonda 'assinatura adulterada' @(401) `
        { Status GET $BUSCA $null @{ Authorization = "Bearer $adulterado" } } `
        'CRITICO: assinatura invalida tem de ser recusada.'

    # Corpo trocado, assinatura mantida: eleva o escopo sem reassinar.
    $corpoElevado = Base64UrlTexto ((CorpoDeCliente @{ scope = 'ofertas:escrever ofertas:todas' }) | ConvertTo-Json -Compress)
    $elevado = "$($partes[0]).$corpoElevado.$($partes[2])"
    Sonda 'escopo elevado sem reassinar' @(401) `
        { Status GET "$App/v1/ofertas/OFR-CREDITO-EMPRESTIMO" $null @{ Authorization = "Bearer $elevado" } } `
        'CRITICO: corpo alterado invalida a assinatura.'

    # Assinado com OUTRO segredo.
    $outroSegredo = MontarJwt @{ alg = 'HS256'; typ = 'JWT' } (CorpoDeCliente) 'outro-segredo-de-32-bytes-ou-mais-aqui'
    Sonda 'assinado com outro segredo' @(401) `
        { Status GET $BUSCA $null @{ Authorization = "Bearer $outroSegredo" } } `
        'CRITICO: so o segredo configurado vale.'

    # Expirado.
    $agora = [int64] ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
    $expirado = MontarJwt @{ alg = 'HS256'; typ = 'JWT' } `
        (CorpoDeCliente @{ iat = $agora - 7200; exp = $agora - 3600 }) $SEGREDO
    Sonda 'expirado ha uma hora' @(401) `
        { Status GET $BUSCA $null @{ Authorization = "Bearer $expirado" } }

    # Emissor errado. Sem conferir iss, um token assinado com o mesmo segredo
    # por outro servico do mesmo ecossistema valeria aqui.
    $outroEmissor = MontarJwt @{ alg = 'HS256'; typ = 'JWT' } `
        (CorpoDeCliente @{ iss = 'https://outro-servico.interno' }) $SEGREDO
    Sonda 'emissor diferente' @(401) `
        { Status GET $BUSCA $null @{ Authorization = "Bearer $outroEmissor" } } `
        'Sem conferir iss, token de outro servico do mesmo ecossistema valeria aqui.'

    # Sem escopo nenhum.
    $semEscopo = MontarJwt @{ alg = 'HS256'; typ = 'JWT' } `
        (CorpoDeCliente @{ scope = '' }) $SEGREDO
    Sonda 'token valido, escopo vazio' @(403) `
        { Status GET $BUSCA $null @{ Authorization = "Bearer $semEscopo" } }

    # Escopo parecido, mas nao igual: prefixo nao concede.
    $quaseEscopo = MontarJwt @{ alg = 'HS256'; typ = 'JWT' } `
        (CorpoDeCliente @{ scope = 'sugestao:ler-tudo relatorio:sugestao:ler' }) $SEGREDO
    Sonda 'escopo parecido nao concede' @(403) `
        { Status GET $BUSCA $null @{ Authorization = "Bearer $quaseEscopo" } } `
        'Escopo tem de casar exato; prefixo ou sufixo nao podem conceder.'

    # Formatos quebrados do cabecalho.
    Sonda 'Authorization sem Bearer' @(401) `
        { Status GET $BUSCA $null @{ Authorization = $bom } }
    Sonda 'Bearer vazio' @(401) `
        { Status GET $BUSCA $null @{ Authorization = 'Bearer ' } }
    Sonda 'Bearer com lixo' @(401) `
        { Status GET $BUSCA $null @{ Authorization = 'Bearer nao.e.um.jwt' } }
}

# ===========================================================================
function Grupo-Entrada {
    Titulo 'ENTRADA: texto hostil onde se espera texto'

    $t = TokenBom
    $cab = @{ Authorization = "Bearer $t" }

    # Todo caractere nao-ASCII e montado por CODIGO, nunca escrito como literal.
    #
    # Duas razoes, e as duas custaram caro aqui.
    #
    # A primeira: este arquivo e .ps1 sem BOM, e o Windows PowerShell 5.1 le
    # isso como ANSI. Um literal arabe vira outra sequencia de caracteres, e se
    # algum byte cair num equivalente de aspa no CP1252 a string termina cedo e
    # o RESTO DO ARQUIVO passa a ser lido como codigo. O sintoma foi uma sonda
    # especifica, em outra funcao, respondendo diferente do mesmo corpo enviado
    # a mao - e levou varias hipoteses erradas ate alguem olhar os bytes.
    #
    # A segunda: `u{200D} e escape do PowerShell 6+. No 5.1 ele nao existe, e a
    # string virava o texto literal "u{200D}" - duas sondas afirmavam testar
    # zero-width joiner e diacritico combinante, e testavam um "u" e uma chave.
    # Sonda que mente e pior que sonda ausente.
    $emoji = [char]::ConvertFromUtf32(0x1F600)
    $arabe = -join (0x0627,0x0645,0x0628,0x0631,0x0633,0x062A,0x064A,0x0645,0x0648 |
                    ForEach-Object { [char] $_ })
    $zwj = [char] 0x200D
    $acuto = [char] 0x0301

    $casos = @(
        @{ n = 'emoji no prefixo';        q = "emprest$emoji" },
        @{ n = 'arabe (RTL)';             q = $arabe },
        @{ n = 'zero-width joiner';       q = "emprest${zwj}imo" },
        @{ n = 'combining diacriticos';   q = "e${acuto}mprestimo" },
        @{ n = 'quebra de linha';         q = "emprest`nimo" },
        @{ n = 'nul byte percent-encoded'; q = 'emprest%00imo' },
        @{ n = 'aspas e chaves do Solr';  q = 'emprest" OR tipo:*' },
        @{ n = 'caractere de controle';   q = "emprest`t`r imo" },
        @{ n = 'so espacos';              q = '     ' },
        @{ n = 'prefixo de 7 mil chars';  q = ('a' * 7000) }
    )
    foreach ($c in $casos) {
        $u = "$App/v1/sugestoes?q=" + [uri]::EscapeDataString($c.q)
        Sonda $c.n @(200) { Status GET $u $null $cab } `
            'A APLICACAO nao deve devolver erro para nenhuma entrada.'
    }

    # A fronteira, medida: o teto de cabecalho do Tomcat e 8 KB, e a linha de
    # requisicao conta. Acima de ~7,9 KB de URL o CONTAINER devolve 400 e a
    # aplicacao nem e chamada - o prefixoMaximo daqui nao chega a ser
    # consultado.
    #
    # Isto e o servidor se defendendo e esta certo. Esperar 200 aqui faria esta
    # sonda acusar para sempre um comportamento correto, que e o jeito mais
    # rapido de um conjunto de sondas passar a ser ignorado.
    Sonda 'prefixo de 10 mil chars (acima do teto do Tomcat)' @(400) `
        { Status GET ("$App/v1/sugestoes?q=" + ('a' * 10000)) $null $cab } `
        'Acima de ~7,9 KB de URL quem responde e o container, nao a aplicacao.'

    Titulo 'ENTRADA: o caminho de /v1/ofertas/{id}'

    $tj = TokenBom 'jornada'
    $cabJ = @{ Authorization = "Bearer $tj" }

    # O id vem da URL e NAO passou por Oferta.de. E o unico ponto onde texto de
    # fora chega a uma consulta ao Solr.
    $ids = @(
        @{ n = 'id com aspas';            v = 'OFR"OR"1' },
        @{ n = 'id com curinga do Solr';  v = 'OFR*' },
        @{ n = 'id so com curinga';       v = '*' },
        @{ n = 'id com travessia';        v = '..%2F..%2Fetc' },
        @{ n = 'id com espaco';           v = 'OFR X' },
        @{ n = 'id de 500 chars';         v = ('A' * 500) }
    )
    foreach ($c in $ids) {
        $u = "$App/v1/ofertas/" + [uri]::EscapeDataString($c.v)
        Sonda $c.n @(404, 400) { Status GET $u $null $cabJ } `
            'Nao pode virar 500 nem devolver oferta alheia por curinga.'
    }
}

# ===========================================================================
function Grupo-Corpo {
    Titulo 'CORPO: o que chega no POST'

    $tj = TokenBom 'jornada'
    $cab = @{ Authorization = "Bearer $tj" }
    $url = "$App/v1/ofertas"

    Sonda 'corpo vazio' @(400) { Status POST $url '' $cab }
    Sonda 'corpo = null' @(400) { Status POST $url 'null' $cab }
    Sonda 'corpo = array' @(400) { Status POST $url '[]' $cab }
    Sonda 'corpo = numero' @(400) { Status POST $url '42' $cab }
    Sonda 'json truncado' @(400) { Status POST $url '{"ofertaId":' $cab }

    Sonda 'content-type text/plain' @(415) `
        { Status POST $url '{}' $cab 'text/plain' } `
        'Deve recusar por media type, nao tentar desserializar.'

    # Aninhamento profundo: classico de exaustao de pilha em parser recursivo.
    $fundo = ('{"a":' * 2000) + '1' + ('}' * 2000)
    Sonda 'json aninhado 2000 niveis' @(400) `
        { Status POST $url $fundo $cab } `
        'Nao pode virar 500 por StackOverflow no parser.'

    # Corpo grande: 5 MB de sinonimos.
    $gigante = @{
        ofertaId = 'OFR-TESTE-GIGANTE'; termo = 'teste'; destino = '/app/x'
        jornada = 'credito'; produtoAlvo = 'TESTE'
        sinonimos = @(1..20000 | ForEach-Object { "sinonimo numero $_ com texto de enchimento" })
        segmentos = @('PF'); relacionamentos = @('VAREJO')
        vigenciaInicio = '2026-01-01T00:00:00Z'; vigenciaFim = '2027-01-01T00:00:00Z'
    }
    Sonda 'corpo com 20 mil sinonimos' @(413, 422, 400) `
        { Status POST $url $gigante $cab } `
        'Deve recusar por teto, nao aceitar e gravar.'

    # Campo desconhecido: nao deve quebrar nem ser aceito em silencio como algo.
    $comExtra = '{"ofertaId":"OFR-TESTE-EXTRA","termo":"teste extra","destino":"/app/x",' +
                '"jornada":"credito","produtoAlvo":"EXTRA","segmentos":["PF"],' +
                '"relacionamentos":["VAREJO"],"vigenciaInicio":"2026-01-01T00:00:00Z",' +
                '"vigenciaFim":"2027-01-01T00:00:00Z","ctr":0.99,"taxaAdesao":0.99,' +
                '"clientesDistintos":99999}'
    Sonda 'campos ctr/adesao/k no corpo' @(200, 201) `
        { Status POST $url $comExtra $cab } `
        'Devem ser IGNORADOS, nunca aceitos.'
}

# ===========================================================================
function Grupo-Concorrencia {
    Titulo 'CONCORRENCIA'

    $tj = TokenBom 'jornada'
    $corpo = (@{
        ofertaId = 'OFR-CREDITO-CORRIDA'; termo = 'oferta de corrida'; destino = '/app/credito/corrida'
        jornada = 'credito'; produtoAlvo = 'CORRIDA'; segmentos = @('PF'); relacionamentos = @('VAREJO')
        vigenciaInicio = '2026-01-01T00:00:00Z'; vigenciaFim = '2027-01-01T00:00:00Z'
    } | ConvertTo-Json -Depth 8)

    # 12 POST simultaneos do MESMO id. A gravacao e idempotente, mas o status
    # e otimista: o caso de uso le existencia e grava em duas chamadas, sem
    # transacao. Espera-se no maximo um 201.
    $pool = [RunspaceFactory]::CreateRunspacePool(1, 12); $pool.Open()
    $tarefas = 1..12 | ForEach-Object {
        $ps = [PowerShell]::Create(); $ps.RunspacePool = $pool
        [void] $ps.AddScript({
            param($url, $token, $corpo)
            try {
                $r = Invoke-WebRequest -Uri $url -Method Post -Headers @{ Authorization = "Bearer $token" } `
                        -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($corpo)) `
                        -UseBasicParsing -TimeoutSec 30
                return [int] $r.StatusCode
            } catch {
                if ($_.Exception.Response) { return [int] $_.Exception.Response.StatusCode }
                return 0
            }
        }).AddArgument("$App/v1/ofertas").AddArgument($tj).AddArgument($corpo)
        @{ ps = $ps; h = $ps.BeginInvoke() }
    }
    $status = $tarefas | ForEach-Object { $r = $_.ps.EndInvoke($_.h); $_.ps.Dispose(); $r }
    $pool.Close()

    $criados = @($status | Where-Object { $_ -eq 201 }).Count
    $ok = @($status | Where-Object { $_ -eq 200 }).Count
    $erros = @($status | Where-Object { $_ -notin @(200, 201) }).Count

    Write-Host ("  12 POST simultaneos do mesmo id -> 201:{0}  200:{1}  outros:{2}" -f $criados, $ok, $erros)
    if ($erros -gt 0) {
        Write-Host '  ACHADO  alguma chamada falhou sob concorrencia' -ForegroundColor Red
        [void] $script:achados.Add("cadastro concorrente: $erros falhas")
    } elseif ($criados -gt 1) {
        Write-Host ("  ESPERADO  {0} responderam 201. O status e otimista: le-existencia e grava" -f $criados) -ForegroundColor Yellow
        Write-Host '            sao duas chamadas sem transacao. A GRAVACAO continua correta.' -ForegroundColor Yellow
    } else {
        Write-Host '  ok      exatamente um 201, o resto 200' -ForegroundColor Green
    }

    # Busca sob carga: 40 em paralelo.
    Titulo 'CARGA: 40 buscas em paralelo'
    $tc = TokenBom
    $pool2 = [RunspaceFactory]::CreateRunspacePool(1, 40); $pool2.Open()
    $t0 = [Diagnostics.Stopwatch]::StartNew()
    $tarefas2 = 1..40 | ForEach-Object {
        $ps = [PowerShell]::Create(); $ps.RunspacePool = $pool2
        [void] $ps.AddScript({
            param($url, $token)
            $tempos = @()
            1..10 | ForEach-Object {
                $sw = [Diagnostics.Stopwatch]::StartNew()
                try { Invoke-RestMethod -Uri $url -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 30 | Out-Null }
                catch { return @(-1) }
                $sw.Stop(); $tempos += $sw.Elapsed.TotalMilliseconds
            }
            return $tempos
        }).AddArgument($BUSCA).AddArgument($tc)
        @{ ps = $ps; h = $ps.BeginInvoke() }
    }
    $todos = @()
    $tarefas2 | ForEach-Object { $todos += $_.ps.EndInvoke($_.h); $_.ps.Dispose() }
    $t0.Stop(); $pool2.Close()

    $falhas = @($todos | Where-Object { $_ -lt 0 }).Count
    $bons = @($todos | Where-Object { $_ -ge 0 } | Sort-Object)
    function Pct($p) { return [math]::Round($bons[[math]::Min($bons.Count - 1, [int][math]::Floor($p * $bons.Count))], 1) }

    Write-Host ("  {0} requisicoes, {1} falhas, {2} s totais, {3} req/s" -f `
        $todos.Count, $falhas, [math]::Round($t0.Elapsed.TotalSeconds, 1),
        [math]::Round($bons.Count / $t0.Elapsed.TotalSeconds, 0))
    Write-Host ("  p50 {0} ms   p95 {1} ms   p99 {2} ms   max {3} ms" -f `
        (Pct 0.50), (Pct 0.95), (Pct 0.99), [math]::Round($bons[-1], 1))
    if ($falhas -gt 0) {
        Write-Host '  ACHADO  houve falha sob carga' -ForegroundColor Red
        [void] $script:achados.Add("carga: $falhas falhas em $($todos.Count)")
    } else {
        Write-Host '  ok      nenhuma falha' -ForegroundColor Green
    }
}

# ===========================================================================
function Grupo-Limites {
    Titulo 'LIMITES: valores de fronteira'

    $t = TokenBom
    $cab = @{ Authorization = "Bearer $t" }

    foreach ($v in @('0', '-1', '1', '8', '9', '100000', '2147483648', 'NaN', '1e9', '')) {
        Sonda ("limite=" + $(if ($v -eq '') { '(vazio)' } else { $v })) @(200) `
            { Status GET "$App/v1/sugestoes?q=emprestim&limite=$v" $null $cab }
    }

    foreach ($q in @('e', 'em', ('a' * 80), ('a' * 81))) {
        $rotulo = if ($q.Length -gt 10) { "q de $($q.Length) chars" } else { "q='$q'" }
        Sonda $rotulo @(200) `
            { Status GET ("$App/v1/sugestoes?q=" + [uri]::EscapeDataString($q)) $null $cab }
    }

    Sonda 'q repetido duas vezes' @(200) `
        { Status GET "$App/v1/sugestoes?q=emprestim&q=consignad" $null $cab }
    Sonda 'parametro desconhecido' @(200) `
        { Status GET "$App/v1/sugestoes?q=emprestim&bisbilhotar=true" $null $cab }
}

# ===========================================================================
try { [void] (Invoke-RestMethod -Uri "$App/actuator/health" -TimeoutSec 5) }
catch { Write-Host "App nao responde em $App" -ForegroundColor Red; exit 1 }

Write-Host ''
Write-Host '  SONDAGEM ADVERSARIAL' -ForegroundColor Cyan
Write-Host '  Cada sonda declara o que espera. O que interessa sao as divergencias.' -ForegroundColor DarkGray

$alvos = if ($Grupo -eq 'todos') { @('token','entrada','corpo','concorrencia','limites') } else { @($Grupo) }
foreach ($a in $alvos) {
    switch ($a) {
        'token'        { Grupo-Token }
        'entrada'      { Grupo-Entrada }
        'corpo'        { Grupo-Corpo }
        'concorrencia' { Grupo-Concorrencia }
        'limites'      { Grupo-Limites }
    }
}

Write-Host ''
Write-Host ('=' * 78) -ForegroundColor DarkGray
if ($script:achados.Count -eq 0) {
    Write-Host '  NENHUM ACHADO. Todas as sondas responderam o esperado.' -ForegroundColor Green
} else {
    Write-Host ("  {0} ACHADO(S):" -f $script:achados.Count) -ForegroundColor Red
    $script:achados | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
}
Write-Host ('=' * 78) -ForegroundColor DarkGray
Write-Host ''
