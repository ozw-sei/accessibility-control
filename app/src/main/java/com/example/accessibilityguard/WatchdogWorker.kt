package com.example.accessibilityguard

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 定期的に AccessibilityService の状態をチェックし、
 * 無効化されていた場合は Device Owner 権限で復旧を試みる。
 *
 * WorkManager の最小間隔は 15 分。
 * Force-stop されてもシステムが WorkManager を再起動する。
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WatchdogWorker"
        private const val WORK_NAME = "accessibility_guard_watchdog"

        /** WorkManager に定期実行を登録 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(
                15, TimeUnit.MINUTES  // 最小間隔
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Watchdog scheduled (every 15 min)")
        }

        /** 登録解除 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Watchdog cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Watchdog tick")

        val checker = ConditionChecker(applicationContext)
        if (!checker.isGuardEnabled) {
            Log.d(TAG, "Guard disabled, skipping")
            return Result.success()
        }

        // AccessibilityService の復旧を試みる
        val restored = DeviceOwnerHelper.ensureAccessibilityServiceEnabled(applicationContext)
        if (restored) {
            Log.i(TAG, "Accessibility service check passed / restored")
        } else {
            Log.w(TAG, "Could not verify/restore accessibility service")
        }

        // アンインストールブロックも念のため確認
        DeviceOwnerHelper.blockUninstall(applicationContext)

        return Result.success()
    }
}
