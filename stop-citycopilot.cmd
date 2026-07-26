@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\citycopilot.ps1" stop
if errorlevel 1 pause
