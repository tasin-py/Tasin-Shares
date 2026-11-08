package com.tasin.shares;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "Allow this device?" on the TV, with the one-time code the device shows too. */
public final class ApproveActivity extends Activity {
    static final String EXTRA_ID = "id";
    private static volatile ApproveActivity showing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String id;

    static void closeIfShowing(String requestId) {
        ApproveActivity a = showing;
        if (a != null && requestId.equals(a.id)) a.runOnUiThread(a::finish);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        id = getIntent().getStringExtra(EXTRA_ID);
        Access access = LinkService.access();
        Access.Request r = access == null ? null : access.find(id);
        if (r == null || !"pending".equals(r.state)) {
            finish();
            return;
        }
        showing = this;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xF2000000);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(48), dp(32), dp(48), dp(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF15171C);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(2), 0xFF0A84FF);
        card.setBackground(bg);

        card.addView(text("Tasin Shares", 15, 0xFF5AA9FF, true));
        card.addView(text(r.name + " wants to control this TV", 24, Color.WHITE, true));
        card.addView(text("Check that the device shows the same code:", 16, 0xFF9AA0A6, false));
        TextView code = text(r.code.substring(0, 3) + " " + r.code.substring(3), 56, Color.WHITE, true);
        code.setLetterSpacing(0.12f);
        card.addView(code);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(16), 0, 0);
        Button once = button("Allow once");
        Button deny = button("Deny");
        once.setOnClickListener(v -> decide(true));
        deny.setOnClickListener(v -> decide(false));
        buttons.addView(once);
        buttons.addView(deny);
        card.addView(buttons);
        card.addView(text("Allowed once: the device needs a new code next time. Owners connect with their owner password in the browser.", 13, 0xFF6E7681, false));

        root.addView(card, new LinearLayout.LayoutParams(dp(720), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView mark = text("Tasin Shares", 12, 0x33FFFFFF, false);
        mark.setPadding(0, dp(18), 0, 0);
        root.addView(mark);
        setContentView(root);
        deny.requestFocus();

        long left = Access.REQUEST_TTL_MS - (System.currentTimeMillis() - r.created);
        handler.postDelayed(this::finish, Math.max(1000, left));
    }

    private void decide(boolean allow) {
        Access access = LinkService.access();
        if (access != null) access.decide(id, allow);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (showing == this) showing = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setAlpha(0.6f);
        b.setOnFocusChangeListener((v, f) -> v.setAlpha(f ? 1f : 0.6f));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
