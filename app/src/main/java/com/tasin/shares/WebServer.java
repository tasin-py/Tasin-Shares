package com.tasin.shares;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * The browser side: the page, its API, the file manager and one WebSocket per controlling page, which carries the
 * screen video, the TV's audio and mouse and keyboard input.
 */
final class WebServer extends NanoWSD {
    static final String SESSION_COOKIE = "ts_session";
    static final String OWNER_COOKIE = "ts_owner";

    private final Context ctx;
    private final Prefs prefs;
    private final Access access;
    private final TvControl tv;
    private final Remote remote;
    private final Runnable onSettingsChanged;
    private final File root = Environment.getExternalStorageDirectory();

    WebServer(Context ctx, Prefs prefs, int port, Access access, TvControl tv, Remote remote, Runnable onSettingsChanged) {
        super(port);
        this.ctx = ctx.getApplicationContext();
        this.prefs = prefs;
        this.access = access;
        this.tv = tv;
        this.remote = remote;
        this.onSettingsChanged = onSettingsChanged;
    }

    // --- WebSocket: one per controlling page

    @Override
    public Response serve(IHTTPSession s) {
        if (isWebsocketRequested(s) && "/ws".equals(s.getUri()) && session(s) == null)
            return closing(text(Response.Status.UNAUTHORIZED, "connect first"));
        return super.serve(s);
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        Access.Session me = session(handshake);
        boolean takeOver = "1".equals(first(handshake, "takeover"));
        return new ControlSocket(handshake, me, takeOver);
    }

    /** The controlling page's socket. Binary out: 1 = video packet, 2 = audio packet; JSON text both ways. */
    final class ControlSocket extends WebSocket {
        final Access.Session me;
        private final boolean takeOver;
        private final Object sendLock = new Object();
        volatile boolean controlling;

        ControlSocket(IHTTPSession h, Access.Session me, boolean takeOver) {
            super(h);
            this.me = me;
            this.takeOver = takeOver;
        }

        @Override
        protected void onOpen() {
            access.socketOpened(me);
            Access.Session holder = access.claim(me, takeOver);
            JSONObject hello = new JSONObject();
            try {
                hello.put("t", "hello");
                hello.put("name", me.name);
                hello.put("owner", me.owner);
                hello.put("bothAudio", ScrcpySession.bothSupported());
                hello.put("sdk", Build.VERSION.SDK_INT);
                if (holder != me) {
                    hello.put("t", "busy");
                    hello.put("by", holder.name);
                    hello.put("canTakeOver", me.owner);
                }
            } catch (Exception ignored) {
            }
            sendText(hello.toString());
            if (holder == me) {
                controlling = true;
                remote.attach(this);
            }
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            android.util.Log.i("TasinShares", "socket closed: " + code + " " + reason + (initiatedByRemote ? " (by the page)" : ""));
            if (controlling) remote.detach(this);
            controlling = false;
            access.socketClosed(me);
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            if (!controlling) return;
            try {
                JSONObject m = new JSONObject(message.getTextPayload());
                remote.onMessage(this, m);
            } catch (Exception e) {
                event("error", e.getMessage());
            }
        }

        @Override
        protected void onPong(WebSocketFrame pong) {
        }

        @Override
        protected void onException(IOException exception) {
            android.util.Log.i("TasinShares", "socket error: " + exception);
        }

        void sendText(String s) {
            synchronized (sendLock) {
                try {
                    if (isOpen()) send(s);
                } catch (IOException ignored) {
                }
            }
        }

        void sendBinary(byte[] b) throws IOException {
            synchronized (sendLock) {
                if (isOpen()) send(b);
            }
        }

        void event(String type, String message) {
            JSONObject o = new JSONObject();
            try {
                o.put("t", type);
                if (message != null) o.put("msg", message);
            } catch (Exception ignored) {
            }
            sendText(o.toString());
        }

        void closeWith(String type, String message) {
            event(type, message);
            controlling = false;
            try {
                close(WebSocketFrame.CloseCode.NormalClosure, type, false);
            } catch (IOException ignored) {
            }
        }
    }

    // --- HTTP

