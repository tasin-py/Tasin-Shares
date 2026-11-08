package com.tasin.shares;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * One run of scrcpy-server (bundled, v5.0.1) on the TV as the shell user, started through the app's own ADB.
 * It captures the screen (H.264), the TV's audio (raw PCM, 48 kHz stereo) and injects input. The server deletes
 * its jar when it starts, so every session puts a fresh copy in /data/local/tmp first.
 */
final class ScrcpySession {
    static final String VERSION = "5.0.1";
    /** Highest size this TV's hardware encoder accepts: 1080p and 1600 are refused (IllegalStateException). */
    static final int MAX_SIZE = 1280;
    private static final String TAG = "TasinShares";
    private static final String DEVICE_JAR = "/data/local/tmp/tasin-scrcpy.jar";
    private static final long FLAG_SESSION = 1L << 63, FLAG_CONFIG = 1L << 62, FLAG_KEY = 1L << 61;

    enum Audio {
        /** No capture: the TV plays its own sound. */
        TV,
        /** REMOTE_SUBMIX capture: the sound comes out of the controlling device and the TV goes quiet. */
        DEVICE,
        /** Playback capture with duplication: both at once. Android 13 and later only. */
        BOTH;

        static Audio parse(String s) {
            if ("device".equals(s)) return DEVICE;
            if ("both".equals(s)) return BOTH;
            return TV;
        }

        String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    static boolean bothSupported() {
        return Build.VERSION.SDK_INT >= 33;
    }

    interface Sink {
        void onSize(int width, int height);

        /** H.264 Annex B; config packets (SPS/PPS) come with config=true and no picture. */
        void onVideo(byte[] data, long ptsUs, boolean config, boolean keyFrame);

        /** Interleaved signed 16-bit little-endian PCM, 48 kHz, 2 channels. */
        void onAudio(byte[] pcm, long ptsUs);

        void onAudioUnavailable(String why);

        void onEnded(String why);
    }

    final boolean video;
    final Audio audio;
    private final Context ctx;
    private final Adb adb;
    private final Sink sink;
    private volatile AdbClient.AdbStream server, videoSock, audioSock, controlSock;
    private volatile boolean stopped;
    private volatile int width, height;
    private final StringBuilder serverLog = new StringBuilder();

    ScrcpySession(Context ctx, Adb adb, boolean video, Audio audio, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.adb = adb;
        this.video = video;
        this.audio = audio == Audio.BOTH && !bothSupported() ? Audio.TV : audio;
        this.sink = sink;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    void start() throws IOException {
        File jar = localJar();
        adb.exec("cp " + TvControl.quote(jar.getAbsolutePath()) + " " + DEVICE_JAR);
        String scid = String.format("%08x", new SecureRandom().nextInt() & 0x7fffffff);
        boolean withAudio = audio != Audio.TV;
        StringBuilder cmd = new StringBuilder("CLASSPATH=" + DEVICE_JAR + " app_process / com.genymobile.scrcpy.Server " + VERSION)
                .append(" scid=").append(scid)
                .append(" log_level=info tunnel_forward=true send_device_meta=false cleanup=true")
                .append(" power_off_on_close=false clipboard_autosync=false control=true")
                .append(" video=").append(video)
                .append(" audio=").append(withAudio);
        if (video) cmd.append(" video_codec=h264 max_size=").append(MAX_SIZE).append(" max_fps=60 video_bit_rate=8000000");
        if (withAudio) {
            cmd.append(" audio_codec=raw");
            if (audio == Audio.BOTH) cmd.append(" audio_source=playback audio_dup=true");
            else cmd.append(" audio_source=output");
        }
        server = adb.dedicated().open("exec:" + cmd + " 2>&1");
        if (server == null) throw new IOException("could not start the capture server");
        thread("scrcpy-log", this::readLog);

        String socket = "localabstract:scrcpy_" + scid;
        boolean first = true;
        if (video) {
            videoSock = connect(socket);
            first = consumeDummy(videoSock, first);
        }
        if (withAudio) {
            audioSock = connect(socket);
            first = consumeDummy(audioSock, first);
        }
        controlSock = connect(socket);
        consumeDummy(controlSock, first);

        if (video) thread("scrcpy-video", this::readVideo);
        if (withAudio) thread("scrcpy-audio", this::readAudio);
        thread("scrcpy-control", this::drainControl);
    }

    void stop() {
        if (stopped) return;
        stopped = true;
        for (AdbClient.AdbStream s : new AdbClient.AdbStream[]{videoSock, audioSock, controlSock, server})
            if (s != null) s.close();
    }

    boolean running() {
        return !stopped;
    }

    // --- input (formats from scrcpy's ControlMessageReader, big-endian)

    void key(int action, int keycode, int repeat, int meta) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(14);
        b.put((byte) 0).put((byte) action).putInt(keycode).putInt(repeat).putInt(meta);
        send(b.array());
    }

