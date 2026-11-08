package com.tasin.shares;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the web remote can do to the TV: keys, text, apps, inputs and screenshots. */
final class TvControl {
    private final Context ctx;
    private final Adb adb;
    private final Map<String, byte[]> iconCache = Collections.synchronizedMap(new LinkedHashMap<>());

    TvControl(Context ctx, Adb adb) {
        this.ctx = ctx.getApplicationContext();
        this.adb = adb;
    }

    void key(int keycode, boolean longPress) throws IOException {
        adb.exec("input keyevent " + (longPress ? "--longpress " : "") + keycode);
    }

    /** One shell command as the shell user, which may write anywhere in shared storage. */
    String shell(String command) throws IOException {
        return adb.exec(command);
    }

    /** Types text into the focused field. `input text` takes ASCII only and needs spaces as %s. */
    void text(String text) throws IOException {
        StringBuilder b = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch == ' ') b.append("%s");
            else if (ch >= 0x20 && ch < 0x7f) b.append(ch);
        }
        if (b.length() == 0) return;
        adb.exec("input text " + quote(b.toString()));
    }

    JSONArray apps() throws Exception {
        PackageManager pm = ctx.getPackageManager();
        Map<String, JSONObject> byPkg = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (byPkg.containsKey(pkg) || pkg.equals(ctx.getPackageName())) continue;
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                o.put("label", String.valueOf(ri.loadLabel(pm)));
                byPkg.put(pkg, o);
            }
        }
        List<JSONObject> list = new ArrayList<>(byPkg.values());
        list.sort((a, b) -> a.optString("label").compareToIgnoreCase(b.optString("label")));
        return new JSONArray(list);
    }

    void launch(String pkg) throws IOException {
        if (!pkg.matches("[A-Za-z0-9_.]+")) throw new IOException("bad package");
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null || i.getComponent() == null) throw new IOException("app has no launcher entry");
        adb.exec("am start -n " + quote(i.getComponent().flattenToString()));
    }

    void stop(String pkg) throws IOException {
        if (!pkg.matches("[A-Za-z0-9_.]+")) throw new IOException("bad package");
        adb.exec("am force-stop " + pkg);
    }

    /** The app's banner (TV apps) or icon as a PNG, cached. */
    byte[] icon(String pkg) throws Exception {
        byte[] cached = iconCache.get(pkg);
        if (cached != null) return cached;
        PackageManager pm = ctx.getPackageManager();
        Drawable d = null;
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i != null) {
            ActivityInfo ai = pm.getActivityInfo(i.getComponent(), 0);
            d = ai.loadBanner(pm);
        }
        if (d == null) d = pm.getApplicationBanner(pkg);
        boolean banner = d != null;
        if (d == null) d = pm.getApplicationIcon(pkg);
        int w = banner ? 320 : 160, h = banner ? 180 : 160;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bmp));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        byte[] png = out.toByteArray();
        if (iconCache.size() > 200) iconCache.clear();
        iconCache.put(pkg, png);
        return png;
    }

    JSONArray inputs() throws Exception {
        JSONArray arr = new JSONArray();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return arr;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput() || info.getParentId() != null) continue;
            JSONObject o = new JSONObject();
            o.put("id", info.getId());
            CharSequence custom = info.loadCustomLabel(ctx);
            o.put("label", String.valueOf(custom != null ? custom : info.loadLabel(ctx)));
            arr.put(o);
        }
        return arr;
    }

    void switchInput(String inputId) throws IOException {
        String uri = TvContract.buildChannelUriForPassthroughInput(inputId).toString();
        adb.exec("am start -a android.intent.action.VIEW -d " + quote(uri) + " -t vnd.android.cursor.item/channel");
    }

    /** The screen as a JPEG about half the TV's width; empty while Live TV or HDMI is on screen. */
    byte[] screenshot() throws IOException {
        byte[] png = adb.screencap();
        Bitmap full = png.length == 0 ? null : BitmapFactory.decodeByteArray(png, 0, png.length);
        if (full == null) throw new IOException("empty screenshot (Live TV, HDMI and some apps cannot be captured)");
        int w = 960, h = Math.max(1, full.getHeight() * w / full.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(full, w, h, true);
        full.recycle();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 75, out);
        small.recycle();
        return out.toByteArray();
    }

    String install(String absPath) throws IOException {
        return adb.exec("pm install -r " + quote(absPath)).trim();
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
