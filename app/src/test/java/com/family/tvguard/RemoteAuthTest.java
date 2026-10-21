package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowViewRootImpl;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class RemoteAuthTest {
    private Application context;
    private Prefs prefs;
    private ActivityController<AdminAuthActivity> controller;
    private PinDialog dialog;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        AdminSession.revoke();
    }

    @After
    public void tearDown() {
        if (dialog != null) {
            dialog.dismiss();
        }
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        AdminSession.revoke();
    }

    @Test
    public void pinSurfacesHaveAnOnScreenPadWithoutAnImeBackedField() {
        prefs.setPassword("135790");
        AdminAuthActivity activity = startAuth();
        assertRemoteOnlyInput(activity.findViewById(android.R.id.content));
        assertNumericPad(activity.findViewById(android.R.id.content));

        List<String> values = new ArrayList<>();
        showDialog(true, 6, values);
        assertRemoteOnlyInput(dialog.getWindow().getDecorView());
        assertNumericPad(dialog.getWindow().getDecorView());
        virtualDigits(dialog, "13579");
        assertEquals("●●●●●", dialogInput());
        assertTrue(values.isEmpty());
        assertTrue(dialog.isShowing());
    }

    @Test
    public void dialogTakesPhysicalDigitsAndOkSubmits() {
        List<String> values = new ArrayList<>();
        showDialog(false, 4, values);

        digits(dialog, "1440");
        assertTrue(hasText(dialog.getWindow().getDecorView(), "1440"));
        assertTrue(values.isEmpty());
        press(dialog, KeyEvent.KEYCODE_DPAD_CENTER);

        assertEquals(List.of("1440"), values);
        assertFalse(dialog.isShowing());
    }

    @Test
    public void dialogLeftArrowDeletesTheLastDigit() {
        List<String> values = new ArrayList<>();
        showDialog(false, 4, values);

        digits(dialog, "123");
        press(dialog, KeyEvent.KEYCODE_DPAD_LEFT);

        assertTrue(hasText(dialog.getWindow().getDecorView(), "12"));
        assertTrue(values.isEmpty());
        assertTrue(dialog.isShowing());
    }

    @Test
    public void parentGateStillAcceptsPhysicalNumberKeysAlongsideTheOnScreenPad() {
        prefs.setPassword("135790");
        AdminAuthActivity activity = startAuth();

        assertNotNull(activity.findViewById(android.R.id.content)
                .findViewWithTag("key_1"));
        digits(activity, "13");
        assertEquals("●●", ((TextView) activity.findViewById(R.id.lock_input)).getText().toString());
        press(activity, KeyEvent.KEYCODE_DEL);
        assertEquals("●", ((TextView) activity.findViewById(R.id.lock_input)).getText().toString());
        press(activity, KeyEvent.KEYCODE_DEL);
        assertEquals("", ((TextView) activity.findViewById(R.id.lock_input)).getText().toString());
        assertFalse(AdminSession.isActive());
        assertEquals(0, prefs.getFailCount());
    }

    @Test
    public void parentGateDoesNotClaimQuotaIsExhaustedWhileProtectionIsOff() {
        prefs.setPassword("135790");
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        prefs.setLocked(true);
        prefs.setProtectionEnabled(false);
        AdminAuthActivity activity = startAuth();

        assertEquals("家长验证", ((TextView) activity.findViewById(R.id.lock_title))
                .getText().toString());
        assertFalse(prefs.isProtectionEnabled());
    }

    @Test
    public void parentGateOnlyShowsExhaustedTitleWhenTheQuotaLockIsActive() {
        prefs.setPassword("135790");
        AdminAuthActivity activity = startAuth();
        TextView title = activity.findViewById(R.id.lock_title);
        assertEquals("家长验证", title.getText().toString());

        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals("今日电视时长已用完", title.getText().toString());
    }

    @Test
    public void parentGateKeepsRepeatedPhysicalDigitsAcrossRefreshTicks() {
        prefs.setPassword("135790");
        AdminAuthActivity activity = startAuth();

        for (int i = 1; i <= 5; i++) {
            press(activity, KeyEvent.KEYCODE_1);
            ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("●".repeat(i), ((TextView) activity.findViewById(R.id.lock_input))
                    .getText().toString());
        }
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals("●●●●", ((TextView) activity.findViewById(R.id.lock_input))
                .getText().toString());
        assertEquals(0, prefs.getFailCount());
    }

    @Test
    public void hardwareNumbersStillSubmitPlaintextLimitOnOkDownWithoutVirtualDigit() {
        List<String> values = new ArrayList<>();
        showDialog(false, 4, values);
        digits(dialog, "1440");
        assertTrue(dialog.isShowing());
        assertTrue(values.isEmpty());
        assertTrue(hasText(dialog.getWindow().getDecorView(), "1440"));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(List.of("1440"), values);
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(1, values.size());
    }

    @Test
    public void passwordDialogMasksAndAutoConfirmsSixDigitsOnce() {
        List<String> values = new ArrayList<>();
        showDialog(true, 6, values);

        digits(dialog, "135790");

        assertFalse(dialog.isShowing());
        assertEquals(List.of("135790"), values);
        // The dialog is gone; a further ENTER cannot submit a second time.
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        assertEquals(1, values.size());
    }

    @Test
    public void hardwareNumbersAfterDpadReturnOkToDirectSubmit() {
        List<String> values = new ArrayList<>();
        showDialog(false, 4, values);
        enterNavigation(dialog);
        digits(dialog, "45");
        press(dialog, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(List.of("45"), values);
    }

    @Test
    public void directionOnlyDurationEntryCanDeleteAndConfirmWithoutNumberKeys() {
        List<String> values = new ArrayList<>();
        showDialog(false, 4, values);
        virtualDigits(dialog, "123");
        focusKey(dialog, "delete");
        press(dialog, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("12", dialogInput());
        focusKey(dialog, "confirm");
        assertEquals("12", dialogInput());
        assertTrue(values.isEmpty());
        press(dialog, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(List.of("12"), values);
        assertFalse(dialog.isShowing());
    }

    @Test
    public void virtualPasswordMasksAndAutoSubmitsOnlyWhenSixDigitsAreEntered() {
        List<String> values = new ArrayList<>();
        showDialog(true, 6, values);
        virtualDigits(dialog, "24681");
        assertEquals("●●●●●", dialogInput());
        assertTrue(values.isEmpty());
        virtualDigits(dialog, "0");
        assertEquals(List.of("246810"), values);
        assertFalse(dialog.isShowing());
    }

    @Test
    public void virtualOkAliasesActivateOnceOnReleaseAndIgnoreLongPressRepeats() {
        List<String> values = new ArrayList<>();
        showDialog(false, 8, values);
        for (int code : new int[] {KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_SELECT}) {
            String before = dialogInput();
            send(dialog, new KeyEvent(KeyEvent.ACTION_DOWN, code));
            send(dialog, new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 3));
            assertEquals(before, dialogInput());
            send(dialog, new KeyEvent(KeyEvent.ACTION_UP, code));
            assertEquals(before + "1", dialogInput());
            send(dialog, new KeyEvent(KeyEvent.ACTION_UP, code));
            assertEquals(before + "1", dialogInput());
        }
        assertTrue(values.isEmpty());
    }

    @Test
    public void canceledOrInterruptedVirtualOkCannotTypeADigit() {
        List<String> values = new ArrayList<>();
        showDialog(false, 4, values);
        send(dialog, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        send(dialog, new KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER,
                0, 0, 0, 0, KeyEvent.FLAG_CANCELED));
        assertEquals("", dialogInput());
        send(dialog, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        press(dialog, KeyEvent.KEYCODE_DPAD_RIGHT);
        send(dialog, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals("", dialogInput());
        press(dialog, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("2", dialogInput());
    }

    @Test
    public void virtualConfirmOfPartialGatePinDoesNotCountAFailure() {
        prefs.setPassword("135790");
        AdminAuthActivity activity = startAuth();
        virtualDigits(activity, "13");
        focusKey(activity, "confirm");
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(0, prefs.getFailCount());
        assertFalse(AdminSession.isActive());
        assertTrue(((TextView) activity.findViewById(R.id.lock_prompt)).getText().toString()
                .contains("请输入 6 位"));
    }

    @Test
    public void virtualParentPinUsesAuthenticationAndOpensConsole() {
        prefs.setPassword("135790");
        AdminAuthActivity activity = startAuth();
        virtualDigits(activity, "13579");
        assertEquals("●●●●●", ((TextView) activity.findViewById(R.id.lock_input)).getText().toString());
        virtualDigits(activity, "0");
        assertTrue(AdminSession.isActive());
        assertTrue(activity.isFinishing());
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(MainActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void factoryPinStillRequiresNondefaultMatchingNewPinWithoutUnlockingQuota() {
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        prefs.setLocked(true);
        AdminAuthActivity activity = startAuth();
        virtualDigits(activity, "000000");
        assertTrue(AdminSession.isActive());
        assertTrue(prefs.needsPasswordSetup());
        assertFalse(prefs.isUnlockedToday());
        assertTrue(prefs.isGuardActive());
        assertFalse(activity.isFinishing());
        virtualDigits(activity, "000000");
        assertTrue(((TextView) activity.findViewById(R.id.lock_prompt)).getText().toString()
                .contains("不能使用出厂密码"));
        virtualDigits(activity, "135790");
        virtualDigits(activity, "135791");
        assertTrue(prefs.needsPasswordSetup());
        assertTrue(((TextView) activity.findViewById(R.id.lock_prompt)).getText().toString()
                .contains("两次输入不一致"));
        virtualDigits(activity, "246810");
        virtualDigits(activity, "246810");
        assertFalse(prefs.needsPasswordSetup());
        assertTrue(prefs.checkPassword("246810"));
        assertTrue(activity.isFinishing());
        assertFalse(prefs.isUnlockedToday());
    }

    @Test
    public void cooldownDisablesVirtualKeysAndHardwareCannotBypassItThenInputRecovers() {
        prefs.setPassword("135790");
        AdminAuthActivity activity = startAuth();
        virtualDigits(activity, "111111111111111111");
        assertEquals(3, prefs.getFailCount());
        assertTrue(prefs.lockoutRemainingSeconds() > 0);
        assertFalse(activity.findViewById(android.R.id.content).findViewWithTag("key_1").isEnabled());
        activity.findViewById(android.R.id.content).findViewWithTag("key_1").performClick();
        digits(activity, "135790");
        press(activity, KeyEvent.KEYCODE_ENTER);
        assertFalse(AdminSession.isActive());
        assertFalse(activity.isFinishing());
        assertEquals(3, prefs.getFailCount());
        assertEquals("", ((TextView) activity.findViewById(R.id.lock_input)).getText().toString());
        prefs.clearFailures();
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(activity.findViewById(android.R.id.content).findViewWithTag("key_1").isEnabled());
        virtualDigits(activity, "135790");
        assertTrue(AdminSession.isActive());
        assertTrue(activity.isFinishing());
    }

    @Test
    public void okWithAPartialPinReportsTheLengthAndDoesNotCountAFailure() {
        prefs.setPassword("135790");
        AdminAuthActivity activity = startAuth();
        digits(activity, "13");
        press(activity, KeyEvent.KEYCODE_BUTTON_A);
        assertFalse(AdminSession.isActive());
        assertFalse(activity.isFinishing());
        assertEquals(0, prefs.getFailCount());
        assertTrue(((TextView) activity.findViewById(R.id.lock_prompt)).getText().toString()
                .contains("请输入 6 位"));
    }

    @Test
    public void dialogAndAuthLeavePowerAndVolumeToSystemAndBackRetainsDestination() {
        List<String> values = new ArrayList<>();
        showDialog(false, 4, values);
        AdminAuthActivity activity = startAuth();
        for (int key : new int[] {KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER}) {
            for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                assertFalse(dialog.dispatchKeyEvent(new KeyEvent(action, key)));
                assertFalse(activity.dispatchKeyEvent(new KeyEvent(action, key)));
            }
        }
        AdminSession.grant();
        press(activity, KeyEvent.KEYCODE_BACK);
        assertFalse(AdminSession.isActive());
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertTrue(next.hasCategory(Intent.CATEGORY_HOME));
        assertTrue(activity.isFinishing());
    }

    private AdminAuthActivity startAuth() {
        controller = Robolectric.buildActivity(AdminAuthActivity.class).setup().visible()
                .windowFocusChanged(true);
        ShadowLooper.idleMainLooper();
        return controller.get();
    }

    private void showDialog(boolean password, int length, List<String> values) {
        dialog = new PinDialog(context, "测试输入", "输入数字", length, password, values::add);
        dialog.show();
        ShadowLooper.idleMainLooper();
        Object root = ReflectionHelpers.callInstanceMethod(dialog.getWindow().getDecorView(),
                "getViewRootImpl");
        if (root == null) {
            // Robolectric only attaches a ViewRoot once the dialog window is laid out.
            ReflectionHelpers.callInstanceMethod(dialog.getWindow(), "makeActive");
            root = ReflectionHelpers.callInstanceMethod(dialog.getWindow().getDecorView(),
                    "getViewRootImpl");
        }
        assertNotNull(root);
        ((ShadowViewRootImpl) Shadow.extract(root)).callWindowFocusChanged(true);
        ShadowLooper.idleMainLooper();
    }

    private void virtualDigits(Window.Callback target, String value) {
        press(target, KeyEvent.KEYCODE_DPAD_UP);
        for (char digit : value.toCharArray()) {
            focusKey(target, String.valueOf(digit));
            press(target, KeyEvent.KEYCODE_DPAD_CENTER);
        }
    }

    private void focusKey(Window.Callback target, String suffix) {
        Window window = target instanceof PinDialog ? ((PinDialog) target).getWindow()
                : ((AdminAuthActivity) target).getWindow();
        View wanted = window.getDecorView().findViewWithTag("key_" + suffix);
        assertNotNull(wanted);
        View focused = window.getDecorView().findFocus();
        assertNotNull("Dpad-only entry needs an initial focus", focused);
        int from = keyIndex(focused.getTag().toString());
        int to = keyIndex(wanted.getTag().toString());
        int row = from / 3;
        int column = from % 3;
        while (row != to / 3) {
            press(target, row < to / 3 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
            row += row < to / 3 ? 1 : -1;
        }
        while (column != to % 3) {
            press(target, column < to % 3 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
            column += column < to % 3 ? 1 : -1;
        }
        assertSame("Dpad must reach the actual key button", wanted, window.getDecorView().findFocus());
    }

    private int keyIndex(String tag) {
        String suffix = tag.substring("key_".length());
        if (suffix.equals("delete")) return 9;
        if (suffix.equals("confirm")) return 11;
        int digit = Integer.parseInt(suffix);
        return digit == 0 ? 10 : digit - 1;
    }

    private void focusDialog(String suffix) {
        assertTrue(dialog.getWindow().getDecorView().findViewWithTag("key_" + suffix).requestFocus());
    }

    private void enterNavigation(Window.Callback target) {
        press(target, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(target, KeyEvent.KEYCODE_DPAD_LEFT);
    }

    private void digits(Window.Callback target, String value) {
        for (char digit : value.toCharArray()) {
            press(target, KeyEvent.KEYCODE_0 + digit - '0');
        }
    }

    private void press(Window.Callback target, int code) {
        Object root = eventRoot(target);
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            long now = SystemClock.uptimeMillis();
            sendToRoot(root, new KeyEvent(now, now, action, code, 0));
        }
    }

    private void send(Window.Callback target, KeyEvent event) {
        sendToRoot(eventRoot(target), event);
    }

    private Object eventRoot(Window.Callback target) {
        Window window = target instanceof PinDialog ? ((PinDialog) target).getWindow()
                : ((AdminAuthActivity) target).getWindow();
        Object root = ReflectionHelpers.callInstanceMethod(window.getDecorView(), "getViewRootImpl");
        assertNotNull("Remote events must enter the attached ViewRoot", root);
        if (RuntimeEnvironment.getApiLevel() == 19) {
            // KitKat drops events when Robolectric's unconnected IME reports NOT_HANDLED.
            ReflectionHelpers.setField(root, "mLastWasImTarget", false);
        }
        return root;
    }

    private void sendToRoot(Object root, KeyEvent event) {
        ReflectionHelpers.callInstanceMethod(root, "dispatchInputEvent",
                ReflectionHelpers.ClassParameter.from(InputEvent.class, event));
        ShadowLooper.idleMainLooper();
    }

    private String dialogInput() {
        TextView input = ReflectionHelpers.getField(dialog, "inputView");
        return input.getText().toString();
    }

    private void assertRemoteOnlyInput(View view) {
        assertFalse("Remote PIN input must not create an IME-backed field", view instanceof EditText);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                assertRemoteOnlyInput(group.getChildAt(i));
            }
        }
    }

    private void assertNumericPad(View root) {
        for (int digit = 0; digit <= 9; digit++) {
            View key = root.findViewWithTag("key_" + digit);
            assertTrue(key instanceof Button);
            assertTrue(key.isFocusable());
        }
        assertNotNull(root.findViewWithTag("key_delete"));
        assertNotNull(root.findViewWithTag("key_confirm"));
    }

    private boolean hasText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), text)) {
                    return true;
                }
            }
        }
        return false;
    }
}
