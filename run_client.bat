@echo off
setlocal
cd /d "%~dp0"

echo ========================================
echo One-Block Reborn - Client
echo ========================================

aif exist "gradlew.bat" (
    call "gradlew.bat" runClient
    exit /b %ERRORLEVEL%
)

where gradle >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Gradle was not found in PATH.
    pause
    exit /b 1
)
call gradle runClient
exit /b %ERRORLEVEL%
