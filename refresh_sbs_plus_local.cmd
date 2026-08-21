@echo off
setlocal
cd /d "%~dp0"

where py >nul 2>nul
if %errorlevel%==0 (
  py -3 refresh_sbs_plus_local.py
) else (
  python refresh_sbs_plus_local.py
)

if errorlevel 1 (
  echo.
  echo SBS Plus refresh FAILED.
  echo Confirm this PC is using a Korean network and try again.
  pause
  exit /b 1
)

echo.
echo SBS Plus refresh completed.
pause
