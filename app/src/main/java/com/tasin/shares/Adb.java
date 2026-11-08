package com.tasin.shares;

import android.content.Context;

import java.io.IOException;

/**
 * The app's connections to the TV's own adbd. Two lanes, so a slow screenshot never holds up a key press.
 * Each lane reconnects on the next call after a failure.
 */
final class Adb {
    enum State { IDLE, CONNECTING, WAITING_FOR_APPROVAL, CONNECTED, FAILED }

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;

    private static volatile State state = State.IDLE;
    private static volatile String lastError = "";

    private final Context ctx;
    private final Object keysLock = new Object();
    private final Object screenLock = new Object();
    private AdbClient keys;
    private AdbClient screen;

    Adb(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    static State state() {
        return state;
    }

    static String lastError() {
        return lastError;
    }

    String exec(String command) throws IOException {
        synchronized (keysLock) {
            if (keys == null) keys = open();
            try {
                return keys.exec(command);
            } catch (IOException e) {
                keys.close();
                keys = null;
                // One retry on a fresh connection: adbd may have restarted since the last call.
                keys = open();
                return keys.exec(command);
            }
        }
    }

    byte[] screencap() throws IOException {
        synchronized (screenLock) {
            if (screen == null) screen = open();
            try {
                return screen.run("exec:screencap -p");
            } catch (IOException e) {
                screen.close();
                screen = null;
                throw e;
            }
        }
    }

    /** A connection of its own, for one long-lived stream (scrcpy's process and sockets). */
    AdbClient dedicated() throws IOException {
        try {
            return AdbClient.connect(HOST, PORT, AdbKey.load(ctx), null, 10000);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Opens the first lane now, so the approval prompt shows while someone is looking at the TV. */
    void warmUp() {
        new Thread(() -> {
            try {
                exec("true");
            } catch (IOException ignored) {
            }
        }, "adb-warmup").start();
    }

    void close() {
        synchronized (keysLock) {
            if (keys != null) keys.close();
            keys = null;
        }
        synchronized (screenLock) {
            if (screen != null) screen.close();
            screen = null;
        }
    }

    private AdbClient open() throws IOException {
        state = State.CONNECTING;
        try {
            AdbClient c = AdbClient.connect(HOST, PORT, AdbKey.load(ctx),
                    () -> state = State.WAITING_FOR_APPROVAL, 120000);
            state = State.CONNECTED;
            lastError = "";
            return c;
        } catch (java.net.ConnectException e) {
            fail("Network debugging is off. Turn it on in Developer options.");
        } catch (java.net.SocketTimeoutException e) {
            fail("No answer to the \"Allow debugging?\" prompt.");
        } catch (Exception e) {
            fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        throw new IOException(lastError);
    }

    private static void fail(String why) {
        state = State.FAILED;
        lastError = why;
    }
}
