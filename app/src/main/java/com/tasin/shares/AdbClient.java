package com.tasin.shares;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * A minimal ADB-over-TCP client, enough to run one command at a time on the TV's own adbd
 * (127.0.0.1:5555). Not thread-safe: callers hold one client per thread of work, under a lock.
 */
final class AdbClient implements Closeable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_PAYLOAD = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    interface PromptListener {
        void onPromptShown();
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;
    private int maxData = 4096;

    private AdbClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream(), 65536));
        this.out = socket.getOutputStream();
    }

    /** Connects and authenticates; if the key is new, the TV shows its prompt and this waits up to promptWaitMs. */
    static AdbClient connect(String host, int port, AdbKey key, PromptListener listener, int promptWaitMs) throws Exception {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), 3000);
        s.setSoTimeout(10000);
        AdbClient c = new AdbClient(s);
        try {
            c.write(CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".getBytes(StandardCharsets.UTF_8));
            boolean sentSignature = false, sentKey = false;
            while (true) {
                Msg m = c.read();
                if (m.cmd == CNXN) {
                    c.maxData = Math.max(4096, m.arg1);
                    s.setSoTimeout(30000);
                    return c;
                }
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected ADB reply during auth");
                if (!sentSignature) {
                    c.write(AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                    sentSignature = true;
                } else if (!sentKey) {
                    c.write(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload());
                    sentKey = true;
                    s.setSoTimeout(promptWaitMs);
                    if (listener != null) listener.onPromptShown();
                } else {
                    throw new IOException("ADB key was rejected on the TV");
                }
            }
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    /** Runs a service ("exec:...", "shell:...") and returns everything it wrote before closing. */
    byte[] run(String service) throws IOException {
        int local = nextLocalId++;
        int remote = 0;
        write(OPEN, local, 0, (service + "\u0000").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue; // a late message for an earlier stream
            switch (m.cmd) {
                case OKAY:
                    remote = m.arg0;
                    break;
                case WRTE:
                    remote = m.arg0;
                    result.write(m.data);
                    write(OKAY, local, remote, null);
                    break;
                case CLSE:
                    if (remote != 0) write(CLSE, local, remote, null);
                    return result.toByteArray();
                default:
                    break;
            }
        }
    }

    String exec(String command) throws IOException {
        return new String(run("exec:" + command), StandardCharsets.UTF_8);
    }

    /**
     * Opens a long-lived two-way stream ("exec:...", "localabstract:..."). The connection then belongs to the
     * stream: a reader thread delivers its data, so run() and exec() must not be used on this client again.
     * Returns null when adbd refuses the service (nothing listening on that socket yet).
     */
    AdbStream open(String service) throws IOException {
        int local = nextLocalId++;
        write(OPEN, local, 0, (service + "\u0000").getBytes(StandardCharsets.UTF_8));
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;
            if (m.cmd == CLSE) return null;
            if (m.cmd == OKAY) return new AdbStream(this, local, m.arg0);
        }
    }

    /** The stream side of a dedicated connection: an InputStream of what the service writes, plus write(). */
    static final class AdbStream extends java.io.InputStream {
        private final AdbClient c;
        private final int local, remote;
        private final java.util.concurrent.LinkedBlockingQueue<byte[]> chunks = new java.util.concurrent.LinkedBlockingQueue<>();
        private final java.util.concurrent.Semaphore writeWindow = new java.util.concurrent.Semaphore(1);
        private static final byte[] EOF = new byte[0];
        private byte[] current;
        private int pos;
        private volatile boolean closed;

        private AdbStream(AdbClient c, int local, int remote) throws IOException {
            this.c = c;
            this.local = local;
            this.remote = remote;
            c.socket.setSoTimeout(0); // a quiet stream (no frames while the screen is still) is not an error
            Thread t = new Thread(this::pump, "adb-stream-" + local);
            t.setDaemon(true);
            t.start();
        }

        private void pump() {
            try {
                while (!closed) {
                    Msg m = c.read();
                    if (m.arg1 != local) continue;
                    if (m.cmd == WRTE) {
                        if (m.data.length > 0) chunks.put(m.data);
                        synchronized (c) {
                            c.write(OKAY, local, remote, null);
                        }
                    } else if (m.cmd == OKAY) {
                        writeWindow.release();
                    } else if (m.cmd == CLSE) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            closed = true;
            chunks.offer(EOF);
            writeWindow.release();
            c.close();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (current == null || pos >= current.length) {
                if (current == EOF) return -1;
                try {
                    current = chunks.take();
                } catch (InterruptedException e) {
                    throw new java.io.InterruptedIOException();
                }
                pos = 0;
                if (current == EOF) {
                    chunks.offer(EOF);
                    return -1;
                }
            }
            int n = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, n);
            pos += n;
            return n;
        }

        /** Writes in payload-sized pieces, each waiting for adbd's OKAY, as the ADB flow control requires. */
        synchronized void write(byte[] data) throws IOException {
            int off = 0;
            while (off < data.length) {
                if (closed) throw new IOException("stream closed");
                try {
                    if (!writeWindow.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IOException("ADB write timed out");
                } catch (InterruptedException e) {
                    throw new java.io.InterruptedIOException();
                }
                if (closed) throw new IOException("stream closed");
                int n = Math.min(c.maxData, data.length - off);
                byte[] piece = new byte[n];
                System.arraycopy(data, off, piece, 0, n);
                synchronized (c) {
                    c.write(WRTE, local, remote, piece);
                }
                off += n;
            }
        }

        boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                synchronized (c) {
                    c.write(CLSE, local, remote, null);
                }
            } catch (IOException ignored) {
            }
            c.close();
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private Msg read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = bb.getInt();
        m.arg0 = bb.getInt();
        m.arg1 = bb.getInt();
        int len = bb.getInt();
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("bad ADB packet length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private void write(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        if (cmd == WRTE && len > maxData) throw new IOException("payload too large");
        int sum = 0;
        if (data != null) for (byte b : data) sum += b & 0xff;
        ByteBuffer bb = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        if (data != null) bb.put(data);
        out.write(bb.array());
        out.flush();
    }
}
