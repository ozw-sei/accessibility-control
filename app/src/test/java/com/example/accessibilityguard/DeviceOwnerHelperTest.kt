package com.example.accessibilityguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDevicePolicyManager

/**
 * DeviceOwnerHelper のテスト。
 * Robolectric の ShadowDevicePolicyManager を使って Device Owner 状態をシミュレート。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceOwnerHelperTest {

    private lateinit var context: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var shadowDpm: ShadowDevicePolicyManager
    private lateinit var adminComponent: ComponentName

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowDpm = Shadows.shadowOf(dpm)
        adminComponent = GuardAdminReceiver.getComponentName(context)
    }

    // ===== isDeviceOwner =====

    @Test
    fun `isDeviceOwner - false by default`() {
        assertFalse(DeviceOwnerHelper.isDeviceOwner(context))
    }

    @Test
    fun `isDeviceOwner - true when set as device owner`() {
        shadowDpm.setDeviceOwner(adminComponent)
        assertTrue(DeviceOwnerHelper.isDeviceOwner(context))
    }

    // ===== blockUninstall =====

    @Test
    fun `blockUninstall - no crash when not device owner`() {
        // Should not throw even without Device Owner
        DeviceOwnerHelper.blockUninstall(context)
    }

    @Test
    fun `blockUninstall - sets uninstall blocked when device owner`() {
        shadowDpm.setDeviceOwner(adminComponent)
        DeviceOwnerHelper.blockUninstall(context)
        assertTrue(dpm.isUninstallBlocked(adminComponent, context.packageName))
    }

    // ===== restrictAccessibilityServices =====

    @Test
    fun `restrictAccessibilityServices - no crash when not device owner`() {
        DeviceOwnerHelper.restrictAccessibilityServices(context)
    }

    @Test
    fun `restrictAccessibilityServices - sets permitted packages when device owner`() {
        shadowDpm.setDeviceOwner(adminComponent)
        DeviceOwnerHelper.restrictAccessibilityServices(context)

        val permitted = dpm.getPermittedAccessibilityServices(adminComponent)
        assertNotNull(permitted)
        assertTrue(permitted!!.contains(context.packageName))
        assertTrue(permitted.contains("to.freedom.android2"))
    }

    @Test
    fun `restrictAccessibilityServices - custom freedom package`() {
        shadowDpm.setDeviceOwner(adminComponent)
        DeviceOwnerHelper.restrictAccessibilityServices(
            context,
            freedomPackage = "com.custom.freedom"
        )

        val permitted = dpm.getPermittedAccessibilityServices(adminComponent)
        assertNotNull(permitted)
        assertTrue(permitted!!.contains("com.custom.freedom"))
    }

    // ===== ensureAccessibilityServiceEnabled =====

    @Test
    fun `ensureAccessibilityServiceEnabled - returns false when not device owner and disabled`() {
        // No Device Owner, service is not enabled
        val result = DeviceOwnerHelper.ensureAccessibilityServiceEnabled(context)
        assertFalse(result)
    }

    @Test
    fun `ensureAccessibilityServiceEnabled - returns true when service already enabled`() {
        // Simulate enabled service
        val serviceComponent = ComponentName(
            context,
            GuardAccessibilityService::class.java
        ).flattenToString()

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            serviceComponent
        )

        val result = DeviceOwnerHelper.ensureAccessibilityServiceEnabled(context)
        assertTrue(result)
    }

    // ===== removeDeviceOwner =====

    @Test
    fun `removeDeviceOwner - no crash when not device owner`() {
        DeviceOwnerHelper.removeDeviceOwner(context)
    }

    @Test
    fun `removeDeviceOwner - clears device owner`() {
        shadowDpm.setDeviceOwner(adminComponent)
        assertTrue(DeviceOwnerHelper.isDeviceOwner(context))

        DeviceOwnerHelper.removeDeviceOwner(context)
        assertFalse(DeviceOwnerHelper.isDeviceOwner(context))
    }

    // ===== GuardAdminReceiver =====

    @Test
    fun `admin component name is correct`() {
        val cn = GuardAdminReceiver.getComponentName(context)
        assertEquals(context.packageName, cn.packageName)
        assertEquals(
            GuardAdminReceiver::class.java.name,
            cn.className
        )
    }
}
