[CmdletBinding()]
param(
    [switch]$Failover
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composeArguments = @("compose", "-f", "compose.cluster.yml")
$gateway = "http://127.0.0.1:18080"

Set-Location $projectRoot

function Invoke-DockerChecked {
    param([string[]]$Arguments)

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed: docker $($Arguments -join ' ')"
    }
}

function Show-NodeDistribution {
    param(
        [string]$Title,
        [int]$Count = 8
    )

    Write-Host ""
    Write-Host $Title -ForegroundColor Cyan
    $results = 1..$Count | ForEach-Object {
        Invoke-RestMethod "$gateway/cluster/node" -TimeoutSec 10
    }
    $results |
        Group-Object instance |
        Select-Object Name, Count |
        Format-Table -AutoSize
}

Write-Host "1. Cluster containers" -ForegroundColor Cyan
Invoke-DockerChecked -Arguments ($composeArguments + @("ps"))

Write-Host ""
Write-Host "2. Business application" -ForegroundColor Cyan
$page = Invoke-WebRequest "$gateway/login" -UseBasicParsing -TimeoutSec 10
Write-Host "HTTP $($page.StatusCode) - $gateway/login" -ForegroundColor Green

Show-NodeDistribution -Title "3. Nginx round-robin results"

if ($Failover) {
    Write-Host ""
    Write-Host "4. Failover test: stop app1" -ForegroundColor Yellow
    Invoke-DockerChecked -Arguments ($composeArguments + @("stop", "app1")) | Out-Null
    try {
        Show-NodeDistribution -Title "After app1 stops, all requests should reach app2" -Count 6
        $page = Invoke-WebRequest "$gateway/login" -UseBasicParsing -TimeoutSec 10
        Write-Host "Business page while app1 is stopped: HTTP $($page.StatusCode)" -ForegroundColor Green
    }
    finally {
        Write-Host ""
        Write-Host "5. Restore app1" -ForegroundColor Yellow
        Invoke-DockerChecked -Arguments ($composeArguments + @("start", "app1")) | Out-Null
        $healthy = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            try {
                $response = Invoke-WebRequest "http://127.0.0.1:18081/health/ready" -UseBasicParsing -TimeoutSec 2
                if ($response.StatusCode -eq 200) {
                    $healthy = $true
                    break
                }
            }
            catch {
                Start-Sleep -Seconds 1
            }
        }
        if (-not $healthy) {
            throw "app1 did not become healthy in time."
        }
        Write-Host "Waiting for Nginx fail_timeout window..." -ForegroundColor DarkGray
        Start-Sleep -Seconds 11
        Show-NodeDistribution -Title "app1 restored; both nodes are serving requests" -Count 6
    }
}

Write-Host ""
Write-Host "Cluster check complete: $gateway" -ForegroundColor Green
