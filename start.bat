@echo off
setlocal
cd /d "%~dp0"

echo Starting Activity Assistant containers...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
  echo.
  echo Startup failed. See the message above or run: docker compose logs
  pause
)

exit /b %EXIT_CODE%
