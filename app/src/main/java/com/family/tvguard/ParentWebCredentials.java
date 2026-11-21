package com.family.tvguard;

import android.content.Context;

/** The browser verifies the same parent password, without granting television-side privileges. */
final class ParentWebCredentials {
    // Legacy storage is no longer consulted. Keep the name for migration regression fixtures.
    static final String PREFS = "tvguard_web_credentials";
    private final Prefs prefs;

    ParentWebCredentials(Context context) {
        prefs = new Prefs(context);
    }

    /** Internal session binding; never sent to the browser. Any password save invalidates it. */
    String version() {
        return prefs.passwordVersion();
    }

    boolean authenticate(String password) {
        return prefs.authenticateRemote(password) == Prefs.AuthResult.SUCCESS;
    }

    long lockoutRemainingSeconds() {
        return prefs.lockoutRemainingSeconds();
    }
}
