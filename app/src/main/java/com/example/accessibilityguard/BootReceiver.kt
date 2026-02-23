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

                // AccessibilityService の即時復旧を試みる
                val restored = DeviceOwnerHelper.ensureAccessibilityServiceEnabled(context)

                // 即時復旧に失敗した場合、リトライワーカーをスケジュール
                // （更新直後はタイミングの問題で setSecureSetting が失敗することがある）
                if (!restored) {
                    Log.w(TAG, "Immediate restore failed, scheduling retry worker")
                    ServiceRestoreWorker.schedule(context)
                }

                // アンインストールブロック再確認
                DeviceOwnerHelper.blockUninstall(context)
            }
        }
    }
}
