package com.family.tvguard;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.view.KeyEvent;

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
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Native editors must validate against changes made while another parent uses the web console. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class ConcurrentBonusTest {
    private Prefs prefs;
    private ActivityController<MainActivity> panel;
    private ServiceController<GuardService> guard;

    @Before
    public void setUp() {
        Application context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("tvguard_updates", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        prefs.setProtectionEnabled(true);
        AdminSession.revoke();
        AdminSession.grant();
        guard = Robolectric.buildService(GuardService.class).create();
        ShadowToast.reset();
    }

    @After
    public void tearDown() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (panel != null) panel.pause().stop().destroy();
        if (guard != null) guard.destroy();
        AdminSession.revoke();
    }

    @Test
    public void bonusSubmissionRejectsTheNowUnavailableAmountWithoutCrashing() {
        PinDialog dialog = openBonusEditor();
        // The web console uses the same Prefs and can add time while this native dialog is open.
        prefs.setBonusSeconds(59 * 60L);

        submit(dialog, "2");

        assertEquals(59 * 60L, prefs.getBonusSeconds());
        assertFalse(panel.get().isFinishing());
        String feedback = latestFeedback();
        assertTrue(feedback, feedback.contains("1-1"));
        assertFalse(feedback, feedback.contains("已临时增加"));
    }

    @Test
    public void bonusSubmissionCanStillUseTheActualRemainingMinute() {
        PinDialog dialog = openBonusEditor();
        prefs.setBonusSeconds(59 * 60L);

        submit(dialog, "1");

        assertEquals(60 * 60L, prefs.getBonusSeconds());
        assertEquals("今天已临时增加 1 分钟", latestFeedback());
    }

    @Test
    public void aConcurrentFullAllowanceProducesFeedbackInsteadOfSilentlyDiscardingInput() {
        PinDialog dialog = openBonusEditor();
        prefs.setBonusSeconds(60 * 60L);

        submit(dialog, "1");

        assertEquals(60 * 60L, prefs.getBonusSeconds());
        String feedback = latestFeedback();
        assertTrue(feedback, feedback.contains("60"));
        assertFalse(feedback, feedback.contains("已临时增加"));
    }

    @Test
    public void theEditorUsesCurrentAllowanceWhenTheDayOrBonusChanged() {
        prefs.setBonusSeconds(59 * 60L);
        PinDialog dialog = openBonusEditor();
        prefs.setBonusSeconds(0L);

        submit(dialog, "30");

        assertEquals(30 * 60L, prefs.getBonusSeconds());
        assertEquals("今天已临时增加 30 分钟", latestFeedback());
    }

    private PinDialog openBonusEditor() {
        panel = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        for (int i = 0; i < 2; i++) {
            panel.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2));
            panel.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_2));
        }
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog instanceof PinDialog);
        assertTrue(dialog.isShowing());
        return (PinDialog) dialog;
    }

    private void submit(PinDialog dialog, String minutes) {
        ShadowToast.reset();
        for (char digit : minutes.toCharArray()) {
            int code = KeyEvent.KEYCODE_0 + digit - '0';
            dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
    }

    private String latestFeedback() {
        CharSequence message = ShadowToast.getTextOfLatestToast();
        assertNotNull("The parent needs a visible result for their submitted edit", message);
        return message.toString();
    }
}
