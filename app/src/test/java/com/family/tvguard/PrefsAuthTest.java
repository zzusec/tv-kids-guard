package com.family.tvguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class PrefsAuthTest {

    private Prefs prefs;

    @Before
    public void setUp() {
        prefs = new Prefs(RuntimeEnvironment.getApplication());
    }

    @Test
    public void freshInstallAcceptsFactoryPinButReportsSetupPending() {
        assertTrue(prefs.needsPasswordSetup());
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate(Prefs.DEFAULT_PASSWORD));
    }

    @Test
    public void customPinReplacesTheFactoryDefault() {
        prefs.setPassword("135790");

        assertFalse(prefs.needsPasswordSetup());
        assertEquals(Prefs.AuthResult.INCORRECT, prefs.authenticate(Prefs.DEFAULT_PASSWORD));
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate("135790"));
    }

    @Test
    public void settingTheFactoryPinBackIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> prefs.setPassword(Prefs.DEFAULT_PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> prefs.setPassword("12345"));
        assertThrows(IllegalArgumentException.class, () -> prefs.setPassword("abcdef"));
    }

    @Test
    public void cooldownStartsAfterThreeWrongPinsAndBlocksEvenTheCorrectOne() {
        prefs.setPassword("246810");

        assertEquals(Prefs.AuthResult.INCORRECT, prefs.authenticate("000001"));
        assertEquals(Prefs.AuthResult.INCORRECT, prefs.authenticate("000002"));
        assertEquals(Prefs.AuthResult.INCORRECT, prefs.authenticate("000003"));
        assertTrue(prefs.lockoutRemainingSeconds() > 0L);

        assertEquals(Prefs.AuthResult.COOLDOWN, prefs.authenticate("246810"));
    }

    @Test
    public void aSuccessfulPinClearsTheFailureHistory() {
        prefs.setPassword("246810");
        prefs.authenticate("000001");
        prefs.authenticate("000002");

        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate("246810"));
        assertEquals(0, prefs.getFailCount());
        assertEquals(0L, prefs.lockoutRemainingSeconds());
    }

    @Test
    public void guardStaysActiveUntilTheAllowanceIsGrantedOrTheDayIsUnlocked() {
        assertFalse(prefs.isGuardActive());

        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        assertTrue(prefs.isGuardActive());

        prefs.setUnlockedToday(true);
        assertFalse(prefs.isGuardActive());

        prefs.setUnlockedToday(false);
        prefs.setBonusSeconds(600L);
        assertFalse(prefs.isGuardActive());
    }

    @Test
    public void protectionCanBeSwitchedOffButIsOnByDefault() {
        assertTrue(prefs.isProtectionEnabled());

        prefs.setProtectionEnabled(false);
        assertFalse(prefs.isProtectionEnabled());
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        assertFalse(prefs.isGuardActive());
    }

    @Test
    public void dailyLimitDefaultsToThirtyMinutesAndSavedLimitSurvivesRestart() {
        assertEquals(30, prefs.getDailyLimitMinutes());
        prefs.setDailyLimitMinutes(45);
        prefs.setUsedSeconds(600L);
        prefs.setBonusSeconds(300L);

        Prefs reopened = new Prefs(RuntimeEnvironment.getApplication());
        assertEquals(45, reopened.getDailyLimitMinutes());
        assertEquals(2400L, reopened.remainingSeconds());
    }

    @Test
    public void changingDailyLimitKeepsUsageAndBonusAndRejectsInvalidValues() {
        prefs.setUsedSeconds(600L);
        prefs.setBonusSeconds(300L);
        prefs.setDailyLimitMinutes(1);
        assertEquals(600L, prefs.getUsedSeconds());
        assertEquals(300L, prefs.getBonusSeconds());
        assertTrue(prefs.isGuardActive());

        prefs.setDailyLimitMinutes(Prefs.MAX_LIMIT_MINUTES);
        assertFalse(prefs.isGuardActive());
        assertThrows(IllegalArgumentException.class, () -> prefs.setDailyLimitMinutes(0));
        assertThrows(IllegalArgumentException.class, () -> prefs.setDailyLimitMinutes(-1));
        assertThrows(IllegalArgumentException.class,
                () -> prefs.setDailyLimitMinutes(Prefs.MAX_LIMIT_MINUTES + 1));
        assertEquals(Prefs.MAX_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
    }

    @Test
    public void newDayResetsUsageButKeepsTheConfiguredDailyLimit() {
        prefs.setDailyLimitMinutes(45);
        prefs.setUsedSeconds(600L);
        prefs.setBonusSeconds(300L);
        prefs.setLocked(true);
        RuntimeEnvironment.getApplication().getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE)
                .edit().putString("day", "2000-01-01").commit();

        assertTrue(prefs.rollOverIfNeeded());
        assertEquals(45, prefs.getDailyLimitMinutes());
        assertEquals(2700L, prefs.remainingSeconds());
        assertFalse(prefs.isLocked());
    }

    @Test
    public void bonusIsCappedAtTheDailyMaximum() {
        prefs.setBonusSeconds(Prefs.MAX_BONUS_MINUTES * 60L + 3600L);

        assertEquals(Prefs.MAX_BONUS_MINUTES * 60L, prefs.getBonusSeconds());
    }

    @Test
    public void stateSurvivesARecreatedPrefsInstance() {
        prefs.setPassword("975310");
        prefs.setUsedSeconds(120L);

        Prefs reopened = new Prefs((Context) RuntimeEnvironment.getApplication());

        assertEquals(Prefs.AuthResult.SUCCESS, reopened.authenticate("975310"));
        assertEquals(120L, reopened.getUsedSeconds());
    }
}
