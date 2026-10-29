package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
        boolean light;   // mostly bright banner: the focus sweep becomes a shadow band
        View.OnClickListener action;   // set: the tile runs this instead of opening an app (Hidden apps)
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final Map<String, Integer> colorOverrides = new HashMap<>();   // from config.xml app_colors
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private ImageView glow;               // a small radial-gradient bitmap, stretched; colour is a filter
    private ValueAnimator glowAnim;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, title, wifiPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private int lastFocusedId = View.NO_ID;
    private boolean warmed;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup, tileMenu;
    private final Set<String> userHidden = new HashSet<>();   // hidden from the remote; kept in preferences
    private final List<String> dockOrder = new ArrayList<>();  // config.xml's dock, or the order saved from the remote
    static final int DOCK_MAX = 7;
    private final List<App> hiddenApps = new ArrayList<>();

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = Math.max(1, getResources().getInteger(R.integer.columns));
        userHidden.addAll(getSharedPreferences("launcher", MODE_PRIVATE).getStringSet("hidden", new HashSet<>()));
        String savedDock = getSharedPreferences("launcher", MODE_PRIVATE).getString("dock", null);
        if (savedDock == null) dockOrder.addAll(Arrays.asList(dockApps));
        else for (String p : savedDock.split(",")) if (!p.isEmpty()) dockOrder.add(p);
        for (String s : getResources().getStringArray(R.array.app_colors)) {   // "package=#RRGGBB"
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            try { colorOverrides.put(s.substring(0, eq).trim(), Color.parseColor(s.substring(eq + 1).trim())); }
            catch (IllegalArgumentException ignored) { }
        }
        setContentView(buildUi());
        // Fullscreen via the API 30 insets controller (the SYSTEM_UI_FLAG_* route is deprecated and
        // target 35+ draws edge-to-edge by default anyway). Must run after setContentView: the
        // controller belongs to the decor view, which does not exist before that.
        getWindow().setDecorFitsSystemWindows(false);
        android.view.WindowInsetsController ic = getWindow().getDecorView().getWindowInsetsController();
        if (ic != null) {
            ic.hide(android.view.WindowInsets.Type.systemBars());
            ic.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        register(packages, pf);
    }

    /** Android 14+ (target 34+) insists on an export flag; both receivers only want system broadcasts. */
    private void register(BroadcastReceiver r, IntentFilter f) {
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        tf.addAction(ConnectivityManager.CONNECTIVITY_ACTION); // Ethernet plugged or unplugged
        register(tick, tf);
        updateClock();
        updateWifi();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
        if (tileMenu != null) tileMenu.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (tileMenu != null) tileMenu.dismiss();
        View first = firstTile();
        if (first != null) first.requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        View first = firstTile();
        if (scroll.getScrollY() > 0 && first != null) first.requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable. A 1x256
        // gradient bitmap stretched to the screen, not a GradientDrawable: see glowBitmap().
        ImageView scrim = new ImageView(this);
        scrim.setScaleType(ImageView.ScaleType.FIT_XY);
        scrim.setImageBitmap(scrimBitmap());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        glow = new ImageView(this);
        glow.setScaleType(ImageView.ScaleType.FIT_XY);
        glow.setImageBitmap(glowBitmap());
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x61080612);   // dark tint, so white tiles have an edge to lift from
        band.setStroke(dp(1), 0x1AFFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        TextView inputs = pill("Inputs", R.drawable.ic_inputs);
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = pill("Wi-Fi", R.drawable.ic_wifi);
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView settings = pill("Settings", R.drawable.ic_settings);
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        for (TextView p : new TextView[]{inputs, wifiPill, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, int icon) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(11), 0, dp(14), 0);
        p.setCompoundDrawablePadding(dp(6));
        setPillIcon(p, icon);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            tintPillIcon((TextView) v, has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); dimDock(-1); fadeGlow(1f); }
        });
        return p;
    }

    /** Vector icon at the start of a pill, tinted like its text. */
    private void setPillIcon(TextView p, int icon) {
        Drawable d = getDrawable(icon).mutate();
        d.setBounds(0, 0, dp(13), dp(13));
        d.setTint(p.getCurrentTextColor());
        p.setCompoundDrawablesRelative(d, null, null, null);
    }

    private void tintPillIcon(TextView p, int color) {
        Drawable d = p.getCompoundDrawablesRelative()[0];
        if (d != null) d.setTint(color);
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        hiddenApps.clear();   // refilled by the loop below; the Hidden apps tile's menu reads it later
        // Tiles get fresh ids on every rebuild, so remember the focused app by package, not by id.
        View focusedNow = getCurrentFocus();
        String keep = focusedNow != null && focusedNow.getTag() instanceof App ? ((App) focusedNow.getTag()).pkg : null;
        if (tileMenu != null) tileMenu.dismiss();      // their anchors are about to be rebuilt
        if (inputsPopup != null) inputsPopup.dismiss();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            Integer forced = colorOverrides.get(a.pkg);
            a.color = forced != null ? forced : sampleColor(a.pkg, a.art);
            a.light = isLight(a.art);
            if (userHidden.contains(a.pkg)) { hiddenApps.add(a); continue; }
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockOrder) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        Collections.sort(hiddenApps, (x, y) -> x.label.compareToIgnoreCase(y.label));
        if (!hiddenApps.isEmpty()) {
            // Last tile of the grid: the way back for apps hidden from the long-press menu.
            App h = new App();
            h.pkg = getPackageName() + ".hidden";
            h.label = "Hidden apps (" + hiddenApps.size() + ")";
            h.art = getDrawable(R.drawable.ic_hidden);
            h.color = 0xFF5A5A66;
            h.action = this::showHiddenMenu;
            rest.add(h);
        }

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        // Tiles share the band equally, but never fewer than five slots: with one or two apps the
        // tiles keep their normal size on the left instead of stretching across the band.
        int slots = Math.max(dock.size(), 5);
        for (int i = 0; i < slots; i++) {
            View v = i < dock.size() ? tile(dock.get(i), true) : new View(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, i < dock.size() ? -2 : 1, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == slots - 1 ? 0 : dp(10), 0);
            dockRow.addView(v, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        if (!warmed && !dock.isEmpty()) { warmed = true; warmUpGpu(dock); }
        if (keep == null || !(focusDockApp(keep) || focusGridApp(keep))) {
            View first = firstTile();
            if (first != null) first.requestFocus();
        }
    }

    /** Where focus lands by default: the first dock tile, or the first grid tile if the dock is empty. */
    private View firstTile() {
        return !dockCards.isEmpty() ? dockCards.get(0) : !gridCards.isEmpty() ? gridCards.get(0) : null;
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art: a white ring inside a thin dark one, so it
        // reads on white banners as well as dark ones.
        GradientDrawable outer = new GradientDrawable();
        outer.setCornerRadius(dp(10));
        outer.setStroke(dp(1), 0xA6000000);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(9));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        android.graphics.drawable.LayerDrawable rings = new android.graphics.drawable.LayerDrawable(new Drawable[]{outer, ring});
        rings.setLayerInset(1, dp(1), dp(1), dp(1), dp(1));
        View shine = new View(this);
        shine.setBackground(rings);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));
        // A band of light (a shadow band on bright banners) that sweeps across once on focus.
        int sweepColor = app.light ? 0x29000000 : 0x61FFFFFF;
        View sweep = new View(this);
        sweep.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{sweepColor & 0x00FFFFFF, sweepColor, sweepColor & 0x00FFFFFF}));
        sweep.setAlpha(0f);
        card.addView(sweep, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> { if (app.action != null) app.action.onClick(v); else openApp(app); });
        if (app.action == null) card.setOnLongClickListener(v -> { showTileMenu(v, app, inDock); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                dimDock(dockCards.indexOf(v));
                runSweep(sweep, v);
                scroll.smoothScrollTo(0, 0);
            } else {
                dimDock(-1);
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId()
                    : g > 0 ? gridCards.get(Math.min(g - 1, cols / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
            // Pressing past either end of the dock: nudge toward the wall and spring back, like tvOS.
            final boolean first = i == 0, last = i == d - 1;
            c.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && first) { if (e.getRepeatCount() == 0) bounceDock(v, -1); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && last) { if (e.getRepeatCount() == 0) bounceDock(v, 1); return true; }
                return false;
            });
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    /** End-of-row bounce: the focused tile moves 9dp toward the wall and the row 3dp, then both spring back. */
    private void bounceDock(View card, int dir) {
        OvershootInterpolator back = new OvershootInterpolator(3f);
        card.animate().translationX(dir * dp(9)).setDuration(90).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> card.animate().translationX(0).setDuration(320).setInterpolator(back).start())
                .start();
        dockRow.animate().translationX(dir * dp(3)).setDuration(90).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> dockRow.animate().translationX(0).setDuration(320).setInterpolator(back).start())
                .start();
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void openApp(App app) {
        launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    // ---------------------------------------------------------------- long-press menu

    /** Hold OK on a tile: Open, App info, dock editing, Hide. */
    private void showTileMenu(View card, App app, boolean inDock) {
        List<TextView> rows = new ArrayList<>();
        rows.add(inputRow("Open", false, () -> openApp(app)));
        rows.add(inputRow("App info", false, () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        if (inDock) {
            // Swap with the visible neighbour: dockOrder may also hold uninstalled or hidden packages.
            int vi = dockCards.indexOf(card);
            if (vi > 0) rows.add(inputRow("Move left", false, () -> {
                swapDock(app.pkg, ((App) dockCards.get(vi - 1).getTag()).pkg); buildApps(); focusDockApp(app.pkg);
            }));
            if (vi >= 0 && vi < dockCards.size() - 1) rows.add(inputRow("Move right", false, () -> {
                swapDock(app.pkg, ((App) dockCards.get(vi + 1).getTag()).pkg); buildApps(); focusDockApp(app.pkg);
            }));
            rows.add(inputRow("Remove from dock", false, () -> {
                dockOrder.remove(app.pkg); saveDock(); buildApps(); focusGridApp(app.pkg);
            }));
        } else {
            boolean room = dockCards.size() < DOCK_MAX;
            rows.add(inputRow(room ? "Add to dock" : "Dock is full (" + DOCK_MAX + ")", !room, () -> {
                if (!room) return;
                dockOrder.remove(app.pkg); dockOrder.add(app.pkg); saveDock(); buildApps(); focusDockApp(app.pkg);
            }));
        }
        rows.add(inputRow("Hide", false, () -> {
            int gi = gridCards.indexOf(card);
            userHidden.add(app.pkg);
            saveHidden();
            buildApps();
            refocusGrid(gi);
        }));
        showMenu(card, rows);
    }

    /** The Hidden apps tile: pick one to bring it back. */
    private void showHiddenMenu(View card) {
        List<TextView> rows = new ArrayList<>();
        for (App a : hiddenApps) {
            rows.add(inputRow(a.label, false, () -> {
                userHidden.remove(a.pkg);
                saveHidden();
                buildApps();
                refocusGrid(gridCards.size() - 1);
            }));
        }
        showMenu(card, rows);
    }

    private void saveDock() {
        getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("dock", String.join(",", dockOrder)).apply();
    }

    private void swapDock(String a, String b) {
        int i = dockOrder.indexOf(a), j = dockOrder.indexOf(b);
        if (i >= 0 && j >= 0) { Collections.swap(dockOrder, i, j); saveDock(); }
    }

    private boolean focusDockApp(String pkg) {
        for (View c : dockCards) if (pkg.equals(((App) c.getTag()).pkg)) { c.requestFocus(); return true; }
        return false;
    }

    private boolean focusGridApp(String pkg) {
        for (View c : gridCards) if (pkg.equals(((App) c.getTag()).pkg)) { c.requestFocus(); return true; }
        return false;
    }

    /** After the grid is rebuilt, stay in it (buildApps would otherwise fall back to the dock). */
    private void refocusGrid(int index) {
        if (index < 0 || gridCards.isEmpty()) return;
        View c = gridCards.get(Math.min(index, gridCards.size() - 1));
        c.requestFocus();
    }

    private void saveHidden() {
        getSharedPreferences("launcher", MODE_PRIVATE).edit().putStringSet("hidden", new HashSet<>(userHidden)).apply();
    }

    /** A small focusable menu over a tile: above it when the tile sits in the lower half of the screen. */
    private void showMenu(View anchor, List<TextView> rows) {
        if (rows.isEmpty()) return;
        if (tileMenu != null) tileMenu.dismiss();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);
        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { tileMenu = null; anchor.requestFocus(); });
        tileMenu = pw;
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = dp(200), h = list.getMeasuredHeight();
        int x = at[0] + (int) (anchor.getWidth() * anchor.getScaleX()) / 2 - w / 2;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        boolean above = at[1] + anchor.getHeight() / 2 > screenH / 2;
        int y = above ? at[1] - h - dp(14) : at[1] + anchor.getHeight() + dp(14);
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, Math.max(dp(8), x), Math.max(dp(8), y));
        rows.get(0).requestFocus();
    }

    private void showInputs(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        // Ports with a cable report CONNECTED; an empty HDMI port reports CONNECTED_STANDBY (that is what
        // the hotplug line maps to). Empty ports are listed last and dimmed, like Sony's own source menu.
        List<TvInputInfo> live = new ArrayList<>(), dead = new ArrayList<>();
        List<TvInputInfo> all = new ArrayList<>();
        Set<String> portsWithDevice = new HashSet<>(), seenLabels = new HashSet<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (!t.isPassthroughInput() || t.isHidden(this)) continue;
            all.add(t);
            if (t.getParentId() != null) portsWithDevice.add(t.getParentId());
        }
        for (TvInputInfo t : all) {
            // Android 14 lists a CEC device (soundbar, console) as its own input under the HDMI port, once per
            // logical address. Show the device instead of the bare port, and only once.
            if (t.getParentId() == null && portsWithDevice.contains(t.getId())) continue;
            if (!seenLabels.add(inputLabel(t))) continue;
            int state = TvInputManager.INPUT_STATE_CONNECTED;
            try { state = tim.getInputState(t.getId()); } catch (IllegalArgumentException ignored) { }
            (state == TvInputManager.INPUT_STATE_CONNECTED ? live : dead).add(t);
        }
        Comparator<TvInputInfo> byLabel = (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y));
        Collections.sort(live, byLabel);
        Collections.sort(dead, byLabel);
        for (TvInputInfo t : live) rows.add(inputRow(inputLabel(t), false, () -> switchToInput(t)));
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", false, () -> openPackage(liveTvPkg)));
        for (TvInputInfo t : dead) rows.add(inputRow(inputLabel(t), true, () -> switchToInput(t)));
        List<TvInputInfo> inputs = new ArrayList<>(live);
        inputs.addAll(dead);
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", false, () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", false, () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        String s = String.valueOf(c);
        // A CEC device's label comes as "BD SYSTEM (HDMI 3 (eARC/ARC))"; show it as "HDMI 3 (eARC/ARC) · BD SYSTEM".
        int p = s.indexOf(" (");
        if (t.getParentId() != null && p > 0 && s.endsWith(")")) s = s.substring(p + 2, s.length() - 1) + " · " + s.substring(0, p);
        return s;
    }

    private void switchToInput(TvInputInfo t) {
        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!launch(i)) openPackage(inputPickerPkg);
    }

    /** One row of the Inputs menu; a dimmed row is a port with nothing plugged in (still selectable). */
    private TextView inputRow(String label, boolean dim, Runnable action) {
        int idle = dim ? 0x73FFFFFF : Color.WHITE, focused = dim ? 0x80111111 : 0xFF111111;
        TextView r = text(13, "sans-serif", idle);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? focused : idle);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    /** Network pill: Ethernet, the Wi-Fi network's name, or Offline, with a matching icon. */
    private void updateWifi() {
        int icon = R.drawable.ic_offline;
        String name = "Offline";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                icon = R.drawable.ic_ethernet;
                name = "Ethernet";
            } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                icon = R.drawable.ic_wifi;
                name = "Wi-Fi";
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null && wm.getConnectionInfo() != null) {
                    String s = wm.getConnectionInfo().getSSID();
                    if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                }
            }
        } catch (SecurityException ignored) { }
        wifiPill.setText(name);
        setPillIcon(wifiPill, icon);
    }

    // ---------------------------------------------------------------- glow

    /**
     * The glow's shape, drawn once as a small white-on-transparent radial gradient. The ImageView
     * stretches it to the screen and tints it, so a colour change is one textured quad per frame.
     * Drawing a full-screen GradientDrawable every frame cost ~40 ms a frame on the Realtek GPU.
     */
    private Bitmap glowBitmap() {
        int w = 320, h = 180;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.RadialGradient(w * 0.5f, h * 0.78f, w * 0.62f,
                new int[]{0x80FFFFFF, 0x24FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        new Canvas(bm).drawRect(0, 0, w, h, p);
        return bm;
    }

    private Bitmap scrimBitmap() {
        int h = 256;
        Bitmap bm = Bitmap.createBitmap(1, h, Bitmap.Config.ARGB_8888);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        new Canvas(bm).drawRect(0, 0, 1, h, p);
        return bm;
    }

    private void setGlow(int color) {
        glowColor = color;
        glow.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
    }

    /**
     * The first D-pad presses used to stall at 17-19 ms a frame while the GPU met new work for the
     * first time: the sweep gradient, the ring layers, and the glyphs of each app name in the title's
     * font. Draw all of that once now, at 1% alpha in a corner, then remove it.
     */
    private void warmUpGpu(List<App> dock) {
        FrameLayout root = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);
        LinearLayout warm = new LinearLayout(this);
        warm.setAlpha(0.01f);
        StringBuilder names = new StringBuilder();
        for (App a : dock) names.append(a.label).append(' ');
        TextView t = text(34, "sans-serif-light", Color.WHITE);
        t.setText(names.toString());
        warm.addView(t, new LinearLayout.LayoutParams(-2, -2));
        View sweep = new View(this);
        sweep.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00FFFFFF, 0x61FFFFFF, 0x00FFFFFF}));
        warm.addView(sweep, new LinearLayout.LayoutParams(dp(24), dp(14)));
        View dark = new View(this);
        dark.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00000000, 0x29000000, 0x00000000}));
        warm.addView(dark, new LinearLayout.LayoutParams(dp(24), dp(14)));
        GradientDrawable outer = new GradientDrawable();
        outer.setCornerRadius(dp(10));
        outer.setStroke(dp(1), 0xA6000000);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(9));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        android.graphics.drawable.LayerDrawable rings = new android.graphics.drawable.LayerDrawable(new Drawable[]{outer, ring});
        rings.setLayerInset(1, dp(1), dp(1), dp(1), dp(1));
        View ringV = new View(this);
        ringV.setBackground(rings);
        warm.addView(ringV, new LinearLayout.LayoutParams(dp(24), dp(14)));
        root.addView(warm, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        handler.postDelayed(() -> root.removeView(warm), 600);
        animateGlow(glowColor ^ 0x000001);   // a 1-step colour fade: exercises the filter animation path
    }


    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    /** Dock tiles other than the focused one sit at 72%; -1 restores them all (focus left the dock). */
    private void dimDock(int focusedIdx) {
        for (int i = 0; i < dockCards.size(); i++) {
            dockCards.get(i).animate().alpha(focusedIdx < 0 || i == focusedIdx ? 1f : 0.72f).setDuration(260).start();
        }
    }

    private void runSweep(View sweep, View card) {
        if (card.getWidth() == 0) { card.post(() -> runSweep(sweep, card)); return; }
        float w = card.getWidth();
        sweep.animate().cancel();
        sweep.setTranslationX(-w);
        sweep.setAlpha(1f);
        sweep.animate().translationX(w).setDuration(650).setInterpolator(new DecelerateInterpolator(1.2f))
                .withEndAction(() -> sweep.setAlpha(0f)).start();
    }

    /** Is the banner mostly bright? Then the focus sweep is a shadow band rather than a light one. */
    private boolean isLight(Drawable d) {
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            double lum = 0; int n = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                lum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p);
                n++;
            }
            bm.recycle();
            return n > 0 && lum / n > 150;
        } catch (RuntimeException e) { return false; }
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        int screenW = getResources().getDisplayMetrics().widthPixels;
        new Thread(() -> {
            Bitmap result = decodeWallpaper(getAssets(), name, screenW);
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    /**
     * Decode a bundled photo no larger than the screen needs (RGB_565, power-of-two subsampling), so
     * an oversized JPEG dropped into assets/wallpapers costs 4 MB like the others instead of 24 MB.
     * Returns null on any failure, including running out of memory.
     */
    static Bitmap decodeWallpaper(android.content.res.AssetManager assets, String name, int targetW) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = assets.open(name)) { BitmapFactory.decodeStream(in, null, bounds); }
            int sample = 1;
            while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= targetW) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inSampleSize = sample;
            try (java.io.InputStream in = assets.open(name)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (java.io.IOException | OutOfMemoryError e) {
            return null;
        }
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Menu key on the remote opens the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            showInputs(pills.get(0));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
