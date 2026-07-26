@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\citycopilot.ps1" start
if errorlevel 1 pause
