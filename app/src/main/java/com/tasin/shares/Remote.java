package com.tasin.shares;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;

/**
 * Ties the controlling page to one scrcpy session: starts it with the page's choice of video and audio, forwards
 * the stream to the page, and turns the page's mouse and keyboard into input on the TV. Remote buttons also work
 * with no page connected (the pop-out remote), through `input keyevent`.
 */
final class Remote {
    private static final String TAG = "TasinShares";
    private final Context ctx;
    private final Adb adb;
    private final TvControl tv;
    private WebServer.ControlSocket page;
    private ScrcpySession session;
    private boolean wantVideo;
    private ScrcpySession.Audio wantAudio = ScrcpySession.Audio.TV;
    private int generation;

    Remote(Context ctx, Adb adb, TvControl tv) {
        this.ctx = ctx.getApplicationContext();
        this.adb = adb;
        this.tv = tv;
    }

    synchronized void attach(WebServer.ControlSocket p) {
        if (page != null && page != p) page.closeWith("replaced", "This page was opened somewhere else.");
        page = p;
        wantVideo = false;
        wantAudio = ScrcpySession.Audio.TV;
        restart();
    }

    synchronized void detach(WebServer.ControlSocket p) {
        if (page != p) return;
        page = null;
        stopSession();
    }

    /** Another device took control: tell this page and let it go. */
    synchronized void kick(Access.Session kicked, Access.Session by) {
        if (page != null && page.me == kicked) {
            WebServer.ControlSocket p = page;
            page = null;
            stopSession();
            p.closeWith("kicked", by == null ? "Access was revoked." : "Control was taken over by " + by.name + ".");
        }
    }

    synchronized void shutdown() {
        if (page != null) page.closeWith("ended", "Tasin Shares restarted.");
        page = null;
        stopSession();
    }

    // --- from the page

    void onMessage(WebServer.ControlSocket p, JSONObject m) throws IOException, org.json.JSONException {
        String t = m.optString("t");
        if ("ping".equals(t)) return;
        if ("log".equals(t)) {
            // The page's own view of the stream (browser, decoder errors), to diagnose a phone from the TV's log.
            String msg = m.optString("msg");
            Log.i(TAG, "page: " + (msg.length() > 400 ? msg.substring(0, 400) : msg));
            return;
        }
        if ("config".equals(t)) {
            synchronized (this) {
                if (p != page) return;
                boolean v = m.optBoolean("video", wantVideo);
                ScrcpySession.Audio a = ScrcpySession.Audio.parse(m.optString("audio", wantAudio.wire()));
                if (a == ScrcpySession.Audio.BOTH && !ScrcpySession.bothSupported()) {
                    p.event("notice", "Playing on both needs Android 13; this TV has Android 12.");
                    a = wantAudio;
                }
                if (v != wantVideo || a != wantAudio || session == null || !session.running()) {
                    wantVideo = v;
                    wantAudio = a;
                    restart();
                } else if (v) {
                    session.resetVideo();
                }
            }
            return;
        }
        ScrcpySession s = current(p);
        switch (t) {
            case "key": {
                int code = m.getInt("code");
                int action = m.optInt("a", -1), meta = m.optInt("m", 0), repeat = m.optInt("r", 0);
                if (s == null) {
                    if (action != 1) tv.key(code, false);
                } else if (action == -1) {
                    s.key(0, code, 0, meta);
                    s.key(1, code, 0, meta);
                } else {
                    s.key(action, code, repeat, meta);
                }
                break;
            }
            case "text":
                if (s == null) tv.text(m.optString("s"));
                else s.text(m.optString("s"));
                break;
            case "touch":
                if (s != null) s.touch(m.getInt("a"), m.optLong("id", -1), m.getInt("x"), m.getInt("y"),
                        m.optInt("ab", 0), m.optInt("b", 0));
                break;
            case "scroll":
                if (s != null) s.scroll(m.getInt("x"), m.getInt("y"), (float) m.optDouble("h", 0), (float) m.optDouble("v", 0));
                break;
            default:
                break;
        }
    }

    /** Remote buttons from the HTTP API (pop-out window): through scrcpy when a session runs, else adb. */
    void key(int code, boolean longPress) throws IOException {
        ScrcpySession s;
        synchronized (this) {
            s = session != null && session.running() ? session : null;
        }
        if (s != null && !longPress) {
            s.key(0, code, 0, 0);
            s.key(1, code, 0, 0);
        } else {
            tv.key(code, longPress);
        }
    }

    void text(String value) throws IOException {
        ScrcpySession s;
        synchronized (this) {
            s = session != null && session.running() ? session : null;
        }
        if (s != null) s.text(value);
        else tv.text(value);
    }

    private synchronized ScrcpySession current(WebServer.ControlSocket p) {
        return p == page && session != null && session.running() ? session : null;
    }

    // --- session

    private void restart() {
        stopSession();
        final WebServer.ControlSocket p = page;
        if (p == null) return;
        final int gen = ++generation;
        final boolean v = wantVideo;
        final ScrcpySession.Audio a = wantAudio;
        ScrcpySession s = new ScrcpySession(ctx, adb, v, a, new ScrcpySession.Sink() {
            @Override
            public void onSize(int width, int height) {
                if (!live(gen)) return;
                p.sendText("{\"t\":\"size\",\"w\":" + width + ",\"h\":" + height + "}");
            }

            @Override
            public void onVideo(byte[] data, long ptsUs, boolean config, boolean keyFrame) {
                if (!live(gen)) return;
                try {
                    p.sendBinary(WebServer.packet((byte) 1, (config ? 1 : 0) | (keyFrame ? 2 : 0), ptsUs, data));
                } catch (IOException e) {
                    Log.w(TAG, "video send", e);
                }
            }

            @Override
            public void onAudio(byte[] pcm, long ptsUs) {
                if (!live(gen)) return;
                try {
                    p.sendBinary(WebServer.packet((byte) 2, 0, ptsUs, pcm));
                } catch (IOException ignored) {
                }
            }

            @Override
            public void onAudioUnavailable(String why) {
                if (live(gen)) p.event("audio-off", why);
            }

            @Override
            public void onEnded(String why) {
                if (live(gen)) p.event("stream-ended", why);
            }
        });
        session = s;
        p.sendText("{\"t\":\"starting\",\"video\":" + v + ",\"audio\":\"" + a.wire() + "\"}");
        new Thread(() -> {
            try {
                s.start();
                if (live(gen)) p.sendText("{\"t\":\"started\",\"video\":" + v + ",\"audio\":\"" + a.wire() + "\"}");
            } catch (IOException e) {
                Log.w(TAG, "scrcpy start", e);
                if (live(gen)) p.event("stream-ended", e.getMessage());
                s.stop();
            }
        }, "scrcpy-start").start();
    }

    private synchronized boolean live(int gen) {
        return gen == generation && page != null;
    }

    private void stopSession() {
        generation++;
        if (session != null) session.stop();
        session = null;
    }
}
