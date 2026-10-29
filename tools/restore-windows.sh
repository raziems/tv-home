#!/usr/bin/env bash
# Windows (Git Bash) restore: put the stock launcher back as Home and re-enable everything a
# debloat list disabled. Uses adb directly.
#   tools/restore-windows.sh                                   stock launcher back, keep tv-home installed
#   tools/restore-windows.sh examples/sony-bravia-vu2/debloat.txt   ...and re-enable that list
#   UNINSTALL=1 tools/restore-windows.sh                       ...and remove the tv-home launcher
set -uo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
HOST="${TV_HOST:-$(cat tools/.tv_host 2>/dev/null || true)}"
[ -n "$HOST" ] || { echo "Don't know the TV's IP: run tools/find-tv-windows.sh or set TV_HOST=192.168.x.y"; exit 1; }
TV="$HOST:${TV_PORT:-5555}"
"$ADB" connect "$TV" >/dev/null
sh() { echo "\$ $*"; "$ADB" -s "$TV" shell "$@"; }

# Stock launcher: whichever of the two Google launchers is installed (disabled or not).
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  if "$ADB" -s "$TV" shell pm list packages -u | grep -q 'com.google.android.apps.tv.launcherx'; then STOCK_LAUNCHER=com.google.android.apps.tv.launcherx
  else STOCK_LAUNCHER=com.google.android.tvlauncher; fi
fi
sh pm enable "$STOCK_LAUNCHER"
sh pm enable com.google.android.tungsten.setupwraith
sh cmd package set-home-activity --user 0 "$STOCK_LAUNCHER/.MainActivity"
sh settings delete secure screensaver_components   # back to the TV's default screensaver
sh am force-stop com.mihir.tvhome
if [ "${UNINSTALL:-0}" = "1" ]; then sh pm uninstall com.mihir.tvhome; fi
if [ -n "${1:-}" ]; then
  grep -vE '^\s*(#|$)' "$1" | awk '{print $1}' | while read -r p; do sh pm enable "$p" < /dev/null; done   # </dev/null: adb must not eat the list
fi
sh am force-stop com.android.tv.settings
sh input keyevent KEYCODE_HOME
echo "Restored. Press Home on the remote."
