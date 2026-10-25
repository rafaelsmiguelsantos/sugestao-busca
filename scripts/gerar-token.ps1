<#
    Gera um JWT HS256 para bater nos endpoints protegidos.

        .\scripts\gerar-token.ps1 -Tipo cliente
        .\scripts\gerar-token.ps1 -Tipo cliente -ClienteId pf-uniclass -Relacionamento UNICLASS
        .\scripts\gerar-token.ps1 -Tipo jornada -Jornada credito
        .\scripts\gerar-token.ps1 -Tipo operacao      # cadastra em qualquer jornada
        .\scripts\gerar-token.ps1 -Tipo cliente -Curl # imprime o -H pronto

    POR QUE ISTO E UM SCRIPT E NAO UM ENDPOINT

    O servico e servidor de recurso: valida token e nao emite nenhum. Emitir e
    papel do provedor de identidade, e um BFF que emite o proprio token e um BFF
    que decide quem ele mesmo e. Um endpoint /dev/token, por bem gateado que
    fosse, seria um caminho de emissao vivendo no mesmo processo que valida — e
    "bem gateado" e uma propriedade que se perde num refactor.

    Aqui, em compensacao, nao ha nada para gatear: este arquivo nao sobe com a
    aplicacao.

    O SEGREDO

    Vem da variavel de ambiente SUGESTAO_SEGREDO, a mesma que a aplicacao le.
    Nao ha valor padrao, nem aqui nem la: sem configurar, nem o servico sobe nem
    este script assina.

        $env:SUGESTAO_SEGREDO = 'qualquer-coisa-com-ao-menos-32-bytes-aqui'

    Ele ja morou em texto dentro de application-local.properties, com o
    raciocinio de que assinava apenas tokens validos contra aquele arquivo. O
    raciocinio valia enquanto o repositorio era privado; num repositorio publico,
    quem subisse a aplicacao com o perfil 'local' teria um servico com segredo
    conhecido por qualquer leitor do GitHub.

    Nota de codificacao: ASCII puro, como os outros scripts. O PowerShell 5.1 le
    .ps1 sem BOM como ANSI.
