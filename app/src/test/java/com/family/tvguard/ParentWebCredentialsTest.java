package com.family.tvguard;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Browser and television share one password and one attempt budget, not authorization. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
public class ParentWebCredentialsTest {
    private static final String PIN = "135790";
    private Context context;
    private SharedPreferences stored;
    private Prefs television;
    private ParentWebCredentials credentials;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        stored = context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE);
        stored.edit().clear().commit();
        context.getSharedPreferences(ParentWebCredentials.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        television = new Prefs(context);
        television.setPassword(PIN);
        AdminSession.revoke();
        credentials = new ParentWebCredentials(context);
    }

    @Test
    public void existingParentPasswordWorksWithoutGrantingTelevisionPrivileges() {
        assertTrue(credentials.authenticate(PIN));
        assertFalse(credentials.authenticate("admin"));
        assertFalse(AdminSession.isActive());
        assertFalse(television.isParentPinWindowActive());
        assertFalse(television.isParentGraceActive());
        assertFalse(stored.getAll().containsValue(PIN));
    }

    @Test
    public void legacyIndependentAccountCannotReplaceTheExistingParentPassword() {
        context.getSharedPreferences(ParentWebCredentials.PREFS, Context.MODE_PRIVATE).edit()
                .putString("username", "admin").putString("hash", "old-web-password")
                .putString("version", "old-version").commit();
        television.setUsedSeconds(1800L);
        television.setLocked(true);
        credentials = new ParentWebCredentials(context);
        assertFalse(credentials.authenticate("admin"));
        assertTrue(credentials.authenticate(PIN));
        assertEquals(1800L, television.getUsedSeconds());
        assertTrue(television.isGuardActive());
    }

    @Test
    public void passwordAndSessionVersionSurviveReopening() {
        String version = credentials.version();
        credentials = new ParentWebCredentials(context);
        assertEquals(version, credentials.version());
        assertTrue(credentials.authenticate(PIN));
    }

    @Test
    public void passwordChangeRevokesBrowserSessionEvenWhenChangingBackToTheSamePin() {
        ParentWebSession browser = new ParentWebSession();
        String original = credentials.version();
        ParentWebSession.Session session = browser.issue(original);
        television.setPassword("246810");
        assertFalse(credentials.authenticate(PIN));
        assertTrue(credentials.authenticate("246810"));
        television.setPassword(PIN);
        assertNotEquals(original, credentials.version());
        assertNull(browser.find(ParentWebSession.cookie(session), credentials.version()));
        assertTrue(credentials.authenticate(PIN));
    }

    @Test
    public void bothInputsShareOneFailureBudgetAndCooldown() {
        assertFalse(credentials.authenticate("999999"));
        assertEquals(Prefs.AuthResult.INCORRECT, television.authenticate("999999"));
        assertFalse(credentials.authenticate("999999"));
        assertEquals(3, television.getFailCount());
        assertTrue(credentials.lockoutRemainingSeconds() >= 59L);
        assertFalse(new ParentWebCredentials(context).authenticate(PIN));
        assertEquals(Prefs.AuthResult.COOLDOWN, television.authenticate(PIN));
        stored.edit().putLong("lockout_until", 0L).commit();
        assertTrue(credentials.authenticate(PIN));
        assertEquals(0, television.getFailCount());
        assertFalse(television.isParentPinWindowActive());
    }

    @Test
    public void invalidPasswordShapesNeverAuthenticate() {
        for (String invalid : new String[]{null, "", "admin", "13579", "1357900", " 135790", "abcdef"}) {
            assertFalse(credentials.authenticate(invalid));
        }
        assertTrue(credentials.authenticate(PIN));
    }

    @Test
    public void factoryPasswordDoesNotGrantSettingsOrViewingAuthorization() {
        stored.edit().remove("password_hash").commit();
        assertTrue(credentials.authenticate(Prefs.DEFAULT_PASSWORD));
        assertFalse(AdminSession.isActive());
        assertFalse(television.isParentPinWindowActive());
        assertFalse(television.isParentGraceDeferringLock());
        assertEquals(Prefs.AuthResult.SUCCESS, television.authenticate(Prefs.DEFAULT_PASSWORD));
        assertFalse("Factory setup is not permission to enter system settings",
                television.isParentPinWindowActive());
    }
}
