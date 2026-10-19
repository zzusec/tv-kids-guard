package com.family.tvguard;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class RemoteKeypadTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private RemoteKeypad keypad;
    private final List<String> calls = new ArrayList<>();

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        keypad = new RemoteKeypad(activity, new RemoteKeypad.Listener() {
            @Override public void onDigit(int digit) { calls.add(String.valueOf(digit)); }
            @Override public void onDelete() { calls.add("delete"); }
            @Override public void onConfirm() { calls.add("confirm"); }
        });
        activity.setContentView(keypad);
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    @Test
    public void twelveKeysHaveUniqueIdsDefaultFocusAndClosedReachableNeighbors() {
        assertEquals(4, keypad.getChildCount());
        assertSame(key("1"), keypad.findFocus());
        Set<Integer> ids = new HashSet<>();
        for (int row = 0; row < 4; row++) {
            ViewGroup line = (ViewGroup) keypad.getChildAt(row);
            assertEquals(3, line.getChildCount());
            for (int col = 0; col < 3; col++) {
                Button button = (Button) line.getChildAt(col);
                assertTrue(ids.add(button.getId()));
                assertNotEquals(View.NO_ID, button.getId());
                for (int direction : new int[] {View.FOCUS_LEFT, View.FOCUS_RIGHT,
                        View.FOCUS_UP, View.FOCUS_DOWN}) {
                    View next = button.focusSearch(direction);
                    assertNotNull(next);
                    assertTrue(next.getTag().toString().startsWith("key_"));
                }
            }
        }
        Set<View> reached = new HashSet<>();
        reached.add(key("1"));
        for (int pass = 0; pass < 12; pass++) {
            for (View view : new ArrayList<>(reached)) {
                for (int direction : new int[] {View.FOCUS_LEFT, View.FOCUS_RIGHT,
                        View.FOCUS_UP, View.FOCUS_DOWN}) {
                    reached.add(view.focusSearch(direction));
                }
            }
        }
        assertEquals(12, reached.size());
        for (int digit = 0; digit <= 9; digit++) {
            key(String.valueOf(digit)).performClick();
        }
        key("delete").performClick();
        key("confirm").performClick();
        assertEquals(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
                "delete", "confirm"), calls);
    }

    @Test
    public void eachSelectVariantClicksOnlyOnReleaseAndNeverRepeats() {
        Button confirm = key("confirm");
        confirm.requestFocus();
        for (int code : new int[] {KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT}) {
            int count = calls.size();
            confirm.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, code, 0, 0));
            confirm.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, code, 1, KeyEvent.FLAG_LONG_PRESS));
            confirm.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, code, 2, 0));
            assertEquals(count, calls.size());
            confirm.dispatchKeyEvent(event(KeyEvent.ACTION_UP, code, 0, 0));
            confirm.dispatchKeyEvent(event(KeyEvent.ACTION_UP, code, 0, 0));
            assertEquals(count + 1, calls.size());
        }
    }

    @Test
    public void canceledSelectAndUnpairedReleaseDoNotClick() {
        Button button = key("1");
        button.dispatchKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 0, 0));
        button.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0, 0));
        button.dispatchKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 0,
                KeyEvent.FLAG_CANCELED));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void disablingKeepsViewsAndFocusGraphAndPreventsEveryEntryPath() {
        Button button = key("5");
        button.requestFocus();
        int next = button.getNextFocusDownId();
        keypad.setInputEnabled(false);
        for (int row = 0; row < 4; row++) {
            ViewGroup line = (ViewGroup) keypad.getChildAt(row);
            for (int column = 0; column < 3; column++) {
                View key = line.getChildAt(column);
                assertFalse(key.isEnabled());
                key.performClick();
                key.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0));
                key.dispatchKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0));
            }
        }
        assertTrue(calls.isEmpty());
        assertSame(button, key("5"));
        assertEquals(next, button.getNextFocusDownId());
        keypad.setInputEnabled(true);
        assertSame(button, keypad.findFocus());
        button.performClick();
        assertEquals(List.of("5"), calls);
    }

    @Test
    public void sharedStylingKeepsLayoutParamsAndKeyboardHeightFitsTv() {
        Button button = new Button(activity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(123, 456);
        button.setLayoutParams(params);
        RemoteKeypad.styleButton(button);
        assertSame(params, button.getLayoutParams());
        assertTrue(button.isFocusable());
        assertNotNull(button.getBackground());
        keypad.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        float density = activity.getResources().getDisplayMetrics().density;
        assertTrue(keypad.getMeasuredHeight() <= Math.round(240 * density));
    }

    @Test
    public void keyPredicatesAcceptOnlyContractKeys() {
        for (int code : new int[] {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT}) {
            assertTrue(RemoteKeypad.isNavigationKey(code));
            assertFalse(RemoteKeypad.isSelectKey(code));
        }
        for (int code : new int[] {KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT}) {
            assertTrue(RemoteKeypad.isSelectKey(code));
            assertFalse(RemoteKeypad.isNavigationKey(code));
        }
        for (int code : new int[] {KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_DEL}) {
            assertFalse(RemoteKeypad.isSelectKey(code));
            assertFalse(RemoteKeypad.isNavigationKey(code));
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp")
    public void narrowLockLayoutStacksDetailsWithoutWeightOrOversizedColumn() {
        activity.setContentView(R.layout.lock_screen);
        RemoteKeypad.adaptLockLayout(activity);
        LinearLayout content = activity.findViewById(R.id.lock_content);
        View details = activity.findViewById(R.id.lock_details);
        View column = activity.findViewById(R.id.keypad_column);
        assertEquals(LinearLayout.VERTICAL, content.getOrientation());
        assertEquals(0f, ((LinearLayout.LayoutParams) details.getLayoutParams()).weight, 0f);
        assertEquals(0, details.getPaddingEnd());
        assertEquals(Math.round(256 * activity.getResources().getDisplayMetrics().density),
                column.getLayoutParams().width);
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp")
    public void wideLockLayoutKeepsTwoColumns() {
        activity.setContentView(R.layout.lock_screen);
        RemoteKeypad.adaptLockLayout(activity);
        LinearLayout content = activity.findViewById(R.id.lock_content);
        View details = activity.findViewById(R.id.lock_details);
        View column = activity.findViewById(R.id.keypad_column);
        assertEquals(LinearLayout.HORIZONTAL, content.getOrientation());
        assertEquals(1f, ((LinearLayout.LayoutParams) details.getLayoutParams()).weight, 0f);
        assertEquals(Math.round(320 * activity.getResources().getDisplayMetrics().density),
                column.getLayoutParams().width);
    }

    private Button key(String suffix) {
        Button result = keypad.findViewWithTag("key_" + suffix);
        assertNotNull(result);
        return result;
    }

    private KeyEvent event(int action, int key, int repeats, int flags) {
        return new KeyEvent(0, 1, action, key, repeats, 0, -1, 0, flags);
    }
}
