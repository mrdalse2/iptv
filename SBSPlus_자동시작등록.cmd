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
  echo   py -3 sbs_plus_local_proxy.py ^>^> sbs-plus-auto.log 2^>^&1
  echo ^) else ^(
  echo   python sbs_plus_local_proxy.py ^>^> sbs-plus-auto.log 2^>^&1
  echo ^)
) > "%LAUNCHER%"

echo.
echo Auto-start registered:
echo %LAUNCHER%
echo Stable playlist: http://iptvproxy.local:8787/playlist.m3u
echo No GitHub credentials or push are used.
echo.
echo Done. Local IPTV Proxy will start automatically at Windows login.
echo To start it right now, run SBSPlus_수동시작.cmd.
pause
