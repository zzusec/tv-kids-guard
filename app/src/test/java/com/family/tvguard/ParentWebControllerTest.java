package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowSystemClock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the real browser router against the real quota service, without local authorization. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class ParentWebControllerTest {
    private static final String PIN = "135790";
    private Application context;
    private Prefs prefs;
    private ParentWebController web;
    private ServiceController<GuardService> guardController;
    private GuardService guard;
    private ActivityController<MainActivity> panelController;

    @Before
    public void setUp() throws Exception {
        assertSame("handle 直接在主线程执行，避免队列等待影响测试", Looper.getMainLooper(), Looper.myLooper());
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword(PIN);
        prefs.setUsedSeconds(1800L);
        prefs.setLocked(true);
        AdminSession.revoke();
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(false);
        ShadowSettings.setCanDrawOverlays(true);
        startGuard();
        web = new ParentWebController(context);
    }

    @After
    public void tearDown() {
        if (web != null) web.close();
        if (panelController != null) panelController.pause().stop().destroy();
        if (guardController != null) guardController.destroy();
        AdminSession.revoke();
    }

    @Test
    public void loginCreatesOnlyABrowserSessionAndLeavesTheSpentQuotaLocked() throws Exception {
        Browser browser = loginDefault();

        assertTrue(json(request("GET", "/api/session", null, browser, true)).getBoolean("authenticated"));
        assertTrue(guard.isLocked());
        assertEquals(1800L, prefs.getUsedSeconds());
        assertEquals(30, prefs.getDailyLimitMinutes());
        assertNoLocalAuthorization();
        JSONObject state = json(request("GET", "/api/state", null, browser, true));
        assertTrue(state.getBoolean("locked"));
        assertFalse(state.getBoolean("grace"));
        assertEquals(0L, state.getLong("remainingSeconds"));
    }

    @Test
    public void unsupportedFirmwareReportsItsCapabilityAndRejectsAdminActivation() throws Exception {
        shadowOf(context.getPackageManager()).setSystemFeature(
                android.content.pm.PackageManager.FEATURE_DEVICE_ADMIN, false);
        Browser browser = loginDefault();
        JSONObject state = json(request("GET", "/api/state", null, browser, true));
        assertFalse(state.getBoolean("adminSupported"));
        assertFalse(state.getBoolean("adminActive"));
        assertFalse(contains(state.getJSONArray("availableActions"), "55"));
        drainStartedActivities();
        assertEquals(409, action(browser, new JSONObject().put("code", "55")).status);
        assertNull(shadowOf(context).getNextStartedActivity());
        assertNoLocalAuthorization();
    }

    @Test
    public void supportedFirmwareOffersActivationOnlyWhileAdministratorIsInactive() throws Exception {
        shadowOf(context.getPackageManager()).setSystemFeature(
                android.content.pm.PackageManager.FEATURE_DEVICE_ADMIN, true);
        android.content.pm.ResolveInfo page = new android.content.pm.ResolveInfo();
        page.activityInfo = new android.content.pm.ActivityInfo();
        page.activityInfo.packageName = "com.android.settings";
        page.activityInfo.name = "com.android.settings.DeviceAdminAdd";
        page.activityInfo.exported = true;
        shadowOf(context.getPackageManager()).addResolveInfoForIntent(
                new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN), page);
        Browser browser = loginDefault();
        JSONObject state = json(request("GET", "/api/state", null, browser, true));
        assertTrue(state.getBoolean("adminSupported"));
        assertFalse(state.getBoolean("adminActive"));
        assertTrue(contains(state.getJSONArray("availableActions"), "55"));
        assertEquals(200, action(browser, new JSONObject().put("code", "55")).status);
        assertFalse(DeviceProtection.isAdminActive(context));
        assertNoLocalAuthorization();

        shadowOf(DeviceProtection.dpm(context)).setActiveAdmin(DeviceProtection.admin(context));
        state = json(request("GET", "/api/state", null, browser, true));
        assertTrue(state.getBoolean("adminActive"));
        assertFalse(contains(state.getJSONArray("availableActions"), "55"));
    }

    @Test
    public void activeAdministratorIsReportedEvenWithoutAnActivationPage() throws Exception {
        shadowOf(context.getPackageManager()).setSystemFeature(
                android.content.pm.PackageManager.FEATURE_DEVICE_ADMIN, false);
        shadowOf(DeviceProtection.dpm(context)).setActiveAdmin(DeviceProtection.admin(context));
        Browser browser = loginDefault();
        JSONObject state = json(request("GET", "/api/state", null, browser, true));
        assertTrue(state.getBoolean("adminActive"));
        assertTrue(state.getBoolean("adminSupported"));
        assertFalse(contains(state.getJSONArray("availableActions"), "55"));
    }

    @Test
    public void listenerFailureIsVisibleAndTheNextServiceStartRestoresTheSameConsole() throws Exception {
        ServiceController<ParentWebService> started = Robolectric.buildService(ParentWebService.class)
                .create();
        try {
            ParentWebService service = started.get();
            ParentHttpServer server = org.robolectric.util.ReflectionHelpers.getField(service, "server");
            assertNotNull(server);
            java.net.ServerSocket listener = org.robolectric.util.ReflectionHelpers.getField(server, "listener");
            assertNotNull(listener);
            java.util.List<java.net.ServerSocket> listeners =
                    org.robolectric.util.ReflectionHelpers.getField(server, "listeners");
            for (java.net.ServerSocket bound : listeners) bound.close();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (server.isListening() && System.nanoTime() < deadline) Thread.yield();
            assertFalse(server.isListening());
            assertFalse("电视状态必须显示监听故障", ParentWebService.lastError().isEmpty());
            assertTrue(ParentWebService.lastError().contains("网页后台监听失败"));

            service.onStartCommand(null, 0, 2);

            assertSame("恢复不应重建网页授权控制器", server,
                    org.robolectric.util.ReflectionHelpers.getField(service, "server"));
            assertTrue(server.isListening());
            assertEquals("", ParentWebService.lastError());
            java.net.ServerSocket rebound = org.robolectric.util.ReflectionHelpers.getField(server, "listener");
            java.net.InetAddress address = rebound.getInetAddress().isAnyLocalAddress()
                    ? java.net.InetAddress.getByName("127.0.0.1") : rebound.getInetAddress();
            try (java.net.Socket socket = new java.net.Socket(address, server.port())) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:"
                        + server.port() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response = new java.io.BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                assertEquals("恢复后真实HTTP请求应正常响应：" + server.lastFailure(),
                        "HTTP/1.1 200 OK", response);
            }
        } finally {
            started.destroy();
        }
    }

    @Test
    public void aBrowserSessionExpiresWhileTheTelevisionIsInDeepSleep() throws Exception {
        Browser browser = loginDefault();
        long uptime = android.os.SystemClock.uptimeMillis();

        ShadowSystemClock.simulateDeepSleep(Duration.ofMinutes(31));

        assertEquals("深睡不推进运行时间，回归必须覆盖两种时钟差异", uptime,
                android.os.SystemClock.uptimeMillis());
        assertEquals("网页登录的30分钟闲置期必须包含深睡", 401,
                request("GET", "/api/state", null, browser, true).status);
        assertNoLocalAuthorization();
    }

    @Test
    public void unauthenticatedAndMissingCsrfCommandsCannotChangeTheTelevision() throws Exception {
        JSONObject input = new JSONObject().put("code", "77");
        assertEquals(401, request("POST", "/api/action", input, null, false).status);
        assertEquals(401, request("GET", "/api/state", null, null, false).status);
        Browser browser = loginDefault();

        assertEquals(403, request("POST", "/api/action", input, browser, false).status);
        Browser wrongCsrf = new Browser(browser.cookie, "incorrect");
        assertEquals(403, request("POST", "/api/action", input, wrongCsrf, true).status);

        assertEquals(1800L, prefs.getUsedSeconds());
        assertTrue(guard.isLocked());
        assertNoLocalAuthorization();
    }

    @Test
    public void quotaCommandsReuseTheServiceWithoutGrantingTelevisionPrivileges() throws Exception {
        Browser browser = loginDefault();

        assertEquals(200, action(browser, new JSONObject().put("code", "12").put("minutes", 45)).status);
        assertEquals(45, prefs.getDailyLimitMinutes());
        assertEquals(1800L, prefs.getUsedSeconds());
        assertFalse("提高每日额度应解除旧额度锁屏", guard.isLocked());
        assertNoLocalAuthorization();

        assertEquals(200, action(browser, new JSONObject().put("code", "22").put("minutes", 5)).status);
        assertEquals(300L, prefs.getBonusSeconds());
        assertEquals(1200L, prefs.remainingSeconds());
        assertNoLocalAuthorization();

        assertEquals(200, action(browser, new JSONObject().put("code", "33")).status);
        assertTrue(guard.isLocked());
        assertEquals(1800L, prefs.getUsedSeconds());
        assertNoLocalAuthorization();

        assertEquals(200, action(browser, new JSONObject().put("code", "77")).status);
        assertEquals(0L, prefs.getUsedSeconds());
        assertEquals(45, prefs.getDailyLimitMinutes());
        assertEquals(300L, prefs.getBonusSeconds());
        assertFalse(guard.isLocked());
        assertEquals(3000L, json(request("GET", "/api/state", null, browser, true))
                .getLong("remainingSeconds"));
        assertNoLocalAuthorization();
    }

    @Test
    public void webLockRevokesAnExistingVisibleParentPanelAndItsHandoverLease() throws Exception {
        Browser browser = loginDefault();
        assertEquals(200, action(browser, new JSONObject().put("code", "77")).status);
        openNativePanel();
        // Cover both ways parent UI suppresses the shield: the live console and the short lease
        // held during gate-to-panel handover. A web lock must override both immediately.
        guard.setParentUiVisible(true);
        assertTrue(MainActivity.isAuthorizedVisible());
        assertTrue(GuardService.isParentUiOnScreen());
        assertFalse(guard.isLocked());

        assertEquals(200, action(browser, new JSONObject().put("code", "33")).status);

        assertFalse("立即锁定必须撤销原生家长会话", AdminSession.isActive());
        assertFalse("已显示的旧面板不能继续被视为已授权", MainActivity.isAuthorizedVisible());
        assertFalse("已存在的交接lease不能盖过本次锁定", GuardService.isParentUiOnScreen());
        assertLockedWithoutGrace(browser);
        assertTrue("立即锁定不能靠耗尽今日额度实现", prefs.remainingSeconds() > 0L);
    }

    @Test
    public void anOldPanelBackCannotGrantNewGraceAfterAWebLock() throws Exception {
        Browser browser = loginDefault();
        assertEquals(200, action(browser, new JSONObject().put("code", "77")).status);
        MainActivity panel = openNativePanel();
        assertEquals(200, action(browser, new JSONObject().put("code", "33")).status);
        drainStartedActivities();

        panel.onBackPressed();

        assertTrue(panel.isFinishing());
        assertLockedWithoutGrace(browser);
        assertNoStartedHome();
        assertTrue("旧面板返回不能消耗或补发时长", prefs.remainingSeconds() > 0L);
    }

    @Test
    public void anOldPanelCloseShortcutCannotGrantGraceAfterAWebLock() throws Exception {
        Browser browser = loginDefault();
        assertEquals(200, action(browser, new JSONObject().put("code", "77")).status);
        MainActivity panel = openNativePanel();
        View close = panel.findViewById(R.id.menu_actions).findViewWithTag("action_00");
        assertNotNull(close);
        assertEquals(200, action(browser, new JSONObject().put("code", "33")).status);
        drainStartedActivities();

        // Activate the very button rendered for the old authorized panel. It must cross the
        // authorization boundary again, even before Android has paused that old activity.
        assertTrue(close.performClick());

        assertTrue(panel.isFinishing());
        assertLockedWithoutGrace(browser);
        assertNoStartedHome();
    }

    @Test
    public void webLockDiscardsACompletedPinAndRequiresAFreshCorrectPin() throws Exception {
        Browser browser = loginDefault();
        enterLockDigits(PIN);
        assertTrue("先模拟锁屏已有输入但尚未确认", guard.isPasswordComplete());
        openNativePanel();
        assertTrue(AdminSession.isActive());

        assertEquals(200, action(browser, new JSONObject().put("code", "33")).status);

        assertEquals("立即锁定必须丢弃锁屏旧输入", "", guard.lockInput());
        assertFalse("旧会话和旧PIN输入均不能让空确认通过", guard.onOk());
        assertLockedWithoutGrace(browser);
        enterLockDigits("999999");
        assertFalse("错误PIN不能重新授权", guard.onOk());
        assertLockedWithoutGrace(browser);

        enterLockDigits(PIN);
        assertTrue("重新输入正确PIN才允许进入家长面板", guard.onOk());
        assertTrue(AdminSession.isActive());
        assertFalse("正确的原生PIN必须立即解除本次锁定", guard.isLocked());
        assertTrue(prefs.isParentGraceDeferringLock());
        assertEquals(1800L, prefs.getUsedSeconds());
        assertEquals(30, prefs.getDailyLimitMinutes());
        assertEquals(0L, prefs.getBonusSeconds());
    }

    @Test
    public void invalidMinutesAreRejectedWithoutChangingSavedQuota() throws Exception {
        Browser browser = loginDefault();
        for (Object value : new Object[]{0, -1, 1441, 1.5, "45"}) {
            assertEquals(400, action(browser, new JSONObject().put("code", "12").put("minutes", value)).status);
            assertEquals(30, prefs.getDailyLimitMinutes());
        }
        for (Object value : new Object[]{0, -1, 61, 1.5, "5"}) {
            assertEquals(400, action(browser, new JSONObject().put("code", "22").put("minutes", value)).status);
            assertEquals(0L, prefs.getBonusSeconds());
        }
        assertEquals(1800L, prefs.getUsedSeconds());
        assertTrue(guard.isLocked());
        assertNoLocalAuthorization();
    }

    @Test
    public void changingTheParentPasswordRevokesEveryOldBrowserSession() throws Exception {
        Browser first = loginDefault();
        Browser second = loginDefault();
        ParentHttpServer.Response changed = action(first, new JSONObject().put("code", "11")
                .put("oldPin", PIN).put("newPin", "246810").put("confirmPin", "246810"));

        assertEquals(200, changed.status);
        assertTrue(changed.headers.get("Set-Cookie").contains("Max-Age=0"));
        assertTrue(prefs.checkPassword("246810"));
        assertEquals(401, request("GET", "/api/state", null, first, true).status);
        assertEquals(401, action(second, new JSONObject().put("code", "77")).status);
        assertFalse(json(request("GET", "/api/session", null, second, true)).getBoolean("authenticated"));
        assertEquals(401, request("POST", "/api/login", new JSONObject()
                .put("password", PIN), null, false).status);
        login("246810");
        assertEquals(1800L, prefs.getUsedSeconds());
        assertNoLocalAuthorization();
    }

    @Test
    public void passwordChangesRequireCsrfAndTheCurrentParentPassword() throws Exception {
        Browser browser = loginDefault();
        JSONObject input = new JSONObject().put("code", "11").put("oldPin", PIN)
                .put("newPin", "246810").put("confirmPin", "246810");
        assertEquals(401, request("POST", "/api/action", input, null, false).status);
        assertEquals(403, request("POST", "/api/action", input, browser, false).status);
        input.put("oldPin", "999999");
        assertEquals(400, action(browser, input).status);
        assertTrue(prefs.checkPassword(PIN));
        assertEquals(404, request("POST", "/api/account", input, browser, true).status);
        assertNoLocalAuthorization();
    }

    @Test
    public void changingTheSharedPasswordRequiresTheOldPinAndEndsTheSession() throws Exception {
        Browser browser = loginDefault();
        JSONObject input = new JSONObject().put("code", "11").put("oldPin", "999999")
                .put("newPin", "246810").put("confirmPin", "246810");
        assertEquals(400, action(browser, input).status);
        assertTrue(prefs.checkPassword(PIN));

        input.put("oldPin", PIN);
        assertEquals(200, action(browser, input).status);

        assertTrue(prefs.checkPassword("246810"));
        assertEquals(401, request("GET", "/api/state", null, browser, true).status);
        login("246810");
        assertNoLocalAuthorization();
    }

    @Test
    public void aPinChangeOnTheTelevisionInvalidatesOldBrowserSessions() throws Exception {
        Browser browser = loginDefault();

        prefs.setPassword("246810");

        assertEquals(401, request("GET", "/api/state", null, browser, true).status);
        login("246810");
        assertEquals(1800L, prefs.getUsedSeconds());
        assertNoLocalAuthorization();
    }

    @Test
    public void disablingProtectionKeepsTheWebConsoleAvailableToReenableIt() throws Exception {
        Browser browser = loginDefault();

        assertEquals(200, action(browser, new JSONObject().put("code", "44")).status);

        assertFalse(prefs.isProtectionEnabled());
        Intent stopped = shadowOf(context).getNextStoppedService();
        assertNotNull(stopped);
        assertEquals(GuardService.class.getName(), stopped.getComponent().getClassName());
        // Android performs this lifecycle transition after stopService; drive it explicitly here.
        guardController.destroy();
        guardController = null;
        JSONObject disabled = json(request("GET", "/api/state", null, browser, true));
        assertFalse(disabled.getBoolean("enabled"));
        assertFalse(disabled.getBoolean("guardRunning"));
        assertTrue(contains(disabled.getJSONArray("availableActions"), "99"));
        assertFalse(contains(disabled.getJSONArray("availableActions"), "77"));
        assertEquals(409, action(browser, new JSONObject().put("code", "77")).status);

        assertEquals(200, action(browser, new JSONObject().put("code", "99")).status);
        assertTrue(prefs.isProtectionEnabled());
        Intent started = shadowOf(context).getNextStartedService();
        assertNotNull(started);
        assertEquals(GuardService.class.getName(), started.getComponent().getClassName());
        startGuard();
        assertTrue(json(request("GET", "/api/state", null, browser, true)).getBoolean("guardRunning"));
        assertTrue(guard.isLocked());
        assertEquals(1800L, prefs.getUsedSeconds());
        assertNoLocalAuthorization();
    }

    @Test
    @Config(shadows = DeviceProtectionTest.PolicyShadow.class)
    public void anAuthenticatedWebRemovalDoesNotOpenTheExternalDeactivationGate() throws Exception {
        Browser browser = loginDefault();
        DeviceProtectionTest.PolicyShadow policy = Shadow.extract(DeviceProtection.dpm(context));
        policy.setActiveAdmin(DeviceProtection.admin(context));
        policy.delayAdminRemoval = true;
        drainStartedActivities();

        assertEquals(200, action(browser, new JSONObject().put("code", "44")).status);
        assertTrue("关闭状态要等系统真正解除管理员后才能保存", prefs.isProtectionEnabled());
        policy.delayAdminRemoval = false;
        DeviceProtection.dpm(context).removeActiveAdmin(DeviceProtection.admin(context));
        new GuardDeviceAdmin().onDisabled(context, new Intent(
                "android.app.action.DEVICE_ADMIN_DISABLED"));

        assertNull("家长已通过网页发起解除，系统回调不应再弹本机密码页",
                shadowOf(context).getNextStartedActivity());
        assertNoLocalAuthorization();
        ShadowLooper.idleMainLooper(250, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertFalse(prefs.isProtectionEnabled());
        prefs.setProtectionEnabled(true);
        assertExternalDeactivationOpensGate();
    }

    @Test
    @Config(shadows = DeviceProtectionTest.PolicyShadow.class)
    public void aFailedWebRemovalDoesNotSuppressLaterExternalDeactivation() throws Exception {
        Browser browser = loginDefault();
        DeviceProtectionTest.PolicyShadow policy = Shadow.extract(DeviceProtection.dpm(context));
        policy.setDeviceOwner(DeviceProtection.admin(context));
        policy.setActiveAdmin(DeviceProtection.admin(context));
        policy.ignoreOwnerRemoval = true;
        drainStartedActivities();

        assertEquals(409, action(browser, new JSONObject().put("code", "44")).status);

        assertTrue(prefs.isProtectionEnabled());
        assertNoLocalAuthorization();
        assertExternalDeactivationOpensGate();
    }

    @Test
    @Config(shadows = DeviceProtectionTest.PolicyShadow.class)
    public void aWebRemovalRetryTimeoutDoesNotSuppressExternalDeactivation() throws Exception {
        Browser browser = loginDefault();
        DeviceProtectionTest.PolicyShadow policy = beginDelayedAdminRemoval(browser);

        ShadowLooper.idleMainLooper(2250, java.util.concurrent.TimeUnit.MILLISECONDS);

        assertTrue(prefs.isProtectionEnabled());
        policy.delayAdminRemoval = false;
        DeviceProtection.dpm(context).removeActiveAdmin(DeviceProtection.admin(context));
        assertExternalDeactivationOpensGate();
    }

    @Test
    @Config(shadows = DeviceProtectionTest.PolicyShadow.class)
    public void anExpiredWebRemovalCannotBeResumedByItsDelayedCallback() throws Exception {
        Browser browser = loginDefault();
        DeviceProtectionTest.PolicyShadow policy = beginDelayedAdminRemoval(browser);
        // Elapsed time can pass while the main queue is blocked; no retry has run yet.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3));
        policy.delayAdminRemoval = false;
        DeviceProtection.dpm(context).removeActiveAdmin(DeviceProtection.admin(context));

        assertExternalDeactivationOpensGate();
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.MILLISECONDS);

        assertTrue("过期操作的旧轮询不能事后关闭防护", prefs.isProtectionEnabled());
        assertNoLocalAuthorization();
    }

    @Test
    @Config(shadows = DeviceProtectionTest.PolicyShadow.class)
    public void loggingOutRevokesThePendingAdminRemovalCapability() throws Exception {
        Browser browser = loginDefault();
        DeviceProtectionTest.PolicyShadow policy = beginDelayedAdminRemoval(browser);

        assertEquals(200, request("POST", "/api/logout", new JSONObject(), browser, true).status);
        policy.delayAdminRemoval = false;
        DeviceProtection.dpm(context).removeActiveAdmin(DeviceProtection.admin(context));
        assertExternalDeactivationOpensGate();
        ShadowLooper.idleMainLooper(250, java.util.concurrent.TimeUnit.MILLISECONDS);

        assertTrue(prefs.isProtectionEnabled());
        assertNoLocalAuthorization();
    }

    @Test
    @Config(shadows = DeviceProtectionTest.PolicyShadow.class)
    public void closingTheWebControllerRevokesThePendingAdminRemovalCapability() throws Exception {
        Browser browser = loginDefault();
        DeviceProtectionTest.PolicyShadow policy = beginDelayedAdminRemoval(browser);

        web.close();
        policy.delayAdminRemoval = false;
        DeviceProtection.dpm(context).removeActiveAdmin(DeviceProtection.admin(context));
        assertExternalDeactivationOpensGate();
        ShadowLooper.idleMainLooper(250, java.util.concurrent.TimeUnit.MILLISECONDS);

        assertTrue(prefs.isProtectionEnabled());
        assertNoLocalAuthorization();
    }

    @Test
    public void factoryPasswordCanOnlyConfigureTheSharedParentPassword() throws Exception {
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE)
                .edit().remove("password_hash").commit();
        assertTrue(prefs.needsPasswordSetup());

        Browser browser = login(Prefs.DEFAULT_PASSWORD);

        assertEquals(200, request("GET", "/api/state", null, browser, true).status);
        for (String code : new String[]{"77", "22", "12", "44", "99", "55"}) {
            assertEquals(409, action(browser, new JSONObject().put("code", code).put("minutes", 5)).status);
        }
        assertTrue("登录网页不隐式设置电视密码", prefs.needsPasswordSetup());
        assertTrue(guard.isLocked());
        assertEquals(1800L, prefs.getUsedSeconds());
        assertNoLocalAuthorization();
    }

    @Test
    public void theParentPasswordIsTheOnlyWebPassword() throws Exception {
        assertEquals(401, request("POST", "/api/login", new JSONObject()
                .put("username", "admin").put("password", "admin"), null, false).status);
        login(PIN);
        assertEquals(401, request("POST", "/api/login", new JSONObject()
                .put("pin", PIN), null, false).status);
        assertTrue(guard.isLocked());
        assertNoLocalAuthorization();
    }

    @Test
    public void logoutInvalidatesTheCookieAndDoesNotGrantParentGrace() throws Exception {
        Browser browser = loginDefault();

        assertEquals(403, request("POST", "/api/logout", new JSONObject(), browser, false).status);
        assertEquals(200, request("POST", "/api/logout", new JSONObject(), browser, true).status);

        assertEquals(401, request("GET", "/api/state", null, browser, true).status);
        assertEquals(401, action(browser, new JSONObject().put("code", "77")).status);
        assertTrue(guard.isLocked());
        assertNoLocalAuthorization();
    }

    private void startGuard() {
        guardController = Robolectric.buildService(GuardService.class).create();
        guard = guardController.get();
    }

    private MainActivity openNativePanel() {
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate(PIN));
        AdminSession.grant();
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(true);
        if (android.os.Build.VERSION.SDK_INT >= 20) {
            Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            shadowOf(display).setState(Display.STATE_ON);
        }
        panelController = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().windowFocusChanged(true);
        ShadowLooper.idleMainLooper();
        assertTrue("复现必须保留仍在前台的已授权原生面板", MainActivity.isAuthorizedVisible());
        assertTrue(GuardService.isParentUiOnScreen());
        return panelController.get();
    }

    private void enterLockDigits(String value) {
        for (int i = 0; i < value.length(); i++) guard.onDigit(value.charAt(i) - '0');
    }

    private void assertLockedWithoutGrace(Browser browser) throws Exception {
        assertFalse(AdminSession.isActive());
        assertFalse("旧面板退出不得重新发放家长宽限", prefs.isParentGraceActive());
        assertTrue(guard.isLocked());
        JSONObject state = json(request("GET", "/api/state", null, browser, true));
        assertTrue("网页状态必须如实报告已锁定", state.getBoolean("locked"));
        assertFalse("网页状态不得残留或重建宽限", state.getBoolean("grace"));
    }

    private void drainStartedActivities() {
        while (shadowOf(context).getNextStartedActivity() != null) { /* discard setup launches */ }
    }

    private DeviceProtectionTest.PolicyShadow beginDelayedAdminRemoval(Browser browser) throws Exception {
        DeviceProtectionTest.PolicyShadow policy = Shadow.extract(DeviceProtection.dpm(context));
        policy.setActiveAdmin(DeviceProtection.admin(context));
        policy.delayAdminRemoval = true;
        drainStartedActivities();
        assertEquals(200, action(browser, new JSONObject().put("code", "44")).status);
        assertTrue(prefs.isProtectionEnabled());
        return policy;
    }

    private void assertExternalDeactivationOpensGate() {
        new GuardDeviceAdmin().onDisabled(context, new Intent(
                "android.app.action.DEVICE_ADMIN_DISABLED"));
        Intent gate = shadowOf(context).getNextStartedActivity();
        assertNotNull("非当前已认证解除操作的停用必须重新验证", gate);
        assertEquals(AdminAuthActivity.class.getName(), gate.getComponent().getClassName());
    }

    private void assertNoStartedHome() {
        Intent started;
        while ((started = shadowOf(context).getNextStartedActivity()) != null) {
            assertFalse("网页已锁定后，旧面板不得把遥控器交给桌面", started.hasCategory(Intent.CATEGORY_HOME));
        }
    }

    private Browser loginDefault() throws Exception {
        return login(PIN);
    }

    private Browser login(String password) throws Exception {
        ParentHttpServer.Response response = request("POST", "/api/login",
                new JSONObject().put("password", password), null, false);
        assertEquals(new String(response.body, StandardCharsets.UTF_8), 200, response.status);
        assertTrue(json(response).getBoolean("authenticated"));
        String cookie = response.headers.get("Set-Cookie");
        assertNotNull(cookie);
        return new Browser(cookie.substring(0, cookie.indexOf(';')), json(response).getString("csrf"));
    }

    private ParentHttpServer.Response action(Browser browser, JSONObject input) throws Exception {
        return request("POST", "/api/action", input, browser, true);
    }

    private ParentHttpServer.Response request(String method, String path, JSONObject body,
                                               Browser browser, boolean csrf) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        if (browser != null) {
            headers.put("cookie", browser.cookie);
            if (csrf) headers.put("x-csrf-token", browser.csrf);
        }
        return web.handle(new ParentHttpServer.Request(method, path,
                body == null ? "" : body.toString(), headers));
    }

    private JSONObject json(ParentHttpServer.Response response) throws Exception {
        assertEquals(200, response.status);
        return new JSONObject(new String(response.body, StandardCharsets.UTF_8));
    }

    private void assertNoLocalAuthorization() {
        assertFalse("网页操作不授予电视家长会话", AdminSession.isActive());
        assertFalse("网页操作不授予维护权限", AdminSession.isMaintenanceActive());
        assertFalse("网页操作不开放电视设置的两分钟窗口", prefs.isParentPinWindowActive());
        assertFalse("网页操作不产生家长宽限", prefs.isParentGraceActive());
    }

    private boolean contains(JSONArray values, String value) throws Exception {
        for (int i = 0; i < values.length(); i++) {
            if (value.equals(values.getString(i))) return true;
        }
        return false;
    }

    private static final class Browser {
        final String cookie;
        final String csrf;
        Browser(String cookie, String csrf) { this.cookie = cookie; this.csrf = csrf; }
    }
}
