package com.example.accessibilityguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * Device Owner 権限を使ったユーティリティ。
 * - アプリ + Freedom のアンインストールブロック
 * - AccessibilityService の自動復旧
 * - 許可された AccessibilityService の制限
 */
object DeviceOwnerHelper {

    private const val TAG = "DeviceOwnerHelper"

    private fun getDpm(context: Context): DevicePolicyManager {
        return context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private fun getAdmin(context: Context): ComponentName {
        return GuardAdminReceiver.getComponentName(context)
    }

    /** Device Owner として登録されているか */
    fun isDeviceOwner(context: Context): Boolean {
        return getDpm(context).isDeviceOwnerApp(context.packageName)
    }

    /** アンインストールをブロック（自アプリ + Freedom） */
    fun blockUninstall(context: Context, freedomPackage: String = "to.freedom.android2") {
        if (!isDeviceOwner(context)) return
        val admin = getAdmin(context)
        val dpm = getDpm(context)
        for (pkg in listOf(context.packageName, freedomPackage)) {
            try {
                dpm.setUninstallBlocked(admin, pkg, true)
                Log.i(TAG, "Uninstall blocked: $pkg")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to block uninstall: $pkg", e)
            }
        }
    }

    /**
     * このアプリの AccessibilityService が有効かチェックし、
     * 無効なら Device Owner 権限で再有効化を試みる。
     */
    fun ensureAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceComponent = ComponentName(
            context,
            GuardAccessibilityService::class.java
        ).flattenToString()

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        if (enabledServices.contains(serviceComponent)) {
            return true // 既に有効
        }

        // Device Owner で再有効化を試みる
        if (!isDeviceOwner(context)) {
            Log.w(TAG, "Not device owner, cannot re-enable accessibility service")
            return false
        }

        return try {
            val newValue = if (enabledServices.isEmpty()) {
                serviceComponent
            } else {
                "$enabledServices:$serviceComponent"
            }

            getDpm(context).setSecureSetting(
                getAdmin(context),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newValue
            )
            getDpm(context).setSecureSetting(
                getAdmin(context),
                Settings.Secure.ACCESSIBILITY_ENABLED,
                "1"
            )
            Log.i(TAG, "Accessibility service re-enabled via Device Owner")
            true
        } catch (e: SecurityException) {
            // 一部のデバイス/バージョンでは setSecureSetting が制限される
            Log.e(TAG, "setSecureSetting blocked: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-enable accessibility service", e)
            false
        }
    }

    /**
     * 許可する AccessibilityService を制限する。
     * このアプリと Freedom のみ許可。
     */
    fun restrictAccessibilityServices(context: Context, freedomPackage: String = "to.freedom.android2") {
        if (!isDeviceOwner(context)) return
        try {
            val allowed = listOf(
                // 自分自身
                ComponentName(context, GuardAccessibilityService::class.java).flattenToString(),
                // Freedom (パッケージ名は実際のものに合わせる)
                // Freedom は複数の AccessibilityService を持つ可能性があるので
                // パッケージ名だけでフィルタ
            )
            // null を設定すると「全てのサービスを許可」
            // 空リストだと「全てブロック」
            // 特定のリストだと「リスト内のみ許可」

            // 注意: setPermittedAccessibilityServices はコンポーネント名ではなく
            // パッケージ名のリストを受け取る
            val allowedPackages = listOf(
                context.packageName,
                freedomPackage
            )
            getDpm(context).setPermittedAccessibilityServices(
                getAdmin(context),
                allowedPackages
            )
            Log.i(TAG, "Permitted accessibility services restricted to: $allowedPackages")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restrict accessibility services", e)
        }
    }

    /** Device Owner 状態を解除（デバッグ用） */
    fun removeDeviceOwner(context: Context) {
        if (!isDeviceOwner(context)) return
        try {
            getDpm(context).clearDeviceOwnerApp(context.packageName)
            Log.i(TAG, "Device Owner removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove device owner", e)
        }
    }
}
