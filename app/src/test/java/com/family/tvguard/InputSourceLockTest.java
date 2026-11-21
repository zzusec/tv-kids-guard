package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

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

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Input switching must not reach the system while the quota lock is active, even off its window. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class InputSourceLockTest {
    private static final int[] ESCAPE_KEYS = {
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_TV_INPUT_HDMI_1,
            KeyEvent.KEYCODE_TV_INPUT_HDMI_2, KeyEvent.KEYCODE_TV_INPUT_HDMI_3,
            KeyEvent.KEYCODE_TV_INPUT_HDMI_4, KeyEvent.KEYCODE_TV_INPUT_COMPOSITE_1,
            KeyEvent.KEYCODE_TV_INPUT_COMPONENT_1, KeyEvent.KEYCODE_TV_INPUT_VGA_1,
            KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    };
    private Application context;
    private Prefs prefs;
    private ServiceController<AppLockService> controller;
    private AppLockService watcher;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        prefs.setUsedSeconds(1800L);
        prefs.setLocked(true);
        AdminSession.revoke();
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(true);
        controller = Robolectric.buildService(AppLockService.class).create();
        watcher = controller.get();
        watcher.onServiceConnected();
        drain();
    }

    @After
    public void tearDown() {
        controller.destroy();
        AdminSession.revoke();
    }

    @Test
    public void sourceAndEscapeKeyPairsAreConsumedEvenWhileTheLauncherOwnsFocus() {
        window("com.gathin.tvhome");
        for (int key : ESCAPE_KEYS) {
            assertTrue("Locked DOWN must not reach the source/system handler: " + key,
                    watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)));
            assertTrue("Locked UP must not complete the system action: " + key,
                    watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key)));
        }
        assertTrue(prefs.isGuardActive());
        assertFalse(AdminSession.isActive());
    }

    @Test
    public void returningFromHdmiOrTheSystemSoundDialogCannotAuthorizeTheLauncher() {
        for (String name : new String[]{"com.sony.dtv.tvx", "com.android.systemui", "com.gathin.tvhome"}) {
            drain();
            window(name);
            Intent next = shadowOf(context).getNextStartedActivity();
            assertNotNull("Every outside window must reassert the spent quota: " + name, next);
            assertEquals(LockActivity.class.getName(), next.getComponent().getClassName());
            assertTrue(prefs.isGuardActive());
            assertFalse(prefs.isParentGraceDeferringLock());
        }
    }

    @Test
    public void powerVolumeAndPasswordInputRemainAvailableWhileLocked() {
        for (int key : new int[]{KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_1,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK}) {
            assertFalse(watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)));
            assertFalse(watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key)));
        }
        assertTrue(prefs.isGuardActive());
    }

    @Test
    public void unspentQuotaAndFreshParentReleaseStillAllowInputSwitching() {
        prefs.setLocked(false);
        prefs.setUsedSeconds(0L);
        for (int key : ESCAPE_KEYS) assertFalse(watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)));
        prefs.setUsedSeconds(1800L);
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate("135790"));
        prefs.startParentGrace(true);
        for (int key : ESCAPE_KEYS) assertFalse(watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)));
        prefs.endParentGrace();
        prefs.setProtectionEnabled(false);
        assertFalse(watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TV_INPUT_HDMI_4)));
    }

    @Test
    public void authorizedMaintenanceKeepsSystemControlsAvailable() {
        AdminSession.grant();
        assertTrue(AdminSession.grantMaintenance());
        for (int key : ESCAPE_KEYS) assertFalse(watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)));
    }

    private void window(String name) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(name);
        watcher.onAccessibilityEvent(event);
        event.recycle();
    }

    private void drain() {
        while (shadowOf(context).getNextStartedActivity() != null) { /* setup */ }
    }
}
