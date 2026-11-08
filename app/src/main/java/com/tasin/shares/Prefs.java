package com.tasin.shares;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Ports, the app password (FTP login and web settings) and the trusted owner devices. */
final class Prefs {
    static final int DEFAULT_WEB_PORT = 8080;
    static final int DEFAULT_FTP_PORT = 2121;
    static final String FTP_USER = "tv";

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("tvlink", Context.MODE_PRIVATE);
        if (!sp.contains("password")) {
            // Version 1 kept a PIN and a separate FTP password; keep the FTP password the owner already uses.
            String old = sp.getString("ftp", null);
            sp.edit().putString("password", old != null ? old : randomWord(10)).remove("pin").remove("sessions").apply();
        }
    }

    int webPort() {
        return sp.getInt("webPort", DEFAULT_WEB_PORT);
    }

    int ftpPort() {
        return sp.getInt("ftpPort", DEFAULT_FTP_PORT);
    }

    String password() {
        return sp.getString("password", "");
    }

    /** The colour theme of the TV screens, and the web page's default. */
    Theme theme() {
        return Theme.of(sp.getString("theme", "ember"));
    }

    void setTheme(String id) {
        sp.edit().putString("theme", Theme.of(id).id).apply();
    }

    /** Validates and saves; returns an error message, or null when saved. */
    String update(Integer webPort, Integer ftpPort, String password) {
        int w = webPort != null ? webPort : webPort();
        int f = ftpPort != null ? ftpPort : ftpPort();
        if (w < 1024 || w > 65535 || f < 1024 || f > 65535) return "Ports must be between 1024 and 65535.";
        if (w == f) return "The web port and the FTP port must differ.";
        // FTP passive data uses the next nine ports after the FTP port.
        if (w > f && w <= f + 9) return "The web port must not be one of the FTP data ports (" + (f + 1) + "-" + (f + 9) + ").";
        if (f + 9 > 65535) return "The FTP port is too high (it needs nine ports after it).";
        if (password != null && (password.length() < 6 || password.length() > 64 || password.contains(" ")))
            return "The password needs 6 to 64 characters and no spaces.";
        SharedPreferences.Editor e = sp.edit().putInt("webPort", w).putInt("ftpPort", f);
        if (password != null) e.putString("password", password);
        e.apply();
        return null;
    }

    // --- owner password: lets a browser become an owner without the TV; only a salted hash is stored

    boolean hasOwnerPassword() {
        return !sp.getString("ownerPwHash", "").isEmpty();
    }

    /** Empty clears it; returns an error message, or null when saved. */
    String setOwnerPassword(String password) {
        if (password.isEmpty()) {
            sp.edit().remove("ownerPwHash").remove("ownerPwSalt").apply();
            return null;
        }
        if (password.length() < 8 || password.length() > 64) return "The owner password needs 8 to 64 characters.";
        String salt = randomWord(16);
        sp.edit().putString("ownerPwSalt", salt).putString("ownerPwHash", hash(salt + password)).apply();
        return null;
    }

    boolean checkOwnerPassword(String password) {
        String h = sp.getString("ownerPwHash", "");
        if (h.isEmpty() || password == null) return false;
        return MessageDigest.isEqual(h.getBytes(), hash(sp.getString("ownerPwSalt", "") + password).getBytes());
    }

    void newRandomPassword() {
        sp.edit().putString("password", randomWord(10)).apply();
    }

    // --- owners: {id, name, keyHash, created, lastSeen}; only a hash of each owner key is stored

    synchronized String addOwner(String name, String key) {
        JSONArray owners = owners();
        String id = randomWord(8);
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("keyHash", hash(key));
            o.put("created", System.currentTimeMillis());
            o.put("lastSeen", System.currentTimeMillis());
            owners.put(o);
        } catch (JSONException ignored) {
        }
        sp.edit().putString("owners", owners.toString()).apply();
        return id;
    }

    synchronized String ownerIdForKey(String key) {
        if (key == null || key.isEmpty()) return null;
        String h = hash(key);
        JSONArray owners = owners();
        for (int i = 0; i < owners.length(); i++) {
            JSONObject o = owners.optJSONObject(i);
            if (o != null && MessageDigest.isEqual(h.getBytes(), o.optString("keyHash").getBytes())) return o.optString("id");
        }
        return null;
    }

    synchronized boolean hasOwner(String id) {
        return find(id) != null;
    }

    synchronized String ownerName(String id) {
        JSONObject o = find(id);
        return o == null ? "Owner" : o.optString("name", "Owner");
    }

    synchronized void touchOwner(String id) {
        JSONArray owners = owners();
        for (int i = 0; i < owners.length(); i++) {
            JSONObject o = owners.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                long now = System.currentTimeMillis();
                if (now - o.optLong("lastSeen") < 60_000) return; // don't write on every request
                try {
                    o.put("lastSeen", now);
                } catch (JSONException ignored) {
                }
            }
        }
        sp.edit().putString("owners", owners.toString()).apply();
    }

    synchronized boolean removeOwner(String id) {
        JSONArray owners = owners(), kept = new JSONArray();
        boolean found = false;
        for (int i = 0; i < owners.length(); i++) {
            JSONObject o = owners.optJSONObject(i);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) found = true;
            else kept.put(o);
        }
        sp.edit().putString("owners", kept.toString()).apply();
        return found;
    }

    synchronized void removeAllOwners() {
        sp.edit().putString("owners", "[]").apply();
    }

    /** Owners without their key hashes, for the settings page. */
    synchronized JSONArray publicOwners() {
        JSONArray out = new JSONArray(), owners = owners();
        for (int i = 0; i < owners.length(); i++) {
            JSONObject o = owners.optJSONObject(i);
            if (o == null) continue;
            JSONObject p = new JSONObject();
            try {
                p.put("id", o.optString("id"));
                p.put("name", o.optString("name"));
                p.put("created", o.optLong("created"));
                p.put("lastSeen", o.optLong("lastSeen"));
            } catch (JSONException ignored) {
            }
            out.put(p);
        }
        return out;
    }

    synchronized int ownerCount() {
        return owners().length();
    }

    private JSONObject find(String id) {
        if (id == null) return null;
        JSONArray owners = owners();
        for (int i = 0; i < owners.length(); i++) {
            JSONObject o = owners.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    private JSONArray owners() {
        try {
            return new JSONArray(sp.getString("owners", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String randomWord(int n) {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return b.toString();
    }
}
