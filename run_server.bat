@echo off
setlocal
cd /d "%~dp0"

echo ========================================
echo One-Block Reborn - Dev Server
echo ========================================

echo This launches the NeoForge development server configured by build.gradle.
echo.

if exist "gradlew.bat" (
    call "gradlew.bat" runServer
    exit /b %ERRORLEVEL%
)

where gradle >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Gradle was not found in PATH.
    pause
    exit /b 1
)
call gradle runServer
exit /b %ERRORLEVEL%
