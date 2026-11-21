package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.view.KeyEvent;

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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSettings;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** A factory-password setup page must not retain authority after a web parent configures the PIN. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class FactorySetupConcurrencyTest {
    private static final String REMOTE_PIN = "135790";
    private static final String SETUP_PIN = "246810";
    private Application context;
    private Prefs prefs;
    private ParentWebController web;
    private ActivityController<AdminAuthActivity> gate;
    private ServiceController<GuardService> guard;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(ParentWebCredentials.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setProtectionEnabled(false);
        AdminSession.revoke();
        web = new ParentWebController(context);
    }

    @After
    public void tearDown() {
        if (web != null) web.close();
        if (gate != null) gate.pause().stop().destroy();
        if (guard != null) guard.destroy();
        AdminSession.revoke();
    }

    @Test
    public void remoteConfigurationSynchronouslyRevokesFactoryProofBeforeTheGateExists()
            throws Exception {
        prefs.setProtectionEnabled(true);
        prefs.setLocked(true);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIsInteractive(false);
        ShadowSettings.setCanDrawOverlays(true);
        guard = Robolectric.buildService(GuardService.class).create();
        for (int i = 0; i < Prefs.DEFAULT_PASSWORD.length(); i++) guard.get().onDigit(0);
        assertFalse("The factory PIN advances setup without unlocking", guard.get().onOk());
        Intent pendingGate = shadowOf(context).getNextStartedActivity();
        assertNotNull(pendingGate);
        assertEquals(AdminAuthActivity.class.getName(), pendingGate.getComponent().getClassName());
        assertTrue(AdminSession.isActive());
        assertFalse("出厂密码不能发放系统设置通行", prefs.isParentPinWindowActive());
        prefs.noteSettingsPresence();
        assertTrue(prefs.isSettingsHoldActive());

        configurePinFromWeb();

        assertFalse("Factory proof must be revoked in the web write, before a gate can exist",
                AdminSession.isActive());
        assertFalse(prefs.isParentPinWindowActive());
        assertFalse(prefs.isSettingsHoldActive());
        gate = Robolectric.buildActivity(AdminAuthActivity.class, pendingGate).setup().visible();
        assertFalse("A delayed gate must require the newly configured PIN", gate.get().isFinishing());
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
        assertTrue(guard.get().isLocked());
    }

    @Test
    public void remoteConfigurationSynchronouslyRevokesAnExistingSetupGateSettingsPass()
            throws Exception {
        beginFactorySetup();
        prefs.noteSettingsPresence();
        assertFalse("出厂密码不能发放系统设置通行", prefs.isParentPinWindowActive());
        assertTrue(prefs.isSettingsHoldActive());

        configurePinFromWeb();

        // Deliberately no ticker, resume or subsequent input: the completed web write is itself
        // the boundary at which the old factory proof must stop authorizing settings.
        assertFalse(AdminSession.isActive());
        assertFalse(prefs.isParentPinWindowActive());
        assertFalse(prefs.isSettingsHoldActive());
        assertFalse(prefs.isSettingsGateFree());
        assertFalse(gate.get().isFinishing());
    }

    @Test
    public void changingAnAlreadyConfiguredPinRevokesOldTelevisionAuthorization()
            throws Exception {
        prefs.setPassword(REMOTE_PIN);
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate(REMOTE_PIN));
        AdminSession.grant();
        prefs.noteSettingsPresence();

        configurePinFromWeb(REMOTE_PIN, SETUP_PIN);

        assertFalse(AdminSession.isActive());
        assertFalse(prefs.isParentPinWindowActive());
        assertFalse(prefs.isSettingsHoldActive());
        assertTrue(prefs.checkPassword(SETUP_PIN));
    }

    @Test
    public void remoteConfigurationInvalidatesTheOpenNewPinStep() throws Exception {
        beginFactorySetup();
        configurePinFromWeb();

        digits(SETUP_PIN);
        digits(SETUP_PIN);

        assertRemotePinPreservedAndSetupNotAuthorized();
    }

    @Test
    public void remoteConfigurationInvalidatesTheOpenConfirmationStep() throws Exception {
        beginFactorySetup();
        digits(SETUP_PIN);
        configurePinFromWeb();

        digits(SETUP_PIN);

        assertRemotePinPreservedAndSetupNotAuthorized();
    }

    @Test
    public void setupHeartbeatRequiresNewVerificationAfterRemoteConfiguration() throws Exception {
        beginFactorySetup();
        configurePinFromWeb();

        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);

        assertRemotePinPreservedAndSetupNotAuthorized();
    }

    @Test
    public void aResumedSetupPageCannotTreatTheFactorySessionAsConfiguredPinAuthentication()
            throws Exception {
        beginFactorySetup();
        configurePinFromWeb();

        gate.get().onResume();

        assertRemotePinPreservedAndSetupNotAuthorized();
    }

    @Test
    public void correctlyEnteringTheRemotePinWorksAfterTheSetupProofWasInvalidated()
            throws Exception {
        beginFactorySetup();
        configurePinFromWeb();
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
        assertFalse(AdminSession.isActive());

        digits(REMOTE_PIN);

        assertTrue(gate.get().isFinishing());
        assertTrue(AdminSession.isActive());
        assertTrue(prefs.checkPassword(REMOTE_PIN));
        Intent panel = shadowOf(gate.get()).getNextStartedActivity();
        assertNotNull(panel);
        assertEquals(MainActivity.class.getName(), panel.getComponent().getClassName());
    }

    @Test
    public void ordinaryFactorySetupCanStillSaveAndOpenTheConfigurationPanel() {
        beginFactorySetup();

        digits(SETUP_PIN);
        digits(SETUP_PIN);

        assertTrue(prefs.checkPassword(SETUP_PIN));
        assertTrue(AdminSession.isActive());
        assertTrue(gate.get().isFinishing());
        Intent panel = shadowOf(gate.get()).getNextStartedActivity();
        assertNotNull(panel);
        assertEquals(MainActivity.class.getName(), panel.getComponent().getClassName());
    }

    @Test
    public void aQueuedSetupHeartbeatCannotRevokeItsOwnSuccessfulPinSave() {
        beginFactorySetup();
        digits(SETUP_PIN);
        digits(SETUP_PIN);
        assertTrue(gate.get().isFinishing());

        // Activity launch/finish is asynchronous; a due heartbeat can run before onPause.
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);

        assertTrue(prefs.checkPassword(SETUP_PIN));
        assertTrue("本机完成首次设密不是远端改密，交接期间不能撤销自身会话",
                AdminSession.isActive());
        assertFalse("出厂密码不能发放系统设置通行", prefs.isParentPinWindowActive());
        assertFalse("首次设密本身仍不得授予时长宽限", prefs.isParentGraceActive());
        Intent panel = shadowOf(gate.get()).getNextStartedActivity();
        assertNotNull(panel);
        try (ActivityController<MainActivity> launched = Robolectric.buildActivity(MainActivity.class,
                panel).create().start().resume().visible().windowFocusChanged(true)) {
            assertTrue(MainActivity.isAuthorizedVisible());
            assertNull("已完成的首次设密不应立即再要求验证", shadowOf(launched.get()).getNextStartedActivity());
        }
    }

    private void beginFactorySetup() {
        gate = Robolectric.buildActivity(AdminAuthActivity.class).setup().visible();
        digits(Prefs.DEFAULT_PASSWORD);
        assertTrue(AdminSession.isActive());
        assertTrue(prefs.needsPasswordSetup());
        assertFalse(gate.get().isFinishing());
    }

    private void configurePinFromWeb() throws Exception {
        configurePinFromWeb(Prefs.DEFAULT_PASSWORD, REMOTE_PIN);
    }

    private void configurePinFromWeb(String oldPin, String newPin) throws Exception {
        ParentHttpServer.Response login = web.handle(new ParentHttpServer.Request("POST", "/api/login",
                new JSONObject().put("password", oldPin).toString(),
                Collections.emptyMap()));
        assertEquals(200, login.status);
        JSONObject session = new JSONObject(new String(login.body, StandardCharsets.UTF_8));
        String cookie = login.headers.get("Set-Cookie");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("cookie", cookie.substring(0, cookie.indexOf(';')));
        headers.put("x-csrf-token", session.getString("csrf"));
        ParentHttpServer.Response changed = web.handle(new ParentHttpServer.Request("POST",
                "/api/action", new JSONObject().put("code", "11")
                        .put("oldPin", oldPin).put("newPin", newPin)
                        .put("confirmPin", newPin).toString(), headers));
        assertEquals(new String(changed.body, StandardCharsets.UTF_8), 200, changed.status);
        assertTrue(prefs.checkPassword(newPin));
    }

    private void digits(String value) {
        for (char digit : value.toCharArray()) {
            int code = KeyEvent.KEYCODE_0 + digit - '0';
            gate.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            gate.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }
    }

    private void assertRemotePinPreservedAndSetupNotAuthorized() {
        assertTrue("The native setup must preserve the PIN configured by the web parent",
                prefs.checkPassword(REMOTE_PIN));
        assertFalse(prefs.checkPassword(SETUP_PIN));
        assertFalse("A factory-password proof is no longer current after remote configuration",
                AdminSession.isActive());
        assertFalse("The stale factory proof must not retain the settings PIN window",
                prefs.isParentPinWindowActive());
        assertFalse(gate.get().isFinishing());
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
    }
}
