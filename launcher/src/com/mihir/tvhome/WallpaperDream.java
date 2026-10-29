package com.mihir.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Screensaver: the launcher's bundled wallpapers, crossfading once a minute, with a small clock
 * in the corner. Offline, one decoded photo at a time, nothing redrawn between crossfades.
 */
public class WallpaperDream extends DreamService {
    static final long INTERVAL_MS = 60_000, FADE_MS = 2_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable next = this::nextWallpaper;
    private final Runnable tick = this::updateClock;
    private FrameLayout root;
    private ImageView back, front;
    private TextView clock, ampm, date;
    private String[] wallpapers = new String[0];
    private int index;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key ends the dream
        setFullscreen(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = layer();
        front = layer();
        root.addView(back, full());
        root.addView(front, full());

        // Darker towards the bottom so the clock reads on bright photos (a stretched 1x256 bitmap).
        ImageView scrim = new ImageView(this);
        scrim.setScaleType(ImageView.ScaleType.FIT_XY);
        scrim.setImageBitmap(scrimBitmap());
        root.addView(scrim, full());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        row.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.setMargins(dp(6), 0, 0, dp(8));
        row.addView(ampm, ap);
        box.addView(row);
        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setLetterSpacing(0.22f);
        box.addView(date);
        for (TextView t : new TextView[]{clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        bp.setMargins(dp(48), 0, 0, dp(40));
        root.addView(box, bp);
        setContentView(root);

        try { wallpapers = getAssets().list("wallpapers"); } catch (java.io.IOException ignored) { }
        index = wallpapers.length == 0 ? -1 : new Random().nextInt(wallpapers.length) - 1;
        nextWallpaper();
        updateClock();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (back != null) back.setImageDrawable(null);
        if (front != null) front.setImageDrawable(null);
        super.onDetachedFromWindow();
    }

    private void nextWallpaper() {
        handler.removeCallbacks(next);
        if (wallpapers.length == 0) return;
        index = (index + 1) % wallpapers.length;
        String name = "wallpapers/" + wallpapers[index];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            handler.post(() -> show(result));
        }).start();
        handler.postDelayed(next, INTERVAL_MS);
    }

    /** Swap roles: the old front becomes the back, the new photo fades in on top of it. */
    private void show(Bitmap bm) {
        if (bm == null) return;
        ImageView old = front;
        front = back;
        back = old;
        root.removeView(front);
        root.addView(front, 1, full());
        front.setImageBitmap(bm);
        front.setAlpha(0f);
        front.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> back.setImageDrawable(null)).start();
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (now.get(Calendar.HOUR_OF_DAY) < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
        long ms = System.currentTimeMillis();
        handler.postDelayed(tick, 60_000 - ms % 60_000 + 50);   // on the next minute
    }

    private ImageView layer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private Bitmap scrimBitmap() {
        int h = 256;
        Bitmap bm = Bitmap.createBitmap(1, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x00000000, 0x00000000, 0x99000000},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(bm).drawRect(0, 0, 1, h, p);
        return bm;
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private FrameLayout.LayoutParams full() { return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
