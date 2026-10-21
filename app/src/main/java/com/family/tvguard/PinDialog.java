package com.family.tvguard;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** IME-free numeric entry driven only by the remote number keys; no on-screen keypad. */
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
    private boolean submitted;

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
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int pad = Math.round(density * 28);
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.parent_card_bg);
        box.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(getContext());
        titleView.setText(title);
        titleView.setTextColor(getContext().getResources().getColor(R.color.panel_ink));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(titleView);

        TextView messageView = new TextView(getContext());
        messageView.setText(message);
        messageView.setTextColor(getContext().getResources().getColor(R.color.panel_muted));
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        messageView.setPadding(0, Math.round(density * 12), 0, 0);
        box.addView(messageView);

        inputView = new TextView(getContext());
        inputView.setTextColor(getContext().getResources().getColor(R.color.panel_blue_text));
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        inputView.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            inputView.setLetterSpacing(0.3f);
        }
        inputView.setGravity(Gravity.CENTER);
        inputView.setMinHeight(Math.round(density * 72));
        inputView.setBackgroundResource(R.drawable.parent_input_bg);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = Math.round(density * 22);
        box.addView(inputView, inputParams);

        TextView help = new TextView(getContext());
        help.setText(password ? "数字键输入 · 左键删除 · 返回取消"
                : "数字键输入 · 左键删除 · OK 确认 · 返回取消");
        help.setTextColor(getContext().getResources().getColor(R.color.panel_muted));
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        help.setPadding(0, Math.round(density * 18), 0, 0);
        box.addView(help);
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
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return true;
        }
        int digit = digitOf(code);
        if (digit >= 0) {
            appendDigit(digit);
        } else if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_DPAD_LEFT) {
            deleteDigit();
        } else if (RemoteKeypad.isSelectKey(code)) {
            submit();
        } else if (code == KeyEvent.KEYCODE_BACK) {
            dismiss();
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
