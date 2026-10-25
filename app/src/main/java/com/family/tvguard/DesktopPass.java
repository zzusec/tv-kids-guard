package com.family.tvguard;

/**
 * The short pass a parent gets when they leave the console for the television's own desktop.
 *
 * <p>Since 2.5.15 backing out of the panel lands on the launcher instead of the quota lock, because
 * sitting on the desktop is not watching television. The pass is what keeps the lock from
 * immediately fighting the launcher for the screen - the two of them trading the foreground every
 * few hundred milliseconds looked like a password prompt that would not go away (BRAVIA 4K 2015).
 *
 * <p>It is deliberately narrow. Only the settings watcher can see which window is on screen, so the
 * pass is granted while that watcher is connected and ends the moment any window outside the
 * desktop appears: the lock comes straight back, and it still covers a child pressing HOME. The
 * screen going off ends it too, so whoever wakes the television finds the lock again. It is a
 * one-step pass, never a day of unlimited viewing - that is the same line 2.5.12 drew under the
 * parent password.
 */
final class DesktopPass {

    private static volatile boolean active;

    private DesktopPass() {
    }

    static boolean isActive() {
        return active;
    }

    static void grant() {
        active = true;
    }

    static void revoke() {
        active = false;
    }
}
