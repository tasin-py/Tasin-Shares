package com.tasin.shares;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;

/**
 * The TV-side screen, in the web page's look (dark warm greys, one ember accent): a large QR code and the address
 * to open, the FTP login and who is in control below it, and a column of actions on the right for the remote's D-pad.
 */
public final class MainActivity extends Activity {
    // The chosen theme's colours (Settings), as ARGB; Ember by default, the web page's own palette.
    private int PAPER, PAPER_2, INK, INK_2, MUTED, RULE, ACCENT, ACCENT_INK, OK, WARN, ERROR;
    private String themeId;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextView url, urlError, ftpAddress, ftpUser, ftpPassword, ftpError, controller, owners, status;
    private GradientDrawable statusDot;
    private ImageView qr;
    private String qrUrl;
    private Adb probe;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        LinkService.start(this);
        Theme th = prefs.theme();
        themeId = th.id;
        PAPER = th.paper; PAPER_2 = th.paper2; INK = th.ink; INK_2 = th.ink2; MUTED = th.muted; RULE = th.rule;
        ACCENT = th.accent; ACCENT_INK = th.accentInk; OK = th.ok; WARN = th.warn; ERROR = th.error;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(PAPER);

        // Two columns: what to point a phone at on the left, the actions on the right.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(56), dp(36), dp(56), dp(32));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        // The top line: the wordmark and the remote-control status.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(26, INK, true);
        title.setText("Tasin Shares");
        top.addView(title);
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), dp(6), dp(14), dp(6));
        chip.setBackground(surface(PAPER_2, dp(999), true));
        View dot = new View(this);
        statusDot = new GradientDrawable();
        statusDot.setShape(GradientDrawable.OVAL);
        statusDot.setColor(MUTED);
        dot.setBackground(statusDot);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(8);
        chip.addView(dot, dotLp);
        status = label(14, INK_2, false);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        chip.addView(status);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
        chipLp.leftMargin = dp(16);
        top.addView(chip, chipLp);
        left.addView(top);

        // The signature: the QR code, large, beside the address it encodes.
        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(20), dp(20), dp(28), dp(20));
        hero.setBackground(surface(PAPER_2, dp(28), true));
        qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setBackground(surface(Color.WHITE, dp(16), false));
        qr.setPadding(dp(8), dp(8), dp(8), dp(8));
        qr.setContentDescription("QR code with the web address");
        hero.addView(qr, new LinearLayout.LayoutParams(dp(176), dp(176)));
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(dp(28), 0, 0, 0);
        TextView heroTitle = label(26, INK, true);
        heroTitle.setText("Scan to control this TV");
        heroText.addView(heroTitle);
        TextView heroSub = label(16, INK_2, false);
        heroSub.setText("Or open this address in a browser on the same Wi-Fi.");
        heroSub.setPadding(0, dp(6), 0, dp(14));
        heroText.addView(heroSub);
        url = label(24, INK, true);
        url.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        url.setFontFeatureSettings("tnum");
        heroText.addView(url);
        TextView allow = label(14, MUTED, false);
        allow.setText("A new device shows a code that you allow here on the TV.");
        allow.setPadding(0, dp(10), 0, 0);
        heroText.addView(allow);
        urlError = label(14, ERROR, false);
        urlError.setVisibility(View.GONE);
        heroText.addView(urlError);
        hero.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.topMargin = dp(24);
        left.addView(hero, heroLp);

        // Two cards under it: the FTP login, and who is in control.
        LinearLayout cards = new LinearLayout(this);
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(-1, -2);
        cardsLp.topMargin = dp(16);
        left.addView(cards, cardsLp);

        LinearLayout ftpCard = card(cards, "File transfer (FTP)", 0);
        ftpAddress = label(18, INK, true);
        ftpAddress.setSingleLine(true);
        ftpAddress.setEllipsize(TextUtils.TruncateAt.END);
        ftpAddress.setFontFeatureSettings("tnum");
        ftpCard.addView(ftpAddress);
        ftpUser = label(15, INK_2, false);
        ftpUser.setPadding(0, dp(6), 0, 0);
        ftpCard.addView(ftpUser);
        ftpPassword = label(15, INK_2, false);
        ftpPassword.setPadding(0, dp(4), 0, 0);
        ftpCard.addView(ftpPassword);
        ftpError = label(14, ERROR, false);
        ftpError.setVisibility(View.GONE);
        ftpCard.addView(ftpError);

        LinearLayout nowCard = card(cards, "In control", dp(16));
        controller = label(18, INK, true);
        controller.setSingleLine(true);
        controller.setEllipsize(TextUtils.TruncateAt.END);
        nowCard.addView(controller);
        owners = label(15, INK_2, false);
        owners.setPadding(0, dp(6), 0, 0);
        nowCard.addView(owners);

        // The actions, one column for the D-pad, the first one focused.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(dp(256), -1);
        actionsLp.leftMargin = dp(32);
        root.addView(actions, actionsLp);
        // The focused action grows a little; let it draw past the column instead of being clipped.
        actions.setClipChildren(false);
        root.setClipChildren(false);
        frame.setClipChildren(false);
        root.setClipToPadding(false);

        TextView retry = action(actions, "Connect remote control", false);
        retry.setOnClickListener(v -> {
            probe = new Adb(this);
            probe.warmUp();
        });
        TextView receive = action(actions, "Cast a phone or PC", false);
        receive.setOnClickListener(v -> showReceiveHelp());
        TextView renew = action(actions, "New FTP password", false);
        renew.setOnClickListener(v -> {
            prefs.newRandomPassword();
            startService(new Intent(this, LinkService.class).setAction(LinkService.ACTION_RESTART));
            update();
        });
        TextView disconnect = action(actions, "Disconnect all devices", false);
        disconnect.setOnClickListener(v -> {
            startService(new Intent(this, LinkService.class).setAction(LinkService.ACTION_DISCONNECT_ALL));
            handler.postDelayed(this::update, 1200);
        });
        TextView settings = action(actions, "Settings", false);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        TextView forget = action(actions, "Forget owner devices", true);
        forget.setOnClickListener(v -> {
            prefs.removeAllOwners();
            Access a = LinkService.access();
            if (a != null) a.revokeAll();
            update();
        });

        setContentView(frame);
        retry.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from Settings with another theme: build the screen again in its colours.
        if (!prefs.theme().id.equals(themeId)) {
            recreate();
            return;
        }
        Overlay.appOpen(true);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        Overlay.appOpen(false);
        super.onPause();
    }

    private void update() {
        String ip = LinkService.address();
        String host = ip == null ? null : ip;
        String link = ip == null ? null : "http://" + ip + ":" + prefs.webPort() + "/";
        if (link == null ? qrUrl != null : !link.equals(qrUrl)) {
            qrUrl = link;
            qr.setImageBitmap(link == null ? null : qrCode(link, dp(160)));
            qr.setVisibility(link == null ? View.INVISIBLE : View.VISIBLE);
        }
        url.setText(host == null ? "No network yet" : host + ":" + prefs.webPort());
        url.setTextColor(host == null ? MUTED : INK);
        showError(urlError, LinkService.webError);

        ftpAddress.setText(host == null ? "No network yet" : "ftp://" + host + ":" + prefs.ftpPort());
        ftpUser.setText("User  " + Prefs.FTP_USER);
        ftpPassword.setText("Password  " + prefs.password());
        showError(ftpError, LinkService.ftpError);

        String text;
        int color;
        switch (Adb.state()) {
            case CONNECTED: text = "Remote control ready"; color = OK; break;
            case WAITING_FOR_APPROVAL: text = "Choose Always allow, then Allow, on the TV's prompt"; color = WARN; break;
            case CONNECTING: text = "Connecting the remote control…"; color = WARN; break;
            case FAILED: text = Adb.lastError(); color = ERROR; break;
            default: text = "Remote control not connected"; color = MUTED; break;
        }
        status.setText(text);
        statusDot.setColor(color);

        Access access = LinkService.access();
        Access.Session c = access == null ? null : access.controller();
        controller.setText(c == null ? "Nobody" : c.name + (c.owner ? " (owner)" : ""));
        int n = prefs.ownerCount();
        owners.setText(n == 0 ? "No owner devices" : n == 1 ? "1 owner device" : n + " owner devices");
    }

    private void showError(TextView t, String error) {
        boolean has = error != null && !error.isEmpty();
        t.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) t.setText(error);
        t.setPadding(0, dp(6), 0, 0);
    }

    /** Black on white, pixel-exact: each module is a whole number of pixels, so the code stays sharp. */
    private static Bitmap qrCode(String text, int maxPx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int n = m.getWidth(), scale = Math.max(1, maxPx / n), size = n * scale;
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    px[y * size + x] = m.get(x / scale, y / scale) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Receiving a screen: the TV's own Miracast and AirPlay receivers wait in the background and take the screen as
     * soon as a phone or PC connects, under the TV's device name.
     */
    private void showReceiveHelp() {
        String name = android.provider.Settings.Global.getString(getContentResolver(), "device_name");
        if (name == null || name.isEmpty()) name = "this TV";
        new android.app.AlertDialog.Builder(this)
                .setTitle("Show a phone or PC screen on " + name)
                .setMessage("Nothing to start here: the TV is always ready. Pick \"" + name + "\" on the other device.\n\n"
                        + "Windows PC:  press Windows + K, then choose " + name + ".\n\n"
                        + "Android phone:  Quick settings, then Cast, Screen cast or Smart View, then " + name + ".\n\n"
                        + "iPhone, iPad or Mac:  Control Centre, then Screen Mirroring (AirPlay), then " + name + ".\n\n"
                        + "Press Back on the TV remote to stop showing it.")
                .setPositiveButton("OK", null)
                .show();
    }

    /** A card in the row under the QR code: a small muted heading, then its lines. */
    private LinearLayout card(LinearLayout parent, String heading, int leftMargin) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(24), dp(18), dp(24), dp(20));
        c.setBackground(surface(PAPER_2, dp(20), true));
        TextView h = label(14, MUTED, false);
        h.setText(heading);
        h.setPadding(0, 0, 0, dp(8));
        c.addView(h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.leftMargin = leftMargin;
        parent.addView(c, lp);
        return c;
    }

    /** A focusable row: a quiet pill at rest, ember-filled and a little larger when the D-pad lands on it. */
    private TextView action(LinearLayout parent, String text, boolean destructive) {
        TextView b = label(17, destructive ? ERROR : INK, true);
        b.setText(text);
        b.setFocusable(true);
        b.setClickable(true);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(24), 0, dp(24), 0);
        GradientDrawable rest = surface(PAPER_2, dp(999), true);
        GradientDrawable on = surface(destructive ? ERROR : ACCENT, dp(999), false);
        b.setBackground(rest);
        b.setOnFocusChangeListener((View v, boolean f) -> {
            b.setBackground(f ? on : rest);
            b.setTextColor(f ? ACCENT_INK : destructive ? ERROR : INK);
            v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(160).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = parent.getChildCount() == 0 ? 0 : dp(10);
        parent.addView(b, lp);
        return b;
    }

    private GradientDrawable surface(int color, int radius, boolean ruled) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (ruled) d.setStroke(dp(1), RULE);
        return d;
    }

    private TextView label(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
