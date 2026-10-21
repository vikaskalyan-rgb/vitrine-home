#!/usr/bin/env sh
# Vitrine Home helper for macOS / Linux.
#   scripts/vitrine.sh <command> <tv-address> [argument]
# <tv-address> is your TV's IP (port 5555 is added if you leave it out).
#
# Commands:
#   install              build the app, install it and grant its permissions
#   home                 make Vitrine Home the TV's home screen
#   config <file>        send your config.json (name, weather location)
#   wallpapers <folder>  send up to 10 photos (jpg/png/webp) as wallpapers 1-10
#   screensaver          use Vitrine Home as the screensaver
#   backup               copy your setup from the TV into ./backups
#   restore <file>       put a backup back (applied on the next start)
#   uninstall            remove Vitrine Home (the TV returns to its own home screen)
set -e
cd "$(dirname "$0")/.."
PKG=app.vitrine.home
FILES=/sdcard/Android/data/$PKG/files
ADB=${ADB:-adb}
command -v "$ADB" >/dev/null 2>&1 || ADB="$ANDROID_HOME/platform-tools/adb"

cmd=$1; tv=$2; arg=$3
if [ -z "$cmd" ] || [ -z "$tv" ]; then sed -n '2,16p' "$0"; exit 1; fi
case "$tv" in *:*) ;; *) tv="$tv:5555" ;; esac

"$ADB" connect "$tv" >/dev/null
if ! "$ADB" -s "$tv" get-state >/dev/null 2>&1; then
  echo "Can't reach $tv. Is the TV on, with network debugging enabled? Accept the prompt on the TV, then retry."; exit 1
fi
a() { "$ADB" -s "$tv" "$@"; }
restart() { a shell am force-stop $PKG; a shell am start -n $PKG/.MainActivity >/dev/null; }

case "$cmd" in
  install)
    ./gradlew assembleDebug
    a install -r app/build/outputs/apk/debug/app-debug.apk
    for p in android.permission.READ_CALENDAR android.permission.ACCESS_FINE_LOCATION \
             android.permission.BLUETOOTH_CONNECT com.android.providers.tv.permission.READ_EPG_DATA; do
      a shell pm grant $PKG $p 2>/dev/null || true
    done
    a shell cmd notification allow_listener $PKG/$PKG.MediaListener 2>/dev/null || true
    a shell cmd package compile -m speed -f $PKG >/dev/null 2>&1 || true
    restart
    echo "Installed. It opens now as a preview; run 'home' to make it your home screen." ;;
  home)
    a shell cmd package set-home-activity --user 0 $PKG/.MainActivity
    a shell input keyevent KEYCODE_HOME
    echo "Vitrine Home is now the home screen." ;;
  config)
    [ -f "$arg" ] || { echo "Usage: config <path to config.json>"; exit 1; }
    a shell mkdir -p $FILES
    a push "$arg" $FILES/config.json >/dev/null
    restart; echo "Config sent." ;;
  wallpapers)
    [ -d "$arg" ] || { echo "Usage: wallpapers <folder of photos>"; exit 1; }
    a shell rm -rf $FILES/wallpapers; a shell mkdir -p $FILES/wallpapers
    n=0
    for f in "$arg"/*; do
      case "$f" in *.jpg|*.jpeg|*.png|*.webp|*.JPG|*.JPEG|*.PNG|*.WEBP) ;; *) continue ;; esac
      [ $n -ge 10 ] && break
      n=$((n + 1)); ext=$(echo "${f##*.}" | tr 'A-Z' 'a-z')
      a push "$f" "$FILES/wallpapers/$n.$ext" >/dev/null; echo "  Wallpaper $n = $(basename "$f")"
    done
    restart; echo "$n wallpaper(s) sent. Choose one: hold OK on the gear button." ;;
  screensaver)
    echo "Previous screensaver: $(a shell settings get secure screensaver_components)"
    a shell settings put secure screensaver_components $PKG/.GlassDream
    a shell settings put secure screensaver_enabled 1
    echo "Vitrine Home is now the screensaver (put the previous value back to undo)." ;;
  backup)
    mkdir -p backups; stamp=$(date +%Y%m%d-%H%M)
    a pull $FILES/backup.json "backups/backup-$stamp.json" >/dev/null
    cp "backups/backup-$stamp.json" backups/latest.json
    rm -rf backups/wallpapers; a pull $FILES/wallpapers backups/wallpapers >/dev/null 2>&1 || true
    echo "Saved backups/backup-$stamp.json (and backups/latest.json)." ;;
  restore)
    src=${arg:-backups/latest.json}
    [ -f "$src" ] || { echo "No backup at $src"; exit 1; }
    a shell mkdir -p $FILES; a push "$src" $FILES/restore.json >/dev/null
    if [ -d backups/wallpapers ]; then a shell mkdir -p $FILES/wallpapers; for f in backups/wallpapers/*; do a push "$f" $FILES/wallpapers/ >/dev/null; done; fi
    restart; echo "Restored from $src." ;;
  uninstall)
    a uninstall $PKG && echo "Removed. The TV goes back to its own home screen." ;;
  *) sed -n '2,16p' "$0"; exit 1 ;;
esac
