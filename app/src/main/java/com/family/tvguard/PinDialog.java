package com.family.tvguard;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** IME-free PIN or numeric entry, operable with either number keys or a DPAD-only remote. */
final class PinDialog extends Dialog {

    interface Listener {
        void onConfirm(String value);
    }

    private final String title;
    private final String message;
    private final int maxLength;
    private final boolean password;
    private final Listener listener;
    private final StringBuilder input = new StringBuilder();
    private TextView inputView;
    private boolean navigationMode = true;
    private boolean submitted;
    private int selectKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean selectNavigates;

    PinDialog(Context ctx, String title, String message, Listener listener) {
        this(ctx, title, message, Prefs.PASSWORD_LENGTH, listener);
    }

    /** @param maxLength maximum digits; password inputs submit automatically when full. */
    PinDialog(Context ctx, String title, String message, int maxLength, Listener listener) {
        this(ctx, title, message, maxLength, true, listener);
    }

    PinDialog(Context ctx, String title, String message, int maxLength, boolean password,
              Listener listener) {
        super(ctx);
        this.title = title;
        this.message = message;
        this.maxLength = maxLength;
        this.password = password;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        float density = getContext().getResources().getDisplayMetrics().density;
        int pad = Math.round(density * 20);
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#12171D"));
        box.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(getContext());
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(titleView);

        TextView messageView = new TextView(getContext());
        messageView.setText(message);
        messageView.setTextColor(Color.parseColor("#B9C4CF"));
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        messageView.setPadding(0, Math.round(density * 12), 0, 0);
        box.addView(messageView);

        inputView = new TextView(getContext());
        inputView.setTextColor(Color.parseColor("#4FC3F7"));
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        inputView.setTypeface(Typeface.DEFAULT_BOLD);
        inputView.setLetterSpacing(0.3f);
        inputView.setGravity(Gravity.CENTER);
        inputView.setMinHeight(Math.round(density * 60));
        box.addView(inputView);
        RemoteKeypad keypad = new RemoteKeypad(getContext(), new RemoteKeypad.Listener() {
            @Override
            public void onDigit(int digit) {
                appendDigit(digit);
            }

            @Override
            public void onDelete() {
                deleteDigit();
            }

            @Override
            public void onConfirm() {
                submit();
            }
        });
        box.addView(keypad, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.addView(box);
        setContentView(scroll);
        setCanceledOnTouchOutside(false);
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        float density = getContext().getResources().getDisplayMetrics().density;
        int width = getContext().getResources().getDisplayMetrics().widthPixels;
        getWindow().setLayout(Math.min(Math.round(density * 560),
                Math.max(1, width - Math.round(density * 24))), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (isVolumeKey(code) || code == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(event);
        }
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return true;
        }
        if (RemoteKeypad.isNavigationKey(code)) {
            navigationMode = true;
            return super.dispatchKeyEvent(event);
        }
        if (RemoteKeypad.isSelectKey(code)) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                selectKey = code;
                selectNavigates = navigationMode;
                if (!selectNavigates) {
                    submit();
                }
            }
            boolean dispatch = selectKey == code && selectNavigates;
            if (action == KeyEvent.ACTION_UP) {
                selectKey = KeyEvent.KEYCODE_UNKNOWN;
            }
            return dispatch ? super.dispatchKeyEvent(event) : true;
        }
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int digit = digitOf(code);
            if (digit >= 0) {
                navigationMode = false;
                appendDigit(digit);
            } else if (code == KeyEvent.KEYCODE_DEL) {
                navigationMode = false;
                deleteDigit();
            } else if (code == KeyEvent.KEYCODE_BACK) {
                dismiss();
            }
        }
        return true;
    }

    private void appendDigit(int digit) {
        if (submitted) {
            return;
        }
        if (input.length() < maxLength) {
            input.append((char) ('0' + digit));
        }
        refresh();
        if (password && input.length() == maxLength) {
            submit();
        }
    }

    private void deleteDigit() {
        if (input.length() > 0) {
            input.setLength(input.length() - 1);
        }
        refresh();
    }

    private void submit() {
        if (input.length() == 0 || submitted) {
            return;
        }
        submitted = true;
        String value = input.toString();
        dismiss();
        listener.onConfirm(value);
    }

    private void refresh() {
        if (!password) {
            inputView.setText(input.toString());
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            sb.append('●');
        }
        inputView.setText(sb.toString());
    }

    static int digitOf(int code) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            return code - KeyEvent.KEYCODE_0;
        }
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
            return code - KeyEvent.KEYCODE_NUMPAD_0;
        }
        return -1;
    }

    static boolean isVolumeKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE;
    }
}
