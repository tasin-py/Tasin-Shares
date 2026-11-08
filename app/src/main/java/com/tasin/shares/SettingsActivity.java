package com.tasin.shares;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** The TV's settings: the colour theme, updates from GitHub, and a Buy me a coffee code. */
public final class SettingsActivity extends Activity {
    static final String COFFEE_URL = "https://www.supportkori.com/tasinpy";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Theme t;
    private TextView updateStatus, updateBtn;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus.setText(Updater.describe(SettingsActivity.this));
            updateStatus.setTextColor(Updater.phase == Updater.Phase.FAILED ? t.error
                    : Updater.phase == Updater.Phase.AVAILABLE ? t.ink : t.ink2);
            updateBtn.setText(Updater.phase == Updater.Phase.AVAILABLE ? "Download and install " + Updater.latest
                    : Updater.busy() ? "Please wait…" : "Check for updates");
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        t = prefs.theme();
        getWindow().setBackgroundDrawable(new ColorDrawable(t.paper));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(56), dp(36), dp(56), dp(32));
        page.setBackgroundColor(t.paper);

        TextView title = label(26, t.ink, true);
        title.setText("Settings");
        page.addView(title);
        TextView sub = label(15, t.muted, false);
        sub.setText("Press Back on the remote to return.");
        sub.setPadding(0, dp(6), 0, 0);
        page.addView(sub);

        LinearLayout cols = new LinearLayout(this);
        LinearLayout.LayoutParams colsLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        colsLp.topMargin = dp(24);
        page.addView(cols, colsLp);

        // Left: the themes, one focusable row each, the one in use marked.
        LinearLayout themes = column(cols, 0);
        heading(themes, "Theme");
        TextView first = null;
        for (Theme th : Theme.ALL) {
            TextView row = action(themes, th.name, false);
            boolean inUse = th.id.equals(t.id);
            GradientDrawable sw = new GradientDrawable();
            sw.setShape(GradientDrawable.OVAL);
            sw.setColor(th.accent);
            sw.setStroke(dp(3), th.paper2);
            sw.setSize(dp(22), dp(22));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(sw, null, null, null);
            row.setCompoundDrawablePadding(dp(14));
            if (inUse) row.setText(th.name + "   (in use)");
            row.setOnClickListener(v -> {
                if (th.id.equals(t.id)) return;
                prefs.setTheme(th.id);
                recreate();
            });
            if (first == null || inUse) first = row;
        }

        // Right: updates, then support.
        LinearLayout right = column(cols, dp(40));
        heading(right, "Updates");
        updateStatus = label(16, t.ink2, false);
        updateStatus.setPadding(0, 0, 0, dp(12));
        right.addView(updateStatus);
        updateBtn = action(right, "Check for updates", false);
        updateBtn.setOnClickListener(v -> {
            if (Updater.phase == Updater.Phase.AVAILABLE) Updater.install(this);
            else Updater.check(this);
        });
        TextView note = label(14, t.muted, false);
        note.setText("New versions come from the app's GitHub releases. The TV needs internet access.");
        note.setPadding(0, dp(10), 0, 0);
        right.addView(note);

        View gap = new View(this);
        right.addView(gap, new LinearLayout.LayoutParams(1, dp(32)));
        heading(right, "Support");
        TextView support = label(16, t.ink2, false);
        support.setText("If Tasin Shares helps you, you can buy me a coffee.");
        support.setPadding(0, 0, 0, dp(12));
        right.addView(support);
        TextView coffee = action(right, "Buy me a coffee", false);
        coffee.setOnClickListener(v -> showCoffee());

        // Focused rows grow a little; let them draw into the page margin instead of being clipped.
        page.setClipChildren(false);
        page.setClipToPadding(false);
        cols.setClipChildren(false);
        setContentView(page);
        if (first != null) first.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    /** The QR code to scan with a phone, and a button that opens the page on the TV itself. */
    private void showCoffee() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(32), dp(28), dp(32), dp(28));
        box.setBackground(surface(t.paper2, dp(28), true));

        TextView h = label(24, t.ink, true);
        h.setText("Buy me a coffee");
        h.setGravity(Gravity.CENTER);
        box.addView(h, new LinearLayout.LayoutParams(-1, -2));
        TextView s = label(15, t.ink2, false);
        s.setText("Scan the code with your phone, or open the page here.");
        s.setPadding(0, dp(6), 0, dp(18));
        s.setGravity(Gravity.CENTER);
        box.addView(s, new LinearLayout.LayoutParams(-1, -2));

        ImageView qr = new ImageView(this);
        qr.setImageResource(R.drawable.coffee_qr);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setBackground(surface(0xFFFFFFFF, dp(16), false));
        qr.setPadding(dp(8), dp(8), dp(8), dp(8));
        qr.setContentDescription("QR code for the Buy me a coffee page");
        box.addView(qr, new LinearLayout.LayoutParams(dp(208), dp(208)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(300), -2);
        bLp.topMargin = dp(20);
        box.addView(buttons, bLp);
        TextView open = action(buttons, "Open supportkori.com/tasinpy", false);
        open.setGravity(Gravity.CENTER);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(COFFEE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this, "This TV has no web browser. Scan the code with your phone instead.", Toast.LENGTH_LONG).show();
            }
        });
        TextView close = action(buttons, "Close", false);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> d.dismiss());

        // The focused button grows a little; let it draw past its column inside the card.
        box.setClipChildren(false);
        box.setClipToPadding(false);
        buttons.setClipChildren(false);
        d.setContentView(box, new android.view.ViewGroup.LayoutParams(dp(440), -2));
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        d.show();
        open.requestFocus();
    }

    private LinearLayout column(LinearLayout parent, int leftMargin) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.leftMargin = leftMargin;
        parent.addView(c, lp);
        // The focused row grows a little; let it draw past the column instead of being clipped.
        c.setClipChildren(false);
        parent.setClipChildren(false);
        return c;
    }

    private void heading(LinearLayout parent, String text) {
        TextView h = label(19, t.ink, true);
        h.setText(text);
        h.setPadding(0, 0, 0, dp(12));
        parent.addView(h);
    }

    /** A focusable row: a quiet pill at rest, accent-filled and a little larger when the D-pad lands on it. */
    private TextView action(LinearLayout parent, String text, boolean destructive) {
        TextView b = label(17, destructive ? t.error : t.ink, true);
        b.setText(text);
        b.setFocusable(true);
        b.setClickable(true);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable rest = surface(t.paper2, dp(999), true);
        GradientDrawable on = surface(destructive ? t.error : t.accent, dp(999), false);
        b.setBackground(rest);
        b.setOnFocusChangeListener((View v, boolean f) -> {
            b.setBackground(f ? on : rest);
            b.setTextColor(f ? t.accentInk : destructive ? t.error : t.ink);
            v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(160).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.bottomMargin = dp(10);
        parent.addView(b, lp);
        return b;
    }

    private GradientDrawable surface(int color, int radius, boolean ruled) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (ruled) d.setStroke(dp(1), t.rule);
        return d;
    }

    private TextView label(int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setLineSpacing(0, 1.15f);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
