@echo off
setlocal
set "LAUNCHER=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\SBSPlusProxyAuto.cmd"

if exist "%LAUNCHER%" (
  del /q "%LAUNCHER%"
  echo SBS Plus auto-start removed.
) else (
  echo SBS Plus auto-start is not registered.
)

echo.
echo This only disables future automatic startup.
echo If the proxy is running now, close its command window or stop python manually.
pause
