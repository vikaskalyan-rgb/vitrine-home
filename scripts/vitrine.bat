@echo off
rem Vitrine Home helper for Windows.
rem   scripts\vitrine.bat <command> <tv-address> [argument]
rem <tv-address> is your TV's IP (port 5555 is added if you leave it out).
rem
rem Commands:
rem   install              build the app, install it and grant its permissions
rem   home                 make Vitrine Home the TV's home screen
rem   config <file>        send your config.json (name, weather location)
rem   wallpapers <folder>  send up to 10 photos (jpg/png/webp) as wallpapers 1-10
rem   screensaver          use Vitrine Home as the screensaver
rem   backup               copy your setup from the TV into .\backups
rem   restore <file>       put a backup back (applied on the next start)
rem   uninstall            remove Vitrine Home (the TV returns to its own home screen)
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set PKG=app.vitrine.home
set FILES=/sdcard/Android/data/%PKG%/files
set ADB=adb
where adb >nul 2>&1 || set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
set CMD=%~1
set TV=%~2
set ARG=%~3
if "%CMD%"=="" goto usage
if "%TV%"=="" goto usage
echo %TV% | find ":" >nul || set TV=%TV%:5555
"%ADB%" connect %TV% >nul
"%ADB%" -s %TV% get-state >nul 2>&1
if errorlevel 1 (
  echo Can't reach %TV%. Is the TV on, with network debugging enabled? Accept the prompt on the TV, then retry.
  exit /b 1
)
for %%c in (install home config wallpapers screensaver backup restore uninstall) do if /i "%CMD%"=="%%c" goto %%c
goto usage

:install
call gradlew.bat assembleDebug || exit /b 1
"%ADB%" -s %TV% install -r app\build\outputs\apk\debug\app-debug.apk || exit /b 1
for %%p in (android.permission.READ_CALENDAR android.permission.ACCESS_FINE_LOCATION android.permission.BLUETOOTH_CONNECT com.android.providers.tv.permission.READ_EPG_DATA) do "%ADB%" -s %TV% shell pm grant %PKG% %%p >nul 2>&1
"%ADB%" -s %TV% shell cmd notification allow_listener %PKG%/%PKG%.MediaListener >nul 2>&1
"%ADB%" -s %TV% shell cmd package compile -m speed -f %PKG% >nul 2>&1
call :restart
echo Installed. It opens now as a preview; run "home" to make it your home screen.
exit /b 0

:home
"%ADB%" -s %TV% shell cmd package set-home-activity --user 0 %PKG%/.MainActivity
"%ADB%" -s %TV% shell input keyevent KEYCODE_HOME
echo Vitrine Home is now the home screen.
exit /b 0

:config
if not exist "%ARG%" ( echo Usage: config ^<path to config.json^> & exit /b 1 )
"%ADB%" -s %TV% shell mkdir -p %FILES%
"%ADB%" -s %TV% push "%ARG%" %FILES%/config.json >nul
call :restart
echo Config sent.
exit /b 0

:wallpapers
if not exist "%ARG%\" ( echo Usage: wallpapers ^<folder of photos^> & exit /b 1 )
"%ADB%" -s %TV% shell rm -rf %FILES%/wallpapers
"%ADB%" -s %TV% shell mkdir -p %FILES%/wallpapers
set n=0
for /f "delims=" %%F in ('dir /b /on "%ARG%\*.jpg" "%ARG%\*.jpeg" "%ARG%\*.png" "%ARG%\*.webp" 2^>nul') do (
  if !n! LSS 10 (
    set /a n+=1
    "%ADB%" -s %TV% push "%ARG%\%%F" "%FILES%/wallpapers/!n!%%~xF" >nul
    echo   Wallpaper !n! = %%F
  )
)
call :restart
echo !n! wallpaper(s) sent. Choose one: hold OK on the gear button.
exit /b 0

:screensaver
for /f "delims=" %%v in ('"%ADB%" -s %TV% shell settings get secure screensaver_components') do echo Previous screensaver: %%v
"%ADB%" -s %TV% shell settings put secure screensaver_components %PKG%/.GlassDream
"%ADB%" -s %TV% shell settings put secure screensaver_enabled 1
echo Vitrine Home is now the screensaver (put the previous value back to undo).
exit /b 0

:backup
if not exist backups mkdir backups
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set STAMP=%%t
"%ADB%" -s %TV% pull %FILES%/backup.json "backups\backup-%STAMP%.json" >nul || exit /b 1
copy /y "backups\backup-%STAMP%.json" backups\latest.json >nul
if exist backups\wallpapers rmdir /s /q backups\wallpapers
"%ADB%" -s %TV% pull %FILES%/wallpapers backups\wallpapers >nul 2>&1
echo Saved backups\backup-%STAMP%.json (and backups\latest.json).
exit /b 0

:restore
set SRC=%ARG%
if "%SRC%"=="" set SRC=backups\latest.json
if not exist "%SRC%" ( echo No backup at %SRC% & exit /b 1 )
"%ADB%" -s %TV% shell mkdir -p %FILES%
"%ADB%" -s %TV% push "%SRC%" %FILES%/restore.json >nul
if exist backups\wallpapers (
  "%ADB%" -s %TV% shell mkdir -p %FILES%/wallpapers
  for %%F in (backups\wallpapers\*) do "%ADB%" -s %TV% push "%%F" %FILES%/wallpapers/ >nul
)
call :restart
echo Restored from %SRC%.
exit /b 0

:uninstall
"%ADB%" -s %TV% uninstall %PKG% && echo Removed. The TV goes back to its own home screen.
exit /b 0

:restart
"%ADB%" -s %TV% shell am force-stop %PKG%
"%ADB%" -s %TV% shell am start -n %PKG%/.MainActivity >nul
exit /b 0

:usage
echo Usage: scripts\vitrine.bat ^<command^> ^<tv-address^> [argument]
echo   install              build the app, install it and grant its permissions
echo   home                 make Vitrine Home the TV's home screen
echo   config ^<file^>        send your config.json (name, weather location)
echo   wallpapers ^<folder^>  send up to 10 photos as wallpapers 1-10
echo   screensaver          use Vitrine Home as the screensaver
echo   backup               copy your setup from the TV into .\backups
echo   restore [file]       put a backup back (default: backups\latest.json)
echo   uninstall            remove Vitrine Home
exit /b 1
