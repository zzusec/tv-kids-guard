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
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land-mdpi",
        shadows = RemoteInputModeTest.DigitInputDevice.class)
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
        context.getSharedPreferences("tvguard_remote", 0).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        AdminSession.revoke();
        // These screens are written for a remote that can type digits, so the hardware answer has
        // to say so: since 2.5.13 nothing else decides whether the pad appears.
        withDigitRemote();
    }

    @After
    public void tearDown() {
        if (dialog != null) dialog.dismiss();
        if (controller != null) controller.pause().stop().destroy();
        if (service != null) service.destroy();
        context.getSharedPreferences("tvguard_remote", 0).edit().clear().commit();
        RemoteInputModeTest.DigitInputDevice.reset();
        AdminSession.revoke();
    }

    @Test
    public void physicalGateHasNoNumberGridAndKeepsMaskedDirectEntry() {
        AdminAuthActivity activity = startGate();
        assertEquals(View.GONE, activity.findViewById(R.id.keypad_column).getVisibility());
        assertNull("2.5.13 removed the manual input switch",
                activity.getWindow().getDecorView().findViewWithTag("keypad_toggle"));
        assertFalse(activity.findViewById(android.R.id.content).findViewWithTag("key_1").isShown());
        assertFalse("A remote that can type digits is not even offered the icon",
                ScreenRemote.offersIcon(activity));
        press(activity.getWindow(), KeyEvent.KEYCODE_1);
        press(activity.getWindow(), KeyEvent.KEYCODE_3);
        assertEquals("●●", ((TextView) activity.findViewById(R.id.lock_input)).getText().toString());
        press(activity.getWindow(), KeyEvent.KEYCODE_DPAD_LEFT);
        digits(activity.getWindow(), "35790");
        assertTrue(AdminSession.isActive());
        assertTrue(activity.isFinishing());
    }

    @Test
    public void aRemoteWithoutNumberKeysIsOfferedAnIconThatOpensThePad() {
        RemoteInputModeTest.DigitInputDevice.reset();
        AdminAuthActivity activity = startGate();
        View root = activity.getWindow().getDecorView();
        assertNull(root.findViewWithTag("keypad_toggle"));
        assertEquals(View.GONE, activity.findViewById(R.id.keypad_column).getVisibility());
        assertSame(activity.findViewById(R.id.lock_input), root.findFocus());
        assertTrue("The pad is behind an icon, not on the screen", ScreenRemote.offersIcon(activity));
        ScreenRemote.openPad(activity);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.keypad_column).getVisibility());
        assertEquals("key_1", root.findFocus().getTag());
        ScreenRemote.digits(activity, "135790");
        assertTrue("The pad must still be enough to authorize a parent", AdminSession.isActive());
        assertTrue(activity.isFinishing());
    }

    @Test
    public void aRemotePairedAfterTheScreenOpenedRetiresThePadAndTheIcon() {
        RemoteInputModeTest.DigitInputDevice.reset();
        AdminAuthActivity activity = startGate();
        ScreenRemote.openPad(activity);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.keypad_column).getVisibility());
        withDigitRemote();
        controller.pause().resume();
        ShadowLooper.idleMainLooper();
        assertEquals(View.GONE, activity.findViewById(R.id.keypad_column).getVisibility());
        assertFalse("A remote that proved its digits loses the icon too",
                ScreenRemote.offersIcon(activity));
        assertFalse(AdminSession.isActive());
    }

    @Test
    public void anOpenedPadStaysUpOnItsOwnScreenAndTheNextScreenStartsBehindTheIcon() {
        RemoteInputModeTest.DigitInputDevice.reset();
        AdminAuthActivity activity = startGate();
        ScreenRemote.openPad(activity);
        controller.pause().resume();
        ShadowLooper.idleMainLooper();
        assertTrue("The screen the parent chose keeps its pad across resume",
                ScreenRemote.padIsOpen(activity));
        List<String> values = new ArrayList<>();
        showDialog(values);
        assertFalse("A new screen is not decided by the last one",
                dialogRoot().findViewWithTag("key_1").isShown());
        assertTrue(dialogRoot().findViewWithTag("keypad_request").isShown());
        dialog.dismiss();
        assertTrue(values.isEmpty());
    }

    @Test
    public void padEntryOnTheLockScreenNeitherBuysTimeNorRelinquishesTheLock() {
        prefs.setUsedSeconds(1800L);
        prefs.setLocked(true);
        RemoteInputModeTest.DigitInputDevice.reset();
        service = Robolectric.buildService(GuardService.class).create();
        controller = Robolectric.buildActivity(LockActivity.class).setup().visible()
                .windowFocusChanged(true);
        ShadowLooper.idleMainLooper();
        Activity activity = controller.get();
        assertNull(activity.getWindow().getDecorView().findViewWithTag("keypad_toggle"));
        assertFalse("The lock screen keeps its clean layout", ScreenRemote.padIsOpen(activity));
        assertTrue(ScreenRemote.offersIcon(activity));
        // The PIN proves a parent is present: it authorizes the panel, it never buys viewing time.
        ScreenRemote.digits(activity, "135790");
        assertTrue(AdminSession.isActive());
        assertTrue(service.get().isLocked());
        assertEquals(1800L, prefs.getUsedSeconds());
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
    public void anUnprovenRemoteKeepsThePadBehindTheIconOnEveryScreen() {
        RemoteInputModeTest.DigitInputDevice.reset();
        AdminAuthActivity activity = startGate();
        List<String> values = new ArrayList<>();
        showDialog(values);
        assertFalse("A numberless remote gets an icon, not a grid",
                dialogRoot().findViewWithTag("key_1").isShown());
        openDialogPad();
        dialog.dismiss();
        showDialog(values);
        assertFalse(dialogRoot().findViewWithTag("key_1").isShown());
        openDialogPad();
        dialog.dismiss();
        controller.pause().resume();
        ShadowLooper.idleMainLooper();
        assertFalse(activity.getWindow().getDecorView().findViewWithTag("key_1").isShown());
        assertTrue(ScreenRemote.offersIcon(activity));
        assertFalse(AdminSession.isActive());
        assertTrue(values.isEmpty());
    }

    private void withDigitRemote() {
        RemoteInputModeTest.DigitInputDevice.reset();
        RemoteInputModeTest.DigitInputDevice.add(false, android.view.InputDevice.SOURCE_KEYBOARD,
                RemoteInputModeTest.digits(KeyEvent.KEYCODE_0, 0, 10));
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

    private View dialogRoot() {
        return dialog.getWindow().getDecorView();
    }

    /** The dialog offers the same icon as the full screens, reached the same way. */
    private void openDialogPad() {
        View root = dialogRoot();
        View request = root.findViewWithTag("keypad_request");
        assertNotNull(request);
        assertTrue(request.isShown());
        press(dialog.getWindow(), KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("keypad_request", root.findFocus().getTag());
        press(dialog.getWindow(), KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue("OK on the icon opens the dialog pad", root.findViewWithTag("key_1").isShown());
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
