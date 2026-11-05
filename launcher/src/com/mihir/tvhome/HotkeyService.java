package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The remote's app buttons (Netflix, YouTube, ...). The TV turns them into global keys that it hands
 * to the manufacturer's handler, which on Google TV sets asks the stock launcher before opening the
 * app and gives up when that launcher is disabled. An accessibility service that filters key events
 * sees every key one step earlier, so this one opens the app itself. Which key opens what is the
 * `hotkeys` array in config.xml ("KEYCODE_BUTTON_4=com.netflix.ninja"). It does not consume the key.
 *
 * Enabled by the install script:
 *   settings put secure enabled_accessibility_services com.mihir.tvhome/.HotkeyService
 *   settings put secure accessibility_enabled 1
 */
public class HotkeyService extends AccessibilityService {
    static final String TAG = "tvhome.Hotkey";
    private final Map<Integer, String> map = new HashMap<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Ask for key filtering at runtime as well as in hotkey_service.xml; some builds only honour
        // the flag once it is set through setServiceInfo.
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
            Log.i(TAG, "flags=0x" + Integer.toHexString(info.flags) + " caps=0x" + Integer.toHexString(info.getCapabilities()));
        }
        map.clear();
        for (String s : getResources().getStringArray(R.array.hotkeys)) {
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String key = s.substring(0, eq).trim(), pkg = s.substring(eq + 1).trim();
            int code = key.matches("\\d+") ? Integer.parseInt(key) : KeyEvent.keyCodeFromString(key);
            if (code > 0 && !pkg.isEmpty()) map.put(code, pkg);
        }
        Log.i(TAG, "hotkeys: " + map);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return false;
        String pkg = map.get(e.getKeyCode());
        // Logged so a new remote's buttons can be read off `adb logcat -s tvhome.Hotkey`.
        Log.d(TAG, "key " + KeyEvent.keyCodeToString(e.getKeyCode()) + " scan=" + e.getScanCode() + (pkg == null ? "" : " -> " + pkg));
        if (pkg != null) open(pkg);
        return false;   // let the TV's own handler see it too; a second start of a singleTask app is a no-op
    }

    private void open(String pkg) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
        ResolveInfo ri = getPackageManager().resolveActivity(q, 0);
        if (ri == null) { Log.w(TAG, "no launcher activity for " + pkg); return; }
        Intent open = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(open); Log.i(TAG, "opened " + pkg); }
        catch (RuntimeException ex) { Log.w(TAG, "could not open " + pkg, ex); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
