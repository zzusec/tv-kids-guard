package com.family.tvguard;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Native previews and geometry checks, not a substitute for testing a Xiaomi TV. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NumericEntryVisualTest {
    private Context context;
    private ActivityController<? extends Activity> controller;
    private PinDialog dialog;
    private ServiceController<GuardService> service;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        new Prefs(context).setPassword("135790");
        AdminSession.revoke();
    }

    @After
    public void tearDown() {
        if (dialog != null) dialog.dismiss();
        if (controller != null) controller.pause().stop().destroy();
        if (service != null) service.destroy();
        AdminSession.revoke();
    }

    @Test
    public void allNumericEntrySurfacesFitATypicalTv() throws Exception {
        captureSurfaces("960x540");
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    public void allNumericEntrySurfacesFitAShortTvViewport() throws Exception {
        captureSurfaces("640x360");
    }

    private void captureSurfaces(String size) throws Exception {
        controller = Robolectric.buildActivity(AdminAuthActivity.class).setup().visible()
                .windowFocusChanged(true);
        ShadowLooper.idleMainLooper();
        View gate = controller.get().getWindow().getDecorView();
        capture(gate, "keypad-gate-" + size);
        assertPadVisible(gate);
        assertFullyVisible(gate.findViewById(R.id.lock_input));
        controller.pause().stop().destroy();
        controller = null;

        Prefs prefs = new Prefs(context);
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        prefs.setLocked(true);
        service = Robolectric.buildService(GuardService.class).create();
        controller = Robolectric.buildActivity(LockActivity.class).setup().visible()
                .windowFocusChanged(true);
        ShadowLooper.idleMainLooper();
        View lock = controller.get().getWindow().getDecorView();
        assertPadVisible(lock);
        assertFullyVisible(lock.findViewById(R.id.lock_input));
        capture(lock, "keypad-lock-" + size);
        controller.pause().stop().destroy();
        controller = null;
        service.destroy();
        service = null;

        for (boolean password : new boolean[] {false, true}) {
            dialog = new PinDialog(context, password ? "再次验证家长密码" : "每日时长",
                    password ? "输入当前 6 位家长密码" : "当前 30 分钟\n输入 1-1440 分钟，选择「确认」保存",
                    password ? 6 : 4, password, value -> {});
            dialog.show();
            ShadowLooper.idleMainLooper();
            View surface = dialog.getWindow().getDecorView();
            assertPadVisible(surface);
            capture(surface, "keypad-" + (password ? "pin" : "duration") + "-" + size);
            dialog.dismiss();
            dialog = null;
        }
    }

    private void assertPadVisible(View root) {
        for (String key : new String[] {"1", "2", "3", "4", "5", "6", "7", "8", "9",
                "delete", "0", "confirm"}) {
            View button = root.findViewWithTag("key_" + key);
            assertNotNull(button);
            assertFullyVisible(button);
            assertTrue(button.getHeight() >= 48);
            assertTrue(button.getWidth() >= 48);
        }
    }

    private void assertFullyVisible(View view) {
        Rect visible = new Rect();
        assertTrue("Input controls must be visible", view.getGlobalVisibleRect(visible));
        assertEquals("Input control must not be clipped vertically: " + view.getTag()
                + " bounds=" + visible + " top=" + view.getTop() + " rootHeight="
                + view.getRootView().getHeight(), view.getHeight(), visible.height());
        assertEquals("Input control must not be clipped horizontally", view.getWidth(), visible.width());
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
