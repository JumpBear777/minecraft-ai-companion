@echo off
setlocal

set "JAVA_HOME=D:\WorkHelper\Java\JDK-21"
set "GRADLE_USER_HOME=D:\tools\gradle-home"
set "GRADLE_BAT=D:\tools\Gradle\gradle-9.6.1\bin\gradle.bat"

cd /d "%~dp0"

echo Starting Minecraft AI Companion test client...
echo JAVA_HOME=%JAVA_HOME%
echo GRADLE_USER_HOME=%GRADLE_USER_HOME%
echo.

"%GRADLE_BAT%" runClient --no-daemon

echo.
echo Client process ended. Press any key to close this window.
pause >nul