    @Override
    protected Response serveHttp(IHTTPSession s) {
        String uri = s.getUri();
        try {
            if (uri.equals("/") || uri.equals("/index.html")) return asset("index.html", "text/html; charset=utf-8", false);
            if (uri.equals("/jmuxer.min.js")) return asset("jmuxer.min.js", "application/javascript", true);
            if (uri.equals("/coffee.png")) return asset("coffee.png", "image/png", true);
            if (uri.equals("/api/me")) return me(s);
            if (uri.equals("/api/request")) return request(s);
            if (uri.equals("/api/owner-login") && s.getMethod() == Method.POST) return ownerLogin(s);
            if (!uri.startsWith("/api/")) return closing(text(Response.Status.NOT_FOUND, "not found"));
            Access.Session me = session(s);
            if (me == null) return closing(text(Response.Status.UNAUTHORIZED, "connect first"));
            return api(uri.substring(5), s, me);
        } catch (Forbidden e) {
            return closing(text(Response.Status.FORBIDDEN, e.getMessage()));
        } catch (Exception e) {
            // The request body may be unread; closing keeps it from being parsed as the next request.
            return closing(text(Response.Status.INTERNAL_ERROR, e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private Response me(IHTTPSession s) throws Exception {
        Access.Session me = session(s);
        JSONObject o = new JSONObject();
        o.put("connected", me != null);
        if (me != null) {
            o.put("name", me.name);
            o.put("owner", me.owner);
        }
        Access.Session c = access.controller();
        o.put("controller", c == null ? JSONObject.NULL : c.name);
        o.put("adb", Adb.state().name());
        o.put("adbError", Adb.lastError());
        o.put("theme", prefs.theme().id);
        o.put("ownerPasswordSet", prefs.hasOwnerPassword());
        return json(o.toString());
    }

    /** POST name=… asks the TV; GET id=… polls, and sets the cookies once someone at the TV allowed it. */
    private Response request(IHTTPSession s) throws Exception {
        Map<String, String> q = params(s);
        if (s.getMethod() == Method.POST) {
            Access.Request r;
            try {
                r = access.request(q.get("name"));
            } catch (IllegalStateException e) {
                return text(Response.Status.CONFLICT, e.getMessage());
            }
            JSONObject o = new JSONObject();
            o.put("id", r.id);
            o.put("code", r.code);
            o.put("ttl", Access.REQUEST_TTL_MS);
            return json(o.toString());
        }
        Access.Request r = access.find(q.get("id"));
        if (r == null) return text(Response.Status.NOT_FOUND, "expired");
        JSONObject o = new JSONObject();
        o.put("state", r.state);
        Response res = json(o.toString());
        if ("approved".equals(r.state) && r.sessionToken != null) {
            res.addHeader("Set-Cookie", SESSION_COOKIE + "=" + r.sessionToken + "; Path=/; HttpOnly; SameSite=Strict");
            if (r.ownerKey != null)
                res.addHeader("Set-Cookie", OWNER_COOKIE + "=" + r.ownerKey + "; Max-Age=315360000; Path=/; HttpOnly; SameSite=Strict");
            r.sessionToken = null; // the cookies go to the first poll only
            r.ownerKey = null;
        }
        return res;
    }

    private Response ownerLogin(IHTTPSession s) throws Exception {
        Map<String, String> q = params(s);
        String[] t;
        try {
            t = access.ownerLogin(q.get("name"), q.get("password"));
        } catch (IllegalStateException e) {
            return text(Response.Status.FORBIDDEN, e.getMessage());
        }
        Response res = json("{\"ok\":true}");
        res.addHeader("Set-Cookie", SESSION_COOKIE + "=" + t[0] + "; Path=/; HttpOnly; SameSite=Strict");
        res.addHeader("Set-Cookie", OWNER_COOKIE + "=" + t[1] + "; Max-Age=315360000; Path=/; HttpOnly; SameSite=Strict");
        return res;
    }

    private Response api(String path, IHTTPSession s, Access.Session me) throws Exception {
        Map<String, String> q = params(s);
        boolean post = s.getMethod() == Method.POST;
        switch (path) {
            // remote control: only the device in control (an idle TV is claimed by the first press)
            case "key":
                requirePost(post);
                requireControl(me);
                remote.key(Integer.parseInt(q.get("code")), "1".equals(q.get("long")));
                return ok();
            case "text":
                requirePost(post);
                requireControl(me);
                remote.text(q.getOrDefault("value", ""));
                return ok();
            case "launch":
                requirePost(post);
                requireControl(me);
                tv.launch(q.get("pkg"));
                return ok();
            case "stop":
                requirePost(post);
                requireControl(me);
                tv.stop(q.get("pkg"));
                return ok();
            case "input":
                requirePost(post);
                requireControl(me);
                tv.switchInput(q.get("id"));
                return ok();
            case "apps":
                return json(tv.apps().toString());
            case "icon":
                return bytes(tv.icon(q.get("pkg")), "image/png", true);
            case "inputs":
                return json(tv.inputs().toString());

            // files: any connected device
            case "files":
                return listFiles(q.getOrDefault("path", "/"));
            case "download":
                return download(q.get("path"));
            case "upload":
                return upload(s, q.get("path"), q.get("name"));
            case "mkdir":
                requirePost(post);
                File made = resolve(q.get("path"));
                if (!made.mkdirs() && !made.isDirectory()) shellOrFail("mkdir -p " + q(made), "could not create the folder");
                return ok();
            case "delete":
                requirePost(post);
                deleteRecursively(resolve(q.get("path")));
                return ok();
            case "rename": {
                requirePost(post);
                File from = resolve(q.get("path"));
                File to = new File(from.getParentFile(), safeName(q.get("to")));
                if (!from.renameTo(to)) shellOrFail("mv " + q(from) + " " + q(to), "could not rename");
                return ok();
            }
            case "install": {
                requirePost(post);
                requireControl(me);
                return text(Response.Status.OK, tv.install(resolve(q.get("path")).getAbsolutePath()));
            }

            // settings: owners only
            case "settings":
                // First run: no owner exists yet, so a device the TV allowed may set the first owner password.
                if (!me.owner && post && !prefs.hasOwnerPassword() && q.size() == 1 && q.containsKey("ownerPassword")) {
                    String first = q.get("ownerPassword");
                    if (first == null || first.isEmpty()) return text(Response.Status.BAD_REQUEST, "The owner password needs 8 to 64 characters.");
                    String err = prefs.setOwnerPassword(first);
                    if (err != null) return text(Response.Status.BAD_REQUEST, err);
                    return json("{\"ok\":true}");
                }
                requireOwner(me);
                if (post) return saveSettings(q);
                return settings();
            case "update": {
                // GET: the updater's state; POST action=check|install. Owners only: it replaces the app.
                requireOwner(me);
                if (post) {
                    if ("install".equals(q.get("action"))) Updater.install(ctx);
                    else Updater.check(ctx);
                }
                JSONObject o = new JSONObject();
                o.put("phase", Updater.phase.name());
                o.put("current", Updater.current(ctx));
                o.put("latest", Updater.latest);
                o.put("text", Updater.describe(ctx));
                return json(o.toString());
            }
            case "owners/revoke":
                requirePost(post);
                requireOwner(me);
                String id = q.get("id");
                if (prefs.removeOwner(id)) access.revokeOwner(id);
                return ok();
            case "disconnect":
                requirePost(post);
                Response r = ok();
                r.addHeader("Set-Cookie", SESSION_COOKIE + "=; Max-Age=0; Path=/");
                return r;
            default:
                return text(Response.Status.NOT_FOUND, "no such call");
        }
    }

    private Response settings() throws Exception {
        JSONObject o = new JSONObject();
        o.put("webPort", prefs.webPort());
        o.put("ftpPort", prefs.ftpPort());
        o.put("ftpUser", Prefs.FTP_USER);
        o.put("password", prefs.password());
        o.put("owners", prefs.publicOwners());
        o.put("hasOwnerPassword", prefs.hasOwnerPassword());
        return json(o.toString());
    }

    private Response saveSettings(Map<String, String> q) throws Exception {
        Integer web = q.containsKey("webPort") ? Integer.valueOf(q.get("webPort").trim()) : null;
        Integer ftp = q.containsKey("ftpPort") ? Integer.valueOf(q.get("ftpPort").trim()) : null;
        String pw = q.get("password");
        if (pw != null && pw.isEmpty()) pw = null;
        String opw = q.get("ownerPassword");
        if (opw != null && !opw.isEmpty() && (opw.length() < 8 || opw.length() > 64))
            return text(Response.Status.BAD_REQUEST, "The owner password needs 8 to 64 characters.");
        String err = prefs.update(web, ftp, pw);
        if (err == null && opw != null) err = prefs.setOwnerPassword(opw);
        if (err != null) return text(Response.Status.BAD_REQUEST, err);
        // Only the owner password changed: nothing to restart.
        if (web == null && ftp == null && pw == null) return json("{\"webPort\":" + prefs.webPort() + "}");
        // Restart after this response has gone out: the new port takes over from the one serving it.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(onSettingsChanged, 800);
        return json("{\"webPort\":" + prefs.webPort() + "}");
    }

    /** A request from a connected device that its role does not allow (403, not a server error). */
    private static final class Forbidden extends IOException {
        Forbidden(String message) {
            super(message);
        }
    }

    private void requireControl(Access.Session me) throws IOException {
        if (access.claim(me, false) != me) throw new Forbidden("Another device is controlling the TV.");
    }

    private static void requireOwner(Access.Session me) throws IOException {
        if (!me.owner) throw new Forbidden("Only an owner device can change settings.");
    }

    private Access.Session session(IHTTPSession s) {
        return access.authenticate(s.getCookies().read(SESSION_COOKIE), s.getCookies().read(OWNER_COOKIE));
    }

    // --- files

    private Response listFiles(String rel) throws Exception {
        File dir = resolve(rel);
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("cannot read this folder (storage permission?)");
        JSONArray arr = new JSONArray();
        java.util.Arrays.sort(files, (a, b) -> a.isDirectory() != b.isDirectory()
                ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            JSONObject o = new JSONObject();
            o.put("name", f.getName());
            o.put("dir", f.isDirectory());
            o.put("size", f.length());
            o.put("mtime", f.lastModified());
            arr.put(o);
        }
        JSONObject res = new JSONObject();
        res.put("path", relative(dir));
        res.put("files", arr);
        res.put("free", root.getUsableSpace());
        res.put("ftpPort", LinkService.ftpPort(prefs));
        return json(res.toString());
    }

    private Response download(String rel) throws Exception {
        File f = resolve(rel);
        if (!f.isFile()) throw new IOException("not a file");
        String mime = URLConnection.guessContentTypeFromName(f.getName());
        Response r = newFixedLengthResponse(Response.Status.OK, mime == null ? "application/octet-stream" : mime,
                new FileInputStream(f), f.length());
        r.addHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20"));
        return r;
    }

    /** PUT with the raw file as the body, streamed straight to disk (no temp copy, so large videos fit). */
    private Response upload(IHTTPSession s, String relDir, String name) throws Exception {
        if (s.getMethod() != Method.PUT) throw new IOException("use PUT");
        File dir = resolve(relDir);
        File dest = new File(dir, safeName(name));
        String lenHeader = s.getHeaders().get("content-length");
        long remaining = lenHeader == null ? -1 : Long.parseLong(lenHeader);
        if (remaining < 0) throw new IOException("missing Content-Length");
        // The partial file goes to the app's own storage: Android 11+ lets an app without All files access create
        // only media files in Movies, Music and Pictures, so a ".part" file there is refused.
        File tmpDir = ctx.getExternalCacheDir() != null ? ctx.getExternalCacheDir() : ctx.getCacheDir();
        File part = new File(tmpDir, "upload-" + System.nanoTime() + ".part");
        InputStream in = s.getInputStream();
        try (OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[256 * 1024];
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new IOException("upload cut off");
                out.write(buf, 0, n);
                remaining -= n;
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        try {
            place(part, dest);
        } finally {
            part.delete();
        }
        return ok();
    }

    /** Moves an upload into place: a rename, else a copy, else through the TV's own ADB (the shell user may write anywhere). */
    private void place(File part, File dest) throws IOException {
        if (dest.exists() && !dest.delete()) shellOrFail("rm -f " + q(dest), "could not replace " + dest.getName());
        if (part.renameTo(dest)) return;
        try (InputStream in = new java.io.FileInputStream(part); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return;
        } catch (IOException e) {
            dest.delete();
        }
        part.setReadable(true, false);
        shellOrFail("cp " + q(part) + " " + q(dest), "could not save the file in this folder");
        if (!dest.exists()) throw new IOException("could not save the file in this folder");
    }

    /** Runs a file command through the TV's ADB when Android refused it to the app; any output means it failed. */
    private void shellOrFail(String command, String error) throws IOException {
        String out;
        try {
            out = tv.shell(command + " 2>&1").trim();
        } catch (IOException e) {
            throw new IOException(error + ". Connect the remote control on the TV, which also allows file access.");
        }
        if (!out.isEmpty()) throw new IOException(error + ": " + out);
    }

    private static String q(File f) {
        return "'" + f.getAbsolutePath().replace("'", "'\\''") + "'";
    }

    private File resolve(String rel) throws IOException {
        if (rel == null) rel = "/";
        File f = new File(root, rel).getCanonicalFile();
        String base = root.getCanonicalPath();
        if (!f.getPath().equals(base) && !f.getPath().startsWith(base + File.separator))
            throw new IOException("outside storage");
        return f;
    }

    private String relative(File f) throws IOException {
        String p = f.getCanonicalPath().substring(root.getCanonicalPath().length());
        return p.isEmpty() ? "/" : p;
    }

    private static String safeName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
            throw new IOException("bad file name");
        return name;
    }

    private void deleteRecursively(File f) throws IOException {
        if (f.getCanonicalPath().equals(root.getCanonicalPath())) throw new IOException("refusing to delete storage root");
        File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (File c : children) deleteRecursively(c);
        if (!f.delete() && f.exists()) shellOrFail("rm -rf " + q(f), "could not delete " + f.getName());
    }

    // --- helpers

    private static void requirePost(boolean post) throws IOException {
        if (!post) throw new IOException("use POST");
    }

    private static String first(IHTTPSession s, String key) {
        java.util.List<String> v = s.getParameters().get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    /** Query string plus a url-encoded POST body, without NanoHTTPD's temp-file parsing. */
    private static Map<String, String> params(IHTTPSession s) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : s.getParameters().entrySet())
            if (!e.getValue().isEmpty()) out.put(e.getKey(), e.getValue().get(0));
        if (s.getMethod() == Method.POST) {
            String len = s.getHeaders().get("content-length");
            int n = len == null ? 0 : Integer.parseInt(len);
            if (n > 0 && n < 64 * 1024) {
                byte[] body = new byte[n];
                int read = 0;
                InputStream in = s.getInputStream();
                while (read < n) {
                    int r = in.read(body, read, n - read);
                    if (r < 0) break;
                    read += r;
                }
                for (String pair : new String(body, 0, read, StandardCharsets.UTF_8).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    out.put(java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
            }
        }
        return out;
    }

    private Response asset(String name, String mime, boolean cache) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return bytes(b.toByteArray(), mime, cache);
        }
    }

    private static Response bytes(byte[] data, String mime, boolean cache) {
        Response r = newFixedLengthResponse(Response.Status.OK, mime, new java.io.ByteArrayInputStream(data), data.length);
        r.addHeader("Cache-Control", cache ? "max-age=86400" : "no-store");
        return r;
    }

    private static Response json(String body) {
        return bytes(body.getBytes(StandardCharsets.UTF_8), "application/json", false);
    }

    private static Response closing(Response r) {
        r.closeConnection(true);
        return r;
    }

    private static Response ok() {
        return text(Response.Status.OK, "ok");
    }

    private static Response text(Response.Status status, String msg) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", msg);
    }

    static byte[] packet(byte kind, int flags, long pts, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(10 + data.length);
        b.put(kind).put((byte) flags).putLong(pts).put(data);
        return b.array();
    }
}
