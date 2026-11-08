package com.tasin.shares;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates from the newest GitHub release: compares its tag (vX.Y) with this version, downloads its TasinShares.apk and
 * installs it. It installs through the TV's own adb first (no prompt), and falls back to Android's installer.
 * Releases must be signed with the same key as the installed app, or Android refuses the update.
 */
final class Updater {
    /** The GitHub repository the releases come from, as owner/name. */
    static final String REPO = "tasin-py/Tasin-Shares";
    static final String APK_NAME = "TasinShares.apk";

    enum Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, FAILED }

    static volatile Phase phase = Phase.IDLE;
    static volatile String latest = "";
    static volatile String message = "";
    static volatile int progress;
    private static volatile String apkUrl;

    private Updater() {
    }

    static boolean busy() {
        return phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.INSTALLING;
    }

    static String current(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** One line for the screens: what the updater is doing or found. */
    static String describe(Context ctx) {
        switch (phase) {
            case CHECKING: return "Checking for updates…";
            case UP_TO_DATE: return "Tasin Shares is up to date.";
            case AVAILABLE: return "Version " + latest + " is available.";
            case DOWNLOADING: return "Downloading version " + latest + "… " + progress + "%";
            case INSTALLING: return "Installing version " + latest + ". The app restarts when it is done.";
            case FAILED: return message;
            default: return "Version " + current(ctx) + ".";
        }
    }

    static void check(Context ctx) {
        if (busy()) return;
        Context app = ctx.getApplicationContext();
        phase = Phase.CHECKING;
        new Thread(() -> {
            try {
                // The web redirect from releases/latest names the newest tag, without the API's 60-an-hour limit.
                HttpURLConnection c = open("https://github.com/" + REPO + "/releases/latest");
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (code == 404) throw new IOException("The GitHub repository " + REPO + " was not found.");
                if (code / 100 != 3 || location == null) throw new IOException("GitHub answered " + code + ".");
                int at = location.indexOf("/releases/tag/");
                if (at < 0) throw new IOException("No release has been published yet.");
                String rawTag = Uri.decode(location.substring(at + "/releases/tag/".length()));
                String tag = rawTag.replaceFirst("^[vV]", "");
                latest = tag;
                if (compare(tag, current(app)) <= 0) {
                    phase = Phase.UP_TO_DATE;
                } else {
                    apkUrl = "https://github.com/" + REPO + "/releases/download/" + Uri.encode(rawTag) + "/" + APK_NAME;
                    phase = Phase.AVAILABLE;
                }
            } catch (Exception e) {
                fail("Could not check for updates. " + reason(e));
            }
        }, "update-check").start();
    }

    static void install(Context ctx) {
        if (busy() || phase != Phase.AVAILABLE || apkUrl == null) return;
        Context app = ctx.getApplicationContext();
        phase = Phase.DOWNLOADING;
        progress = 0;
        new Thread(() -> {
            File apk;
            try {
                apk = download(apkUrl);
            } catch (Exception e) {
                fail("The download failed. " + reason(e));
                return;
            }
            phase = Phase.INSTALLING;
            // The TV's adb installs without a prompt; Android replaces this app and restarts it on its own.
            Adb adb = new Adb(app);
            try {
                String out = adb.exec("pm install -r " + apk.getAbsolutePath()).trim();
                if (!out.contains("Success")) throw new IOException(out);
                return;
            } catch (Exception e) {
                if (String.valueOf(e.getMessage()).contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
                    fail("This update is signed with a different key, so Android will not install it over this app.");
                    return;
                }
            } finally {
                adb.close();
            }
            // No adb: ask Android's installer, which shows its own confirmation.
            try {
                Intent i = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse("content://" + ApkProvider.AUTHORITY + "/update.apk"),
                                "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(i);
            } catch (Exception e) {
                fail("Could not start the installer. " + reason(e));
            }
        }, "update-install").start();
    }

    /** The downloaded APK, where both adb's shell and the app's own provider can read it. */
    static File file() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TasinShares-update.apk");
    }

    private static File download(String url) throws IOException {
        HttpURLConnection c = open(url);
        if (c.getResponseCode() == 404) throw new IOException("Version " + latest + " has no " + APK_NAME + " attached.");
        if (c.getResponseCode() != 200) throw new IOException("GitHub answered " + c.getResponseCode() + ".");
        long total = c.getContentLengthLong();
        File out = file();
        File dir = out.getParentFile();
        if (dir != null) dir.mkdirs();
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (total > 0) progress = (int) (done * 100 / total);
            }
        }
        out.setReadable(true, false);
        return out;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "TasinShares-updater");
        return c;
    }

    /** Compares dotted versions number by number: 2.10 is newer than 2.9. */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static void fail(String why) {
        message = why;
        phase = Phase.FAILED;
    }

    private static String reason(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "The TV is not connected to the internet.";
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
