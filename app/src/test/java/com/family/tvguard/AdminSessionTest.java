package com.family.tvguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
public class AdminSessionTest {

    @Before
    public void setUp() {
        AdminSession.revoke();
    }

    @After
    public void tearDown() {
        AdminSession.revoke();
    }

    @Test
    public void nothingIsAuthorizedBeforeTheParentSignsIn() {
        assertFalse(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
    }

    @Test
    public void authorizationExpiresOnItsOwn() {
        AdminSession.grant();
        assertTrue(AdminSession.isActive());

        ShadowSystemClock.advanceBy(Duration.ofMinutes(4));

        assertFalse(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
    }

    @Test
    public void maintenanceNeedsAParentSessionAndNeverOutlivesIt() {
        assertFalse(AdminSession.grantMaintenance());

        AdminSession.grant();
        assertTrue(AdminSession.grantMaintenance());
        assertTrue(AdminSession.isMaintenanceActive());

        ShadowSystemClock.advanceBy(Duration.ofMinutes(4));
        assertFalse(AdminSession.isMaintenanceActive());
    }

    @Test
    public void maintenanceEndsWithoutRevokingTheParentSession() {
        AdminSession.grant();
        AdminSession.grantMaintenance();

        AdminSession.endMaintenance();

        assertTrue(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
    }

    @Test
    public void removalIsOnlyInProgressWhileAuthenticated() {
        assertFalse(AdminSession.beginRemoval());

        AdminSession.grant();
        assertTrue(AdminSession.beginRemoval());
        assertTrue(AdminSession.isRemovalInProgress());

        AdminSession.endRemoval();
        assertFalse(AdminSession.isRemovalInProgress());
        assertTrue(AdminSession.isMaintenanceActive());
    }

    @Test
    public void revokeClearsEveryStateIncludingAnInProgressRemoval() {
        AdminSession.grant();
        AdminSession.beginRemoval();

        AdminSession.revoke();

        assertFalse(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
        assertFalse(AdminSession.isRemovalInProgress());
    }
}
