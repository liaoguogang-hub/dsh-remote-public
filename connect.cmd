@echo off
setlocal
cd /d "%~dp0"
title DSH Remote - connect
echo ==============================================
echo   DSH Remote  -  one click connect
echo ==============================================
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0connect.ps1"
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (
  echo [connect.ps1 exited with code %RC%]
  echo Run it manually to see the error:
  echo   powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0connect.ps1"
)
echo.
echo Press any key to close this window...
pause >nul
