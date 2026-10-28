#!/usr/bin/env bash
# Everything you need to fill in launcher/res/values/config.xml and a debloat list for a new TV.
#   tools/probe-windows.sh            (uses tools/.tv_host or TV_HOST)
set -uo pipefail
cd "$(dirname "$0")"
ADB="${ADB:-$LOCALAPPDATA/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
HOST="${TV_HOST:-$(cat .tv_host 2>/dev/null || true)}"
[ -n "$HOST" ] || { echo "Run tools/find-tv-windows.sh first, or set TV_HOST=192.168.x.y"; exit 1; }
TV="$HOST:${TV_PORT:-5555}"
"$ADB" connect "$TV" >/dev/null
sh() { "$ADB" -s "$TV" shell "$@" | tr -d '\r'; }

echo "== Device"
for p in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk; do echo "$p = $(sh getprop $p)"; done
echo "RAM: $(sh grep MemTotal /proc/meminfo)"
echo
echo "== Home handlers (the first non-tvhome one is the stock launcher to disable)"
sh cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep '/' | sed 's/^ *//'
echo "current Home: $(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
echo
echo "== Apps that show on a TV launcher (package/activity) — pick dock_apps and hidden_apps from these"
sh cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER | grep '/' | sed 's/^ *//' | sort
echo
echo "== Live TV candidates (live_tv_package) — the one whose package is also above"
sh pm list packages | grep -E 'com.android.tv$|\.tv\.|tvx|tvcenter|livetv|wwtv' | sed 's/package://'
echo
echo "== TV inputs the Inputs menu will list (passthrough = HDMI/AV)"
sh dumpsys tv_input | grep -oE 'id=[^,]+' | sort -u | sed 's/id=//'
echo
echo "== Third-party (user-installed) apps"
sh pm list packages -3 | sed 's/package://' | sort
echo
echo "== All system packages — candidates for a debloat list in examples/<model>/debloat.txt"
sh pm list packages -s | sed 's/package://' | sort
