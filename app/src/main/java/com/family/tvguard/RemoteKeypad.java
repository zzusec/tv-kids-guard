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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * Remote-key vocabulary shared by every guard screen.
 *
 * <p>Physical number keys and the on-screen pad use the same input callbacks. The pad needs only
 * directions and OK, so numberless TV remotes never depend on a vendor's input method.
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

    interface NumericListener {
        void onDigit(int digit);
        void onDelete();
        void onConfirm();
    }

    static final class NumericPad extends LinearLayout {
        private final Button[] keys = new Button[12];
        private boolean virtualInput = true;
        private View upperFocusTarget;
        private Button armedButton;
        private int armedSelectKey;

        NumericPad(Context context, NumericListener listener) {
            super(context);
            setOrientation(VERTICAL);
            setTag("numeric_keypad");
            String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "删除", "0", "确认"};
            for (int row = 0; row < 4; row++) {
                LinearLayout line = new LinearLayout(context);
                line.setOrientation(HORIZONTAL);
                line.setBaselineAligned(false);
                LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = row == 0 ? 0 : dp(context, 8);
                addView(line, rowParams);
                for (int column = 0; column < 3; column++) {
                    final int index = row * 3 + column;
                    Button key = new Button(context);
                    key.setId(View.generateViewId());
                    key.setTag(index == 9 ? "key_delete" : index == 11 ? "key_confirm"
                            : "key_" + (index == 10 ? 0 : index + 1));
                    key.setText(labels[index]);
                    key.setTextSize(index == 9 || index == 11 ? 18 : 24);
                    key.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    key.setTextColor(actionColors(context.getResources().getColor(R.color.panel_ink),
                            Color.WHITE));
                    key.setBackgroundResource(R.drawable.parent_button_bg);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        key.setBackgroundTintList(null);
                        key.setStateListAnimator(null);
                    }
                    key.setAllCaps(false);
                    key.setGravity(Gravity.CENTER);
                    key.setIncludeFontPadding(false);
                    key.setPadding(0, 0, 0, 0);
                    key.setMinWidth(0);
                    key.setMinimumWidth(0);
                    key.setMinHeight(dp(context, 48));
                    key.setMinimumHeight(dp(context, 48));
                    key.setFocusable(true);
                    key.setFocusableInTouchMode(true);
                    key.setOnClickListener(view -> {
                        if (!isEnabled() || !key.isEnabled()) return;
                        if (index == 9) listener.onDelete();
                        else if (index == 11) listener.onConfirm();
                        else listener.onDigit(index == 10 ? 0 : index + 1);
                    });
                    LayoutParams cell = new LayoutParams(0, dp(context, 48), 1f);
                    cell.rightMargin = column == 2 ? 0 : dp(context, 8);
                    line.addView(key, cell);
                    keys[index] = key;
                }
            }
            focusFirstKey();
        }

        void setUpperFocusTarget(View target) {
            upperFocusTarget = target;
        }

        void focusFirstKey() {
            virtualInput = true;
            cancelSelect();
            if (isEnabled() && getVisibility() == VISIBLE) keys[0].requestFocus();
        }

        void usePhysicalInput() {
            virtualInput = false;
            cancelSelect();
        }

        /** Preserve physical-entry LEFT/delete and OK/submit until a direction selects the pad. */
        boolean handleKeyEvent(KeyEvent event) {
            if (getVisibility() != VISIBLE) return false;
            int code = event.getKeyCode();
            boolean navigation = isNavigationKey(code);
            if (!navigation && !isSelectKey(code)) return false;
            if (!virtualInput) {
                if (!navigation || code == KeyEvent.KEYCODE_DPAD_LEFT) return false;
                focusFirstKey();
            }
            if (!isEnabled()) return true;
            View focused = findFocus();
            if (focused == null) return false;
            if (navigation) {
                cancelSelect();
                if (event.getAction() == KeyEvent.ACTION_DOWN) moveFocus(focused, code);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                cancelSelect();
                armedButton = (Button) focused;
                armedSelectKey = code;
                armedButton.setPressed(true);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                Button selected = armedButton;
                boolean activate = selected != null && selected == focused && armedSelectKey == code
                        && !event.isCanceled() && selected.isEnabled();
                cancelSelect();
                if (activate) selected.performClick();
            }
            return true;
        }

        private void moveFocus(View focused, int code) {
            int index = 0;
            while (index < keys.length && keys[index] != focused) index++;
            if (index == keys.length) return;
            int row = index / 3;
            int column = index % 3;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (row == 0 && upperFocusTarget != null && upperFocusTarget.isEnabled()) {
                        upperFocusTarget.requestFocus();
                        return;
                    }
                    row = Math.max(0, row - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN: row = Math.min(3, row + 1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: column = Math.max(0, column - 1); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: column = Math.min(2, column + 1); break;
                default: return;
            }
            keys[row * 3 + column].requestFocus();
        }

        @Override
        public void setEnabled(boolean enabled) {
            if (enabled == isEnabled()) return;
            super.setEnabled(enabled);
            cancelSelect();
            if (keys != null) {
                for (Button key : keys) if (key != null) key.setEnabled(enabled);
                if (enabled && getRootView().findFocus() == null) focusFirstKey();
            }
        }

        private void cancelSelect() {
            if (armedButton != null) armedButton.setPressed(false);
            armedButton = null;
            armedSelectKey = 0;
        }
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
