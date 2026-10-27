# Shared by backup.ps1 and restore.ps1. Dot-sourcing performs no Docker operations.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-BackupDocker {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($exitCode -ne 0) { throw "Docker operation failed (exit $exitCode): $($Arguments[0]). No success was recorded. Check Docker/permissions and the selected Compose project." }
    return (($output | ForEach-Object { [string]$_ }) -join "`n")
}
function Get-BackupProperty($Object, [string]$Name, $Default = $null) {
    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    return $property.Value
}
function Get-BackupEnvironment($Container, [string]$Name, [string]$Default = '') {
    foreach ($item in @($Container.Config.Env)) {
        if ($item.StartsWith($Name + '=', [StringComparison]::Ordinal)) { return $item.Substring($Name.Length + 1) }
    }
    return $Default
}
function Get-BackupPath([string]$Path, [string]$Base) {
    if (![IO.Path]::IsPathRooted($Path)) { $Path = Join-Path $Base $Path }
    return [IO.Path]::GetFullPath($Path)
}
function Assert-BackupChild([string]$Path, [string]$Parent) {
    $resolved = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw 'Filesystem target is outside its expected directory.' }
    return $resolved
}
function Remove-BackupTemporaryDirectory([string]$Path, [string]$Parent) {
    $checked = Assert-BackupChild $Path $Parent
    if (Test-Path -LiteralPath $checked) { Remove-Item -LiteralPath $checked -Recurse -Force }
}
function Get-BackupContext([string]$ComposeFile, [string]$EnvFile, [string]$ProjectName) {
    if ($ProjectName -cnotmatch '^[a-z0-9][a-z0-9_-]{0,62}$') { throw 'ProjectName must be an explicit lowercase Compose project name.' }
    $root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $compose = Get-BackupPath $ComposeFile $root
    if (!(Test-Path -LiteralPath $compose -PathType Leaf)) { throw 'ComposeFile does not exist.' }
    $arguments = @('compose', '--project-directory', (Split-Path -Parent $compose), '-f', $compose, '-p', $ProjectName)
    if ($EnvFile) {
        $environmentPath = Get-BackupPath $EnvFile $root
        if (!(Test-Path -LiteralPath $environmentPath -PathType Leaf)) { throw 'EnvFile does not exist.' }
        $arguments += @('--env-file', $environmentPath)
    }
    # Resolve the explicitly selected project, but never print config/environment secrets.
    $null = Invoke-BackupDocker ($arguments + @('config', '--quiet'))
    $engine = (Invoke-BackupDocker @('info', '--format', '{{.ID}}')).Trim()
    if (!$engine) { throw 'Cannot identify Docker Engine.' }
    return @{ Project = $ProjectName; Engine = $engine; Compose = $arguments }
}
function Get-BackupContainers($Context) {
    # Query project labels directly so inactive-profile and orphaned containers cannot be omitted.
    $ids = @((Invoke-BackupDocker @('ps', '--all', '--filter', ('label=com.docker.compose.project=' + $Context.Project), '--format', '{{.ID}}')) -split '[\r\n]+' | Where-Object { $_ })
    if (!$ids.Count) { throw 'Selected Compose project has no created containers. Create the isolated services first.' }
    $containers = @((Invoke-BackupDocker (@('inspect') + $ids)) | ConvertFrom-Json)
    foreach ($container in $containers) {
        $project = Get-BackupProperty $container.Config.Labels 'com.docker.compose.project' ''
        if ($project -cne $Context.Project) { throw 'Compose container project label does not match ProjectName.' }
    }
    return $containers
}
function Get-BackupDatabase($Containers) {
    $databases = @($Containers | Where-Object { (Get-BackupProperty $_.Config.Labels 'com.docker.compose.service' '') -eq 'postgres' })
    if ($databases.Count -ne 1) { throw 'Exactly one created postgres service is required.' }
    $database = $databases[0]
    if (!$database.State.Running) { throw 'The selected postgres container must be running.' }
    return $database
}
function Assert-BackupWritersStopped($Context, $Database) {
    # Include orphaned services, not just the services listed in the Compose file.
    $running = @((Invoke-BackupDocker @('ps', '--filter', ('label=com.docker.compose.project=' + $Context.Project), '--format', '{{.ID}}')) -split '[\r\n]+' | Where-Object { $_ })
    foreach ($id in $running) { if (!$Database.Id.StartsWith($id, [StringComparison]::Ordinal)) { throw 'Stop all project services except postgres before backup/restore (including AI, gateways and both app nodes).' } }
    $connections = Invoke-BackupSql $Database "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'"
    if ($connections.Trim() -ne '0') { throw 'Other database clients are connected. Stop external writers/clients before continuing.' }
}
function Invoke-BackupSql($Database, [string]$Sql) {
    $user = Get-BackupEnvironment $Database 'POSTGRES_USER' 'postgres'
    $name = Get-BackupEnvironment $Database 'POSTGRES_DB' $user
    if ($user -cnotmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$' -or $name -cnotmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') { throw 'Only simple PostgreSQL database/user identifiers are supported.' }
    return Invoke-BackupDocker @('exec', $Database.Id, 'psql', '--no-psqlrc', '--set=ON_ERROR_STOP=1', '--tuples-only', '--no-align', ('--username=' + $user), ('--dbname=' + $name), '--command', $Sql)
}
function Get-BackupContainerPath($Container, [string]$Path) {
    if ($Path.Contains('\') -or $Path.Contains([char]0)) { throw 'Storage path must be a Linux container path.' }
    if (!$Path.StartsWith('/')) { $Path = $Container.Config.WorkingDir.TrimEnd('/') + '/' + $Path }
    $parts = New-Object 'System.Collections.Generic.List[string]'
    foreach ($part in $Path.Split('/')) {
        if (!$part -or $part -eq '.') { continue }
        if ($part -eq '..') { if (!$parts.Count) { throw 'Storage path escapes the container root.' }; $parts.RemoveAt($parts.Count - 1) } else { $parts.Add($part) }
    }
    if (!$parts.Count) { throw 'Storage path cannot be the container root.' }
    return '/' + ($parts -join '/')
}
function Get-BackupMount($Container, [string]$Path, [string]$Kind) {
    $path = Get-BackupContainerPath $Container $Path
    $matches = @($Container.Mounts | Where-Object { $path -ceq $_.Destination.TrimEnd('/') -or $path.StartsWith($_.Destination.TrimEnd('/') + '/', [StringComparison]::Ordinal) } | Sort-Object { $_.Destination.Length } -Descending)
    if (!$matches.Count) { throw "$Kind path is not backed by a bind/volume mount; refusing to omit container-local data." }
    $mount = $matches[0]
    if ($mount.Type -notin @('bind', 'volume')) { throw 'Storage must use a bind mount or Docker volume.' }
    if (@($Container.Mounts | Where-Object { $_.Destination.StartsWith($path + '/', [StringComparison]::Ordinal) }).Count) { throw 'Nested mounts inside a data directory are unsupported; use one explicit backing store.' }
    $suffix = $path.Substring($mount.Destination.TrimEnd('/').Length)
    $source = if ($mount.Type -eq 'volume') { $mount.Name } else { $mount.Source }
    return @{ Kind = $Kind; Container = $Container.Id; Path = $path; Source = $source; HostPath = $mount.Source; Type = $mount.Type; Subpath = $suffix; Writable = $mount.RW; Identity = ($mount.Type + ':' + $source + ':' + $suffix) }
}
function Get-BackupStorage($Containers, [switch]$IncludeQq) {
    $apps = @($Containers | Where-Object {
        (Get-BackupProperty $_.Config.Labels 'com.docker.compose.service' '') -in @('api', 'app1', 'app2') -or (Get-BackupEnvironment $_ 'STORAGE_LOCAL_PATH' '')
    })
    if (!$apps.Count) { throw 'Create an api/app1/app2 container so its actual attachment mount can be inspected.' }
    $uploads = @($apps | ForEach-Object { Get-BackupMount $_ (Get-BackupEnvironment $_ 'STORAGE_LOCAL_PATH' 'data/uploads') 'uploads' })
    if (@($uploads | ForEach-Object { $_.Identity } | Select-Object -Unique).Count -ne 1) { throw 'Application nodes use different attachment stores. Resolve the mismatch before backup/restore.' }
    $stores = @($uploads[0])
    $qq = @($Containers | Where-Object { (Get-BackupProperty $_.Config.Labels 'com.docker.compose.service' '') -eq 'qq-gateway' })
    if ($qq.Count -gt 1) { throw 'Only one QQ gateway is supported.' }
    if ($IncludeQq -and !$qq.Count) { throw 'IncludeQq requires a created qq-gateway container; its persistent log cannot be omitted.' }
    if ($qq.Count) { $stores += Get-BackupMount $qq[0] (Get-BackupEnvironment $qq[0] 'QQ_DELIVERY_PATH' '/app/data/deliveries') 'qq-deliveries' }
    return $stores
}
function Invoke-BackupHelper($Database, $Store, [string[]]$Command, [string]$Directory = '', [switch]$Writable) {
    $mode = if ($Writable) { 'rw' } else { 'ro' }
    $arguments = @('run', '--rm', '--pull=never', '--network=none', '--read-only', '--user=0:0', '--volumes-from', ($Store.Container + ':' + $mode))
    if ($Directory) {
        if ($Directory.Contains(',')) { throw 'Backup directory cannot contain commas (Docker mount syntax).' }
        $arguments += @('--mount', ('type=bind,source=' + $Directory + ',target=/backup' + $(if ($Writable) { ',readonly' } else { '' })))
    }
    $arguments += @('--entrypoint', $Command[0], $Database.Image)
    if ($Command.Count -gt 1) { $arguments += $Command[1..($Command.Count - 1)] }
    return Invoke-BackupDocker $arguments
}
function Assert-BackupStoreDirectory($Database, $Store) {
    # tar -C follows a symlink at the selected root; fail before it can escape the inspected mount.
    $null = Invoke-BackupHelper $Database $Store @('sh', '-c', 'test -d "$1" && test "$(realpath -e -- "$1")" = "$1"', '_', $Store.Path)
}
function Assert-BackupStoreEmpty($Database, $Store) {
    Assert-BackupStoreDirectory $Database $Store
    if (!$Store.Writable) { throw 'Restore requires a writable attachment/log mount.' }
    $null = Invoke-BackupHelper $Database $Store @('sh', '-c', 'test -d "$1" && test -z "$(find "$1" -mindepth 1 -print -quit)"', '_', $Store.Path)
}
function Assert-BackupDatabaseEmpty($Database) {
    $sql = @"
SELECT
 (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema') +
 (SELECT count(*) FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname NOT IN ('information_schema','public')) +
 (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.classid='pg_proc'::regclass AND d.objid=p.oid AND d.deptype='e')) +
 (SELECT count(*) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.classid='pg_type'::regclass AND d.objid=t.oid AND d.deptype='e')) +
 (SELECT count(*) FROM pg_largeobject_metadata) +
 (SELECT count(*) FROM pg_extension WHERE extname<>'plpgsql')
"@
    if ((Invoke-BackupSql $Database $sql).Trim() -ne '0') { throw 'Target database is not empty. Restore never drops or overwrites existing data.' }
}
function Get-BackupHash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Read-BackupOctal([byte[]]$Block, [int]$Offset, [int]$Length) {
    $value = [Text.Encoding]::ASCII.GetString($Block, $Offset, $Length).Trim([char]0, [char]32)
    if (!$value) { return [long]0 }
    if ($value -cnotmatch '^[0-7]+$') { throw 'Unsupported/malformed tar numeric field.' }
    return [Convert]::ToInt64($value, 8)
}
function Assert-BackupArchive([string]$Path) {
    # Parse uncompressed POSIX ustar ourselves; never trust tar -t text or extract before validation.
    $stream = [IO.File]::OpenRead($Path)
    $names = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $utf8 = New-Object Text.UTF8Encoding($false, $true)
    try {
        $block = New-Object byte[] 512
        $ended = $false
        while ($stream.Position -lt $stream.Length) {
            if ($stream.Read($block, 0, 512) -ne 512) { throw 'Truncated tar header.' }
            if (@($block | Where-Object { $_ -ne 0 }).Count -eq 0) {
                if ($stream.Length - $stream.Position -lt 512) { throw 'Tar must end with two empty blocks.' }
                while ($stream.Position -lt $stream.Length) {
                    $read = $stream.Read($block, 0, 512)
                    if ($read -ne 512 -or @($block | Where-Object { $_ -ne 0 }).Count) { throw 'Unexpected data after tar end marker.' }
                }
                $ended = $true; break
            }
            $checksum = Read-BackupOctal $block 148 8
            [long]$sum = 0; for ($i=0; $i -lt 512; $i++) { if ($i -ge 148 -and $i -lt 156) { $sum += 32 } else { $sum += $block[$i] } }
            if ($sum -ne $checksum -or [Text.Encoding]::ASCII.GetString($block,257,5) -cne 'ustar') { throw 'Invalid or unsupported tar header.' }
            $name = $utf8.GetString($block,0,100).Split([char]0)[0]
            $prefix = $utf8.GetString($block,345,155).Split([char]0)[0]
            if ($prefix) { $name = $prefix + '/' + $name }
            $type = [int]$block[156]
            if ($type -notin @(0,48,53)) { throw 'Tar links, devices, extended headers and special files are forbidden.' }
            while ($name.StartsWith('./', [StringComparison]::Ordinal)) { $name = $name.Substring(2) }
            $name = $name.TrimEnd('/')
            if ($name -eq '.') { $name = '' }
            if ($name) {
                if ($name.StartsWith('/') -or $name.Contains('\') -or $name.Contains(':') -or $name -match '[\x00-\x1f\x7f]' -or @($name.Split('/') | Where-Object { $_ -in @('', '.', '..') -or $_ -match '^\.env($|\.)' }).Count) { throw 'Unsafe path or environment file in tar archive.' }
                if (!$names.Add($name)) { throw 'Duplicate tar member path.' }
            } elseif ($type -ne 53) { throw 'Only the tar root directory may have an empty member name.' }
            $size = Read-BackupOctal $block 124 12
            if ($type -eq 53 -and $size -ne 0) { throw 'Directory has unexpected tar payload.' }
            [long]$skip = [long]([Math]::Ceiling($size / 512.0) * 512)
            if ($skip -gt $stream.Length - $stream.Position) { throw 'Truncated tar payload.' }
            $null = $stream.Seek($skip, [IO.SeekOrigin]::Current)
        }
        if (!$ended) { throw 'Tar end marker is missing.' }
    } finally { $stream.Dispose() }
}
function Read-BackupManifest([string]$Directory) {
    $path = Join-Path $Directory 'manifest.json'
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Completed backup manifest is missing.' }
    $manifest = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.format_version -ne 1 -or $manifest.status -cne 'complete' -or $manifest.source.project -cnotmatch '^[a-z0-9][a-z0-9_-]{0,62}$' -or !$manifest.source.engine_id) { throw 'Invalid backup manifest.' }
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($artifact in @($manifest.artifacts)) {
        if ($artifact.file -cnotin @('database.dump','uploads.tar','qq-deliveries.tar') -or !$seen.Add($artifact.file) -or $artifact.sha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Invalid/duplicate manifest artifact.' }
        $file = Join-Path $Directory $artifact.file
        if (!(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item -LiteralPath $file).Length -ne $artifact.bytes -or (Get-BackupHash $file) -cne $artifact.sha256) { throw 'Backup artifact length/checksum validation failed.' }
        if ($artifact.file.EndsWith('.tar')) { Assert-BackupArchive $file }
    }
    if (!$seen.Contains('database.dump') -or !$seen.Contains('uploads.tar')) { throw 'Required backup artifacts are missing.' }
    $stream = [IO.File]::OpenRead((Join-Path $Directory 'database.dump'))
    try { $header = New-Object byte[] 5; if ($stream.Read($header,0,5) -ne 5 -or [Text.Encoding]::ASCII.GetString($header) -cne 'PGDMP') { throw 'Expected PostgreSQL custom-format dump.' } } finally { $stream.Dispose() }
    $kinds = @($manifest.storage | ForEach-Object { $_.kind })
    if (@($kinds | Select-Object -Unique).Count -ne $kinds.Count -or 'uploads' -cnotin $kinds -or @($kinds | Where-Object { $_ -cnotin @('uploads','qq-deliveries') }).Count -or ($seen.Contains('qq-deliveries.tar') -ne ('qq-deliveries' -cin $kinds))) { throw 'Manifest storage does not match its artifacts.' }
    foreach ($store in @($manifest.storage) + @($manifest.source.database_storage)) {
        if ($store.type -cnotin @('bind','volume') -or !$store.source -or !$store.host_path -or ($store.host_path -cnotmatch '^/' -and $store.host_path -notmatch '^[A-Za-z]:[\\/]') -or $null -eq $store.subpath -or ($store.subpath -and (!$store.subpath.StartsWith('/') -or $store.subpath -match '(^|/)\.\.?(/|$)' -or $store.subpath.Contains('\')))) { throw 'Invalid source storage identity.' }
    }
    return $manifest
}
function ConvertTo-BackupStorageRecord($Store) { return @{ kind=$Store.Kind; type=$Store.Type; source=$Store.Source; host_path=$Store.HostPath; subpath=$Store.Subpath } }
function Test-BackupSameStorage($First, $Second) {
    # Inspect reports Linux daemon paths for bind mounts and volume backing directories.
    # Compare them across mount types too: a bind can point directly into a named volume.
    $a = ($First.host_path.TrimEnd('/','\') + $First.subpath).Replace('\','/')
    $b = ($Second.host_path.TrimEnd('/','\') + $Second.subpath).Replace('\','/')
    $comparison = [StringComparison]::OrdinalIgnoreCase
    return $a.Equals($b,$comparison) -or $a.StartsWith($b.TrimEnd('/') + '/',$comparison) -or $b.StartsWith($a.TrimEnd('/') + '/',$comparison)
}
