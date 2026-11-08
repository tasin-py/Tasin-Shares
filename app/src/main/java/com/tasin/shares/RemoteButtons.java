package com.tasin.shares;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Remote buttons whose own app is gone (Netflix, Prime Video, TCL Channel…) open other apps. Watches the raw input
 * events through the app's own ADB (`getevent`, as the shell user) instead of an accessibility key filter: on TCL
 * firmware any key filter stops a held volume button from repeating. The firmware still sees each press, which does
 * nothing for these buttons since their apps are disabled; the app opened here lands on top.
 */
final class RemoteButtons {
    /** Linux key codes (EV_KEY) of the TCL C6KS remote, BT_RC813A_B1, to the package each button opens. */
    private static final Map<Integer, String> BUTTONS = new HashMap<>();

    static {
        BUTTONS.put(0x0f2, "com.tasin.shares");          // Netflix
        BUTTONS.put(0x28b, "com.seraphic.openinet.pre");   // Browser
        BUTTONS.put(0x0ec, "org.courville.nova");          // Media
        BUTTONS.put(0x0e3, "com.ezviz.tv");                // TCL Channel
        BUTTONS.put(0x2f0, "app.blaze.spofficial");        // Prime Video
    }

    /** Netflix: a tap opens Tasin Shares, a 6-second hold disconnects every device. */
    private static final int NETFLIX = 0x0f2;
    private static final long DISCONNECT_HOLD_MS = 6000;

    private final Context ctx;
    private final Adb adb;
    private final Runnable disconnectAll;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean netflixHeld, netflixFired;
    private final Runnable netflixHold;
    private volatile boolean running;
    private volatile AdbClient client;

    RemoteButtons(Context ctx, Adb adb, Runnable disconnectAll) {
        this.ctx = ctx.getApplicationContext();
        this.adb = adb;
        this.disconnectAll = disconnectAll;
        netflixHold = () -> {
            netflixFired = true;
            disconnectAll.run();
        };
    }

    void start() {
        if (running) return;
        running = true;
        new Thread(this::loop, "remote-buttons").start();
    }

    void stop() {
        running = false;
        AdbClient c = client;
        if (c != null) c.close();
    }

    private void loop() {
        while (running) {
            try {
                client = adb.dedicated();
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        client.open("exec:getevent"), StandardCharsets.US_ASCII));
                String line;
                while (running && (line = in.readLine()) != null) handle(line);
            } catch (Exception ignored) {
            } finally {
                AdbClient c = client;
                if (c != null) c.close();
                client = null;
            }
            // adbd restarted, or the remote reconnected over Bluetooth: start over.
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** "/dev/input/event4: 0001 0241 00000001": type, code and value in hex; a key down is type 1, value 1. */
    private void handle(String line) {
        int colon = line.indexOf(": ");
        if (colon < 0) return;
        String[] f = line.substring(colon + 2).trim().split("\\s+");
        if (f.length != 3 || !f[0].equals("0001")) return;
        int code;
        try {
            code = Integer.parseInt(f[1], 16);
        } catch (NumberFormatException e) {
            return;
        }
        if (code == NETFLIX) {
            netflix(f[2]);
            return;
        }
        if (!f[2].equals("00000001")) return;   // key down only; 0 is up, 2 a repeat
        String pkg = BUTTONS.get(code);
        if (pkg != null) open(pkg);
    }

    /** Netflix opens on release, so a long hold can mean "disconnect everyone" instead. */
    private void netflix(String value) {
        if (value.equals("00000001")) {
            if (netflixHeld) return;
            netflixHeld = true;
            netflixFired = false;
            main.postDelayed(netflixHold, DISCONNECT_HOLD_MS);
        } else if (value.equals("00000000")) {
            main.removeCallbacks(netflixHold);
            boolean tap = netflixHeld && !netflixFired;
            netflixHeld = false;
            if (tap) open(BUTTONS.get(NETFLIX));
        }
    }

    private void open(String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return; // not installed
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception e) {
            // A background start can be refused; the shell may start anything.
            try {
                adb.exec("am start -n " + i.getComponent().flattenToShortString());
            } catch (Exception ignored) {
            }
        }
    }
}
