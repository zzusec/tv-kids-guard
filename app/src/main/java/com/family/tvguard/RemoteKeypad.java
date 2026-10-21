package com.family.tvguard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TextAppearanceSpan;
import android.view.Gravity;
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

    /** Native Preline-style action: remote shortcut and title. */
    public static void styleButton(Button button, String code, String label) {
        boolean danger = "44".equals(code);
        Context context = button.getContext();
        button.setBackgroundResource(danger ? R.drawable.parent_danger_bg : R.drawable.parent_button_bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setBackgroundTintList(null);
            button.setStateListAnimator(null);
        }
        int ink = context.getResources().getColor(danger ? R.color.panel_red : R.color.panel_ink);
        int blue = context.getResources().getColor(danger ? R.color.panel_red : R.color.panel_blue_text);
        button.setTextColor(actionColors(ink, Color.WHITE));
        button.setTextSize(18);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 60));
        button.setMinimumHeight(dp(context, 60));
        button.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        String title = code + " " + label;
        SpannableString text = new SpannableString(title);
        text.setSpan(new TextAppearanceSpan("sans-serif-medium", Typeface.NORMAL, 0,
                actionColors(blue, Color.WHITE), null), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(text);
    }

    private static ColorStateList actionColors(int normal, int selected) {
        return new ColorStateList(new int[][] {
                {-android.R.attr.state_enabled}, {android.R.attr.state_pressed},
                {android.R.attr.state_focused}, {}
        }, new int[] {Color.parseColor("#68778D"), selected, selected, normal});
    }

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
