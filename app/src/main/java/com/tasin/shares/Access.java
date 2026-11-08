package com.tasin.shares;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Who may control the TV.
 * - A new device asks to connect and gets a fresh one-time code; the TV shows the same code and someone at the
 *   TV allows or denies it. An allowed device gets a session that lasts while it stays connected.
 * - "Trust as owner" also gives that device an owner key (Prefs) so it reconnects without a code.
 * - One device controls at a time. An owner can take over from anyone else; the TV says so.
 */
final class Access {
    static final long REQUEST_TTL_MS = 120_000;
    /** A non-owner session survives a page reload or a network blip for this long, then needs a new code. */
    static final long RECONNECT_GRACE_MS = 60_000;
    /** A controller with no WebSocket open (the pop-out remote alone) keeps control this long after its last press. */
    static final long LEASE_MS = 45_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Session {
        final String token;
        final String name;
        final boolean owner;
        final String ownerId;
        long lastSeen = System.currentTimeMillis();
        int openSockets;

        Session(String token, String name, boolean owner, String ownerId) {
            this.token = token;
            this.name = name;
            this.owner = owner;
            this.ownerId = ownerId;
        }
    }

    static final class Request {
        final String id = Prefs.randomWord(16);
        final String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        final String name;
        final long created = System.currentTimeMillis();
        volatile String state = "pending"; // pending, approved, denied, expired
        volatile String sessionToken, ownerKey;

        Request(String name) {
            this.name = name;
        }

        boolean expired() {
            return System.currentTimeMillis() - created > REQUEST_TTL_MS;
        }
    }

    interface Listener {
        void onRequest(Request r);

        void onRequestClosed(Request r);

        /** controller may be null (nobody); takenOverFrom is set when an owner took over. */
        void onControllerChanged(Session controller, String takenOverFrom);

        void onKicked(Session kicked, Session by);
    }

    private final Prefs prefs;
    private final Listener listener;
    private final Map<String, Session> sessions = new HashMap<>();
    private Request pending;
    private Session controller;

    Access(Prefs prefs, Listener listener) {
        this.prefs = prefs;
        this.listener = listener;
    }

    // --- requests

    synchronized Request request(String name) throws IllegalStateException {
        if (pending != null && "pending".equals(pending.state) && !pending.expired())
            throw new IllegalStateException("Another device is waiting for approval on the TV. Try again in a minute.");
        pending = new Request(cleanName(name));
        listener.onRequest(pending);
        return pending;
    }

    synchronized Request find(String id) {
        if (pending == null || !pending.id.equals(id)) return null;
        if ("pending".equals(pending.state) && pending.expired()) {
            pending.state = "expired";
            listener.onRequestClosed(pending);
        }
        return pending;
    }

    synchronized Request pending() {
        return pending != null && "pending".equals(pending.state) && !pending.expired() ? pending : null;
    }

    /**
     * Called from the TV's approval screen, which only allows once: anyone sitting at the TV could otherwise make
     * their device an owner. Owners come from the owner password alone (ownerLogin).
     */
    synchronized void decide(String id, boolean allow) {
        Request r = find(id);
        if (r == null || !"pending".equals(r.state)) return;
        if (allow) {
            Session s = new Session(Prefs.randomWord(32), r.name, false, null);
            sessions.put(s.token, s);
            r.sessionToken = s.token;
            r.state = "approved";
        } else {
            r.state = "denied";
        }
        listener.onRequestClosed(r);
    }

    private int ownerPwFailures;
    private long ownerPwLockedUntil;

