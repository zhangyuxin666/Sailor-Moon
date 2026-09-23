param(
    [string]$OutputDirectory = "backups"
)

$ErrorActionPreference = "Stop"
$projectPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$backupRoot = Join-Path $projectPath $OutputDirectory
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$target = Join-Path $backupRoot $timestamp
New-Item -ItemType Directory -Path $target -Force | Out-Null

$productionEnv = Join-Path $projectPath ".env.production"
$composeArgs = if (Test-Path -LiteralPath $productionEnv) {
    @("compose", "--env-file", $productionEnv, "-f", (Join-Path $projectPath "compose.prod.yml"))
} else {
    @("compose", "-f", (Join-Path $projectPath "compose.yml"))
}
& docker @composeArgs exec -T postgres pg_dump -U activity -d activity --clean --if-exists |
    Set-Content -LiteralPath (Join-Path $target "database.sql") -Encoding utf8

$uploads = Join-Path $projectPath "data\uploads"
if (Test-Path -LiteralPath $uploads) {
    Compress-Archive -LiteralPath $uploads -DestinationPath (Join-Path $target "uploads.zip")
}

Write-Output "Backup created: $target"
