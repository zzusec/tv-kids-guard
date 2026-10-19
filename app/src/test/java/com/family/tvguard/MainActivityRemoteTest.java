package com.family.tvguard;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowViewRootImpl;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33}, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityRemoteTest {
    private Application context;
    private Prefs prefs;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("tvguard_updates", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        AdminSession.revoke();
        AdminSession.grant();
    }

    @After
    public void tearDown() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        UpdateInstaller.cancel(context);
        AdminSession.revoke();
    }

    @Test
    public void actualDpadMovesFocusAndOkActivatesOnlyOnRelease() {
        startPanel(false);
        // Visible order with protection on: 00 11 12 13 22 33 44 99.
        assertFocused("12");
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_UP);
        assertFocused("11");
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_DOWN);
        assertFocused("12");
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocused("13");
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_LEFT);
        assertFocused("12");

        send(activity.getWindow(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER);
        assertNull(ShadowDialog.getLatestDialog());
        send(activity.getWindow(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER);

        assertTrue(latestPinDialog().isShowing());
        assertTrue(AdminSession.isActive());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
    }

    @Test
    public void dpadOnlyCanOpenDailyLimitEnter45AndSaveWithoutNumberKeys() {
        startPanel(false);
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_CENTER);
        PinDialog dialog = latestPinDialog();
        Window window = dialog.getWindow();
        // Enter navigation mode, then let ViewRoot perform every focus move.
        press(window, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(window, KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals("key_1", dialog.getCurrentFocus().getTag());
        press(window, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("key_4", dialog.getCurrentFocus().getTag());
        press(window, KeyEvent.KEYCODE_DPAD_CENTER);
        press(window, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("key_5", dialog.getCurrentFocus().getTag());
        press(window, KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(hasExactText(window.getDecorView(), "45"));
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
        press(window, KeyEvent.KEYCODE_DPAD_DOWN);
        press(window, KeyEvent.KEYCODE_DPAD_DOWN);
        press(window, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("key_confirm", dialog.getCurrentFocus().getTag());
        press(window, KeyEvent.KEYCODE_DPAD_CENTER);

        assertFalse(dialog.isShowing());
        assertEquals(45, prefs.getDailyLimitMinutes());
        assertFalse(prefs.isProtectionEnabled());
        assertTrue(((TextView) activity.findViewById(R.id.status_text)).getText().toString()
                .contains("每日限额：45 分钟"));
    }

    @Test
    public void statusRefreshKeepsTheSameButtonsAndSelectedAction() {
        startPanel(true);
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocused("13");
        ViewGroup menu = activity.findViewById(R.id.menu_actions);
        View[] original = menuChildren(menu);
        prefs.setUsedSeconds(123L);

        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);

        assertFocused("13");
        View[] refreshed = menuChildren(menu);
        assertEquals(original.length, refreshed.length);
        for (int i = 0; i < original.length; i++) {
            assertSame(original[i], refreshed[i]);
        }
        assertTrue(((TextView) activity.findViewById(R.id.status_text)).getText().toString()
                .contains("今日已用：2 分 3 秒"));
    }

    @Test
    public void dpadSkipsHiddenActionsAndRecoversFocusOnlyWhenCurrentActionIsHidden() {
        startPanel(true);
        // Grid order: 00 11 / 12 13 / 22 33 / 44 99.
        for (String code : new String[] {"22", "33", "44"}) {
            focusAction(code);
            assertFocused(code);
        }
        Button original = action("44");
        prefs.setProtectionEnabled(false);

        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);

        assertSame(original, action("44"));
        assertFalse(original.isShown());
        assertFocused("12");
        // 22 is hidden now; focus skips it and lands on 13, then on 99.
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_DOWN);
        assertFocused("13");
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_DOWN);
        assertFocused("99");
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(prefs.isProtectionEnabled());
        assertFalse(action("99").isShown());
        assertFocused("12");
    }

    @Test
    public void passwordActionStillRequiresSecondMaskedPin() {
        startPanel(false);
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_UP);
        assertFocused("11");
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_CENTER);
        PinDialog dialog = latestPinDialog();
        press(dialog.getWindow(), KeyEvent.KEYCODE_1);
        press(dialog.getWindow(), KeyEvent.KEYCODE_3);

        assertTrue(hasExactText(dialog.getWindow().getDecorView(), "●●"));
        assertFalse(hasExactText(dialog.getWindow().getDecorView(), "13"));
        assertTrue(prefs.checkPassword("135790"));
        assertTrue(hasExactText(dialog.getWindow().getDecorView(), "再次验证家长密码"));
    }

    @Test
    public void revokedParentCannotActivateTheFocusedAction() {
        startPanel(false);
        AdminSession.revoke();

        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_CENTER);

        assertTrue(activity.isFinishing());
        assertNull(ShadowDialog.getLatestDialog());
        Intent gate = shadowOf(activity).getNextStartedActivity();
        assertNotNull(gate);
        assertEquals(AdminAuthActivity.class.getName(), gate.getComponent().getClassName());
        assertFalse(MainActivity.isAuthorizedVisible());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
    }

    @Test
    public void dpadNavigationClearsAnAbandonedNumericShortcut() {
        startPanel(false);
        press(activity.getWindow(), KeyEvent.KEYCODE_1);
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocused("13");
        press(activity.getWindow(), KeyEvent.KEYCODE_2);

        assertNull(ShadowDialog.getLatestDialog());
        assertFalse(activity.isFinishing());
        assertFocused("13");
        press(activity.getWindow(), KeyEvent.KEYCODE_2); // Hidden 22 remains unavailable.
        assertNull(ShadowDialog.getLatestDialog());
        assertFalse(prefs.isProtectionEnabled());
    }

    @Test
    public void volumeAndPowerRemainUnhandledByThePanel() {
        startPanel(false);
        for (int code : new int[] {KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER}) {
            assertFalse(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)));
            assertFalse(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code)));
        }
        assertFocused("12");
        assertNull(ShadowDialog.getLatestDialog());
        assertTrue(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
    }

    @Test
    @Config(sdk = 33, qualifiers = "w1280dp-h720dp-land-mdpi")
    public void hd720LayoutFitsAndAllActionsCanBeReached() {
        assertResponsiveLayout(true, 1280, 720);
    }

    @Test
    @Config(sdk = 33, qualifiers = "w960dp-h540dp-land-xhdpi")
    public void fullHd1080LayoutFitsAndAllActionsCanBeReached() {
        assertResponsiveLayout(true, 1920, 1080);
    }

    @Test
    @Config(sdk = 33, qualifiers = "w960dp-h540dp-land-xxxhdpi")
    public void ultraHd4kLayoutFitsAndAllActionsCanBeReached() {
        assertResponsiveLayout(true, 3840, 2160);
    }

    @Test
    @Config(sdk = 33, qualifiers = "w360dp-h640dp-port-xhdpi")
    public void narrowLayoutStacksAndScrollsToTheLastAction() {
        assertResponsiveLayout(false, 720, 1280);
        assertTrue(((ScrollView) activity.findViewById(R.id.main_scroll)).getScrollY() > 0);
    }

    private void assertResponsiveLayout(boolean twoColumns, int widthPixels, int heightPixels) {
        startPanel(true);
        assertEquals(widthPixels, activity.getResources().getDisplayMetrics().widthPixels);
        assertEquals(heightPixels, activity.getResources().getDisplayMetrics().heightPixels);
        float density = activity.getResources().getDisplayMetrics().density;
        ScrollView scroll = activity.findViewById(R.id.main_scroll);
        assertTrue(scroll.getWidth() > 0);
        assertTrue(scroll.getWidth() <= Math.round(900 * density));
        assertTrue(scroll.getWidth() <= widthPixels - Math.round(48 * density));
        LinearLayout columns = activity.findViewById(R.id.panel_columns);
        assertEquals(twoColumns ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL,
                columns.getOrientation());
        // Grid order: 00 11 / 12 13 / 22 33 / 44 99. Down column 0, step right for 33, then
        // 44 is reached by wrapping down (its own UP points back to 33).
        for (String code : new String[] {"22", "33", "44"}) {
            focusAction(code);
            assertFocused(code);
        }
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        Button last = action("44");
        Rect visible = new Rect();
        assertTrue(last.getGlobalVisibleRect(visible));
        assertEquals("The focused button must scroll fully into view", last.getHeight(), visible.height());
        assertEquals("The button must not be clipped horizontally", last.getWidth(), visible.width());
        assertTrue(last.getWidth() > 0);
    }

    private void startPanel(boolean protectionEnabled) {
        prefs.setProtectionEnabled(protectionEnabled);
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()
                .windowFocusChanged(true);
        activity = controller.get();
        ShadowLooper.idleMainLooper();
        while (shadowOf(context).getNextStartedService() != null) {
            // UI tests do not create the requested service.
        }
        assertNotNull(ReflectionHelpers.callInstanceMethod(activity.getWindow().getDecorView(),
                "getViewRootImpl"));
    }

    private Button action(String code) {
        ViewGroup menu = activity.findViewById(R.id.menu_actions);
        Button button = menu.findViewWithTag("action_" + code);
        assertNotNull(button);
        return button;
    }

    /** Grid rows plus the two inline adjust rows, in display order. */
    private View[] menuChildren(ViewGroup menu) {
        java.util.List<View> views = new java.util.ArrayList<>();
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            if (child instanceof LinearLayout && child.getTag() == null) {
                ViewGroup row = (ViewGroup) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    views.add(row.getChildAt(j));
                }
            } else {
                views.add(child);
            }
        }
        return views.toArray(new View[0]);
    }

    private void assertFocused(String code) {
        View focused = activity.getWindow().getDecorView().findFocus();
        assertSame("DPAD must move the actual focus to action " + code + " (was "
                        + (focused == null ? "null" : focused.getTag() + " id=" + focused.getId())
                        + "; expected id=" + action(code).getId() + ")",
                action(code), focused);
        assertTrue(action(code).isShown());
    }

    /** Grid order 00 11 / 12 13 / 22 33 / 44 99; move row-first so every action is reachable. */
    private void focusAction(String code) {
        String[][] grid = {{"00", "11"}, {"12", "13"}, {"22", "33"}, {"44", "99"}};
        for (String[] row : grid) {
            for (String cell : row) {
                if (cell.equals(code)) {
                    action(code).requestFocus();
                    return;
                }
            }
        }
    }

    private PinDialog latestPinDialog() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog instanceof PinDialog);
        assertTrue(dialog.isShowing());
        return (PinDialog) dialog;
    }

    private void press(Window window, int code) {
        send(window, KeyEvent.ACTION_DOWN, code);
        send(window, KeyEvent.ACTION_UP, code);
    }

    private void send(Window window, int action, int code) {
        Object root = ReflectionHelpers.callInstanceMethod(window.getDecorView(), "getViewRootImpl");
        assertNotNull("Keys must enter an attached real ViewRoot", root);
        if (!window.getDecorView().hasWindowFocus()) {
            ((ShadowViewRootImpl) Shadow.extract(root)).callWindowFocusChanged(true);
            ShadowLooper.idleMainLooper();
        }
        long now = SystemClock.uptimeMillis();
        ReflectionHelpers.callInstanceMethod(root, "dispatchInputEvent", ClassParameter.from(
                InputEvent.class, new KeyEvent(now, now, action, code, 0)));
        ShadowLooper.idleMainLooper();
    }

    private boolean hasExactText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasExactText(group.getChildAt(i), text)) {
                    return true;
                }
            }
        }
        return false;
    }
}
