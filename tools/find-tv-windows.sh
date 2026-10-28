#!/usr/bin/env bash
# Windows (Git Bash) version of find-tv.sh: scans your /24 for TVs with network ADB open (port 5555),
# connects to the first one, prints what it is, and saves its IP to tools/.tv_host for the other scripts.
#   tools/find-tv-windows.sh            scan and save
#   tools/find-tv-windows.sh 192.168.1.50   skip the scan, use this IP
set -uo pipefail
cd "$(dirname "$0")"
ADB="${ADB:-$LOCALAPPDATA/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
[ -x "$ADB" ] || { echo "adb not found. Install it:  winget install Google.PlatformTools"; exit 1; }

if [ -n "${1:-}" ]; then
  found="$1"
else
  ip=$(ipconfig | grep -m1 'IPv4 Address' | sed 's/.*: //' | tr -d '\r')
  [ -n "$ip" ] || { echo "Couldn't work out this computer's IP. Are you on the Wi-Fi?"; exit 1; }
  net=${ip%.*}
  echo "Scanning $net.0/24 from $ip for ADB (port 5555)…"
  # Wake the ARP table, then probe 5555 on every host that answered.
  for i in $(seq 1 254); do (ping -n 1 -w 150 "$net.$i" >/dev/null 2>&1 &); done; sleep 3
  hosts=$(arp -a | grep dynamic | awk '{print $1}' | grep "^$net\.")
  found=""
  for h in $hosts; do
    if powershell -NoProfile -Command "\$c=New-Object Net.Sockets.TcpClient; \$r=\$c.BeginConnect('$h',5555,\$null,\$null); \$ok=\$r.AsyncWaitHandle.WaitOne(700) -and \$c.Connected; \$c.Close(); if(\$ok){exit 0}else{exit 1}"; then
      echo "ADB open on $h"; found="${found:-$h}"
    fi
  done
  [ -n "$found" ] || { echo "No TV with ADB open. On the TV: Settings → Device Preferences → About → tap Build 7× → Developer options → USB debugging (and Network debugging if listed)."; exit 1; }
fi

"$ADB" connect "$found:5555" >/dev/null
for i in $(seq 1 24); do
  st=$("$ADB" devices | grep "$found:5555" | awk '{print $2}')
  [ "$st" = "device" ] && break
  [ "$i" = 1 ] && echo "Waiting for you to accept 'Allow USB debugging?' on the TV…"
  sleep 5
done
[ "$st" = "device" ] || { echo "TV at $found didn't authorise this PC."; exit 1; }
echo "$found" > .tv_host
echo "Saved $found to tools/.tv_host"
echo "--- this TV"
for p in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.display.id; do
  echo "$p = $("$ADB" -s "$found:5555" shell getprop $p | tr -d '\r')"
done
echo "current Home = $("$ADB" -s "$found:5555" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')"
echo "Next: tools/probe-windows.sh to list apps, inputs and candidates for config.xml"
