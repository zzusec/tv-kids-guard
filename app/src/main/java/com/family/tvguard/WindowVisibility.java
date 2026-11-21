package com.family.tvguard;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;

/** Reports coverage only after drawing a focused window; resume alone is not coverage. */
final class WindowVisibility {
    interface Listener { void onVisible(boolean visible); }

    private final Activity activity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean drawn;
    private boolean drawPending;
    private int generation;
    private ViewTreeObserver boundObserver;
    private final ViewTreeObserver.OnDrawListener onDraw = this::drawn;
    private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {
            if (resumed) {
                bindDrawListener();
                view.invalidate();
            }
        }

        @Override public void onViewDetachedFromWindow(View view) {
            drawn = false;
            drawPending = false;
            generation++;
            unbindDrawListener();
            listener.onVisible(false);
        }
    };

    private void drawn() {
        if (!resumed || drawn || drawPending) return;
        drawPending = true;
        int frameGeneration = generation;
        // Run after this draw traversal, so the opaque shield covers the first frame too.
        handler.post(() -> {
            if (frameGeneration != generation) return;
            drawPending = false;
            drawn = activity.hasWindowFocus() && decor().isShown();
            report();
        });
    }
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            report();
            handler.postDelayed(this, 1000L);
        }
    };

    WindowVisibility(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    private View decor() { return activity.getWindow().getDecorView(); }

    void start() {
        if (resumed) return;
        resumed = true;
        drawn = false;
        drawPending = false;
        generation++;
        decor().addOnAttachStateChangeListener(attachment);
        // On Android 7 and earlier an unattached decor owns a floating ViewTreeObserver whose
        // draw listeners are lost during attach. Bind to the attached window's observer instead.
        if (decor().isAttachedToWindow()) bindDrawListener();
        decor().invalidate();
        handler.post(refresh);
    }

    void focusChanged(boolean focused) {
        if (!focused) {
            drawn = false;
            generation++;
            drawPending = false;
            listener.onVisible(false);
        } else if (resumed) {
            // Regaining focus requires a new frame before standing the shield down again.
            decor().invalidate();
        }
    }

    void stop() {
        resumed = false;
        drawn = false;
        drawPending = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        decor().removeOnAttachStateChangeListener(attachment);
        unbindDrawListener();
        listener.onVisible(false);
    }

    private void bindDrawListener() {
        ViewTreeObserver current = decor().getViewTreeObserver();
        if (boundObserver == current) return;
        unbindDrawListener();
        boundObserver = current;
        current.addOnDrawListener(onDraw);
    }

    private void unbindDrawListener() {
        if (boundObserver != null && boundObserver.isAlive()) {
            boundObserver.removeOnDrawListener(onDraw);
        }
        boundObserver = null;
    }

    private void report() {
        listener.onVisible(resumed && drawn && activity.hasWindowFocus() && decor().isShown());
    }
}
