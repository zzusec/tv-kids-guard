package com.family.tvguard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.KeyEvent;
import android.widget.Button;

/**
 * Remote-key vocabulary shared by every guard screen.
 *
 * <p>There is deliberately no on-screen number pad: a highlighted virtual key would show the
 * parent PIN to anyone in the room. All PIN entry uses the remote's own number keys.
 */
public final class RemoteKeypad {

    private RemoteKeypad() {}

    public static boolean isNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    public static boolean isSelectKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT;
    }

    /** Shared pill-button styling for the parent panel; the caller owns layout parameters. */
    public static void styleButton(Button button) {
        button.setBackgroundResource(R.drawable.remote_button_bg);
        button.setBackgroundTintList(null);
        button.setTextColor(new ColorStateList(new int[][] {
                {-android.R.attr.state_enabled}, {}
        }, new int[] {Color.parseColor("#7A7F87"), Color.WHITE}));
        button.setTextSize(21);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(button.getContext(), 52));
        button.setMinimumHeight(dp(button.getContext(), 52));
        button.setPadding(dp(button.getContext(), 16), dp(button.getContext(), 8),
                dp(button.getContext(), 16), dp(button.getContext(), 8));
        button.setStateListAnimator(null);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
    }

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