    void text(String text) throws IOException {
        // scrcpy caps injected text at 300 bytes; send long text in pieces
        byte[] all = text.getBytes(StandardCharsets.UTF_8);
        for (int off = 0; off < all.length; ) {
            int n = Math.min(300, all.length - off);
            while (n > 0 && off + n < all.length && (all[off + n] & 0xC0) == 0x80) n--; // don't split a character
            ByteBuffer b = ByteBuffer.allocate(5 + n);
            b.put((byte) 1).putInt(n).put(all, off, n);
            send(b.array());
            off += n;
        }
    }

    /** pointerId -1 is the mouse, -2 a generic finger; x and y are in video pixels. */
    void touch(int action, long pointerId, int x, int y, int actionButton, int buttons) throws IOException {
        if (width == 0) return;
        ByteBuffer b = ByteBuffer.allocate(32);
        b.put((byte) 2).put((byte) action).putLong(pointerId).putInt(x).putInt(y)
                .putShort((short) width).putShort((short) height)
                .putShort((short) (action == 1 ? 0 : 0xffff)).putInt(actionButton).putInt(buttons);
        send(b.array());
    }

    /** Scroll amounts in [-16, 16]; positive v scrolls up, as in scrcpy. */
    void scroll(int x, int y, float h, float v) throws IOException {
        if (width == 0) return;
        ByteBuffer b = ByteBuffer.allocate(21);
        b.put((byte) 3).putInt(x).putInt(y).putShort((short) width).putShort((short) height)
                .putShort(fixed(h / 16f)).putShort(fixed(v / 16f)).putInt(0);
        send(b.array());
    }

    // --- the virtual mouse: a UHID device, so Android sees a real mouse and draws its own cursor

    private static final int MOUSE_ID = 1;
    /** scrcpy's own HID mouse: 5 buttons, relative X, Y and wheel, then a horizontal wheel (AC Pan). */
    private static final byte[] MOUSE_DESCRIPTOR = {
            0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01, 0x09, 0x01, (byte) 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, (byte) 0x95, 0x05, 0x75, 0x01, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x03, (byte) 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03, (byte) 0x81, 0x06,
            0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x06,
            (byte) 0xC0, (byte) 0xC0};
    private boolean mouseCreated;

    /** Moves the TV's cursor by dx, dy, with the buttons held (bit 0 left, 1 right, 2 middle) and wheel steps. */
    synchronized void mouse(int dx, int dy, int buttons, int wheel) throws IOException {
        if (!mouseCreated) {
            byte[] name = "Tasin Shares mouse".getBytes(StandardCharsets.UTF_8);
            ByteBuffer c = ByteBuffer.allocate(1 + 2 + 2 + 2 + 1 + name.length + 2 + MOUSE_DESCRIPTOR.length);
            c.put((byte) 12).putShort((short) MOUSE_ID).putShort((short) 0).putShort((short) 0)
                    .put((byte) name.length).put(name).putShort((short) MOUSE_DESCRIPTOR.length).put(MOUSE_DESCRIPTOR);
            send(c.array());
            mouseCreated = true;
        }
        // A report moves at most 127 each way: split a long move into steps.
        do {
            int x = Math.max(-127, Math.min(127, dx)), y = Math.max(-127, Math.min(127, dy));
            int w = Math.max(-127, Math.min(127, wheel));
            ByteBuffer b = ByteBuffer.allocate(1 + 2 + 2 + 5);
            b.put((byte) 13).putShort((short) MOUSE_ID).putShort((short) 5)
                    .put((byte) (buttons & 0x1f)).put((byte) x).put((byte) y).put((byte) w).put((byte) 0);
            send(b.array());
            dx -= x;
            dy -= y;
            wheel -= w;
        } while (dx != 0 || dy != 0 || wheel != 0);
    }

