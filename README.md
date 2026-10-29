# tv-home

**Make a slow, ad-filled Android TV fast and clean again.** Debloat it over Wi-Fi and give it a
tiny Apple TV-style home screen with rotating nature wallpapers. You can hand the whole thing to
Claude Code with one prompt, or do it by hand with the scripts here.

![Before: Google TV home with a sponsored hero. After: clean dock over a Lake Louise wallpaper](before-after.png)

Everything is reversible: packages are disabled, never uninstalled.

## Do it with Claude Code (easiest)

1. On the TV: Settings → System → About → press OK on **Build** 7 times → back → Developer options
   → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.
2. Clone this repo, open Claude Code inside it (so it can reuse the launcher and scripts), and paste:

   ```text
   My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
   disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
   about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
   screen with a dock of my favourite apps and rotating nature wallpapers. Back up before changing
   anything, check every step with screenshots from the TV, and give me a restore script.

   Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
   Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
   test the Home button from inside an app.
   ```

3. Accept "Allow USB debugging?" on the TV when asked, then answer a few questions: which apps you
   use, which go in the dock, whether you want wallpapers.

The prompt is short on purpose; Claude works out the details. The gotchas below are there in case it gets stuck.

## Do it by hand (macOS)

```bash
./setup.sh                                                  # Python ADB client, JDK 17, Android SDK
tools/find-tv.sh                                            # finds the TV, saves its IP
tools/venv/bin/python -I tools/tv.py --probe                # model, Android version
tools/disable.sh examples/tcl-google-tv-g07/debloat.txt     # or your own list, see examples/
launcher/build-and-install.sh                               # build + install the launcher
```

Undo everything with `tools/restore.sh <the same list>`.

## Do it by hand (Windows) — and setting up another TV

The same launcher works on any Android TV / Google TV. What changes from one TV to the next is
five things: the TV's IP, the stock launcher to disable, the dock apps, the Live TV app, and the
debloat list. The Windows scripts find the first two for you; the rest you read off the TV with
`tools/probe-windows.sh` and write into two files. Run everything from Git Bash in this folder.

### 1. One-time tools on the PC

```bash
winget install Google.PlatformTools        # adb
winget install Microsoft.OpenJDK.17        # javac, jar, keytool
```

You also need the Android SDK build-tools and a platform (Android Studio installs them, or
`sdkmanager "build-tools;36.0.0" "platforms;android-35"`). The build script looks in
`C:\Program Files (x86)\Android\android-sdk`; point it elsewhere with `ANDROID_HOME`, and pick
other versions with `BUILD_TOOLS=35.0.0 PLATFORM=android-34`.

### 2. On the TV

Settings → Device Preferences (or System) → About → press OK on **Build** 7 times → back →
Developer options → **USB debugging** on. Some brands also have **Network debugging**; turn it on
too. Keep the TV on, on the same Wi-Fi as the PC.

### 3. Find and read the TV

```bash
tools/find-tv-windows.sh                   # scans the Wi-Fi, saves the IP to tools/.tv_host
tools/probe-windows.sh                     # everything you need for the next step
```

Accept "Allow USB debugging?" on the TV when it appears. The probe prints:

| Section | What to do with it |
|---|---|
| Home handlers | The Google entry is the stock launcher: `com.google.android.tvlauncher` on Android TV sets (Sony, Xiaomi, Nvidia), `com.google.android.apps.tv.launcherx` on Google TV sets (TCL, Hisense, Chromecast). Ignore `FallbackHome` entries. The build script picks the right one itself; set `STOCK_LAUNCHER=` only to override. |
| Apps that show on a TV launcher | Copy package names into `dock_apps` (the dock, left to right) and `hidden_apps` (never shown) in `launcher/res/values/config.xml`. |
| Live TV candidates | Put the tuner app in `live_tv_package`. Common: `com.android.tv` (Google Live Channels, Sony), `com.tcl.tv` (TCL), `com.mediatek.wwtv.tvcenter` (Xiaomi and other MediaTek sets), `com.sony.dtv.tvx` (older Sony). Also add it to `hidden_apps`, since the Inputs menu lists it. |
| TV inputs | Just a check: these are the HDMI/AV rows the Inputs menu will show. |
| All system packages | Write your debloat list as `examples/<brand-model>/debloat.txt`, one package per line, `#` comments. Copy the closest existing example and edit. Never list the launcher's dependencies: `com.android.tv.settings`, `com.google.android.gms`, `com.android.vending`, the Live TV app, or anything with `inputmethod`, `tvinput`, `providers.tv`, `hdmi` or `cec` in the name. |

### 4. Debloat, build, install

