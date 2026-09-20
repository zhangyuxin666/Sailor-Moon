[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8001,
    [switch]$NoBrowser,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$VenvDir = Join-Path $ProjectRoot ".venv"
$PythonExe = Join-Path $VenvDir "Scripts\python.exe"
$EnvFile = Join-Path $ProjectRoot ".env"
$LogDir = Join-Path $ProjectRoot ".run"
$ChildProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()

function Write-Step([string]$Message) {
    Write-Host "[run] $Message" -ForegroundColor Cyan
}

function Test-PortInUse([int]$TargetPort) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $result = $client.BeginConnect("127.0.0.1", $TargetPort, $null, $null)
        if (-not $result.AsyncWaitHandle.WaitOne(300)) { return $false }
        $client.EndConnect($result)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Get-DotEnvValue([string]$Name) {
    $processValue = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($processValue)) { return $processValue }
    if (-not (Test-Path -LiteralPath $EnvFile)) { return "" }

    $line = Get-Content -LiteralPath $EnvFile -Encoding UTF8 |
        Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } |
        Select-Object -Last 1
    if ($null -eq $line) { return "" }
    return (($line -split "=", 2)[1]).Trim().Trim('"').Trim("'")
}

function Start-ServiceProcess(
    [string]$Name,
    [string]$FilePath,
    [string[]]$Arguments
) {
    $stdout = Join-Path $LogDir "$Name.log"
    $stderr = Join-Path $LogDir "$Name.error.log"
    $process = Start-Process -FilePath $FilePath `
        -ArgumentList $Arguments `
        -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru
    $ChildProcesses.Add($process)
    Write-Step "$Name started (PID $($process.Id))"
    return $process
}

function Stop-AllServices {
    if ($ChildProcesses.Count -eq 0) { return }
    Write-Step "Stopping services..."
    foreach ($process in $ChildProcesses) {
        try {
            if (-not $process.HasExited) {
                # /T also stops Python's launcher child and npm's Node.js child.
                & taskkill.exe /PID $process.Id /T /F 2>$null | Out-Null
            }
        }
        catch {
            # A process may have exited between the status check and stop call.
        }
    }
}

try {
    Set-Location $ProjectRoot
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

    if (-not (Test-Path -LiteralPath $PythonExe)) {
        if ($SkipInstall) {
            throw "Python virtual environment not found. Run without -SkipInstall first."
        }

        $systemPython = Get-Command py -ErrorAction SilentlyContinue
        if ($null -ne $systemPython) {
            Write-Step "Creating Python virtual environment..."
            & $systemPython.Source -3 -m venv $VenvDir
        }
        else {
            $systemPython = Get-Command python -ErrorAction SilentlyContinue
            if ($null -eq $systemPython) {
                throw "Python 3 was not found. Install Python 3.11 or newer and try again."
            }
            Write-Step "Creating Python virtual environment..."
            & $systemPython.Source -m venv $VenvDir
        }
        if ($LASTEXITCODE -ne 0) { throw "Failed to create the Python virtual environment." }
    }

    if (-not $SkipInstall) {
        $requirementsFile = Join-Path $ProjectRoot "requirements.txt"
        $requirementsHash = (Get-FileHash -LiteralPath $requirementsFile -Algorithm SHA256).Hash
        $hashMarker = Join-Path $VenvDir ".requirements.sha256"
        $installedHash = if (Test-Path -LiteralPath $hashMarker) {
            (Get-Content -LiteralPath $hashMarker -Raw).Trim()
        } else { "" }

        if ($requirementsHash -ne $installedHash) {
            Write-Step "Installing Python dependencies (first run may take a few minutes)..."
            & $PythonExe -m pip install -r $requirementsFile
            if ($LASTEXITCODE -ne 0) { throw "Python dependency installation failed." }
            Set-Content -LiteralPath $hashMarker -Value $requirementsHash -Encoding ASCII
        }
    }

    if (-not (Test-Path -LiteralPath $EnvFile)) {
        Copy-Item -LiteralPath (Join-Path $ProjectRoot ".env.example") -Destination $EnvFile
        Write-Step "Created .env from .env.example"
    }

    if (Test-PortInUse $Port) {
        throw "Port $Port is already in use. Close the existing program or run: start.bat -Port 8002"
    }

    $api = Start-ServiceProcess "api" $PythonExe @(
        "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "$Port"
    )
    Start-ServiceProcess "worker" $PythonExe @("-m", "app.worker") | Out-Null
    Start-ServiceProcess "scheduler" $PythonExe @("-m", "app.scheduler.runner") | Out-Null

    $qqAppId = Get-DotEnvValue "QQ_BOT_APP_ID"
    $qqSecret = Get-DotEnvValue "QQ_BOT_APP_SECRET"
    if (-not [string]::IsNullOrWhiteSpace($qqAppId) -and
        -not [string]::IsNullOrWhiteSpace($qqSecret)) {
        $npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
        if ($null -eq $npm) {
            Write-Warning "QQ credentials are configured, but Node.js/npm was not found. QQ gateway was skipped."
        }
        else {
            if (-not $SkipInstall -and -not (Test-Path -LiteralPath (Join-Path $ProjectRoot "node_modules"))) {
                Write-Step "Installing QQ gateway dependencies..."
                & $npm.Source ci
                if ($LASTEXITCODE -ne 0) { throw "Node.js dependency installation failed." }
            }
            $env:QQ_GATEWAY_BACKEND_URL = "http://127.0.0.1:$Port"
            Start-ServiceProcess "qq-gateway" $npm.Source @("run", "qq-gateway") | Out-Null
        }
    }
    else {
        Write-Step "QQ credentials are not configured; QQ gateway skipped."
    }

    Write-Step "Waiting for the web service..."
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($api.HasExited) { break }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 1
            if ($response.StatusCode -eq 200) {
                $ready = $true
                break
            }
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }

    if (-not $ready) {
        $apiErrorLog = Join-Path $LogDir "api.error.log"
        if (Test-Path -LiteralPath $apiErrorLog) {
            Get-Content -LiteralPath $apiErrorLog -Tail 30 | Write-Host
        }
        throw "The web service did not become ready. Check .run\api.error.log."
    }

    $url = "http://127.0.0.1:$Port/"
    $startupUrl = "${url}?startup=$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
    Write-Host ""
    Write-Host "Activity Assistant is running: $url" -ForegroundColor Green
    Write-Host "Logs: $LogDir"
    Write-Host "Press Ctrl+C to stop all services."
    # A unique query string prevents a stale authenticated page cached at "/"
    # from entering an auth redirect loop after a session expires.
    if (-not $NoBrowser) { Start-Process $startupUrl }

    while ($true) {
        Start-Sleep -Seconds 1
        foreach ($process in $ChildProcesses) {
            if ($process.HasExited) {
                throw "A service exited unexpectedly (PID $($process.Id), code $($process.ExitCode)). Check the .run logs."
            }
        }
    }
}
catch [System.Management.Automation.PipelineStoppedException] {
    # Ctrl+C is a normal shutdown path.
}
catch {
    Write-Host "[run] ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
finally {
    Stop-AllServices
}
