package com.family.tvguard;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;
import android.view.WindowManager;

/**
 * Whether the television is showing a picture to somebody right now.
 *
 * <p>Two independent subsystems have to agree before a minute is charged to a child. The power
 * manager answers the power-key question; the display subsystem answers the panel question. A set
 * that has blanked its panel, dimmed it into doze, or handed it to a screensaver is not being
 * watched, whatever the power manager still believes, and on the BRAVIA 4K 2015 the power manager
 * is the weaker of the two witnesses: standby there keeps a Sony thermal wake lock held, so the
 * monotonic clock runs almost the whole time and cannot reveal the gap. Only the panel can.
 *
 * <p>Missing or unreadable witnesses fail open: a device that cannot report its panel state keeps
 * the counting rule it had before, rather than silently freezing the day's quota at zero.
 */
final class PanelState {

    private PanelState() {
    }

    static boolean isLit(Context ctx) {
        return isLit(ctx,
                (PowerManager) ctx.getSystemService(Context.POWER_SERVICE),
                (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE));
    }

    static boolean isLit(Context ctx, PowerManager pm, WindowManager wm) {
        return interactive(pm) && panelOn(wm);
    }

    @SuppressWarnings("deprecation")
    private static boolean interactive(PowerManager pm) {
        if (pm == null) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH
                ? pm.isInteractive() : pm.isScreenOn();
    }

    /**
     * The panel itself, as the display subsystem reports it. Doze and dim are dark, not watched.
     *
     * <p>{@code Display.getState()} only exists from API 20, so a KitKat set keeps the rule it had:
     * the power manager on its own. A device that cannot answer is never read as switched off.
     */
    private static boolean panelOn(WindowManager wm) {
        if (wm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH) {
            return true;
        }
        try {
            Display display = wm.getDefaultDisplay();
            return display == null || display.getState() == Display.STATE_ON;
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }
}
