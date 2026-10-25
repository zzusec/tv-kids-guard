package com.family.tvguard;

import android.app.Application;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
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
@Config(sdk = {19, 24, 33})
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
    public void removalConfirmationUsesReadableDarkThemeAndCancelKeepsProtection() {
        MainActivity activity = startPanel(true);
        enterDigits(activity, "44");

        Dialog latest = ShadowDialog.getLatestDialog();
        assertTrue(latest instanceof AlertDialog);
        AlertDialog confirmation = (AlertDialog) latest;
        assertTrue(confirmation.isShowing());
        int[][] colors = {{android.R.attr.textColorPrimary, R.color.panel_ink},
                {android.R.attr.textColorSecondary, R.color.panel_muted}};
        for (int[] color : colors) {
            TypedValue value = new TypedValue();
            assertTrue(confirmation.getContext().getTheme().resolveAttribute(color[0], value, true));
            assertEquals(context.getResources().getColor(color[1]), value.data);
        }

        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertTrue(prefs.isProtectionEnabled());
        assertFalse(AdminSession.isRemovalInProgress());
        assertTrue(AdminSession.isActive());
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
        assertTrue(menu.contains("55"));
        assertTrue(menu.contains("防卸载"));
        assertFalse(menu.contains("99"));
        assertRemovedMenuItems(menu);

        assertShortcutIgnored("99");
        assertHiddenButtonIgnored("99");
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
        assertFalse(menu.contains("55"));
        assertRemovedMenuItems(menu);

        for (String code : new String[] {"22", "33", "44", "55", "66", "77", "88"}) {
            assertShortcutIgnored(code);
        }
        for (String code : new String[] {"22", "33", "44", "55"}) {
            assertHiddenButtonIgnored(code);
        }
        assertFalse(prefs.isProtectionEnabled());
    }

    @Test
    public void cappedBonusIsUnavailableThroughShortcutAndButton() {
        prefs.setBonusSeconds(Prefs.MAX_BONUS_MINUTES * 60L);
        startPanel(true);

        assertFalse(menuText().contains("22"));
        assertShortcutIgnored("22");
        assertHiddenButtonIgnored("22");
    }

    @Test
    public void parentSummaryUsesPlainLanguageWithoutTechnicalDiagnostics() {
        prefs.setUsedSeconds(123L);
        MainActivity activity = startPanel(true);

        assertEquals("防护已开启",
                ((TextView) activity.findViewById(R.id.status_text)).getText().toString());
        assertEquals("30 分钟", ((TextView) activity.findViewById(R.id.daily_limit_value)).getText().toString());
        assertEquals("2 分钟", ((TextView) activity.findViewById(R.id.used_time_value)).getText().toString());
        assertEquals(View.GONE, activity.findViewById(R.id.bonus_summary).getVisibility());
        String warning = ((TextView) activity.findViewById(R.id.warn_text)).getText().toString();
        assertTrue(warning.contains("设置保护未开启"));
        assertTrue(warning.contains("防卸载未开启"));
        assertFalse(warning.contains("强制停止"));
    }

    @Test
    public void enabledAccessibilityPermissionWithoutAConnectionShowsARecoveryWarning() {
        enableAccessibilityPermission();

        MainActivity activity = startPanel(true);

        String warning = ((TextView) activity.findViewById(R.id.warn_text)).getText().toString();
        assertTrue(warning.contains("设置保护未连接"));
        assertFalse(warning.contains("设置保护未开启"));
    }

    @Test
    public void settingsProtectionWarningTracksTheActualServiceConnection() {
        enableAccessibilityPermission();
        ServiceController<AppLockService> accessibility =
                Robolectric.buildService(AppLockService.class).create();
        try {
            accessibility.get().onServiceConnected();
            MainActivity activity = startPanel(true);
            String warning = ((TextView) activity.findViewById(R.id.warn_text)).getText().toString();
            assertFalse(warning.contains("设置保护未连接"));
            assertFalse(warning.contains("设置保护未开启"));

            accessibility.get().onUnbind(new android.content.Intent());
            ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);

            warning = ((TextView) activity.findViewById(R.id.warn_text)).getText().toString();
            assertTrue(warning.contains("设置保护未连接"));
            assertTrue(warning.contains("防卸载未开启"));
        } finally {
            accessibility.destroy();
        }
    }

    private void enableAccessibilityPermission() {
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new android.content.ComponentName(context, AppLockService.class).flattenToString());
    }

    @Test
    public void parentSummaryExplainsTodaysTemporaryBonus() {
        prefs.setBonusSeconds(600L);
        MainActivity activity = startPanel(true);

        assertEquals("防护已开启",
                ((TextView) activity.findViewById(R.id.status_text)).getText().toString());
        assertEquals("30 分钟", ((TextView) activity.findViewById(R.id.daily_limit_value)).getText().toString());
        assertEquals("0 分钟", ((TextView) activity.findViewById(R.id.used_time_value)).getText().toString());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.bonus_summary).getVisibility());
        assertEquals("今天临时加时 10 分钟", ((TextView) activity.findViewById(R.id.bonus_summary)).getText().toString());
    }

    @Test
    public void disabledProtectionDoesNotShowIrrelevantPermissionWarnings() {
        MainActivity activity = startPanel(false);

        assertEquals("防护已关闭",
                ((TextView) activity.findViewById(R.id.status_text)).getText().toString());
        assertEquals("30 分钟", ((TextView) activity.findViewById(R.id.daily_limit_value)).getText().toString());
        assertEquals("0 分钟", ((TextView) activity.findViewById(R.id.used_time_value)).getText().toString());
        String warning = ((TextView) activity.findViewById(R.id.warn_text)).getText().toString();
        assertFalse(warning.contains("悬浮窗"));
        assertFalse(warning.contains("无障碍"));
    }

    @Test
    public void temporaryBonusLabelAndDialogExplainThatItOnlyAppliesToday() {
        MainActivity activity = startPanel(true);
        Button button = activity.findViewById(android.R.id.content).findViewWithTag("action_22");
        assertEquals("22 临时加时长", button.getText().toString());

        enterDigits(activity, "22");
        PinDialog dialog = latestPinDialog();
        assertTrue(hasText(dialog.getWindow().getDecorView(), "临时加时长"));
        assertTrue(hasText(dialog.getWindow().getDecorView(), "仅对今天有效。输入 1-60 分钟"));
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
        assertEquals(0L, prefs.getBonusSeconds());
    }

    @Test
    public void closingThePanelDoesNotTurnOffProtectionOrClearItsSettings() {
        prefs.setUsedSeconds(600L);
        MainActivity activity = startPanel(true);

        enterDigits(activity, "00");

        assertTrue(activity.isFinishing());
        assertTrue(prefs.isProtectionEnabled());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
        assertEquals(600L, prefs.getUsedSeconds());
        assertFalse(AdminSession.isActive());
        assertTrue(shadowOf(activity).getNextStartedActivity().hasCategory(android.content.Intent.CATEGORY_HOME));
    }

    @Test
    public void simplificationDoesNotHideActualGuardFailures() {
        String previous = GuardService.lastFailure();
        org.robolectric.util.ReflectionHelpers.setStaticField(GuardService.class,
                "lastFailure", "计时服务启动失败，请重新打开应用");
        try {
            MainActivity activity = startPanel(true);
            TextView warning = activity.findViewById(R.id.warn_text);

            assertEquals(View.VISIBLE, warning.getVisibility());
            assertTrue(warning.getText().toString().contains("计时服务启动失败，请重新打开应用"));
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(GuardService.class,
                    "lastFailure", previous);
        }
    }

    @Test
    public void disabledButtonAlsoDisablesItsNumericShortcut() {
        MainActivity activity = startPanel(false);
        Button button = activity.findViewById(android.R.id.content).findViewWithTag("action_12");
        button.setEnabled(false);

        assertShortcutIgnored("12");
        button.performClick();
        assertNull(ShadowDialog.getLatestDialog());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
    }

    @Test
    public void removedShortcutsCannotExecuteEvenWhenProtectionIsEnabled() {
        startPanel(true);

        for (String code : new String[] {"66", "77", "88"}) {
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
        TextView status = activity.findViewById(R.id.daily_limit_value);
        assertEquals("45 分钟", status.getText().toString());
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
        ViewGroup menu = controller.get().findViewById(R.id.menu_actions);
        assertNotNull(menu);
        StringBuilder text = new StringBuilder();
        java.util.List<View> views = new java.util.ArrayList<>();
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            if (child instanceof LinearLayout && child.getTag() == null) {
                // Action rows hold one or two cells; a cell is a Button or a stack of mutually
                // exclusive actions (55 while protection runs, 99 after it is switched off).
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View cell = row.getChildAt(j);
                    if (cell instanceof android.view.ViewGroup) {
                        android.view.ViewGroup stack = (android.view.ViewGroup) cell;
                        for (int k = 0; k < stack.getChildCount(); k++) {
                            views.add(stack.getChildAt(k));
                        }
                    } else {
                        views.add(cell);
                    }
                }
            } else {
                views.add(child);
            }
        }
        for (View view : views) {
            if (!(view instanceof Button)) {
                // Inline adjust rows are not shortcut actions.
                assertNull(view.getTag());
                continue;
            }
            Button button = (Button) view;
            if (button.isShown()) {
                assertTrue(button.isFocusable());
                assertTrue(button.isEnabled());
                text.append(button.getText()).append('\n');
            }
        }
        return text.toString();
    }

    private void assertRemovedMenuItems(String menu) {
        for (String code : new String[] {"66", "77", "88"}) {
            assertFalse("Removed shortcut must not appear: " + code, menu.contains(code));
            assertNull(controller.get().findViewById(android.R.id.content)
                    .findViewWithTag("action_" + code));
        }
    }

    private void assertHiddenButtonIgnored(String code) {
        Button button = controller.get().findViewById(android.R.id.content)
                .findViewWithTag("action_" + code);
        assertNotNull(button);
        assertFalse(button.isShown());
        Map<String, ?> before = context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE)
                .getAll();

        button.performClick();

        assertEquals(before, context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).getAll());
        assertNull(ShadowDialog.getLatestDialog());
        assertNull(shadowOf(controller.get()).getNextStartedActivity());
        assertNull(shadowOf(context).getNextStartedService());
        assertTrue(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
        assertFalse(AdminSession.isRemovalInProgress());
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
