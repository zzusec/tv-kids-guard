package com.family.tvguard;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

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
import org.robolectric.shadows.ShadowViewRootImpl;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class RemoteInputScreensTest {
    private Context context;
    private Prefs prefs;
    private ActivityController<? extends Activity> controller;
    private ServiceController<GuardService> service;
    private PinDialog dialog;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", 0).edit().clear().commit();
        context.getSharedPreferences("tvguard_remote", 0).edit().clear()
                .putBoolean("show_keypad", false).commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        AdminSession.revoke();
    }

    @After
    public void tearDown() {
        if (dialog != null) dialog.dismiss();
        if (controller != null) controller.pause().stop().destroy();
        if (service != null) service.destroy();
        context.getSharedPreferences("tvguard_remote", 0).edit().clear().commit();
        AdminSession.revoke();
    }

    @Test
    public void physicalGateHasNoNumberGridAndKeepsMaskedDirectEntry() {
        AdminAuthActivity activity = startGate();
        assertEquals(View.GONE, activity.findViewById(R.id.keypad_column).getVisibility());
        assertFalse(activity.findViewById(android.R.id.content).findViewWithTag("key_1").isShown());
        press(activity.getWindow(), KeyEvent.KEYCODE_1);
        press(activity.getWindow(), KeyEvent.KEYCODE_3);
        assertEquals("●●", ((TextView) activity.findViewById(R.id.lock_input)).getText().toString());
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_LEFT);
        digits(activity.getWindow(), "35790");
        assertTrue(AdminSession.isActive());
        assertTrue(activity.isFinishing());
        assertFalse(prefs.isUnlockedToday());
    }

    @Test
    public void numberlessRemoteCanRecoverFromAMisreportedPhysicalModeBeforeAuthentication() {
        AdminAuthActivity activity = startGate();
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("keypad_toggle", activity.getWindow().getDecorView().findFocus().getTag());
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(activity.findViewById(android.R.id.content).findViewWithTag("key_1").isShown());
        assertEquals("key_1", activity.getWindow().getDecorView().findFocus().getTag());
        assertFalse(AdminSession.isActive());
        assertEquals(0, prefs.getFailCount());
        assertTrue(prefs.checkPassword("135790"));
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
        ScreenRemote.digits(activity, "135790");
        assertTrue(AdminSession.isActive());
    }

    @Test
    public void lockModeToggleNeitherUnlocksNorGrantsParentAuthorization() {
        prefs.setUsedSeconds(1800L);
        prefs.setLocked(true);
        service = Robolectric.buildService(GuardService.class).create();
        controller = Robolectric.buildActivity(LockActivity.class).setup().visible()
                .windowFocusChanged(true);
        ShadowLooper.idleMainLooper();
        Activity activity = controller.get();
        assertFalse(activity.getWindow().getDecorView().findViewWithTag("key_1").isShown());
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("keypad_toggle", activity.getWindow().getDecorView().findFocus().getTag());
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(activity.getWindow().getDecorView().findViewWithTag("key_1").isShown());
        assertTrue(service.get().isLocked());
        assertFalse(prefs.isUnlockedToday());
        assertFalse(AdminSession.isActive());
        assertEquals(1800L, prefs.getUsedSeconds());
        ScreenRemote.digits(activity, "135790");
        assertTrue(prefs.isUnlockedToday());
    }

    @Test
    public void physicalDurationStillDeletesWithLeftAndSubmitsWithOk() {
        List<String> values = new ArrayList<>();
        showDialog(values);
        assertFalse(dialog.getWindow().getDecorView().findViewWithTag("key_1").isShown());
        digits(dialog.getWindow(), "1441");
        press(dialog.getWindow(), KeyEvent.KEYCODE_DPAD_LEFT);
        digits(dialog.getWindow(), "0");
        press(dialog.getWindow(), KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(List.of("1440"), values);
    }

    @Test
    public void dialogChoiceSurvivesReopenAndAnAlreadyCreatedGateRefreshesIt() {
        AdminAuthActivity activity = startGate();
        List<String> values = new ArrayList<>();
        showDialog(values);
        press(dialog.getWindow(), KeyEvent.KEYCODE_DPAD_DOWN);
        press(dialog.getWindow(), KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(dialog.getWindow().getDecorView().findViewWithTag("key_1").isShown());
        dialog.dismiss();
        showDialog(values);
        assertTrue(dialog.getWindow().getDecorView().findViewWithTag("key_1").isShown());
        dialog.dismiss();
        controller.pause().resume();
        ShadowLooper.idleMainLooper();
        assertTrue(activity.getWindow().getDecorView().findViewWithTag("key_1").isShown());
        assertFalse(AdminSession.isActive());
        assertTrue(values.isEmpty());
    }

    private AdminAuthActivity startGate() {
        ActivityController<AdminAuthActivity> gate = Robolectric.buildActivity(AdminAuthActivity.class)
                .setup().visible().windowFocusChanged(true);
        controller = gate;
        ShadowLooper.idleMainLooper();
        return gate.get();
    }

    private void showDialog(List<String> values) {
        dialog = new PinDialog(context, "每日时长", "输入 1-1440 分钟", 4, false, values::add);
        dialog.show();
        ShadowLooper.idleMainLooper();
    }

    private void digits(Window window, String value) {
        for (char digit : value.toCharArray()) press(window, KeyEvent.KEYCODE_0 + digit - '0');
    }

    private void press(Window window, int code) {
        Object root = ReflectionHelpers.callInstanceMethod(window.getDecorView(), "getViewRootImpl");
        assertNotNull(root);
        if (!window.getDecorView().hasWindowFocus()) {
            ((ShadowViewRootImpl) Shadow.extract(root)).callWindowFocusChanged(true);
            ShadowLooper.idleMainLooper();
        }
        if (RuntimeEnvironment.getApiLevel() == 19) ReflectionHelpers.setField(root, "mLastWasImTarget", false);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            ReflectionHelpers.callInstanceMethod(root, "dispatchInputEvent",
                    ReflectionHelpers.ClassParameter.from(InputEvent.class,
                            new KeyEvent(now, now, action, code, 0)));
            ShadowLooper.idleMainLooper();
        }
    }
}
