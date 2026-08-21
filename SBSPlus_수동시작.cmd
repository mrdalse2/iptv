@echo off
setlocal
cd /d "%~dp0"

echo Starting Local IPTV Proxy...
echo TiviMate playlist: http://iptvproxy.local:8787/playlist.m3u
echo Keep this window open while watching IPTV.

where py >nul 2>nul
if %errorlevel%==0 (
  py -3 sbs_plus_local_proxy.py
) else (
  python sbs_plus_local_proxy.py
)

pause
