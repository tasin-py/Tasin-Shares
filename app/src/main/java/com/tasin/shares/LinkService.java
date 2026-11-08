package com.tasin.shares;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Keeps the web remote and the FTP server running, from boot until the app is switched off. */
public final class LinkService extends Service implements Access.Listener {
    static final String ACTION_RESTART = "com.tasin.shares.RESTART";
    static final String ACTION_DISCONNECT_ALL = "com.tasin.shares.DISCONNECT_ALL";
    private static final String TAG = "TasinShares";
    private static final String CHANNEL = "tvlink";

    static volatile String webError = "";
    static volatile String ftpError = "";
    /** The ports the servers really listen on: the saved ones, or the next free ones when another app holds those. */
    private static volatile int webPortInUse, ftpPortInUse;

    static int webPort(Prefs prefs) {
        return webPortInUse > 0 ? webPortInUse : prefs.webPort();
    }

    static int ftpPort(Prefs prefs) {
        return ftpPortInUse > 0 ? ftpPortInUse : prefs.ftpPort();
    }

    private static boolean portTaken(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.BindException) return true;
            String m = String.valueOf(t.getMessage());
            if (m.contains("EADDRINUSE") || m.contains("Address already in use")) return true;
        }
        return false;
    }
    private static volatile Access accessRef;

    private Prefs prefs;
    private Adb adb;
    private Access access;
    private Overlay overlay;
    private RemoteButtons buttons;
    private Remote remote;
    private WebServer web;
    private final Ftp ftp = new Ftp();
    private WifiManager.WifiLock wifiLock;

    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, LinkService.class));
    }

    static Access access() {
        return accessRef;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        adb = new Adb(this);
        overlay = new Overlay(this);
        access = new Access(prefs, this);
        accessRef = access;
        remote = new Remote(this, adb, new TvControl(this, adb));
        buttons = new RemoteButtons(this, adb, () -> new android.os.Handler(android.os.Looper.getMainLooper())
                .post(this::disconnectAll));
        buttons.start();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Tasin Shares", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Tasin Shares is on")
                .setContentText("Remote, screen and file sharing on your home network")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tasinshares");
            wifiLock.acquire();
        }
        startServers();
        new Thread(this::selfSetup, "self-setup").start();
    }

    /**
     * Through its own ADB: the overlay permission for the approval screen and the "controlled by" pill, and
     * All files access, without which Android 11+ refuses files in Movies, Music and the like that are not media.
     */
    private void selfSetup() {
        try {
            if (!Settings.canDrawOverlays(this))
                adb.exec("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager())
                adb.exec("appops set " + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        } catch (Exception e) {
            Log.w(TAG, "self setup", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) restartServers();
        if (intent != null && ACTION_DISCONNECT_ALL.equals(intent.getAction())) disconnectAll();
        return START_STICKY;
    }

    /**
     * Every connection off at once: sessions dropped (owners stay trusted, but must open the page again), the
     * controlling page told, and the web and FTP servers restarted, which closes every socket and transfer.
     */
    void disconnectAll() {
        access.revokeAll();
        restartServers();
        overlay.controlledBy(null, false);
        overlay.flash("All devices disconnected");
    }

    void restartServers() {
        stopServers();
        startServers();
    }

    private void startServers() {
        // Another app may already hold the saved port (another web or file server on the TV): move on to the next
        // free one instead of failing, and show the port actually in use on the TV screen and in its QR code.
        int f = prefs.ftpPort();
        webPortInUse = 0;
        webError = "";
        for (int p = prefs.webPort(), tries = 0; tries < 20; p++, tries++) {
            if (p > 65535) break;
            if (p >= f && p <= f + 9) continue; // never one of FTP's ports
            try {
                web = new WebServer(this, prefs, p, access, new TvControl(this, adb), remote,
                        () -> startService(new Intent(this, LinkService.class).setAction(ACTION_RESTART)));
                // Pages ping every 10 s; a socket silent for 30 s is gone.
                web.start(30000, false);
                webPortInUse = p;
                webError = "";
                break;
            } catch (Exception e) {
                web = null;
                webError = String.valueOf(e.getMessage());
                Log.w(TAG, "web server on " + p, e);
                if (!portTaken(e)) break;
            }
        }
        int webInUse = webPortInUse;
        new Thread(() -> {
            ftpPortInUse = 0;
            ftpError = "";
            // FTP needs its port and the nine after it for data, so it moves in steps of ten.
            for (int p = f, tries = 0; tries < 10 && p + 9 <= 65535; p += 10, tries++) {
                if (webInUse >= p && webInUse <= p + 9) continue;
                try {
                    ftp.start(this, prefs, p);
                    ftpPortInUse = p;
                    ftpError = "";
                    break;
                } catch (Throwable e) {
                    ftp.stop();
                    ftpError = String.valueOf(e.getMessage());
                    Log.w(TAG, "ftp server on " + p, e);
                    if (!portTaken(e)) break;
                }
            }
        }, "ftp-start").start();
    }

    private void stopServers() {
        remote.shutdown();
        if (web != null) web.stop();
        web = null;
        ftp.stop();
    }

    // --- Access.Listener

    @Override
    public void onRequest(Access.Request r) {
        Intent i = new Intent(this, ApproveActivity.class)
                .putExtra(ApproveActivity.EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Runnable viaShell = () -> new Thread(() -> {
            try {
                adb.exec("am start -n " + getPackageName() + "/.ApproveActivity --es id " + r.id);
            } catch (Exception ignored) {
            }
        }).start();
        try {
            startActivity(i);
            // Android may block a start from the background without any error: if the screen is not up in
            // 1.5 s while the request still waits, ask the shell to open it.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!ApproveActivity.isShowing(r.id) && "pending".equals(r.state)) viaShell.run();
            }, 1500);
        } catch (Exception e) {
            viaShell.run();
        }
    }

    @Override
    public void onRequestClosed(Access.Request r) {
        ApproveActivity.closeIfShowing(r.id);
    }

    @Override
    public void onControllerChanged(Access.Session controller, String takenOverFrom) {
        overlay.controlledBy(controller == null ? null : controller.name, controller != null && controller.owner);
        if (controller != null && takenOverFrom != null)
            overlay.flash("Control taken over by " + controller.name + " (owner) from " + takenOverFrom);
    }

    @Override
    public void onKicked(Access.Session kicked, Access.Session by) {
        remote.kick(kicked, by);
    }

    @Override
    public void onDestroy() {
        stopServers();
        buttons.stop();
        adb.close();
        overlay.controlledBy(null, false);
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        accessRef = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** The TV's IPv4 address on the home network, or null when it has none. */
    static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
