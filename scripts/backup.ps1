<#
.SYNOPSIS
Create a verified, quiescent PostgreSQL + attachment + optional QQ log backup.
.DESCRIPTION
Stop ALL selected-project services except postgres first, including AI, app nodes,
gateways and external database clients. This script never stops/starts services.
Created app containers are required to inspect the actual STORAGE_LOCAL_PATH mount;
relative paths are resolved against container WorkingDir. Every created QQ gateway
is included; -IncludeQq also rejects a missing gateway container. Only a PostgreSQL
custom dump and the two data directories are archived, never Compose/.env files.
A complete manifest and atomic directory rename are written only after verification.
Keep the backup private: database data and attachments contain personal information.
Requires Docker with Linux containers and GNU tar in the existing postgres image.
.EXAMPLE
.\scripts\backup.ps1 -ComposeFile compose.prod.yml -EnvFile .env.production -ProjectName activity-prod -IncludeQq -OutputDirectory D:\Backups\activity
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ComposeFile,
    [Parameter(Mandatory)][string]$ProjectName,
    [string]$EnvFile = '',
    [string]$OutputDirectory = '',
    [switch]$IncludeQq
)
. (Join-Path $PSScriptRoot 'backup-common.ps1')
$context = Get-BackupContext $ComposeFile $EnvFile $ProjectName
$containers = @(Get-BackupContainers $context)
$database = Get-BackupDatabase $containers
Assert-BackupWritersStopped $context $database
$stores = @(Get-BackupStorage $containers -IncludeQq:$IncludeQq)
$databaseStore = Get-BackupMount $database ((Invoke-BackupSql $database 'SHOW data_directory').Trim()) 'database'
Assert-BackupStoreDirectory $database $databaseStore
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$OutputDirectory) { $OutputDirectory = Join-Path (Split-Path -Parent $repo) 'activity-backups' }
$backupRoot = Get-BackupPath $OutputDirectory $repo
if ($backupRoot.Contains(',')) { throw 'Output directory cannot contain commas.' }
$null = New-Item -ItemType Directory -Path $backupRoot -Force
$backupId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$target = Join-Path $backupRoot $backupId
$stage = Join-Path $backupRoot ('.partial-' + $backupId)
$null = New-Item -ItemType Directory -Path $stage
$remote = '/tmp/activity-backup-' + [Guid]::NewGuid().ToString('N') + '.dump'
try {
    $user = Get-BackupEnvironment $database 'POSTGRES_USER' 'postgres'
    $name = Get-BackupEnvironment $database 'POSTGRES_DB' $user
    $null = Invoke-BackupDocker @('exec', $database.Id, 'pg_dump', ('--username=' + $user), ('--dbname=' + $name), '--format=custom', '--no-owner', '--no-acl', ('--file=' + $remote))
    $null = Invoke-BackupDocker @('exec', $database.Id, 'pg_restore', '--list', $remote)
    $null = Invoke-BackupDocker @('cp', ($database.Id + ':' + $remote), (Join-Path $stage 'database.dump'))
    foreach ($store in $stores) {
        Assert-BackupStoreDirectory $database $store
        $null = Invoke-BackupHelper $database $store @('tar', '--format=ustar', '-cf', ('/backup/' + $store.Kind + '.tar'), '-C', $store.Path, '.') $stage
        Assert-BackupArchive (Join-Path $stage ($store.Kind + '.tar'))
    }
    Assert-BackupWritersStopped $context $database
    $artifacts = @('database.dump') + @($stores | ForEach-Object { $_.Kind + '.tar' })
    $records = @($artifacts | ForEach-Object { $file = Join-Path $stage $_; @{file=$_; bytes=(Get-Item -LiteralPath $file).Length; sha256=(Get-BackupHash $file)} })
    $manifest = @{
        format_version=1; status='complete'; backup_id=$backupId; created_at=[DateTime]::UtcNow.ToString('o')
        source=@{project=$ProjectName; engine_id=$context.Engine; database=$name; postgres_image=$database.Image; database_storage=(ConvertTo-BackupStorageRecord $databaseStore)}
        consistency='all project services except postgres stopped; no other database clients; operator must keep external file writers stopped'
        artifacts=$records; storage=@($stores | ForEach-Object { ConvertTo-BackupStorageRecord $_ })
    }
    $json = $manifest | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText((Join-Path $stage 'manifest.json'), $json, (New-Object Text.UTF8Encoding($false)))
    $null = Read-BackupManifest $stage
    $null = Invoke-BackupDocker @('exec', $database.Id, 'rm', '-f', $remote)
    $remote = ''
    $null = Assert-BackupChild $stage $backupRoot
    $null = Assert-BackupChild $target $backupRoot
    Move-Item -LiteralPath $stage -Destination $target
    Write-Output "Backup complete: $target"
} catch {
    if (Test-Path -LiteralPath $stage) { Remove-BackupTemporaryDirectory $stage $backupRoot }
    throw
} finally {
    if ($remote) {
        try { $null = Invoke-BackupDocker @('exec', $database.Id, 'rm', '-f', $remote) }
        catch { Write-Warning 'Temporary PostgreSQL dump cleanup failed; remove the named activity-backup temp file from the selected postgres container.' }
    }
}
