@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-deployment.ps1" %*
exit /b %errorlevel%
