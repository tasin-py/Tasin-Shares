package com.tasin.shares;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * The indicator at the top right of the TV while a device controls it. It reads "Controlled by …" for a few seconds
 * when control starts or changes, and for as long as the Tasin Shares screen is open; otherwise it shrinks to a small
 * blue dot, so it stays out of the way in other apps. Short notices such as a takeover show in full the same way.
 * Drawn over every app with SYSTEM_ALERT_WINDOW, never focusable, so the remote keeps working underneath.
 */
final class Overlay {
    private static final long EXPAND_MS = 6000;
    private static volatile Overlay current;
    private static volatile boolean appOpen;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView pill;
    private String steady;
    private boolean flashing;
    private boolean expanded;
    private final Runnable restore = () -> {
        flashing = false;
        render();
    };
    private final Runnable collapse = () -> {
        expanded = false;
        render();
    };

    Overlay(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        current = this;
    }

    /** The Tasin Shares screen opened or closed: the indicator shows in full while it is open. */
    static void appOpen(boolean open) {
        appOpen = open;
        Overlay o = current;
        if (o != null) o.main.post(o::render);
    }

    void controlledBy(String name, boolean owner) {
        String text = name == null ? null : "● Tasin Shares · controlled by " + name + (owner ? " (owner)" : "");
        main.post(() -> {
            boolean changed = text != null && !text.equals(steady);
            steady = text;
            if (changed) expandFor(EXPAND_MS);
            // A notice in progress (a takeover) stays its full time; the steady state follows it.
            if (!flashing) render();
        });
    }

    /** A notice for a few seconds, then back to the steady state. */
    void flash(String text) {
        main.post(() -> {
            main.removeCallbacks(restore);
            flashing = true;
            expandFor(EXPAND_MS);
            show("● " + text, true);
            main.postDelayed(restore, 5000);
        });
    }

    private void expandFor(long ms) {
        expanded = true;
        main.removeCallbacks(collapse);
        main.postDelayed(collapse, ms);
    }

    private void render() {
        if (flashing) return;
        show(steady, expanded || appOpen);
    }

    private void show(String text, boolean full) {
        if (!Settings.canDrawOverlays(ctx)) return;
        WindowManager wm = ctx.getSystemService(WindowManager.class);
        if (text == null) {
            if (pill != null) {
                try {
                    wm.removeView(pill);
                } catch (Exception ignored) {
                }
                pill = null;
            }
            return;
        }
        if (pill == null) {
            pill = new TextView(ctx);
            pill.setTextColor(Color.WHITE);
            pill.setSingleLine(true);
            pill.setIncludeFontPadding(false);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(24);
            lp.y = dp(16);
            try {
                wm.addView(pill, lp);
            } catch (Exception e) {
                pill = null;
                return;
            }
        }
        GradientDrawable bg = new GradientDrawable();
        if (full) {
            bg.setColor(0xCC0A4FB8);
            bg.setCornerRadius(dp(20));
            int px = dp(14), py = dp(6);
            pill.setPadding(px, py, px, py);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            pill.setText(text);
            pill.setMinimumWidth(0);
            pill.setMinimumHeight(0);
        } else {
            // The dot: a small blue circle with a faint light ring, visible on dark and light screens alike.
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xFF1E7BFF);
            bg.setStroke(dp(1), 0x99FFFFFF);
            pill.setPadding(0, 0, 0, 0);
            // An empty line still takes the font's height, so a 1px text size keeps the circle round.
            pill.setTextSize(TypedValue.COMPLEX_UNIT_PX, 1);
            pill.setText("");
            pill.setMinimumWidth(dp(10));
            pill.setMinimumHeight(dp(10));
        }
        pill.setBackground(bg);
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
