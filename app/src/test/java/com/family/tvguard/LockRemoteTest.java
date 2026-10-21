package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.InputEvent;
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
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 28, 30, 33, 34}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class LockRemoteTest {
    private Prefs prefs;
    private ServiceController<GuardService> serviceController;
    private ActivityController<LockActivity> controller;
    private GuardService service;
    private LockActivity activity;

    @Before
    public void setUp() {
        Application context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
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
    public void standbySuppressesLockLaunchAndLitScreenRestoresIt() {
        Application context = RuntimeEnvironment.getApplication();
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        while (shadowOf(context).getNextStartedActivity() != null) {
            // Ignore lock launches made while creating the guard service.
        }
        shadowOf(power).setIsScreenOn(false);
        try {
            LockActivity.launch(context);
            assertNull(shadowOf(context).getNextStartedActivity());
            assertTrue(service.isLocked());
            assertFalse(prefs.isUnlockedToday());

            shadowOf(power).setIsScreenOn(true);
            LockActivity.launch(context);
            assertEquals(LockActivity.class.getName(), shadowOf(context).getNextStartedActivity()
                    .getComponent().getClassName());
        } finally {
            shadowOf(power).setIsScreenOn(true);
        }
    }

    @Test
    public void physicalDigitsAndOkUnlockWithTheParentPin() {
        for (char digit : "135790".toCharArray()) {
            press(KeyEvent.KEYCODE_0 + digit - '0');
        }
        assertFalse(service.isLocked());
        assertTrue(prefs.isUnlockedToday());
        assertEquals(1800L, prefs.getUsedSeconds());
    }

    @Test
    public void deleteOnlyEditsInputAndCannotResetTheFailureCounter() {
        prefs.registerFailedAttempt();
        prefs.registerFailedAttempt();
        press(KeyEvent.KEYCODE_1);
        press(KeyEvent.KEYCODE_3);
        assertEquals("13", service.lockInput());

        press(KeyEvent.KEYCODE_DEL);

        assertEquals("1", service.lockInput());
        assertEquals(2, prefs.getFailCount());
        assertTrue(service.isLocked());
        press(KeyEvent.KEYCODE_BACK);
        assertTrue(prefs.isGuardActive());
        assertFalse(prefs.isUnlockedToday());
    }

    @Test
    public void emptyOkOpensTheParentGateWithoutUnlockingQuota() {
        // No on-screen controls: an empty buffer plus OK is the documented parent entry.
        assertNull(activity.getWindow().getDecorView().findViewWithTag("parent_manage"));
        press(KeyEvent.KEYCODE_DPAD_CENTER);

        assertNotNull(shadowOf(activity).getNextStartedActivity());
        assertTrue(service.isLocked());
        assertFalse(prefs.isUnlockedToday());
        assertFalse(AdminSession.isActive());
    }

    private void select(String tag) {
        View target = activity.getWindow().getDecorView().findViewWithTag(tag);
        assertNotNull(target);
        assertTrue(target.isEnabled());
        View focused = activity.getWindow().getDecorView().findFocus();
        assertNotNull(focused);
        int[] directions = {View.FOCUS_UP, View.FOCUS_DOWN, View.FOCUS_LEFT, View.FOCUS_RIGHT};
        int[] codes = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
        Queue<Step> queue = new ArrayDeque<>();
        Set<View> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        queue.add(new Step(focused, new ArrayList<>()));
        seen.add(focused);
        List<Integer> path = null;
        while (!queue.isEmpty()) {
            Step step = queue.remove();
            if (step.view == target) {
                path = step.path;
                break;
            }
            for (int i = 0; i < directions.length; i++) {
                View next = step.view.focusSearch(directions[i]);
                if (next != null && next.isEnabled() && next.isShown() && seen.add(next)) {
                    List<Integer> nextPath = new ArrayList<>(step.path);
                    nextPath.add(codes[i]);
                    queue.add(new Step(next, nextPath));
                }
            }
        }
        assertNotNull("No D-pad focus path to " + tag, path);
        for (int code : path) press(code);
        assertEquals(target, activity.getWindow().getDecorView().findFocus());
        press(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    private void press(int code) {
        long now = SystemClock.uptimeMillis();
        Object root = ReflectionHelpers.callInstanceMethod(activity.getWindow().getDecorView(),
                "getViewRootImpl");
        if (RuntimeEnvironment.getApiLevel() == 19) {
            // KitKat drops events when Robolectric's unconnected IME reports NOT_HANDLED.
            ReflectionHelpers.setField(root, "mLastWasImTarget", false);
        }
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            ReflectionHelpers.callInstanceMethod(root, "dispatchInputEvent",
                    ReflectionHelpers.ClassParameter.from(InputEvent.class,
                            new KeyEvent(now, now, action, code, 0)));
            ShadowLooper.idleMainLooper();
        }
    }

    private static final class Step {
        final View view;
        final List<Integer> path;

        Step(View view, List<Integer> path) {
            this.view = view;
            this.path = path;
        }
    }
}
