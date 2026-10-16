package com.family.tvguard;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Password entry dialog driven by the remote-control number keys.
 *
 * <p>The TV's on-screen keyboard swallows DPAD/OK, so a normal {@code EditText} inside an
 * {@code AlertDialog} cannot be operated with the remote: the user cannot reach the buttons.
 * This dialog never shows an IME. It reads the raw key events itself, exactly like the lock
 * screen, so digits, OK and the arrow buttons all work with a physical remote.
 */
final class PinDialog extends Dialog {

    interface Listener {
        /** Called with the entered digits when the user confirms. */
        void onConfirm(String value);
    }

    private final String title;
    private final String message;
    private final int maxLength;
    private final boolean password;
    private final Listener listener;

    private final StringBuilder input = new StringBuilder();
    private TextView inputView;

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

        float density = getContext().getResources().getDisplayMetrics().density;
        int pad = Math.round(density * 36);

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#12171D"));
        box.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(getContext());
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(titleView);

        TextView messageView = new TextView(getContext());
        messageView.setText(message);
        messageView.setTextColor(Color.parseColor("#B9C4CF"));
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        messageView.setPadding(0, Math.round(density * 18), 0, 0);
        box.addView(messageView);

        inputView = new TextView(getContext());
        inputView.setTextColor(Color.parseColor("#4FC3F7"));
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        inputView.setTypeface(Typeface.DEFAULT_BOLD);
        inputView.setLetterSpacing(0.3f);
        inputView.setGravity(Gravity.CENTER);
        inputView.setMinHeight(Math.round(density * 70));
        inputView.setPadding(0, Math.round(density * 18), 0, Math.round(density * 18));
        box.addView(inputView);

        setContentView(box);
        setCanceledOnTouchOutside(false);
        refresh();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        if (isVolumeKey(event.getKeyCode())) {
            return super.dispatchKeyEvent(event);
        }
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int code = event.getKeyCode();
            int digit = digitOf(code);
            if (digit >= 0) {
                if (input.length() < maxLength) {
                    input.append((char) ('0' + digit));
                }
                refresh();
                if (password && input.length() == maxLength) {
                    submit();
                }
            } else if (code == KeyEvent.KEYCODE_DEL) {
                if (input.length() > 0) {
                    input.setLength(input.length() - 1);
                }
                refresh();
            } else if (code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                submit();
            } else if (code == KeyEvent.KEYCODE_BACK) {
                dismiss();
            }
        }
        return true;
    }

    private void submit() {
        if (input.length() == 0) {
            return;
        }
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

    /**
     * Volume buttons are never the guard's business: swallowing them would take the television's
     * audio away from whoever is in the room. Every screen that reads raw remote keys hands these
     * back to the system, which owns the volume stream.
     */
    static boolean isVolumeKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE;
    }
}
