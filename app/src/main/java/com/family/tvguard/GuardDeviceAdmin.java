package com.family.tvguard;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Device-management callbacks; deactivation notifications cannot veto system removal. */
public class GuardDeviceAdmin extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        if (new Prefs(context).isProtectionEnabled()) {
            DeviceProtection.applyIfOwner(context);
            GuardService.start(context);
        }
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Prefs prefs = new Prefs(context);
        if (!prefs.isProtectionEnabled() || AdminSession.isRemovalInProgress()) {
            return;
        }
        AdminSession.revoke();
        GuardService service = GuardService.get();
        if (service != null) {
            service.refreshProtection();
        }
        AdminAuthActivity.launch(context);
    }
}