#>
[CmdletBinding()]
param(
    [ValidateSet('cliente', 'jornada', 'operacao')]
    [string] $Tipo = 'cliente',

    [string] $ClienteId = 'pf-pix-recusado',
    [ValidateSet('PF', 'PJ')]
    [string] $Segmento = 'PF',
    [ValidateSet('VAREJO', 'UNICLASS', 'PERSONNALITE', 'PRIVATE')]
    [string] $Relacionamento = 'VAREJO',

    [string] $Jornada = 'credito',

    [int]    $ValidoPorMinutos = 60,
    [string] $Emissor = 'https://sugestao-busca.dev',
    [string] $Segredo,

    [switch] $Curl,

    # So o token, sem a decoracao. Para quem chama este script de dentro de
    # outro - requisicoes\bater.ps1 gera um token por cenario, e tres blocos de
    # cabecalho por cenario deixam a saida ilegivel.
    [switch] $Silencioso
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# O segredo
# ---------------------------------------------------------------------------
# Le a MESMA variavel de ambiente que a aplicacao le.
#
# Antes este script extraia o segredo de application-local.properties com uma
# expressao regular. Funcionava e tinha um defeito que so apareceu ao abrir o
# repositorio: enquanto o segredo morava no arquivo versionado, qualquer leitor
# do GitHub podia assinar token valido para qualquer instancia subida com o
# perfil 'local'.
#
# Com o segredo no ambiente, o script e a aplicacao leem da mesma fonte e nenhuma
# das duas tem valor padrao. A consequencia e deliberada: sem configurar, nada
# funciona - nem o servico, nem o gerador.
if (-not $Segredo) {
    $Segredo = $env:SUGESTAO_SEGREDO
}

if (-not $Segredo) {
    throw @"
Nenhum segredo configurado.

Defina a variavel de ambiente que a aplicacao tambem le:

    `$env:SUGESTAO_SEGREDO = 'qualquer-coisa-com-ao-menos-32-bytes-aqui'

Ou passe de forma explicita:

    .\scripts\gerar-token.ps1 -Segredo 'ao-menos-32-bytes-....'

Nao ha valor padrao de proposito. Um segredo embutido funcionaria em qualquer
lugar, inclusive em producao, e assinaria tokens que qualquer pessoa com acesso
a este repositorio saberia forjar.
"@
}

if ([Text.Encoding]::UTF8.GetByteCount($Segredo) -lt 32) {
    throw "HS256 exige ao menos 32 bytes de segredo; o atual tem $([Text.Encoding]::UTF8.GetByteCount($Segredo))."
}

# ---------------------------------------------------------------------------
# base64url: base64 sem padding, com - e _ no lugar de + e /
# ---------------------------------------------------------------------------
function ParaBase64Url([byte[]] $bytes) {
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function TextoParaBase64Url([string] $texto) {
    return ParaBase64Url ([Text.Encoding]::UTF8.GetBytes($texto))
}

# ---------------------------------------------------------------------------
# As reivindicacoes, por tipo de chamador
# ---------------------------------------------------------------------------
$agora = [DateTimeOffset]::UtcNow
$iat = [int64] $agora.ToUnixTimeSeconds()
$exp = [int64] $agora.AddMinutes($ValidoPorMinutos).ToUnixTimeSeconds()

switch ($Tipo) {
    'cliente' {
        # O app, em nome de uma pessoa. Segmento e faixa vao no token porque sao
        # atributos de coorte, conhecidos antes de qualquer leitura de perfil — e
        # e isso que permite a consulta ao Solr arrancar junto da leitura do
        # perfil em vez de atras dela.
        $corpo = [ordered] @{
            iss            = $Emissor
            sub            = $ClienteId
            iat            = $iat
            exp            = $exp
            segmento       = $Segmento
            relacionamento = $Relacionamento
            scope          = 'sugestao:ler eventos:escrever'
        }
        $descricao = "cliente $ClienteId ($Segmento/$Relacionamento)"
    }
    'jornada' {
        # Uma jornada de produto. A reivindicacao 'jornada' e o que diz de qual
        # jornada ela e dona, e o caso de uso confere o corpo do cadastro contra
        # ela. Sem isso, a jornada de seguros publicaria oferta de credito.
        $corpo = [ordered] @{
            iss     = $Emissor
            sub     = "jornada-$Jornada"
            iat     = $iat
            exp     = $exp
            jornada = $Jornada
            scope   = 'ofertas:escrever ofertas:ler'
        }
        $descricao = "jornada $Jornada (dona so dela)"
    }
    'operacao' {
        # Privilegio alto, com nome proprio: 'ofertas:todas'. Numa auditoria,
        # procurar quem o recebe e uma busca de uma linha.
        $corpo = [ordered] @{
            iss   = $Emissor
            sub   = 'operacao-catalogo'
            iat   = $iat
            exp   = $exp
            # 'metricas:ler' entra aqui e NAO no token de cliente: a metrica de
            # qualidade de busca nao carrega prefixo nem cliente, mas carrega a
            # forma do servico — taxa de zero-resultado por faixa de prefixo,
            # latencia por etapa e volume de descarte por motivo contam a quem
            # perguntar onde este servico e fragil e quais produtos pouca gente
            # pode contratar.
            scope = 'ofertas:escrever ofertas:ler ofertas:todas aprendizado:executar metricas:ler'
        }
        $descricao = 'operacao (cadastra em QUALQUER jornada)'
    }
}

$cabecalho = [ordered] @{ alg = 'HS256'; typ = 'JWT' }

# -Compress porque espaco em branco entra na assinatura: o JSON que se assina e
# o JSON que se envia, byte a byte.
$parte1 = TextoParaBase64Url ($cabecalho | ConvertTo-Json -Compress)
$parte2 = TextoParaBase64Url ($corpo | ConvertTo-Json -Compress)
$aAssinar = "$parte1.$parte2"

$hmac = New-Object System.Security.Cryptography.HMACSHA256
$hmac.Key = [Text.Encoding]::UTF8.GetBytes($Segredo)
$assinatura = ParaBase64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($aAssinar)))
$hmac.Dispose()

$token = "$aAssinar.$assinatura"

# ---------------------------------------------------------------------------
# Saida
# ---------------------------------------------------------------------------
if ($Curl) {
    Write-Output "-H `"Authorization: Bearer $token`""
    exit 0
}

if ($Silencioso) {
    Write-Output $token
    exit 0
}

Write-Host ''
Write-Host "  tipo    : $descricao" -ForegroundColor Cyan
Write-Host "  escopos : $($corpo.scope)" -ForegroundColor DarkGray
Write-Host "  expira  : $($agora.AddMinutes($ValidoPorMinutos).ToLocalTime().ToString('HH:mm:ss')) (em $ValidoPorMinutos min)" -ForegroundColor DarkGray
Write-Host ''
Write-Output $token
Write-Host ''
Write-Host '  Para usar:' -ForegroundColor DarkGray
Write-Host "    `$t = .\scripts\gerar-token.ps1 -Tipo $Tipo | Select-Object -Last 1" -ForegroundColor DarkGray
Write-Host '    Invoke-RestMethod -Uri "http://localhost:8080/v1/sugestoes?q=emprestim" `' -ForegroundColor DarkGray
Write-Host '        -Headers @{ Authorization = "Bearer $t" }' -ForegroundColor DarkGray
Write-Host ''
