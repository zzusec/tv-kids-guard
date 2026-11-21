package com.family.tvguard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Browser-only authorization. It never grants a television-side AdminSession or parent grace. */
final class ParentWebSession {
    static final String COOKIE_NAME = "tvguard_parent";
    static final long IDLE_TIMEOUT_MILLIS = 30 * 60_000L;
    private static final int MAX_SESSIONS = 8;

    interface Clock {
        long now();
    }

    static final class Session {
        final String token;
        final String csrfToken;
        private final String credentialVersion;
        private long lastUsed;

        private Session(String token, String csrfToken, String credentialVersion, long now) {
            this.token = token;
            this.csrfToken = csrfToken;
            this.credentialVersion = credentialVersion;
            this.lastUsed = now;
        }
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    ParentWebSession() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    ParentWebSession(Clock clock) {
        this.clock = clock;
    }

    synchronized Session issue(String credentialVersion) {
        long now = clock.now();
        prune(now);
        while (sessions.size() >= MAX_SESSIONS) {
            sessions.remove(sessions.keySet().iterator().next());
        }
        Session session = new Session(randomToken(), randomToken(), credentialVersion, now);
        sessions.put(session.token, session);
        return session;
    }

    synchronized Session find(String cookieHeader, String credentialVersion) {
        long now = clock.now();
        prune(now);
        Session session = sessions.get(cookieToken(cookieHeader));
        if (session == null) return null;
        if (!same(session.credentialVersion, credentialVersion)) {
            sessions.remove(session.token);
            return null;
        }
        return session;
    }

    /** Only a successfully authorized user command, never polling/checking, renews idle time. */
    synchronized void recordAction(Session session) {
        long now = clock.now();
        prune(now);
        if (session != null && sessions.get(session.token) == session) session.lastUsed = now;
    }

    static boolean csrfMatches(Session session, String token) {
        return session != null && same(session.csrfToken, token);
    }

    synchronized void revoke(String cookieHeader) {
        sessions.remove(cookieToken(cookieHeader));
    }

    synchronized void clear() {
        sessions.clear();
    }

    static String cookie(Session session) {
        return COOKIE_NAME + "=" + session.token + "; HttpOnly; SameSite=Strict; Path=/";
    }

    static String expiredCookie() {
        return COOKIE_NAME + "=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/";
    }

    private void prune(long now) {
        Iterator<Session> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (now < session.lastUsed || now - session.lastUsed >= IDLE_TIMEOUT_MILLIS) {
                iterator.remove();
            }
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        char[] chars = new char[64];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = Character.forDigit(value >>> 4, 16);
            chars[i * 2 + 1] = Character.forDigit(value & 15, 16);
        }
        return new String(chars);
    }

    private static String cookieToken(String header) {
        if (header == null) return "";
        String token = "";
        boolean found = false;
        for (String part : header.split(";")) {
            String cookie = part.trim();
            if (cookie.startsWith(COOKIE_NAME + "=")) {
                if (found) return "";
                found = true;
                token = cookie.substring(COOKIE_NAME.length() + 1);
            }
        }
        return token.matches("[0-9a-f]{64}") ? token : "";
    }

    private static boolean same(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }
}
