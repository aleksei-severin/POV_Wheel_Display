@echo off
setlocal

if "%~1"=="" (
    echo Drop a video file onto this .bat file to run the audio-sync utility.
    pause
    exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0POV-BlendFPS.ps1" -InputFile "%~1" -AudioSync

pause