    /** Asks the encoder for a fresh key frame, so a viewer that joins late starts at once. */
    void resetVideo() throws IOException {
        send(new byte[]{17});
    }

    private static short fixed(float f) {
        f = Math.max(-1f, Math.min(1f, f));
        return f >= 1f ? 0x7fff : (short) (f * 0x8000);
    }

    private void send(byte[] msg) throws IOException {
        AdbClient.AdbStream s = controlSock;
        if (s == null || stopped) throw new IOException("not connected");
        s.write(msg);
    }

    // --- streams

    private void readVideo() {
        try {
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(videoSock, 1 << 16));
            int codec = in.readInt();
            if (codec == 0 || codec == 1) throw new IOException("the TV could not start screen capture");
            while (!stopped) {
                long head = in.readLong();
                int third = in.readInt();
                if ((head & FLAG_SESSION) != 0) {
                    width = (int) head;
                    height = third;
                    sink.onSize(width, height);
                    continue;
                }
                byte[] data = new byte[third];
                in.readFully(data);
                boolean config = (head & FLAG_CONFIG) != 0;
                sink.onVideo(data, config ? 0 : head & ~(FLAG_CONFIG | FLAG_KEY), config, (head & FLAG_KEY) != 0);
            }
        } catch (IOException e) {
            end("video: " + e.getMessage());
        }
    }

    private void readAudio() {
        try {
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(audioSock, 1 << 15));
            int codec = in.readInt();
            if (codec == 0 || codec == 1) {
                sink.onAudioUnavailable(codec == 0 ? "The TV did not allow its audio to be captured." : "Audio capture failed.");
                return;
            }
            while (!stopped) {
                long head = in.readLong();
                int size = in.readInt();
                byte[] data = new byte[size];
                in.readFully(data);
                if ((head & FLAG_CONFIG) == 0) sink.onAudio(data, head & ~FLAG_KEY);
            }
        } catch (IOException e) {
            if (!stopped) sink.onAudioUnavailable("Audio stopped: " + e.getMessage());
        }
    }

    private void drainControl() {
        // Device messages (clipboard, acks) are not used; read them so the socket never fills up.
        byte[] buf = new byte[4096];
        try {
            while (controlSock.read(buf, 0, buf.length) >= 0) {
                // discard
            }
        } catch (IOException ignored) {
        }
        end("control socket closed");
    }

    private void readLog() {
        byte[] buf = new byte[2048];
        try {
            int n;
            while ((n = server.read(buf, 0, buf.length)) >= 0) {
                String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                Log.i(TAG, "scrcpy: " + s.trim());
                synchronized (serverLog) {
                    serverLog.append(s);
                    if (serverLog.length() > 4000) serverLog.delete(0, serverLog.length() - 4000);
                }
            }
        } catch (IOException ignored) {
        }
        end("capture server exited");
    }

    private void end(String why) {
        if (stopped) return;
        String log;
        synchronized (serverLog) {
            log = serverLog.toString().trim();
        }
        String last = log.isEmpty() ? "" : log.substring(log.lastIndexOf('\n') + 1);
        stop();
        sink.onEnded(last.contains("ERROR") ? last : why);
    }

    private AdbClient.AdbStream connect(String socket) throws IOException {
        AdbClient client = adb.dedicated();
        for (int i = 0; i < 60 && !stopped; i++) {
            AdbClient.AdbStream s = client.open(socket);
            if (s != null) return s;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        client.close();
        String log;
        synchronized (serverLog) {
            log = serverLog.toString().trim();
        }
        throw new IOException("capture server did not start" + (log.isEmpty() ? "" : ": " + log));
    }

    private static boolean consumeDummy(InputStream s, boolean first) throws IOException {
        if (first && s.read() < 0) throw new IOException("capture server closed the connection");
        return false;
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    /** The bundled server, unpacked once per app version into storage the shell user can read. */
    private File localJar() throws IOException {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) throw new IOException("no shared storage for the capture server");
        File jar = new File(dir, "scrcpy-server-" + VERSION + ".jar");
        if (jar.length() > 0) return jar;
        try (InputStream in = ctx.getAssets().open("scrcpy-server.jar");
             OutputStream out = new FileOutputStream(jar)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return jar;
    }
}
