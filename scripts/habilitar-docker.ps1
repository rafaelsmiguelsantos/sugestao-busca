<#
    Habilita o que o Docker Desktop precisa e esta faltando nesta maquina:
    a Virtual Machine Platform e o WSL 2.

    PRECISA DE ELEVACAO. O usuario desta maquina e administrador local, entao o
    prompt do UAC pede apenas confirmacao, sem senha.

    Como rodar:
      1. Menu Iniciar > digite "terminal" > Ctrl+Shift+Enter
         (ou clique com o botao direito neste arquivo > Executar com o PowerShell
          a partir de um terminal ja elevado)
      2. pwsh scripts\habilitar-docker.ps1
      3. Reiniciar o computador
      4. Rodar de novo o instalador do Docker Desktop (x86_64, nao o Arm)

    Depois disso, de volta a um terminal normal:
      docker compose up -d
      pwsh scripts\criar-colecao.ps1

    Isto e o unico passo do projeto que exige privilegio de administrador.
    Nada mais aqui precisa — o Solr do zip (scripts\solr-local.ps1) ja roda sem.
#>
#Requires -RunAsAdministrator

[CmdletBinding()]
param(
    # Por padrao nao instala distribuicao Linux: o Docker Desktop cria a sua propria
    # (docker-desktop). Use -ComUbuntu se tambem quiser um Ubuntu para uso geral.
    [switch] $ComUbuntu
)

$ErrorActionPreference = 'Stop'

Write-Host 'Habilitando WSL 2 e Virtual Machine Platform...' -ForegroundColor Cyan

$ok = $true
try {
    if ($ComUbuntu) { wsl.exe --install }
    else            { wsl.exe --install --no-distribution }
    if ($LASTEXITCODE -ne 0) { $ok = $false }
} catch {
    $ok = $false
    Write-Warning "wsl --install falhou: $($_.Exception.Message)"
}

if (-not $ok) {
    Write-Host 'Caindo para o DISM, recurso a recurso...' -ForegroundColor Yellow
    dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
    dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
}

Write-Host ''
Write-Host 'Estado dos recursos:' -ForegroundColor Cyan
foreach ($f in @('VirtualMachinePlatform', 'Microsoft-Windows-Subsystem-Linux')) {
    $estado = (Get-WindowsOptionalFeature -Online -FeatureName $f).State
    Write-Host ("  {0,-36} {1}" -f $f, $estado)
}

Write-Host ''
Write-Host 'Reinicie o computador e rode de novo o instalador do Docker Desktop (x86_64).' -ForegroundColor Green
