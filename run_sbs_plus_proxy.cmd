@echo off
setlocal
cd /d "%~dp0"

echo [1/2] Configure SBS Plus local proxy route and publish M3U...
python setup_sbs_plus_proxy.py
if errorlevel 1 (
  echo Setup failed.
  pause
  exit /b 1
)

echo.
echo [2/2] Start SBS Plus token-refreshing proxy...
echo Keep this window open while watching SBS Plus in TiviMate.
python sbs_plus_local_proxy.py

pause
