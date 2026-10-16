package com.family.tvguard;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivitySettingsTest {

    private Application context;
    private Prefs prefs;
    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
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
        AdminSession.revoke();
    }

    @Test
    public void authorizedParentSeesDailyLimitAndEnabledActionsButCannotResumeAgain() {
        MainActivity activity = startPanel(true);
        String menu = menuText();

        assertFalse(activity.isFinishing());
        assertTrue(MainActivity.isAuthorizedVisible());
        assertTrue(menu.contains("12"));
        assertTrue(menu.contains("每日时长"));
        assertTrue(menu.contains("22"));
        assertTrue(menu.contains("33"));
        assertTrue(menu.contains("44"));
        assertFalse(menu.contains("99"));
        assertRemovedMenuItems(menu);

        assertShortcutIgnored("99");
    }

    @Test
    public void disabledProtectionShowsResumeButHiddenActionsCannotExecute() {
        startPanel(false);
        String menu = menuText();

        assertTrue(menu.contains("12"));
        assertTrue(menu.contains("每日时长"));
        assertTrue(menu.contains("99"));
        assertFalse(menu.contains("22"));
        assertFalse(menu.contains("33"));
        assertFalse(menu.contains("44"));
        assertRemovedMenuItems(menu);

        for (String code : new String[] {"22", "33", "44", "55", "66", "77", "88"}) {
            assertShortcutIgnored(code);
        }
        assertFalse(prefs.isProtectionEnabled());
    }

    @Test
    public void removedShortcutsCannotExecuteEvenWhenProtectionIsEnabled() {
        startPanel(true);

        for (String code : new String[] {"55", "66", "77", "88"}) {
            assertShortcutIgnored(code);
        }
        assertTrue(prefs.isProtectionEnabled());
    }

    @Test
    public void dailyLimitShortcutShowsPlaintextAndSaves45OnlyAfterOk() {
        MainActivity activity = startPanel(false);
        enterDigits(activity, "12");
        PinDialog dialog = latestPinDialog();

        enterDigits(dialog, "45");

        assertTrue(dialog.isShowing());
        assertTrue(hasText(dialog.getWindow().getDecorView(), "45"));
        assertFalse(hasText(dialog.getWindow().getDecorView(), "●●"));
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());

        pressOk(dialog);

        assertFalse(dialog.isShowing());
        assertEquals(45, new Prefs(context).getDailyLimitMinutes());
        TextView status = activity.findViewById(R.id.status_text);
        assertTrue(status.getText().toString().contains("每日限额：45 分钟"));
        assertFalse(prefs.isProtectionEnabled());
        assertNull(GuardService.get());
        assertNull(shadowOf(context).getNextStartedService());
    }

    @Test
    public void passwordVerificationStillMasksTheEnteredDigits() {
        MainActivity activity = startPanel(false);
        enterDigits(activity, "11");
        PinDialog dialog = latestPinDialog();

        enterDigits(dialog, "13");

        assertTrue(dialog.isShowing());
        assertTrue(hasText(dialog.getWindow().getDecorView(), "●●"));
        assertFalse(hasText(dialog.getWindow().getDecorView(), "13"));
        assertTrue(prefs.checkPassword("135790"));
    }

    @Test
    public void fourDigitDailyLimitDoesNotSubmit1440UntilOk() {
        MainActivity activity = startPanel(false);
        enterDigits(activity, "12");
        PinDialog dialog = latestPinDialog();

        enterDigits(dialog, "1440");

        assertTrue(dialog.isShowing());
        assertTrue(hasText(dialog.getWindow().getDecorView(), "1440"));
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());

        pressOk(dialog);

        assertFalse(dialog.isShowing());
        assertEquals(1440, new Prefs(context).getDailyLimitMinutes());
        assertFalse(prefs.isProtectionEnabled());
        assertNull(GuardService.get());
        assertNull(shadowOf(context).getNextStartedService());
    }

    private MainActivity startPanel(boolean protectionEnabled) {
        prefs.setProtectionEnabled(protectionEnabled);
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        ShadowLooper.idleMainLooper();
        // Launching an enabled panel requests the service, but these UI tests do not create it.
        while (shadowOf(context).getNextStartedService() != null) {
            // Discard only the initial start request before checking shortcut side effects.
        }
        assertNull(GuardService.get());
        return controller.get();
    }

    private String menuText() {
        TextView menu = controller.get().findViewById(R.id.menu_text);
        assertNotNull(menu);
        return menu.getText().toString();
    }

    private void assertRemovedMenuItems(String menu) {
        for (String code : new String[] {"55", "66", "77", "88"}) {
            assertFalse("Removed shortcut must not appear: " + code, menu.contains(code));
        }
    }

    private void assertShortcutIgnored(String code) {
        Map<String, ?> before = context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE)
                .getAll();

        enterDigits(controller.get(), code);

        assertEquals("Hidden shortcut must not change settings: " + code, before,
                context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).getAll());
        assertNull("Hidden shortcut must not open a dialog: " + code, ShadowDialog.getLatestDialog());
        assertNull("Hidden shortcut must not launch another screen: " + code,
                shadowOf(controller.get()).getNextStartedActivity());
        assertNull("Hidden shortcut must not start a service: " + code,
                shadowOf(context).getNextStartedService());
        assertFalse(controller.get().isFinishing());
        assertTrue(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
        assertFalse(AdminSession.isRemovalInProgress());
    }

    private PinDialog latestPinDialog() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog instanceof PinDialog);
        assertTrue(dialog.isShowing());
        return (PinDialog) dialog;
    }

    private void enterDigits(android.view.Window.Callback target, String digits) {
        for (int i = 0; i < digits.length(); i++) {
            int keyCode = KeyEvent.KEYCODE_0 + digits.charAt(i) - '0';
            assertTrue(target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode)));
            target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    private void pressOk(Dialog dialog) {
        assertTrue(dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER)));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
    }

    private boolean hasText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), text)) {
                    return true;
                }
            }
        }
        return false;
    }
}
