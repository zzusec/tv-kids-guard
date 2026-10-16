package com.family.tvguard;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Marks the app as a device administrator. Combined with device-owner provisioning this is what
 * lets the lock screen use Lock Task mode, where the system itself blocks HOME, BACK and recents.
 */
public class GuardDeviceAdmin extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        if (new Prefs(context).isProtectionEnabled()) {
            DeviceProtection.applyIfOwner(context);
            GuardService.start(context);
        }
    }
}
