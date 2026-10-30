[CmdletBinding()]
param(
    [ValidateRange(0, 65535)]
    [int]$Port = 0,
    [switch]$NoBrowser,
    [switch]$WithQq,
    [switch]$Build,
    [switch]$Single,
    [switch]$ReadWriteSplit
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$envFile = Join-Path $projectRoot ".env"
$clusterMode = -not $Single
if ($Port -eq 0) {
    $Port = if ($clusterMode) { 18080 } else { 8080 }
}

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
$readWriteMode = $ReadWriteSplit -or ((Get-DotEnvValue 'DB_READ_ENABLED') -ieq 'true')
$composeArguments = if ($clusterMode) {
    @("compose", "-f", "compose.cluster.yml")
} else {
    @("compose")
}
if ($readWriteMode) {
    foreach ($name in @('DB_READ_PASSWORD', 'DB_REPLICATION_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace((Get-DotEnvValue $name))) {
            throw "请先在 .env 中设置 $name（见 docs/READ_WRITE_SPLIT.md）。"
        }
    }
    # An explicit base file is necessary when composing overlays in single-instance mode.
    if (-not $clusterMode) { $composeArguments = @('compose', '-f', 'compose.yml') }
    $composeArguments += @('-f', 'compose.replication.yml')
    $env:DB_READ_ENABLED = 'true'
} else { $env:DB_READ_ENABLED = 'false' }
$qqAppId = Get-DotEnvValue "QQ_BOT_APP_ID"
$qqSecret = Get-DotEnvValue "QQ_BOT_APP_SECRET"
$wantQq = $WithQq -or (-not [string]::IsNullOrWhiteSpace($qqAppId) -and -not [string]::IsNullOrWhiteSpace($qqSecret))
if ($wantQq) {
    $composeArguments += @("--profile", "qq")
}
$composeArguments += @("up", "-d")
if ($clusterMode) {
    $composeArguments += "--remove-orphans"
}
if ($Build) {
    $composeArguments += "--build"
}

if ($Build) {
    Write-Host "[run] 正在重新构建并启动活动管家集群..." -ForegroundColor Cyan
} else {
    Write-Host "[run] 正在使用已有镜像启动活动管家集群..." -ForegroundColor Cyan
    Write-Host "[run] 源码有改动时请使用 start.bat -Build" -ForegroundColor DarkGray
}
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
    $logArguments = @("compose")
    if ($clusterMode) {
        $logArguments += @("-f", "compose.cluster.yml")
    }
    if ($readWriteMode) {
        if (-not $clusterMode) { $logArguments += @('-f', 'compose.yml') }
        $logArguments += @('-f', 'compose.replication.yml')
    }
    $logArguments += @("logs", "--tail", "100")
    if ($clusterMode) {
        $logArguments += @("app1", "app2", "nginx")
    } else {
        $logArguments += @("api", "ai", "postgres")
    }
    & docker @logArguments
    throw "服务未在预期时间内就绪。"
}

Write-Host "[run] 活动管家已启动：$url" -ForegroundColor Green
if ($clusterMode) {
    Write-Host "[run] Nginx 入口：$url"
    Write-Host "[run] Tomcat 节点：http://127.0.0.1:18081 、http://127.0.0.1:18082"
    Write-Host "[run] 查看日志：docker compose -f compose.cluster.yml logs -f app1 app2 nginx"
} else {
    Write-Host "[run] 查看日志：docker compose logs -f api ai"
}
if (-not $NoBrowser) { Start-Process $url }
