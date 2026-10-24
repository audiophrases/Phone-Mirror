@echo off
setlocal enabledelayedexpansion
title Phone Mirror (scrcpy)
set "CFG=%~dp0phone_ip.txt"

adb start-server >nul 2>&1

rem --- Clean up stale instances ---
taskkill /f /im scrcpy.exe >nul 2>&1
for /f "tokens=1" %%d in ('adb devices ^| findstr /r "offline unauthorized"') do adb disconnect %%d >nul 2>&1
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object CommandLine -match 'Receiver.java' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >nul 2>&1
adb forward --remove tcp:47800 >nul 2>&1

rem --- Connect: USB first (switches phone to Wi-Fi mode), else saved/typed address ---
set "TARGET="
set "USBSERIAL="
adb -d get-state >nul 2>&1
if !errorlevel!==0 (
    echo USB phone found, enabling Wi-Fi mode...
    adb -d tcpip 5555 >nul
    timeout /t 2 /nobreak >nul
    for /f "tokens=4 delims=/ " %%a in ('adb -d shell "ip -o -4 addr show wlan0"') do set "TARGET=%%a:5555"
    if defined TARGET (
        >"%CFG%" echo !TARGET!
        adb connect !TARGET! | findstr /i /c:"connected to" >nul
        if !errorlevel!==0 (
            echo Connected over Wi-Fi. You can unplug the USB cable.
        ) else (
            echo.
            echo Could not reach !TARGET! over Wi-Fi. Phone and laptop must be on the same network.
            echo Continuing over USB instead.
            set "TARGET="
        )
    ) else (
        echo Phone has no Wi-Fi address, continuing over USB.
    )
) else (
    if exist "%CFG%" set /p TARGET=<"%CFG%"
    if defined TARGET (
        echo Connecting to !TARGET! ...
        adb connect !TARGET! | findstr /i /c:"connected to" >nul
        if errorlevel 1 set "TARGET="
    )
    if not defined TARGET (
        echo.
        echo No saved phone found. Enter IP:port ^(Wireless debugging screen^), or plug in USB and rerun.
        set /p TARGET=Phone IP:port:
        if not defined TARGET exit /b 1
        adb connect !TARGET! | findstr /i /c:"connected to" >nul
        if errorlevel 1 (
            echo Could not connect.
            pause
            exit /b 1
        )
        >"%CFG%" echo !TARGET!
    )
)

rem --- Options ---
set "OPTS="

choice /c YN /m "Normal phone audio ON (videos, music, games)"
if errorlevel 2 (set "NORMAL_AUDIO=") else set "NORMAL_AUDIO=1"

choice /c YN /m "ChatGPT live conversation audio ON (needs the Vector hook module)"
if errorlevel 2 (set "CHATGPT_AUDIO=") else set "CHATGPT_AUDIO=1"

if not defined NORMAL_AUDIO set "OPTS=!OPTS! --no-audio"

choice /c YN /m "Low load mode ON (smaller picture, less bandwidth)"
if errorlevel 2 (echo.) else set "OPTS=!OPTS! --max-size=1024 --video-bit-rate=2M --max-fps=30"

choice /c YN /m "Phone screen OFF while mirroring"
if errorlevel 2 (echo.) else set "OPTS=!OPTS! --turn-screen-off"

echo.
if defined CHATGPT_AUDIO (
    if defined TARGET (adb -s !TARGET! forward tcp:47800 tcp:47800) else (adb -d forward tcp:47800 tcp:47800)
    echo Starting ChatGPT audio receiver. Restart the ChatGPT app on the phone if it was open before the module was enabled.
    start "ChatGPT audio" /min java "%~dp0ClassAudioHook\laptop\Receiver.java"
)
if defined TARGET (
    echo Starting: scrcpy -s !TARGET! !OPTS!
    scrcpy -s !TARGET! !OPTS!
) else (
    echo Starting over USB: scrcpy -d !OPTS!
    scrcpy -d !OPTS!
)
if errorlevel 1 pause
endlocal
