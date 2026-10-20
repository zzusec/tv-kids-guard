package com.family.tvguard;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** IME-free, focus-contained number pad for television remotes. */
public final class RemoteKeypad extends LinearLayout {

    public interface Listener {
        void onDigit(int digit);
        void onDelete();
        void onConfirm();
    }

    private final Button[] keys = new Button[12];
    private boolean inputEnabled = true;
    private View savedFocus;
    private View focusedKey;

    public RemoteKeypad(Context context, Listener listener) {
        super(context);
        setOrientation(VERTICAL);
        setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        int[] digits = {1, 2, 3, 4, 5, 6, 7, 8, 9, -1, 0, -2};
        int gap = dp(context, 4);
        for (int row = 0; row < 4; row++) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(HORIZONTAL);
            addView(line, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            for (int column = 0; column < 3; column++) {
                int index = row * 3 + column;
                int digit = digits[index];
                Button key = new Button(context);
                key.setId(View.generateViewId());
                key.setTag(digit >= 0 ? "key_" + digit : digit == -1 ? "key_delete" : "key_confirm");
                key.setText(digit >= 0 ? String.valueOf(digit) : digit == -1 ? "删除" : "确认");
                styleButton(key);
                key.setOnClickListener(view -> {
                    if (!inputEnabled) {
                        return;
                    }
                    if (digit >= 0) {
                        listener.onDigit(digit);
                    } else if (digit == -1) {
                        listener.onDelete();
                    } else {
                        listener.onConfirm();
                    }
                });
                // All select variants click on release, exactly once, including a held OK key.
                key.setOnKeyListener(new OnKeyListener() {
                    private int pressedKey = KeyEvent.KEYCODE_UNKNOWN;

                    @Override
                    public boolean onKey(View view, int keyCode, KeyEvent event) {
                        if (!isSelectKey(keyCode)) {
                            return false;
                        }
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            if (event.getRepeatCount() == 0 && inputEnabled) {
                                pressedKey = keyCode;
                                view.setPressed(true);
                            }
                        } else if (event.getAction() == KeyEvent.ACTION_UP) {
                            boolean click = pressedKey == keyCode && view.isPressed()
                                    && inputEnabled && !event.isCanceled();
                            pressedKey = KeyEvent.KEYCODE_UNKNOWN;
                            view.setPressed(false);
                            if (click) {
                                view.performClick();
                            }
                        }
                        return true;
                    }
                });
                key.setOnFocusChangeListener((view, hasFocus) -> {
                    if (hasFocus) {
                        focusedKey = view;
                    } else if (focusedKey == view) {
                        focusedKey = null;
                    }
                    if (!hasFocus) {
                        view.setPressed(false);
                    }
                });
                LayoutParams params = new LayoutParams(0, dp(context, 52), 1f);
                params.setMargins(gap, gap, gap, gap);
                line.addView(key, params);
                keys[index] = key;
            }
        }
        for (int i = 0; i < keys.length; i++) {
            int row = i / 3;
            int column = i % 3;
            keys[i].setNextFocusLeftId(keys[row * 3 + (column + 2) % 3].getId());
            keys[i].setNextFocusRightId(keys[row * 3 + (column + 1) % 3].getId());
            keys[i].setNextFocusUpId(keys[((row + 3) % 4) * 3 + column].getId());
            keys[i].setNextFocusDownId(keys[((row + 1) % 4) * 3 + column].getId());
            keys[i].setNextFocusForwardId(keys[(i + 1) % keys.length].getId());
        }
        keys[0].requestFocus();
    }

    /** Focus the "1" key; used by the parent gate before it dispatches the first OK. */
    public void focusFirst() {
        keys[0].requestFocus();
    }

    /**
     * Clicks the key that currently owns the focus ring.
     *
     * <p>Television remotes are inconsistent about delivering OK to the focused view: some
     * firmwares never run the button's key handler, so the screen looks focused but inert.
     * Activating the focused key from the activity's own dispatch removes that dependency.
     *
     * @return true when a key was activated, false when some other control holds the focus.
     */
    public boolean activateFocused() {
        if (!inputEnabled) {
            return false;
        }
        // Ask the view hierarchy directly: a re-layout can move focus without our listener
        // observing a matching pair of callbacks, which used to leave focusedKey stale/null and
        // made the keypad stop after the first digit.
        View key = findFocus();
        if (key == null || key.getTag() == null
                || !key.getTag().toString().startsWith("key_")) {
            key = focusedKey;
        }
        if (key == null || !key.isEnabled() || !key.isShown()) {
            return false;
        }
        focusedKey = key;
        key.performClick();
        return true;
    }

    /** Disable entry without replacing views or changing their focus-neighbor graph. */
    public void setInputEnabled(boolean enabled) {
        if (inputEnabled == enabled) {
            return;
        }
        if (!enabled) {
            savedFocus = findFocus();
        }
        inputEnabled = enabled;
        for (Button key : keys) {
            key.setEnabled(enabled);
            key.setPressed(false);
        }
        if (enabled) {
            (savedFocus == null ? keys[0] : savedFocus).requestFocus();
        }
    }

    public static boolean isNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    public static boolean isSelectKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT;
    }

    /** Shared remote-button styling; the caller retains ownership of layout parameters. */
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

    /** Keep the shared lock/auth screen usable on narrow, high-density television displays. */
    public static void adaptLockLayout(Activity activity) {
        LinearLayout content = activity.findViewById(R.id.lock_content);
        LinearLayout details = activity.findViewById(R.id.lock_details);
        LinearLayout column = activity.findViewById(R.id.keypad_column);
        int widthDp = activity.getResources().getConfiguration().screenWidthDp;
        if (widthDp <= 0) {
            widthDp = Math.round(activity.getResources().getDisplayMetrics().widthPixels
                    / activity.getResources().getDisplayMetrics().density);
        }
        boolean narrow = widthDp < 600;
        content.setOrientation(narrow ? VERTICAL : HORIZONTAL);
        LayoutParams detailsParams = (LayoutParams) details.getLayoutParams();
        detailsParams.width = narrow ? LayoutParams.MATCH_PARENT : 0;
        detailsParams.weight = narrow ? 0f : 1f;
        details.setLayoutParams(detailsParams);
        details.setPaddingRelative(0, 0, narrow ? 0 : dp(activity, 24), 0);
        LayoutParams columnParams = (LayoutParams) column.getLayoutParams();
        columnParams.width = dp(activity, Math.max(0, Math.min(320, widthDp - 64)));
        column.setLayoutParams(columnParams);
    }

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
