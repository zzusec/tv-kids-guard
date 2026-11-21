package com.family.tvguard;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ParentWebSessionTest {
    private long now = 1000L;
    private final ParentWebSession sessions = new ParentWebSession(() -> now);

    @Test
    public void unguessableIndependentSessionAndCsrfTokensHaveBrowserCookieRestrictions() {
        ParentWebSession.Session session = sessions.issue("credential-v1");
        assertTrue(session.token.matches("[0-9a-f]{64}"));
        assertTrue(session.csrfToken.matches("[0-9a-f]{64}"));
        assertNotEquals(session.token, session.csrfToken);
        assertNotEquals(session.token, sessions.issue("credential-v1").token);
        assertTrue(ParentWebSession.cookie(session).contains("; HttpOnly; SameSite=Strict; Path=/"));
        assertSame(session, sessions.find(ParentWebSession.cookie(session), "credential-v1"));
        assertTrue(ParentWebSession.csrfMatches(session, session.csrfToken));
        assertFalse(ParentWebSession.csrfMatches(session, session.token));
        assertFalse(ParentWebSession.csrfMatches(null, session.csrfToken));
    }

    @Test
    public void browserActivityRefreshesIdleTimeButExpiredTokenCannotComeBack() {
        ParentWebSession.Session session = sessions.issue("v1");
        String cookie = ParentWebSession.cookie(session);
        now += ParentWebSession.IDLE_TIMEOUT_MILLIS - 1;
        assertSame(session, sessions.find(cookie, "v1"));
        sessions.recordAction(session);
        now += ParentWebSession.IDLE_TIMEOUT_MILLIS - 1;
        assertSame(session, sessions.find(cookie, "v1"));
        now += 1;
        assertNull(sessions.find(cookie, "v1"));
        sessions.recordAction(session);
        assertNull("An expired session cannot be revived by a late callback", sessions.find(cookie, "v1"));
        now = 1000L;
        assertNull(sessions.find(cookie, "v1"));
    }

    @Test
    public void pinChangesInvalidateAnExistingSession() {
        ParentWebSession.Session session = sessions.issue("v1");
        String cookie = ParentWebSession.cookie(session);
        assertNull(sessions.find(cookie, "v2"));
        assertNull(sessions.find(cookie, "v1"));
    }

    @Test
    public void logoutAndServiceRestartForgetAuthorization() {
        ParentWebSession.Session first = sessions.issue("v1");
        ParentWebSession.Session second = sessions.issue("v1");
        sessions.revoke(ParentWebSession.cookie(first));
        assertNull(sessions.find(ParentWebSession.cookie(first), "v1"));
        assertSame(second, sessions.find(ParentWebSession.cookie(second), "v1"));
        sessions.clear();
        assertNull(sessions.find(ParentWebSession.cookie(second), "v1"));
        assertTrue(ParentWebSession.expiredCookie().contains("Max-Age=0"));
    }

    @Test
    public void duplicateOrMalformedCookiesNeverAuthenticate() {
        ParentWebSession.Session session = sessions.issue("v1");
        String tokenCookie = ParentWebSession.COOKIE_NAME + "=" + session.token;
        assertNull(sessions.find(tokenCookie + "; " + tokenCookie, "v1"));
        assertNull(sessions.find(ParentWebSession.COOKIE_NAME + "=abc", "v1"));
        assertSame(session, sessions.find("other=value; " + tokenCookie, "v1"));
    }
}
