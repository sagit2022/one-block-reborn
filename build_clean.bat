@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo ========================================
echo One-Block Reborn - Clean Build
echo ========================================
echo.

if exist "gradlew.bat" (
    call "gradlew.bat" clean build
    set "RESULT=!ERRORLEVEL!"
) else (
    where gradle >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Gradle was not found in PATH.
        echo Install Gradle 8.8 or generate a Gradle Wrapper.
        pause
        exit /b 1
    )
    call gradle clean build
    set "RESULT=!ERRORLEVEL!"
)

if "%RESULT%"=="0" (
    echo [OK] Clean build completed successfully.
) else (
    echo [ERROR] Clean build failed with code %RESULT%.
)
pause
exit /b %RESULT%
