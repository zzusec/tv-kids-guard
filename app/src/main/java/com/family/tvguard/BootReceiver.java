package com.family.tvguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores update scheduling and, when enabled, the quota guard after boot or replacement. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // Both actions are system-protected, but checking the action keeps a spoofed broadcast
        // with no action from being treated as a boot.
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        UpdateInstaller.recover(context);
        UpdateManager.reconcileInstalled(context);
        UpdateManager.schedule(context);
        Prefs prefs = new Prefs(context);
        if (!prefs.isProtectionEnabled()) {
            return;
        }
        DeviceProtection.applyIfOwner(context);
        GuardService.start(context);
        WatchdogReceiver.schedule(context);
    }
}