```bash
tools/disable-windows.sh examples/<brand-model>/debloat.txt    # reversible: disables, never uninstalls
bash launcher/build-windows.sh                                # build, snapshot, install, set as Home
```

The build script saves the TV's Home app and enabled-package list to `launcher/build/backup-*.txt`
before changing anything. On **Google TV** sets, also pass `DISABLE_SETUPWRAITH=1`, or the setup app
hijacks the Home button (see Gotchas). Then press Home from inside an app, such as Netflix, to confirm
it returns to the new launcher.

### 5. Undo

```bash
tools/restore-windows.sh examples/<brand-model>/debloat.txt    # stock launcher back, list re-enabled
UNINSTALL=1 tools/restore-windows.sh                           # ...and remove the launcher
```

### Windows-only gotchas

- **aapt2 writes asset paths with backslashes** (`assets/wallpapers\01.jpg`), which Android's asset
  listing cannot see, so no wallpaper shows. `build-windows.sh` adds the assets with the JDK's `jar`
  instead of passing them to aapt2.
- **javac defaults to the Windows code page**, which garbles any non-ASCII text in the source. The
  script passes `-encoding UTF-8`.
- **Keep `launcher/debug.keystore`** (git-ignored). Updates over an installed launcher must be signed
  with the same key; lose it and the TV refuses the update until you uninstall first, which needs ADB
  to set Home again.
- **Git Bash rewrites `/sdcard/...` arguments** into Windows paths. When calling adb by hand, prefix
  the command with `MSYS_NO_PATHCONV=1`.

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen. 4.7 MB including the wallpapers; plain Android, no network, no background services |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Dock apps, hidden apps, Live TV app, grid columns |
| [tools/tv.py](tools/tv.py) | Pure-Python ADB client: shell, batch, screenshots, chunked APK push |
| [tools/find-tv.sh](tools/find-tv.sh) | Finds Android TVs on your Wi-Fi |
| [tools/type.sh](tools/type.sh) | Type into the TV's on-screen keyboard from your laptop (`tools/type.sh` alone prompts hidden, for passwords) |
| [tools/disable.sh](tools/disable.sh), [tools/restore.sh](tools/restore.sh) | Apply / undo a debloat list |
| [launcher/build-windows.sh](launcher/build-windows.sh) | Windows build + install (Git Bash, real adb, no Python) |
| [tools/find-tv-windows.sh](tools/find-tv-windows.sh), [tools/probe-windows.sh](tools/probe-windows.sh) | Windows: find the TV; print apps, Home handlers, inputs and packages for config.xml |
| [tools/disable-windows.sh](tools/disable-windows.sh), [tools/restore-windows.sh](tools/restore-windows.sh) | Windows: apply / undo a debloat list and put the stock launcher back |
| [examples/](examples/) | Debloat lists per TV model |

## The launcher

- Clock, greeting and date top-left. Frosted pills top-right for **Inputs** (HDMI/AV/Live TV
  dropdown; the remote's Menu key opens it too), **Wi-Fi** (shows the network name) and **Settings**.
- A dock of your favourite apps. The focused app's name appears above it over a glow coloured from
  its banner. Press Down for every other app in a grid.
- 12 nature photos crossfade every 10 minutes, with a scrim so text stays readable. Swap them in
  `launcher/assets/wallpapers/` (1920×1080 JPEGs, credits in `launcher/WALLPAPER-CREDITS.md`).
- Deterministic D-pad focus, remembers the last tile, picks up newly installed apps on return.
- **Hold OK on a tile** for a menu: Open, App info, Add to dock / Move left / Move right / Remove
  from dock, and Hide. The dock order and hidden apps are kept in the launcher's preferences, so
  `config.xml` is only the starting point; a "Hidden apps" tile at the end of the grid brings hidden
  apps back. To return to `config.xml`'s dock, clear the launcher's data from App info.
- A screensaver (Settings → Screen saver → "Wallpapers") shows the same photos with the clock.

Change the dock: edit `config.xml`, then run `launcher/build-and-install.sh`.

## Gotchas we hit

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) has a higher HOME priority than any third-party
  launcher. The install script disables it; `restore.sh` re-enables it.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The install script does this.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.
- **Unsplash blocks scripted downloads** with a bot check. The wallpapers come from Wikimedia
  Commons Featured Pictures instead.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11, macOS build), Sony BRAVIA VU2 (Android TV 11, Realtek,
Windows build — see `examples/sony-bravia-vu2/`), Sony BRAVIA VU3 (Google TV / Android 14, Realtek,
Windows build with `DISABLE_SETUPWRAITH=1` — see `examples/sony-bravia-vu3/`). Add yours to `examples/`.

## Credits

Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are
from Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Code is MIT, see [LICENSE](LICENSE).
