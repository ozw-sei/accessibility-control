package com.example.accessibilityguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

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
     * 無効なら再有効化を試みる。
     *
     * 有効化の優先順位:
     * 1. Device Owner の setSecureSetting（一部デバイスでブロックされる）
     * 2. WRITE_SECURE_SETTINGS パーミッションで Settings.Secure.putString
     *    （adb shell pm grant ... android.permission.WRITE_SECURE_SETTINGS が必要）
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

        val newValue = if (enabledServices.isEmpty()) {
            serviceComponent
        } else {
            "$enabledServices:$serviceComponent"
        }

        return writeAccessibilitySettings(context, newValue)
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

    /**
     * Freedom の AccessibilityService を有効化する。
     * ユーザー補助設定画面を開かずに Freedom を有効化できる。
     *
     * @return 有効化に成功した場合 true、既に有効な場合も true
     */
    fun enableFreedomAccessibilityService(
        context: Context,
        freedomPackage: String = "to.freedom.android2"
    ): Boolean {
        // Freedom のインストール確認
        if (!isPackageInstalled(context, freedomPackage)) {
            Log.w(TAG, "Freedom ($freedomPackage) is not installed")
            return false
        }

        // Freedom の AccessibilityService コンポーネントを検出
        val freedomServices = findAccessibilityServices(context, freedomPackage)
        if (freedomServices.isEmpty()) {
            Log.w(TAG, "No AccessibilityService found in $freedomPackage")
            return false
        }

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        // 既に全て有効か確認
        val allEnabled = freedomServices.all { enabledServices.contains(it) }
        if (allEnabled) {
            Log.i(TAG, "Freedom accessibility services already enabled")
            return true
        }

        // 新しいサービスを追加
        var newValue = enabledServices
        for (service in freedomServices) {
            if (!newValue.contains(service)) {
                newValue = if (newValue.isEmpty()) service else "$newValue:$service"
            }
        }

        return writeAccessibilitySettings(context, newValue)
    }

    /**
     * Freedom の AccessibilityService が有効かどうか確認する。
     */
    fun isFreedomAccessibilityEnabled(
        context: Context,
        freedomPackage: String = "to.freedom.android2"
    ): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == freedomPackage }
    }

    /**
     * Freedom がインストールされているか確認する。
     */
    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * AccessibilityService の有効化設定を書き込む。
     *
     * 書き込み方法の優先順位:
     * 1. Device Owner の setSecureSetting
     * 2. WRITE_SECURE_SETTINGS パーミッションで Settings.Secure.putString
     *
     * Pixel (Android 14) では Device Owner でも enabled_accessibility_services の
     * 書き込みがブロックされるため、WRITE_SECURE_SETTINGS へのフォールバックが必須。
     * パーミッション付与: adb shell pm grant com.example.accessibilityguard android.permission.WRITE_SECURE_SETTINGS
     */
    private fun writeAccessibilitySettings(context: Context, newServicesValue: String): Boolean {
        // 方法1: Device Owner の setSecureSetting を試みる
        if (isDeviceOwner(context)) {
            try {
                getDpm(context).setSecureSetting(
                    getAdmin(context),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newServicesValue
                )
                getDpm(context).setSecureSetting(
                    getAdmin(context),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    "1"
                )
                Log.i(TAG, "Accessibility settings written via Device Owner")
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "Device Owner setSecureSetting blocked, trying WRITE_SECURE_SETTINGS: ${e.message}")
            }
        }

        // 方法2: WRITE_SECURE_SETTINGS パーミッションで直接書き込み
        return try {
            val success1 = Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newServicesValue
            )
            val success2 = Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                "1"
            )
            if (success1 && success2) {
                Log.i(TAG, "Accessibility settings written via WRITE_SECURE_SETTINGS")
                true
            } else {
                Log.e(TAG, "putString returned false (WRITE_SECURE_SETTINGS not granted?)")
                false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS also denied. Grant via: adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write accessibility settings", e)
            false
        }
    }

    /**
     * 指定パッケージの AccessibilityService コンポーネント名を検出する。
     */
    private fun findAccessibilityServices(context: Context, packageName: String): List<String> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val allServices = am.installedAccessibilityServiceList
        return allServices
            .filter { it.resolveInfo.serviceInfo.packageName == packageName }
            .map {
                val si = it.resolveInfo.serviceInfo
                ComponentName(si.packageName, si.name).flattenToString()
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
