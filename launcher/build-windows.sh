#!/usr/bin/env bash
# Windows (Git Bash) port of build-and-install.sh: builds the launcher APK with the bare SDK tools
# and installs it on the TV over adb as the home screen.
#   launcher/build-windows.sh            build + install
#   launcher/build-windows.sh --build    build only
# Env: JAVA_HOME, ANDROID_HOME, BUILD_TOOLS (version folder), PLATFORM (android-NN), ADB (path to
#      adb.exe), TV_HOST (TV IP), STOCK_LAUNCHER, DISABLE_SETUPWRAITH=1 (Google TV only).
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/c/Program Files/Microsoft/jdk-17.0.21.21-hotspot}"
SDK="${ANDROID_HOME:-/c/Program Files (x86)/Android/android-sdk}"
BT="$SDK/build-tools/${BUILD_TOOLS:-36.0.0}"
JAR="$SDK/platforms/${PLATFORM:-android-36}/android.jar"
TARGET_SDK="${TARGET_SDK:-36}"   # Android 16; min stays 30 (Android 11)
ADB="${ADB:-$LOCALAPPDATA/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
# TV address: TV_HOST env, else tools/.tv_host (written by tools/find-tv-windows.sh, git-ignored).
HOST="${TV_HOST:-$(cat ../tools/.tv_host 2>/dev/null || true)}"
[ -n "$HOST" ] || [ "${1:-}" = "--build" ] || { echo "Don't know the TV's IP: run tools/find-tv-windows.sh or set TV_HOST=192.168.x.y"; exit 1; }
TV="$HOST:${TV_PORT:-5555}"
# Stock launcher to disable. Android TV (Sony, Xiaomi, Nvidia): com.google.android.tvlauncher.
# Google TV (TCL, Hisense, Chromecast with Google TV): com.google.android.apps.tv.launcherx.
# Leave unset to detect from the TV's current Home handler.
STOCK_LAUNCHER="${STOCK_LAUNCHER:-}"
export PATH="$JAVA_HOME/bin:$PATH"
B=build
W() { cygpath -w "$1"; }   # the .bat/.exe tools want Windows paths

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2.exe" compile --dir res -o "$B/res.zip"
# Assets are NOT passed to aapt2 here: on Windows it writes their zip entries with backslashes
# (assets/wallpapers\01.jpg), which AssetManager.list() can't see. jar adds them below instead.
"$BT/aapt2.exe" link -o "$B/base.apk" -I "$(W "$JAR")" --manifest AndroidManifest.xml --java "$B/gen" \
  --min-sdk-version 30 --target-sdk-version "$TARGET_SDK" "$B/res.zip"
javac -nowarn -Xlint:-options -encoding UTF-8 --release 11 -classpath "$(W "$JAR")" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
java -cp "$(W "$BT/lib/d8.jar")" com.android.tools.r8.D8 --min-api 30 --lib "$(W "$JAR")" \
  --output "$(W "$B/dex")" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
jar --update --file "$B/unsigned.apk" --no-manifest --no-compress -C "$B/dex" classes.dex -C . assets
"$BT/zipalign.exe" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
java -jar "$(W "$BT/lib/apksigner.jar")" sign --ks debug.keystore --ks-pass pass:android \
  --out "$B/tvhome.apk" "$B/aligned.apk"
echo "built $B/tvhome.apk ($(wc -c < "$B/tvhome.apk") bytes)"
[ "${1:-}" = "--build" ] && exit 0

"$ADB" connect "$TV" >/dev/null
sh() { echo "\$ $*"; "$ADB" -s "$TV" shell "$@"; }   # one pm call per shell: chained pm calls can stop early
home_now=$("$ADB" -s "$TV" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')
if [ -z "$STOCK_LAUNCHER" ]; then
  # Whichever Google launcher the TV ships (listed even when already disabled).
  if "$ADB" -s "$TV" shell pm list packages -u | grep -q 'com.google.android.apps.tv.launcherx'; then STOCK_LAUNCHER=com.google.android.apps.tv.launcherx
  else STOCK_LAUNCHER=com.google.android.tvlauncher; fi
fi
echo "TV $TV · current Home: $home_now · stock launcher to disable: $STOCK_LAUNCHER"
snap="$B/backup-$(date +%Y%m%d-%H%M%S).txt"
{ echo "# home before install:"; echo "$home_now"
  echo "# enabled packages before install:"; "$ADB" -s "$TV" shell pm list packages -e; } > "$snap"
echo "saved $snap"
"$ADB" -s "$TV" install -r "$B/tvhome.apk"
sh pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION
sh cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity
sh pm disable-user --user 0 "$STOCK_LAUNCHER"
if [ "${DISABLE_SETUPWRAITH:-0}" = "1" ]; then sh pm disable-user --user 0 com.google.android.tungsten.setupwraith; fi
sh cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity
sh am force-stop com.android.tv.settings
sh am force-stop com.mihir.tvhome
sh input keyevent KEYCODE_HOME
echo "Installed. Press Home on the remote. Undo with tools/restore-windows.sh"
