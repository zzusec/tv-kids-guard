package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowSystemClock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** Narrow audit of browser authorization; all credentials are synthetic test fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class UnauthorizedAuthAuditTest {
    private static final String PIN = "135790";
    private static final String[] ACTIONS = {"00", "11", "12", "13", "22", "33", "44", "55", "99", "77"};
    private Application context;
    private Prefs prefs;
    private ParentWebController web;
    private ServiceController<GuardService> guard;

    @Before
    public void setUp() throws Exception {
        assertSame(Looper.getMainLooper(), Looper.myLooper());
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword(PIN);
        prefs.setUsedSeconds(1800L);
        prefs.setLocked(true);
        AdminSession.revoke();
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(false);
        ShadowSettings.setCanDrawOverlays(true);
        guard = Robolectric.buildService(GuardService.class).create();
        web = new ParentWebController(context);
    }

    @After
    public void tearDown() {
        if (web != null) web.close();
        if (guard != null) guard.destroy();
        AdminSession.revoke();
    }

    @Test
    public void unattendedStatePollingCannotRenewTheParentLoginIndefinitely() throws Exception {
        Map<String, String> browser = login();

        // app.js polls /api/state every five seconds while visible, even if nobody touches
        // the browser. Run only those reads for 31 minutes; no human action or new PIN occurs.
        for (int seconds = 5; seconds <= 31 * 60; seconds += 5) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
            ParentHttpServer.Response status = request("GET", "/api/state", null, browser);
            assertTrue("Polling must either report state or require a new login",
                    status.status == 200 || status.status == 401);
        }
        assertEquals(1800L, prefs.getUsedSeconds());
        assertFalse("Remote login must not create local authority", AdminSession.isActive());

        ParentHttpServer.Response clearUsage = request("POST", "/api/action",
                new JSONObject().put("code", "77"), browser);

        assertEquals("An unattended tab must not let a child clear viewing usage after the idle timeout",
                1800L, prefs.getUsedSeconds());
        assertEquals("A fresh parent login is required after thirty minutes without a human action",
                401, clearUsage.status);
        assertTrue(prefs.isGuardActive());
        assertFalse(prefs.isParentGraceActive());
    }

    @Test
    public void everyActionRejectsMissingOrForgedAuthorization() throws Exception {
        Map<String, String> forged = new LinkedHashMap<>();
        forged.put("cookie", ParentWebSession.COOKIE_NAME
                + "=0000000000000000000000000000000000000000000000000000000000000000");
        forged.put("x-csrf-token", "forged");
        for (String code : ACTIONS) {
            JSONObject input = actionInput(code);
            assertEquals(code + " without a session", 401,
                    request("POST", "/api/action", input, Collections.emptyMap()).status);
            assertEquals(code + " with a fabricated session", 401,
                    request("POST", "/api/action", input, forged).status);
        }
        assertUnchangedLockedState();
    }

    @Test
    public void everyActionRejectsAMissingOrWrongCsrfProof() throws Exception {
        Map<String, String> browser = login();
        Map<String, String> noCsrf = new LinkedHashMap<>(browser);
        noCsrf.remove("x-csrf-token");
        Map<String, String> wrongCsrf = new LinkedHashMap<>(browser);
        wrongCsrf.put("x-csrf-token", "incorrect");
        for (String code : ACTIONS) {
            JSONObject input = actionInput(code);
            assertEquals(code + " without CSRF", 403,
                    request("POST", "/api/action", input, noCsrf).status);
            assertEquals(code + " with wrong CSRF", 403,
                    request("POST", "/api/action", input, wrongCsrf).status);
        }
        assertUnchangedLockedState();
    }

    private JSONObject actionInput(String code) throws Exception {
        // Supply otherwise valid inputs, including the current PIN for the change operation,
        // so a rejection proves the session/CSRF boundary rather than an unrelated input error.
        return new JSONObject().put("code", code).put("minutes", 5)
                .put("oldPin", PIN).put("newPin", "246810").put("confirmPin", "246810");
    }

    private Map<String, String> login() throws Exception {
        ParentHttpServer.Response response = request("POST", "/api/login",
                new JSONObject().put("password", PIN), Collections.emptyMap());
        assertEquals(200, response.status);
        JSONObject session = new JSONObject(new String(response.body, StandardCharsets.UTF_8));
        String cookie = response.headers.get("Set-Cookie");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("cookie", cookie.substring(0, cookie.indexOf(';')));
        headers.put("x-csrf-token", session.getString("csrf"));
        return headers;
    }

    private ParentHttpServer.Response request(String method, String path, JSONObject input,
                                               Map<String, String> headers) throws Exception {
        return web.handle(new ParentHttpServer.Request(method, path,
                input == null ? "" : input.toString(), headers));
    }

    private void assertUnchangedLockedState() {
        assertTrue(prefs.checkPassword(PIN));
        assertTrue(prefs.isProtectionEnabled());
        assertEquals(1800L, prefs.getUsedSeconds());
        assertEquals(30, prefs.getDailyLimitMinutes());
        assertEquals(0L, prefs.getBonusSeconds());
        assertTrue(prefs.isGuardActive());
        assertFalse(prefs.isParentGraceActive());
        assertFalse(AdminSession.isActive());
        assertFalse(prefs.isSettingsGateFree());
    }
}
