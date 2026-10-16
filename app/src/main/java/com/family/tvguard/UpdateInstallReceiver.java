package com.family.tvguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit PackageInstaller callback; the manifest must register this receiver exported=false. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !UpdateInstaller.ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            return;
        }
        PendingResult result = goAsync();
        UpdateInstaller.receive(context.getApplicationContext(), intent, () -> {
            if (result != null) {
                result.finish();
            }
        });
    }
}