    /**
     * The owner password typed in a browser: trusts that browser as an owner. The only way to become one.
     * Returns {sessionToken, ownerKey}. Five wrong tries lock it for five minutes.
     */
    synchronized String[] ownerLogin(String name, String password) {
        long now = System.currentTimeMillis();
        if (now < ownerPwLockedUntil) throw new IllegalStateException("Too many wrong tries. Wait a few minutes.");
        if (!prefs.hasOwnerPassword()) throw new IllegalStateException("No owner password is set yet.");
        if (!prefs.checkOwnerPassword(password)) {
            if (++ownerPwFailures >= 5) {
                ownerPwFailures = 0;
                ownerPwLockedUntil = now + 5 * 60_000;
            }
            throw new IllegalStateException("Wrong owner password.");
        }
        ownerPwFailures = 0;
        name = name == null || name.trim().isEmpty() ? "Browser" : name.trim();
        if (name.length() > 40) name = name.substring(0, 40);
        String ownerKey = Prefs.randomWord(40);
        String ownerId = prefs.addOwner(name, ownerKey);
        Session s = new Session(Prefs.randomWord(32), name, true, ownerId);
        sessions.put(s.token, s);
        return new String[]{s.token, ownerKey};
    }

    // --- sessions

    /** The session behind a browser's cookies, creating one from an owner key; null when it must ask first. */
    synchronized Session authenticate(String sessionToken, String ownerKey) {
        expire();
        Session s = sessionToken == null ? null : sessions.get(sessionToken);
        if (s != null && s.owner && !prefs.hasOwner(s.ownerId)) {
            sessions.remove(s.token); // owner was revoked
            s = null;
        }
        if (s == null && ownerKey != null) {
            String ownerId = prefs.ownerIdForKey(ownerKey);
            // The browser lost its session cookie (the TV app restarted): every request would otherwise make a
            // new session, and the one holding control would not match the page's other requests.
            if (ownerId != null) {
                for (Session other : sessions.values()) if (ownerId.equals(other.ownerId)) s = other;
            }
            if (s == null && ownerId != null) {
                s = new Session(Prefs.randomWord(32), prefs.ownerName(ownerId), true, ownerId);
                sessions.put(s.token, s);
            }
        }
        if (s != null) {
            s.lastSeen = System.currentTimeMillis();
            if (s.owner) prefs.touchOwner(s.ownerId);
        }
        return s;
    }

    synchronized void socketOpened(Session s) {
        s.openSockets++;
        s.lastSeen = System.currentTimeMillis();
    }

    synchronized void socketClosed(Session s) {
        s.openSockets = Math.max(0, s.openSockets - 1);
        s.lastSeen = System.currentTimeMillis();
        if (s.openSockets == 0 && controller == s) setController(null, null);
    }

    /** Ends every session of a revoked owner, and drops it from control. */
    synchronized void revokeOwner(String ownerId) {
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            Session s = it.next();
            if (s.owner && ownerId.equals(s.ownerId)) {
                it.remove();
                if (controller == s) {
                    listener.onKicked(s, null);
                    setController(null, null);
                }
            }
        }
    }

    synchronized void revokeAll() {
        if (controller != null) listener.onKicked(controller, null);
        sessions.clear();
        setController(null, null);
    }

    // --- control

    synchronized Session controller() {
        expire();
        return controller;
    }

    /**
     * Makes s the controller if nobody else is. With takeOver, an owner replaces whoever controls.
     * Returns the session that keeps control (s on success).
     */
    synchronized Session claim(Session s, boolean takeOver) {
        expire();
        s.lastSeen = System.currentTimeMillis();
        if (controller == null || controller == s) {
            if (controller != s) setController(s, null);
            return s;
        }
        if (takeOver && s.owner) {
            Session old = controller;
            listener.onKicked(old, s);
            if (!old.owner) sessions.remove(old.token);
            setController(s, old.name);
            return s;
        }
        return controller;
    }

    private void setController(Session s, String takenOverFrom) {
        controller = s;
        listener.onControllerChanged(s, takenOverFrom);
    }

    private void expire() {
        long now = System.currentTimeMillis();
        if (controller != null && controller.openSockets == 0 && now - controller.lastSeen > LEASE_MS)
            setController(null, null);
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            Session s = it.next();
            if (!s.owner && s.openSockets == 0 && s != controller && now - s.lastSeen > RECONNECT_GRACE_MS) it.remove();
        }
    }

    private static String cleanName(String name) {
        if (name == null) name = "";
        name = name.replaceAll("[\\p{Cntrl}<>]", "").trim();
        if (name.length() > 40) name = name.substring(0, 40);
        return name.isEmpty() ? "Unnamed device" : name;
    }
}
