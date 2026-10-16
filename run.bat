@echo off
cd /d "%~dp0"
call mvn -q clean package
if errorlevel 1 (
  echo Build failed. Check that JDK 17+ and Maven are installed.
  pause
  exit /b 1
)
java -jar target\medibridge.jar %*
pause
