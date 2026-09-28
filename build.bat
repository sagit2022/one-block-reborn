@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo ========================================
echo One-Block Reborn - Build
echo ========================================
echo.

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java was not found in PATH.
    echo Install Java 21 and make sure java.exe is available in PATH.
    pause
    exit /b 1
)

java -version
if exist "gradlew.bat" (
    call "gradlew.bat" build
    set "RESULT=!ERRORLEVEL!"
) else (
    where gradle >nul 2>&1
    if errorlevel 1 (
        echo.
        echo [ERROR] Gradle was not found and this project does not contain Gradle Wrapper.
        echo Install Gradle 8.8 or generate the wrapper from IntelliJ/Gradle, then run this file again.
        pause
        exit /b 1
    )
    call gradle build
    set "RESULT=!ERRORLEVEL!"
)

echo.
if "%RESULT%"=="0" (
    echo [OK] Build completed successfully.
    echo JAR files are in build\libs\
) else (
    echo [ERROR] Build failed with code %RESULT%.
)
pause
exit /b %RESULT%
