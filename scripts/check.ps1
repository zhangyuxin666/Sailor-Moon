#Requires -Version 7.2
[CmdletBinding()]
param(
    [string]$Python = "python",
    [string]$Maven = "mvn",
    [string]$ResultsDirectory,
    [switch]$SkipInstall,
    [switch]$SkipDocker,
    [switch]$WithE2E
)
# Full entry point: pwsh -File scripts/check.ps1 -WithE2E
# Requires Node 22, Python 3.12, Java 21, Maven 3.9 and Docker Compose v2.24+.
# -SkipInstall uses packages already installed for -Python; default installs into a temporary venv.
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
Set-StrictMode -Version Latest
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runId = [guid]::NewGuid().ToString("N")
$testProject = "sm-ci-$runId"
$scratch = Join-Path ([IO.Path]::GetTempPath()) "sm-check-$runId"
$emptyEnv = Join-Path $scratch "empty.env"
if (-not $ResultsDirectory) { $ResultsDirectory = Join-Path ([IO.Path]::GetTempPath()) "sm-check-results-$runId" }
$ResultsDirectory = [IO.Path]::GetFullPath($ResultsDirectory)
$e2eStarted = $false
$locationPushed = $false
$apiImageBuilt = $false
$aiImageBuilt = $false
$testPython = $Python
$savedEnvironment = @{}
$safeEnvironment = @{
    COMPOSE_DISABLE_ENV_FILE = "true"; COMPOSE_PROJECT_NAME = $testProject
    COMPOSE_FILE = ""; COMPOSE_PROFILES = ""; COMPOSE_ENV_FILES = ""
    POSTGRES_PASSWORD = "ci-only-password"; DOMAIN = "ci.invalid"
    AI_INTERNAL_TOKEN = "ci-only-ai-token"; QQ_GATEWAY_TOKEN = "ci-only-gateway-token"
    QQ_BOT_APP_ID = ""; QQ_BOT_APP_SECRET = ""; QQ_SANDBOX = "true"
    LLM_API_KEY = ""; EMBEDDING_API_KEY = ""; EMBEDDING_BASE_URL = ""
    LLM_BASE_URL = "http://127.0.0.1:1/v1"
    DATABASE_URL = "postgresql://ci:ci@127.0.0.1:1/ci"
    SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:1/ci"
    SPRING_DATASOURCE_USERNAME = "ci"; SPRING_DATASOURCE_PASSWORD = "ci"
    AI_SERVICE_URL = "http://127.0.0.1:1"; QQ_GATEWAY_URL = "http://127.0.0.1:1"
    PYTHONPATH = $projectRoot; PYTHONPYCACHEPREFIX = (Join-Path $scratch "pycache")
    E2E_PORT = "18401"; APP_PORT = "18401"; AI_PORT = "18402"
    APP1_PORT = "18403"; APP2_PORT = "18404"
    E2E_API_IMAGE = "sailor-moon-e2e-api:ci-$runId"
    E2E_AI_IMAGE = "sailor-moon-e2e-ai:ci-$runId"
}
function Invoke-Checked([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed (exit $LASTEXITCODE)." }
}
function Assert-Version([string]$Command, [string[]]$Arguments, [string]$Pattern, [string]$Label) {
    $output = (& $Command @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $output -notmatch $Pattern) { throw "$Label is required. $output" }
    Write-Host "[check] $output"
}
function Invoke-Compose([string[]]$Arguments) {
    Invoke-Checked "docker" (@("compose", "--env-file", $emptyEnv, "-p", $testProject, "-f", (Join-Path $projectRoot "compose.test.yml")) + $Arguments)
}
function Assert-NewTestProject {
    foreach ($kind in @("container", "volume", "network")) {
        $listArguments = @($kind, "ls", "-q", "--filter", "label=com.docker.compose.project=$testProject")
        if ($kind -eq "container") { $listArguments += "-a" }
        $existing = @(& docker @listArguments)
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect Docker $kind resources." }
        if ($existing.Count -gt 0) { throw "Refusing to reuse existing test project $testProject ($kind)." }
    }
}
try {
    if ($WithE2E -and $SkipDocker) { throw "-WithE2E cannot be combined with -SkipDocker." }
    $rootPrefix = $projectRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($ResultsDirectory.Equals($projectRoot, [StringComparison]::OrdinalIgnoreCase) -or $ResultsDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "ResultsDirectory must be outside the repository."
    }
    New-Item -ItemType Directory -Path $scratch | Out-Null
    Set-Content -LiteralPath $emptyEnv -Value "" -Encoding utf8
    foreach ($name in $safeEnvironment.Keys) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        [Environment]::SetEnvironmentVariable($name, $safeEnvironment[$name], "Process")
    }
    Push-Location $projectRoot
    $locationPushed = $true
    Assert-Version "node" @("--version") '^v22\.' "Node.js 22"
    Assert-Version $Python @("--version") '^Python 3\.12\.' "Python 3.12"
    Assert-Version "java" @("-version") 'version "21[\."]' "Java 21 (set JAVA_HOME)"
    Assert-Version $Maven @("--version") 'Apache Maven 3\.9\.' "Maven 3.9"
    if (-not $SkipInstall) {
        Invoke-Checked "npm" @("ci", "--prefix", "web")
        Invoke-Checked "npm" @("ci", "--prefix", "qq-gateway")
        $venv = Join-Path $scratch "venv"
        Invoke-Checked $Python @("-m", "venv", $venv)
        $testPython = Join-Path $venv $(if ($IsWindows) { "Scripts/python.exe" } else { "bin/python" })
        Invoke-Checked $testPython @("-m", "pip", "install", "--no-compile", "-r", "requirements-test.lock")
    }
    Invoke-Checked $testPython @("-m", "pip", "check")
    Invoke-Checked "npm" @("test", "--prefix", "web")
    Invoke-Checked "npm" @("test", "--prefix", "qq-gateway")
    foreach ($module in Get-ChildItem -LiteralPath (Join-Path $projectRoot "qq-gateway") -Filter "*.mjs" -File) {
        Invoke-Checked "node" @("--check", $module.FullName)
    }
    # Settings reads cwd/.env. Run Python tests with no local env file and fake process settings.
    Push-Location $scratch
    try { Invoke-Checked $testPython @("-B", "-m", "pytest", "-p", "no:cacheprovider", (Join-Path $projectRoot "tests_ai"), "-q") }
    finally { Pop-Location }
    Invoke-Checked "npm" @("run", "build", "--prefix", "web")
    Invoke-Checked $Maven @("-B", "-ntp", "verify")
    if (-not (Get-ChildItem -LiteralPath (Join-Path $projectRoot "target") -Filter "activity-assistant-*.war" -File)) { throw "WAR artifact is missing." }
    if (-not $SkipDocker) {
        Invoke-Checked "docker" @("version", "--format", "{{.Server.Version}}")
        $composeVersion = (& docker compose version --short 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or $composeVersion -notmatch '^v?(\d+\.\d+\.\d+)') {
            throw "Cannot determine Docker Compose version: $composeVersion"
        }
        if ([version]$Matches[1] -lt [version]'2.24.0') { throw "Docker Compose 2.24 or newer is required: $composeVersion" }
        Write-Host "[check] Docker Compose $composeVersion"
        foreach ($file in @("compose.yml", "compose.prod.yml", "compose.cluster.yml")) {
            # Resolve optional env_file paths in scratch, where no .env or .env.production exists.
            Copy-Item -LiteralPath (Join-Path $projectRoot $file) -Destination (Join-Path $scratch $file)
            Invoke-Checked "docker" @("compose", "--env-file", $emptyEnv, "--project-directory", $scratch, "-p", "sm-config-ci", "-f", (Join-Path $scratch $file), "--profile", "qq", "config", "--quiet")
            Write-Host "[check] $file validates with fake values and no local env files."
        }
    }
    if ($WithE2E) {
        foreach ($file in @("compose.test.yml", "scripts/e2e_offline.py", "scripts/fixtures/Dockerfile.api-runtime")) {
            if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $file))) { throw "Missing E2E dependency: $file" }
        }
        Assert-NewTestProject
        New-Item -ItemType Directory -Path $ResultsDirectory -Force | Out-Null
        Write-Host "[check] Disposable project: $testProject; retained E2E reports: $ResultsDirectory"
        Invoke-Checked "docker" @("build", "-f", "scripts/fixtures/Dockerfile.api-runtime", "-t", $safeEnvironment.E2E_API_IMAGE, ".")
        $apiImageBuilt = $true
        Invoke-Checked "docker" @("build", "-f", "Dockerfile.ai", "-t", $safeEnvironment.E2E_AI_IMAGE, ".")
        $aiImageBuilt = $true
        $e2eStarted = $true
        foreach ($mode in @("single", "cluster")) {
            Invoke-Compose @("--profile", $mode, "up", "-d", "--no-build")
            if ($mode -eq "cluster") {
                Invoke-Checked $testPython @("scripts/e2e_offline.py", "--base-url", "http://127.0.0.1:18401", "--project", $testProject, "--compose-file", "compose.test.yml", "--mode", $mode, "--verify-state", (Join-Path $ResultsDirectory "e2e-single.json"), "--output", (Join-Path $ResultsDirectory "e2e-transition.json"))
            }
            Invoke-Checked $testPython @("scripts/e2e_offline.py", "--base-url", "http://127.0.0.1:18401", "--project", $testProject, "--compose-file", "compose.test.yml", "--mode", $mode, "--output", (Join-Path $ResultsDirectory "e2e-$mode.json"))
            $apiService = if ($mode -eq "single") { "api" } else { "app1" }
            Assert-Version "docker" @("compose", "--env-file", $emptyEnv, "-p", $testProject, "-f", "compose.test.yml", "--profile", $mode, "exec", "-T", $apiService, "java", "-version") 'version "21[\."]' "Container Java 21"
            Assert-Version "docker" @("compose", "--env-file", $emptyEnv, "-p", $testProject, "-f", "compose.test.yml", "--profile", $mode, "exec", "-T", $apiService, "java", "-cp", "/usr/local/tomcat/lib/catalina.jar", "org.apache.catalina.util.ServerInfo") 'Apache Tomcat/10\.1\.' "Container Tomcat 10.1"
            Assert-Version "docker" @("compose", "--env-file", $emptyEnv, "-p", $testProject, "-f", "compose.test.yml", "exec", "-T", "ai", "python", "--version") '^Python 3\.12\.' "Container Python 3.12"
            Assert-Version "docker" @("compose", "--env-file", $emptyEnv, "-p", $testProject, "-f", "compose.test.yml", "exec", "-T", "qq-gateway", "node", "--version") '^v22\.' "Container Node.js 22"
            Assert-Version "docker" @("compose", "--env-file", $emptyEnv, "-p", $testProject, "-f", "compose.test.yml", "exec", "-T", "postgres", "postgres", "--version") 'PostgreSQL\) 16\.' "Container PostgreSQL 16"
            # Preserve the shared database, uploads and outbox while switching the public port.
            if ($mode -eq "single") { Invoke-Compose @("--profile", "single", "stop", "api") }
        }
    }
    Write-Host "[check] All requested checks passed."
}
finally {
    if ($e2eStarted) {
        # This random project was confirmed empty before creation; only its test resources are removed.
        & docker compose --env-file $emptyEnv -p $testProject -f (Join-Path $projectRoot "compose.test.yml") --profile single --profile cluster down -v --remove-orphans
        if ($LASTEXITCODE -ne 0) { Write-Warning "$testProject cleanup failed; inspect this disposable project manually." }
    }
    if ($apiImageBuilt) { & docker image rm $safeEnvironment.E2E_API_IMAGE | Out-Null }
    if ($aiImageBuilt) { & docker image rm $safeEnvironment.E2E_AI_IMAGE | Out-Null }
    if ($locationPushed) { Pop-Location }
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process") }
    $resolvedScratch = [IO.Path]::GetFullPath($scratch)
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($resolvedScratch.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($resolvedScratch).StartsWith("sm-check-") -and (Test-Path -LiteralPath $resolvedScratch)) {
        Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
    }
}