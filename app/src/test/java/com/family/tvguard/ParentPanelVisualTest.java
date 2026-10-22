package com.family.tvguard;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.provider.Settings;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.TextAppearanceSpan;
import android.view.KeyEvent;
import android.view.View;
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
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Native-rendered previews use synthetic settings; device verification is still required. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ParentPanelVisualTest {
    private Context context;
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
        prefs.setUsedSeconds(136 * 60L);
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
    public void enabledPanelHasReadableFocusedTitleAndShortcut() throws Exception {
        start(true);
        Button button = activity.findViewById(android.R.id.content).findViewWithTag("action_12");
        assertTrue(button.hasFocus());
        assertEquals(Color.WHITE, button.getCurrentTextColor());
        Spanned text = (Spanned) button.getText();
        for (TextAppearanceSpan span : text.getSpans(0, text.length(), TextAppearanceSpan.class)) {
            TextPaint paint = new TextPaint();
            paint.drawableState = button.getDrawableState();
            span.updateDrawState(paint);
            assertEquals("Focused text spans must not retain dark normal-state colors", Color.WHITE, paint.getColor());
        }
        capture(activity.getWindow().getDecorView(), "tv-enabled");
    }

    @Test
    public void pageTitleAlignsWithShortcutHeadingAndContentStartsTogether() {
        start(true);
        TextView pageTitle = activity.findViewById(R.id.panel_title);
        TextView shortcuts = activity.findViewById(R.id.menu_heading);
        Rect pageTitleBounds = new Rect();
        Rect shortcutBounds = new Rect();
        assertTrue(pageTitle.getGlobalVisibleRect(pageTitleBounds));
        assertTrue(shortcuts.getGlobalVisibleRect(shortcutBounds));
        assertTrue("Page and shortcut titles must share the same center line",
                Math.abs(pageTitleBounds.centerY() - shortcutBounds.centerY()) <= 1);

        View card = activity.findViewById(R.id.status_card);
        Rect cardBounds = new Rect();
        assertTrue(card.getGlobalVisibleRect(cardBounds));
        for (String code : new String[] {"00", "11"}) {
            View button = activity.findViewById(android.R.id.content)
                    .findViewWithTag("action_" + code);
            Rect buttonBounds = new Rect();
            assertTrue(button.getGlobalVisibleRect(buttonBounds));
            assertEquals("Card border and actual button " + code + " must share their top edge",
                    cardBounds.top, buttonBounds.top);
        }

        TextView overview = activity.findViewById(R.id.status_heading);
        Rect overviewBounds = new Rect();
        assertTrue(overview.getGlobalVisibleRect(overviewBounds));
        assertTrue("Overview title needs breathing room below the card border",
                overviewBounds.top - cardBounds.top >= Math.round(10 * activity.getResources()
                        .getDisplayMetrics().density));
        View hint = activity.findViewById(R.id.menu_hint);
        assertTrue("Shortcut hint must stay on the heading row", hint.getTop() < shortcuts.getBottom());
    }

    @Test
    public void unlimitedStateAndBonusRemainVisibleWithoutChangingDailyQuota() throws Exception {
        prefs.setUnlockedToday(true);
        prefs.setBonusSeconds(600L);
        start(true);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.today_override).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.bonus_summary).getVisibility());
        assertEquals(30, prefs.getDailyLimitMinutes());
        for (int id : new int[] {R.id.today_override, R.id.bonus_summary, R.id.warn_text, R.id.version_text}) {
            View view = activity.findViewById(id);
            Rect visible = new Rect();
            assertTrue("TV summary element must be visible: " + getClass().getSimpleName()
                    + " id=" + id + " height=" + view.getHeight(), view.getGlobalVisibleRect(visible));
            assertEquals("Summary, permission notices and version must fit together on TV", view.getHeight(), visible.height());
        }
        capture(activity.getWindow().getDecorView(), "tv-unlimited");
    }

    @Test
    public void disconnectedSettingsProtectionWarningAndVersionFitOnTheTvPanel() throws Exception {
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new android.content.ComponentName(context, AppLockService.class).flattenToString());
        prefs.setUnlockedToday(true);
        prefs.setBonusSeconds(600L);
        start(true);

        assertTrue(((TextView) activity.findViewById(R.id.warn_text)).getText().toString()
                .contains("设置保护未连接"));
        for (int id : new int[] {R.id.warn_text, R.id.version_text}) {
            View view = activity.findViewById(id);
            Rect visible = new Rect();
            assertTrue(view.getGlobalVisibleRect(visible));
            assertEquals("Recovery warning and version must fit on TV", view.getHeight(), visible.height());
        }
        capture(activity.getWindow().getDecorView(), "tv-settings-recovery");
    }

    @Test
    public void thirtyMinuteQuotaShowsTheNativeLockScreen() throws Exception {
        AdminSession.revoke();
        prefs.setUsedSeconds(30 * 60L);
        assertEquals(30, prefs.getDailyLimitMinutes());
        assertEquals(0L, prefs.remainingSeconds());
        ServiceController<GuardService> guard = Robolectric.buildService(GuardService.class).create();
        ActivityController<LockActivity> lock = null;
        try {
            assertTrue(guard.get().isLocked());
            lock = Robolectric.buildActivity(LockActivity.class).create().start().resume().visible()
                    .windowFocusChanged(true);
            ShadowLooper.idleMainLooper();
            LockActivity screen = lock.get();
            assertEquals("今日电视时长已用完", ((TextView) screen.findViewById(R.id.lock_title)).getText().toString());
            assertEquals("输入 6 位家长密码解除限时", ((TextView) screen.findViewById(R.id.lock_prompt)).getText().toString());
            assertEquals("", ((TextView) screen.findViewById(R.id.lock_input)).getText().toString());
            capture(screen.getWindow().getDecorView(), "tv-quota-used");
        } finally {
            if (lock != null) {
                lock.pause().stop().destroy();
            }
            guard.destroy();
        }
    }

    @Test
    public void disabledPanelAndTemporaryEntryDialogUseTheDimPanelPalette() throws Exception {
        start(false);
        assertEquals(View.GONE, activity.findViewById(R.id.warn_text).getVisibility());
        capture(activity.getWindow().getDecorView(), "tv-disabled");
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2));
        ShadowLooper.idleMainLooper();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog instanceof PinDialog);
        assertTrue(dialog.isShowing());
        capture(dialog.getWindow().getDecorView(), "duration-dialog");
        assertEquals(30, prefs.getDailyLimitMinutes());
    }

    @Test
    @Config(qualifiers = "w360dp-h480dp-port-xhdpi")
    public void narrowPanelUsesTheSameNativeComponents() throws Exception {
        start(true);
        capture(activity.getWindow().getDecorView(), "narrow");
    }

    @Test
    public void panelSurfacesAreDimButTextAndFocusRemainReadable() {
        int canvas = context.getResources().getColor(R.color.panel_canvas);
        int surface = context.getResources().getColor(R.color.panel_surface);
        assertTrue("TV canvas must not return to a bright light theme", luminance(canvas) < 0.03);
        assertTrue("Large panel surfaces must stay low luminance", luminance(surface) < 0.06);
        for (int color : new int[] {R.color.panel_ink, R.color.panel_muted, R.color.panel_blue_text}) {
            assertTrue("All panel text needs readable contrast", contrast(context.getResources().getColor(color), surface) >= 4.5);
        }
        for (int color : new int[] {R.color.panel_blue, R.color.panel_blue_deep, R.color.panel_danger_focus}) {
            assertTrue("Selected button text must contrast against its fill", contrast(Color.WHITE,
                    context.getResources().getColor(color)) >= 4.5);
        }
        assertTrue(contrast(context.getResources().getColor(R.color.panel_green),
                context.getResources().getColor(R.color.panel_green_soft)) >= 4.5);
        assertTrue(contrast(context.getResources().getColor(R.color.panel_amber),
                context.getResources().getColor(R.color.panel_amber_soft)) >= 4.5);
        assertTrue(contrast(context.getResources().getColor(R.color.panel_red),
                context.getResources().getColor(R.color.panel_red_soft)) >= 4.5);
    }

    private double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private double luminance(int color) {
        return linear(Color.red(color)) * 0.2126 + linear(Color.green(color)) * 0.7152
                + linear(Color.blue(color)) * 0.0722;
    }

    private double linear(int component) {
        double value = component / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private void start(boolean enabled) {
        prefs.setProtectionEnabled(enabled);
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()
                .windowFocusChanged(true);
        activity = controller.get();
        ShadowLooper.idleMainLooper();
    }

    private void capture(View view, String name) throws Exception {
        File directory = new File("build/outputs/ui-preview");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            image.recycle();
        }
    }
}
