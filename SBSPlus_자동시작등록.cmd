@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
set "LAUNCHER=%STARTUP%\SBSPlusProxyAuto.cmd"

echo Creating Windows login auto-start launcher...
(
  echo @echo off
  echo cd /d "%~dp0"
  echo where py ^>nul 2^>nul
  echo if %%errorlevel%%==0 ^(
  echo   py -3 setup_sbs_plus_proxy.py ^>^> sbs-plus-auto.log 2^>^&1
  echo   if errorlevel 1 exit /b 1
  echo   py -3 sbs_plus_local_proxy.py ^>^> sbs-plus-auto.log 2^>^&1
  echo ^) else ^(
  echo   python setup_sbs_plus_proxy.py ^>^> sbs-plus-auto.log 2^>^&1
  echo   if errorlevel 1 exit /b 1
  echo   python sbs_plus_local_proxy.py ^>^> sbs-plus-auto.log 2^>^&1
  echo ^)
) > "%LAUNCHER%"

echo.
echo Auto-start registered:
echo %LAUNCHER%
echo.
echo Running setup now so M3U route is configured and auto-pushed...
where py >nul 2>nul
if %errorlevel%==0 (
  py -3 setup_sbs_plus_proxy.py
) else (
  python setup_sbs_plus_proxy.py
)
if errorlevel 1 (
  echo.
  echo Auto-start was registered, but initial setup or GitHub push failed.
  echo Check git credentials/network, then run SBSPlus_수동시작.cmd once.
  pause
  exit /b 1
)

echo.
echo Done. SBS Plus proxy will start automatically at Windows login.
echo To start it right now, run SBSPlus_수동시작.cmd.
pause
