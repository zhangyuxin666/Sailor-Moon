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
if (Test-Path -LiteralPath $productionEnv) {
    & docker compose --env-file $productionEnv -f (Join-Path $projectPath "compose.prod.yml") exec -T postgres `
        pg_dump -U activity -d activity --clean --if-exists | Set-Content -LiteralPath (Join-Path $target "database.sql") -Encoding utf8
} else {
    $database = Join-Path $projectPath "activity.db"
    if (Test-Path -LiteralPath $database) {
        Copy-Item -LiteralPath $database -Destination (Join-Path $target "activity.db")
    }
}

$uploads = Join-Path $projectPath "data\uploads"
if (Test-Path -LiteralPath $uploads) {
    Compress-Archive -LiteralPath $uploads -DestinationPath (Join-Path $target "uploads.zip")
}

Write-Output "Backup created: $target"
