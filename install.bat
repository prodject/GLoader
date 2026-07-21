@echo off
chcp 65001 >nul
title GLoader - ADB installer
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install-all.ps1"
echo.
pause
