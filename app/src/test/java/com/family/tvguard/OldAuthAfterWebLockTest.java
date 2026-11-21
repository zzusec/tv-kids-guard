package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSettings;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** A web lock is a fresh PIN boundary even before Android pauses the previous auth window. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class OldAuthAfterWebLockTest {
    private static final String PIN = "135790";
    private Prefs prefs;
    private ParentWebController web;
    private ServiceController<GuardService> guard;
    private ActivityController<AdminAuthActivity> gate;

    @Before
    public void setUp() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(ParentWebCredentials.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword(PIN);
        prefs.setProtectionEnabled(true);
        AdminSession.revoke();
        // Like ParentWebControllerTest, avoid launching framework activities automatically. The
        // assertion deliberately delivers one old-window key before its pending pause transition.
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIsInteractive(false);
        ShadowSettings.setCanDrawOverlays(true);
        guard = Robolectric.buildService(GuardService.class).create();
        web = new ParentWebController(context);
        gate = Robolectric.buildActivity(AdminAuthActivity.class).setup().visible();
    }

    @After
    public void tearDown() {
        if (web != null) web.close();
        if (gate != null) gate.pause().stop().destroy();
        if (guard != null) guard.destroy();
        AdminSession.revoke();
    }

    @Test
    public void theLastDigitOfThePreviousAttemptCannotReleaseTheNewWebLock() throws Exception {
        digits("13579");

        lockFromWeb();
        digits("0");

        assertTrue("The new lock cannot accept the old window's five remembered digits",
                guard.get().isLocked());
        assertTrue(prefs.isGuardActive());
        assertFalse(prefs.isParentGraceDeferringLock());
        assertFalse(AdminSession.isActive());
        assertFalse(gate.get().isFinishing());
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
        assertEquals("Only the digit entered after 33 may remain", "●",
                ((TextView) gate.get().findViewById(R.id.lock_input)).getText().toString());
    }

    @Test
    public void enteringTheFullPinAfterTheNewLockStillAuthenticatesNormally() throws Exception {
        digits("13579");

        lockFromWeb();
        digits(PIN);

        assertFalse(guard.get().isLocked());
        assertTrue(prefs.isParentGraceDeferringLock());
        assertTrue(AdminSession.isActive());
        assertTrue(gate.get().isFinishing());
        assertEquals(0, prefs.getFailCount());
    }

    private void digits(String value) {
        for (char digit : value.toCharArray()) {
            int code = KeyEvent.KEYCODE_0 + digit - '0';
            gate.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            gate.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }
    }

    private void lockFromWeb() throws Exception {
        ParentHttpServer.Response login = web.handle(new ParentHttpServer.Request("POST", "/api/login",
                new JSONObject().put("password", PIN).toString(),
                Collections.emptyMap()));
        assertEquals(200, login.status);
        JSONObject session = new JSONObject(new String(login.body, StandardCharsets.UTF_8));
        String cookie = login.headers.get("Set-Cookie");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("cookie", cookie.substring(0, cookie.indexOf(';')));
        headers.put("x-csrf-token", session.getString("csrf"));
        ParentHttpServer.Response locked = web.handle(new ParentHttpServer.Request("POST",
                "/api/action", new JSONObject().put("code", "33").toString(), headers));
        assertEquals(200, locked.status);
        assertTrue(guard.get().isLocked());
        assertFalse(AdminSession.isActive());
    }
}
