package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.view.KeyEvent;
import android.view.View;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Repeated OK presses on the same focused key must keep appending digits. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class LockRepeatedDigitTest {
    private ServiceController<GuardService> serviceController;
    private ActivityController<LockActivity> controller;
    private GuardService service;
    private LockActivity activity;

    @Before
    public void setUp() {
        Application context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs prefs = new Prefs(context);
        prefs.setPassword("774109");
        prefs.setUsedSeconds(1800L);
        prefs.setLocked(true);
        AdminSession.revoke();
        serviceController = Robolectric.buildService(GuardService.class).create();
        service = serviceController.get();
        controller = Robolectric.buildActivity(LockActivity.class).create().start().resume()
                .visible().windowFocusChanged(true);
        activity = controller.get();
        ShadowLooper.idleMainLooper();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        serviceController.destroy();
        AdminSession.revoke();
    }

    @Test
    public void partialBufferIsNotSubmittedByOk() {
        service.onDigit(7);
        service.onDigit(7);
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("77", service.lockInput());
        assertTrue(service.isLocked());
    }

    @Test
    public void repeatedPhysicalDigitsKeepAppending() {
        for (int i = 1; i <= 5; i++) {
            press(KeyEvent.KEYCODE_1);
            assertEquals("after press " + i, repeat('1', i), service.lockInput());
        }
    }

    private String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private void press(int code) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        ShadowLooper.idleMainLooper();
    }
}
