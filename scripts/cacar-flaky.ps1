<#
    Caca teste nao deterministico rodando a suite varias vezes sob contencao de
    CPU.

        .\scripts\cacar-flaky.ps1                  # 10 rodadas, com carga
        .\scripts\cacar-flaky.ps1 -Rodadas 30
        .\scripts\cacar-flaky.ps1 -SemCarga        # so repeticao, sem ocupar CPU

    POR QUE A CARGA E A PARTE QUE IMPORTA

    Rodar a suite dez vezes numa maquina ociosa nao acha quase nada: os testes
    sensiveis a tempo tem margem de sobra quando a CPU esta livre. O que os
    derruba e o agendador - thread descolocada, pausa de GC, janela de relogio
    virando - e isso acontece em CI, nao na maquina de quem escreveu o teste.

    Foi assim que o unico flaky remanescente deste projeto apareceu: 12 rodadas
    ociosas passaram 12 vezes; com os 12 nucleos ocupados, a rodada 6 falhou em
    AgregadosEmMemoriaTest.normalizaAJornada, que afirmava isEqualTo(1.0) sobre
    um valor que o decaimento tornava 0,9999999996.

    O QUE ELE NAO ACHA

    Flakiness por ORDEM de execucao. O surefire roda as classes numa ordem
    estavel, e um teste que depende de estado deixado por outro passa sempre
    nesta configuracao. Para isso existe -Ordem, que embaralha.

    Nota de codificacao: ASCII puro. O PowerShell 5.1 le .ps1 sem BOM como ANSI.
#>
[CmdletBinding()]
param(
    [int]    $Rodadas = 10,
    [switch] $SemCarga,

    # Embaralha a ordem das classes de teste a cada rodada, para achar
    # dependencia de ordem. O surefire aceita 'random' ou 'alphabetical'.
    [switch] $Ordem,

    [string] $JavaHome = 'C:\Users\migue\tools\jdk-25'
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome

$raiz = Split-Path -Parent $PSScriptRoot
Push-Location $raiz

$cargas = @()
try {
    if (-not $SemCarga) {
        $nucleos = (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors
        $segundos = [math]::Max(120, $Rodadas * 25)

        Write-Host "Ocupando $nucleos nucleos por ate $segundos s..." -ForegroundColor DarkGray
        $cargas = 1..$nucleos | ForEach-Object {
            Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList @(
                '-NoProfile', '-Command',
                "`$x=0; `$fim=(Get-Date).AddSeconds($segundos); while((Get-Date) -lt `$fim){ `$x=[math]::Sqrt(`$x+1) }"
            )
        }
        Start-Sleep -Seconds 2
    }

    Write-Host ''
    Write-Host "Rodando a suite $Rodadas vez(es)$(if($SemCarga){''}else{' sob contencao'})..." -ForegroundColor Cyan

    $achados = @()

    for ($i = 1; $i -le $Rodadas; $i++) {
        $argumentos = @('-o', '-q', 'surefire:test')
        if ($Ordem) { $argumentos += '-Dsurefire.runOrder=random' }

        $saida = & "$raiz\mvnw.cmd" @argumentos 2>&1 | Out-String

        # @() nao e decoracao: sem ele um unico resultado vira objeto e .Count
        # devolve vazio em vez de 1.
        $ruins = @($saida -split "`r?`n" | Where-Object {
            $_ -match 'FAILURE!|AssertionError|expected:|but was:|Tests run:.*(Failures: [1-9]|Errors: [1-9])'
        })

        if ($ruins.Count -gt 0) {
            $achados += [pscustomobject]@{
                Rodada = $i
                Linhas = ($ruins | Select-Object -First 8)
            }
            Write-Host "F" -NoNewline -ForegroundColor Red
        } else {
            Write-Host "." -NoNewline -ForegroundColor Green
        }
    }

    Write-Host ''
    Write-Host ''

    if ($achados.Count -eq 0) {
        Write-Host "$Rodadas/$Rodadas rodadas verdes." -ForegroundColor Green
        Write-Host ''
        Write-Host 'Isto NAO prova determinismo - prova que nenhuma instabilidade' -ForegroundColor DarkGray
        Write-Host 'apareceu nesta amostra. Aumentar -Rodadas aumenta a confianca;' -ForegroundColor DarkGray
        Write-Host 'nada aqui a transforma em certeza.' -ForegroundColor DarkGray
        exit 0
    }

    Write-Host "INSTAVEL: $($achados.Count) de $Rodadas rodadas falharam." -ForegroundColor Red
    foreach ($a in $achados) {
        Write-Host ''
        Write-Host "--- rodada $($a.Rodada) ---" -ForegroundColor Red
        $a.Linhas | ForEach-Object { Write-Host "  $_" }
    }
    exit 1

} finally {
    Pop-Location
    $cargas | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
}
