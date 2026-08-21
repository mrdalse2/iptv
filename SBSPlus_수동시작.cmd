@echo off
setlocal
cd /d "%~dp0"

echo [1/2] Configure SBS Plus local proxy route and auto-push M3U...
where py >nul 2>nul
if %errorlevel%==0 (
  py -3 setup_sbs_plus_proxy.py
) else (
  python setup_sbs_plus_proxy.py
)
if errorlevel 1 (
  echo.
  echo Setup or GitHub push failed.
  pause
  exit /b 1
)

echo.
echo [2/2] Start SBS Plus local proxy...
echo Keep this window open while watching SBS Plus in TiviMate.
where py >nul 2>nul
if %errorlevel%==0 (
  py -3 sbs_plus_local_proxy.py
) else (
  python sbs_plus_local_proxy.py
)

pause
