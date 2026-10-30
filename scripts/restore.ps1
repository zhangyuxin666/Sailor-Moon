<#
.SYNOPSIS
Restore a verified backup ONLY to an explicitly selected, empty isolated Compose target.
.DESCRIPTION
Create target postgres and stopped app/gateway containers first. Start only postgres;
leave every other target-project service stopped and all external database/file writers
off. The target project name must differ from the manifest source, its database must
be empty, and every attachment/log mount must be empty and separate from source stores.
The script never creates/drops databases, starts services, or overwrites existing data.
Checksums, POSIX tar paths/types, dump format, project labels and storage separation are
checked before data writes. A failed restore leaves the isolated target stopped for
inspection; it never deletes user data to undo a partial restore. Use a new empty target
after diagnosing a failure. Only restore trusted backups; checksums detect corruption,
not malicious SQL from an untrusted backup producer. Requires the existing postgres
image with GNU tar and pg_restore; no image pulls/builds are performed.
.EXAMPLE
.\scripts\restore.ps1 -BackupDirectory D:\Backups\activity\20261027T010000Z-12345678 -ComposeFile D:\Recovery\compose.yml -EnvFile D:\Recovery\.env -ProjectName activity-recovery
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackupDirectory,
    [Parameter(Mandatory)][string]$ComposeFile,
    [Parameter(Mandatory)][string]$ProjectName,
    [string]$EnvFile = ''
)
. (Join-Path $PSScriptRoot 'backup-common.ps1')
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$directory = Get-BackupPath $BackupDirectory $repo
$manifest = Read-BackupManifest $directory
$context = Get-BackupContext $ComposeFile $EnvFile $ProjectName
if ($context.Project -ceq $manifest.source.project) { throw 'Restore target must use a different Compose project name from the backup source.' }
$containers = @(Get-BackupContainers $context)
$database = Get-BackupDatabase $containers
Assert-BackupWritersStopped $context $database
Assert-BackupDatabaseEmpty $database
$needsQq = @($manifest.storage | Where-Object { $_.kind -ceq 'qq-deliveries' }).Count -gt 0
$stores = @(Get-BackupStorage $containers -IncludeQq:$needsQq)
if ($stores.Count -ne @($manifest.storage).Count) { throw 'Target QQ inclusion must match the backup; refusing to omit or invent a delivery journal.' }
$databaseStore = Get-BackupMount $database ((Invoke-BackupSql $database 'SHOW data_directory').Trim()) 'database'
Assert-BackupStoreDirectory $database $databaseStore
$targets = @($stores | ForEach-Object { ConvertTo-BackupStorageRecord $_ }) + @((ConvertTo-BackupStorageRecord $databaseStore))
if ($context.Engine -ceq $manifest.source.engine_id) {
    foreach ($targetStore in $targets) {
        foreach ($sourceStore in @($manifest.storage) + @($manifest.source.database_storage)) {
            if (Test-BackupSameStorage $targetStore $sourceStore) { throw 'Target storage overlaps source storage. Use new isolated bind paths/volumes.' }
        }
    }
}
for ($i=0; $i -lt $targets.Count; $i++) { for ($j=$i+1; $j -lt $targets.Count; $j++) { if (Test-BackupSameStorage $targets[$i] $targets[$j]) { throw 'Target database/uploads/QQ stores must be separate.' } } }
foreach ($store in $stores) { Assert-BackupStoreEmpty $database $store }
$remote = '/tmp/activity-restore-' + [Guid]::NewGuid().ToString('N') + '.dump'
try {
    $null = Invoke-BackupDocker @('cp', (Join-Path $directory 'database.dump'), ($database.Id + ':' + $remote))
    $null = Invoke-BackupDocker @('exec', $database.Id, 'pg_restore', '--list', $remote)
    # Recheck all destructive boundaries immediately before the first store write.
    $null = Read-BackupManifest $directory
    Assert-BackupWritersStopped $context $database
    Assert-BackupDatabaseEmpty $database
    foreach ($store in $stores) {
        Assert-BackupStoreEmpty $database $store
        $null = Invoke-BackupHelper $database $store @('tar', '--extract', '--file', ('/backup/' + $store.Kind + '.tar'), '--directory', $store.Path, '--keep-old-files', '--no-same-owner', '--no-same-permissions', '--delay-directory-restore') $directory -Writable
    }
    Assert-BackupWritersStopped $context $database
    Assert-BackupDatabaseEmpty $database
    $user = Get-BackupEnvironment $database 'POSTGRES_USER' 'postgres'
    $name = Get-BackupEnvironment $database 'POSTGRES_DB' $user
    $null = Invoke-BackupDocker @('exec', $database.Id, 'pg_restore', ('--username=' + $user), ('--dbname=' + $name), '--single-transaction', '--exit-on-error', '--no-owner', '--no-acl', $remote)
    $null = Invoke-BackupDocker @('exec', $database.Id, 'rm', '-f', $remote)
    $remote = ''
    Assert-BackupWritersStopped $context $database
    Write-Output "Restore complete into isolated project '$ProjectName'. Services remain stopped; verify database, attachments and QQ journal before starting them."
} finally {
    if ($remote) {
        try { $null = Invoke-BackupDocker @('exec', $database.Id, 'rm', '-f', $remote) }
        catch { Write-Warning 'Temporary restore dump cleanup failed in the selected target postgres container.' }
    }
}
