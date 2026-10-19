package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
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
@Config(sdk = {24, 33})
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
    public void firstFocusedDigitWorksOnOkWithoutAnyArrowKey() {
        List<String> values = new ArrayList<>();
        showDialog(false, 4, values);

        press(dialog, KeyEvent.KEYCODE_DPAD_CENTER);

        assertEquals("1", dialogInput());
        assertTrue(values.isEmpty());
        assertTrue(dialog.isShowing());
    }

    @Test
    public void parentGateKeepsTheSimpleNumberEntryInterface() {
        prefs.setPassword("135790");
        AdminAuthActivity activity = startAuth();

        // The previous, keypad-free gate: physical number keys, delete and OK only.
        assertNull(activity.findViewById(R.id.keypad_container));
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
    public void dpadOnlyDialogEntersDeletesAndConfirmsOnRelease() {
        List<String> values = new ArrayList<>();
        showDialog(false, 4, values);
        enterNavigation(dialog);
        assertEquals("key_1", dialog.getCurrentFocus().getTag());
        press(dialog, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(dialog, KeyEvent.KEYCODE_BUTTON_A);
        assertEquals("2", dialogInput());
        focusDialog("delete");
        press(dialog, KeyEvent.KEYCODE_ENTER);
        assertEquals("", dialogInput());
        focusDialog("4");
        press(dialog, KeyEvent.KEYCODE_DPAD_CENTER);
        focusDialog("5");
        press(dialog, KeyEvent.KEYCODE_NUMPAD_ENTER);
        assertTrue(hasText(dialog.getWindow().getDecorView(), "45"));
        assertTrue(dialog.isShowing());
        focusDialog("confirm");
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT));
        assertTrue(values.isEmpty());
        dialog.dispatchKeyEvent(new KeyEvent(0, 1, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BUTTON_SELECT, 1));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT));
        assertEquals(List.of("45"), values);
    }

    @Test
    public void passwordDialogMasksAndAutoConfirmsSixVirtualDigitsOnce() {
        List<String> values = new ArrayList<>();
        showDialog(true, 6, values);
        enterNavigation(dialog);
        for (char digit : "13579".toCharArray()) {
            focusDialog(String.valueOf(digit));
            press(dialog, KeyEvent.KEYCODE_DPAD_CENTER);
        }
        assertTrue(hasText(dialog.getWindow().getDecorView(), "●●●●●"));
        assertFalse(hasText(dialog.getWindow().getDecorView(), "13579"));
        focusDialog("0");
        press(dialog, KeyEvent.KEYCODE_DPAD_CENTER);
        assertFalse(dialog.isShowing());
        assertEquals(List.of("135790"), values);
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
        digits(activity, "135790");
        press(activity, KeyEvent.KEYCODE_ENTER);
        assertFalse(AdminSession.isActive());
        assertFalse(activity.isFinishing());
        assertEquals(3, prefs.getFailCount());
        assertEquals("", ((TextView) activity.findViewById(R.id.lock_input)).getText().toString());
        prefs.clearFailures();
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        digits(activity, "135790");
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
        ((ShadowViewRootImpl) Shadow.extract(root)).callWindowFocusChanged(true);
        ShadowLooper.idleMainLooper();
    }

    private void virtualDigits(AdminAuthActivity activity, String value) {
        digits(activity, value);
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
        Window window = target instanceof PinDialog ? ((PinDialog) target).getWindow()
                : ((AdminAuthActivity) target).getWindow();
        Object root = ReflectionHelpers.callInstanceMethod(window.getDecorView(), "getViewRootImpl");
        assertNotNull("Remote events must enter the attached ViewRoot", root);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            ReflectionHelpers.callInstanceMethod(root, "dispatchInputEvent",
                    ReflectionHelpers.ClassParameter.from(InputEvent.class,
                            new KeyEvent(now, now, action, code, 0)));
            ShadowLooper.idleMainLooper();
        }
    }

    private String dialogInput() {
        TextView input = ReflectionHelpers.getField(dialog, "inputView");
        return input.getText().toString();
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
