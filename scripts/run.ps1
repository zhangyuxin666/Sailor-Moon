[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [switch]$NoBrowser,
    [switch]$WithQq
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$envFile = Join-Path $projectRoot ".env"

function Get-DotEnvValue([string]$Name) {
    if (-not (Test-Path -LiteralPath $envFile)) { return "" }
    $line = Get-Content -LiteralPath $envFile -Encoding UTF8 |
        Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } |
        Select-Object -Last 1
    if ($null -eq $line) { return "" }
    return (($line -split "=", 2)[1]).Trim().Trim('"').Trim("'")
}

Set-Location $projectRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "未找到 Docker。请先安装并启动 Docker Desktop。"
}
& docker version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Engine 未运行。请先启动 Docker Desktop。"
}

if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ".env.example") -Destination $envFile
    Write-Host "[run] 已从 .env.example 创建 .env" -ForegroundColor Cyan
}

$env:APP_PORT = "$Port"
$composeArguments = @("compose")
$qqAppId = Get-DotEnvValue "QQ_BOT_APP_ID"
$qqSecret = Get-DotEnvValue "QQ_BOT_APP_SECRET"
if ($WithQq -or (-not [string]::IsNullOrWhiteSpace($qqAppId) -and -not [string]::IsNullOrWhiteSpace($qqSecret))) {
    $composeArguments += @("--profile", "qq")
}
$composeArguments += @("up", "-d", "--build")

Write-Host "[run] 正在构建并启动 PostgreSQL、AI FastAPI 和 Spring Boot..." -ForegroundColor Cyan
& docker @composeArguments
if ($LASTEXITCODE -ne 0) { throw "Docker Compose 启动失败。" }

$url = "http://127.0.0.1:$Port/"
$ready = $false
for ($attempt = 0; $attempt -lt 120; $attempt++) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "${url}health/ready" -TimeoutSec 2
        if ($response.StatusCode -eq 200) { $ready = $true; break }
    }
    catch {
        Start-Sleep -Seconds 1
    }
}

if (-not $ready) {
    & docker compose logs --tail 100 api ai postgres
    throw "服务未在预期时间内就绪。"
}

Write-Host "[run] 活动管家已启动：$url" -ForegroundColor Green
Write-Host "[run] 查看日志：docker compose logs -f api ai"
if (-not $NoBrowser) { Start-Process $url }
