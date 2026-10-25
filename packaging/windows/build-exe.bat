@echo off
rem Double-click to build EarthLife.exe (needs JDK 21+; installer also needs WiX Toolset 3.14).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-exe.ps1" %*
if errorlevel 1 (echo Build failed. & pause & exit /b 1)
echo.
echo Your launcher is in dist\windows\EarthLife\EarthLife.exe
pause
