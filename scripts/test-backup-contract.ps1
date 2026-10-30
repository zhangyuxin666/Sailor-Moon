# Pure safety-contract checks; this script never calls Docker.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'backup-common.ps1')
$script:checks = 0
function Assert-Test([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
    $script:checks++
}
function Assert-Rejected([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-Test $rejected $Message
}
function Write-TestTar([string]$Path, [string]$Name, [byte]$Type = 48) {
    $bytes = New-Object byte[] 10240
    [Text.Encoding]::UTF8.GetBytes($Name).CopyTo($bytes, 0)
    $bytes[156] = $Type
    [Text.Encoding]::ASCII.GetBytes('ustar').CopyTo($bytes, 257)
    [Text.Encoding]::ASCII.GetBytes('00').CopyTo($bytes, 263)
    for ($i = 148; $i -lt 156; $i++) { $bytes[$i] = 32 }
    $sum = 0
    for ($i = 0; $i -lt 512; $i++) { $sum += $bytes[$i] }
    $checksum = [Convert]::ToString($sum, 8).PadLeft(6, '0') + [char]0 + ' '
    [Text.Encoding]::ASCII.GetBytes($checksum).CopyTo($bytes, 148)
    [IO.File]::WriteAllBytes($Path, $bytes)
}
$temporaryParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$directory = Join-Path $temporaryParent ('sailor-backup-contract-' + [Guid]::NewGuid().ToString('N'))
$null = Assert-BackupChild $directory $temporaryParent
$null = New-Item -ItemType Directory -Path $directory
try {
    foreach ($file in @('backup.ps1', 'restore.ps1', 'backup-common.ps1')) {
        $tokens = $null; $errors = $null
        $null = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file), [ref]$tokens, [ref]$errors)
        Assert-Test ($errors.Count -eq 0) ('PowerShell parse failed: ' + $file)
    }
    function docker {
        $global:LASTEXITCODE = 0
        if ($args[0] -ceq 'ps') { "first`nsecond" }
        elseif ($args[0] -ceq 'inspect') {
            if ($args.Count -ne 3 -or $args[1] -cne 'first' -or $args[2] -cne 'second') { throw 'Docker inspect did not receive separate container IDs.' }
            '[{"Id":"first","Config":{"Labels":{"com.docker.compose.project":"fixture"}}},{"Id":"second","Config":{"Labels":{"com.docker.compose.project":"fixture"}}}]'
        } else { throw 'Unexpected Docker mock operation.' }
    }
    $inspected = @(Get-BackupContainers @{ Project='fixture' })
    Assert-Test ($inspected.Count -eq 2) 'Failed multiline Docker container inventory.'
    $volume = @{ type='volume'; source='uploads'; host_path='/var/lib/docker/volumes/uploads/_data'; subpath='' }
    $bind = @{ type='bind'; source='/var/lib/docker/volumes/uploads/_data/child'; host_path='/var/lib/docker/volumes/uploads/_data/child'; subpath='' }
    Assert-Test (Test-BackupSameStorage $volume $bind) 'Missed bind/volume overlap.'
    $other = @{ type='volume'; source='restored'; host_path='/var/lib/docker/volumes/restored/_data'; subpath='' }
    Assert-Test (!(Test-BackupSameStorage $volume $other)) 'Independent volumes overlap.'
    Assert-Rejected { Assert-BackupChild (Join-Path $temporaryParent 'outside') $directory } 'Accepted filesystem traversal.'
    $container = @{ Id='fixture'; Config=@{ WorkingDir='/app'; Env=@() }; Mounts=@(@{ Destination='/app/data'; Source=$volume.host_path; Type='volume'; Name='uploads'; RW=$true }) }
    $mount = Get-BackupMount $container 'data/uploads' 'uploads'
    Assert-Test ($mount.Path -ceq '/app/data/uploads' -and $mount.Subpath -ceq '/uploads' -and $mount.HostPath -ceq $volume.host_path) 'Failed relative container path resolution.'
    $container.Mounts += @{ Destination='/app/data/uploads/child'; Source='/other'; Type='bind'; RW=$true }
    Assert-Rejected { Get-BackupMount $container 'data/uploads' 'uploads' } 'Accepted nested mount.'
    $archive = Join-Path $directory 'uploads.tar'
    Write-TestTar $archive 'safe.txt'
    Assert-BackupArchive $archive
    $script:checks++
    foreach ($bad in @('../escape', '/absolute', 'nested/.env.production', 'windows\escape')) {
        Write-TestTar $archive $bad
        Assert-Rejected { Assert-BackupArchive $archive } ('Accepted tar path: ' + $bad)
    }
    foreach ($type in @(49, 50, 51, 52, 54, 120)) {
        Write-TestTar $archive 'unsafe' $type
        Assert-Rejected { Assert-BackupArchive $archive } ('Accepted tar special member: ' + $type)
    }
    Write-TestTar $archive 'safe.txt'
    [IO.File]::WriteAllBytes((Join-Path $directory 'database.dump'), [Text.Encoding]::ASCII.GetBytes('PGDMPfixture'))
    $artifacts = @('database.dump','uploads.tar') | ForEach-Object {
        $file = Join-Path $directory $_
        @{ file=$_; bytes=(Get-Item -LiteralPath $file).Length; sha256=(Get-BackupHash $file) }
    }
    $manifest = @{ format_version=1; status='complete'; source=@{ project='source'; engine_id='fixture'; database_storage=@{ kind='database'; type='volume'; source='db'; host_path='/volumes/db'; subpath='' } }; artifacts=@($artifacts); storage=@(@{ kind='uploads'; type='volume'; source='uploads'; host_path='/volumes/uploads'; subpath='' }) }
    $manifestPath = Join-Path $directory 'manifest.json'
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8))
    $null = Read-BackupManifest $directory
    $script:checks++
    [IO.File]::WriteAllBytes((Join-Path $directory 'database.dump'), [Text.Encoding]::ASCII.GetBytes('PGDMPtampered'))
    Assert-Rejected { Read-BackupManifest $directory } 'Accepted a tampered artifact.'
    function docker { $global:LASTEXITCODE = 37; 'simulated native failure' }
    Assert-Rejected { Invoke-BackupDocker @('fixture') } 'Ignored a native Docker exit failure.'
    Write-Output ("PASS: $script:checks pure backup/restore contract checks; Docker was not accessed.")
} finally {
    Remove-BackupTemporaryDirectory $directory $temporaryParent
}

# This standalone test intentionally simulates a native failure; report successful assertions as exit zero.
exit 0
