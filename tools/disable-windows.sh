#!/usr/bin/env bash
# Windows (Git Bash) version of disable.sh: disable every package in a debloat list, one pm call per
# shell command (chained pm calls can stop early). Packages are disabled for user 0, never uninstalled.
#   tools/disable-windows.sh examples/sony-bravia-vu2/debloat.txt
# Undo with tools/restore-windows.sh <same list>.
set -uo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: tools/disable-windows.sh <debloat list>}"
ADB="${ADB:-$LOCALAPPDATA/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
HOST="${TV_HOST:-$(cat tools/.tv_host 2>/dev/null || true)}"
[ -n "$HOST" ] || { echo "Run tools/find-tv-windows.sh first, or set TV_HOST=192.168.x.y"; exit 1; }
TV="$HOST:${TV_PORT:-5555}"
"$ADB" connect "$TV" >/dev/null
grep -vE '^\s*(#|$)' "$LIST" | awk '{print $1}' | while read -r p; do
  echo "$p -> $("$ADB" -s "$TV" shell pm disable-user --user 0 "$p" | tr -d '\r')"
done
