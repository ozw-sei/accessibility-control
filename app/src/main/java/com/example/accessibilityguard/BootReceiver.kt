package com.example.accessibilityguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 端末起動時 / アプリ更新時に Watchdog を再スケジュールし、
 * AccessibilityService の有効化を確認する。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Boot/update detected: ${intent.action}")

                // Watchdog 再登録
                WatchdogWorker.schedule(context)

                // AccessibilityService の復旧
                DeviceOwnerHelper.ensureAccessibilityServiceEnabled(context)

                // アンインストールブロック再確認
                DeviceOwnerHelper.blockUninstall(context)
            }
        }
    }
}
