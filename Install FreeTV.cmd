@echo off
rem Double-click to install FreeTV on a Fire TV. It opens setup.ps1, which guides you step by step.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" %*
