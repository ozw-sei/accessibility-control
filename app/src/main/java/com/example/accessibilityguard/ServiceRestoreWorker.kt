package com.example.accessibilityguard

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * アプリ更新後に AccessibilityService を自動復旧するワーカー。
 *
 * Android はアプリ更新時に AccessibilityService を無効化する。
 * BootReceiver の即時復旧が失敗する場合（タイミング問題）に備え、
 * 遅延実行 + 指数バックオフでリトライすることで確実に復旧する。
 *
 * リトライ戦略:
 * - 初回: 更新から 5 秒後に実行
 * - 失敗時: 30 秒 → 60 秒 → 120 秒 ... と指数バックオフ
 * - 最終的には WatchdogWorker（15分毎）が最終防衛線
 */
class ServiceRestoreWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ServiceRestore"
        private const val WORK_NAME = "accessibility_service_restore"

        /**
         * アプリ更新後に復旧ワーカーをスケジュールする。
         * 初回は 5 秒後に実行。
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ServiceRestoreWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS   // 最小バックオフ: 30秒
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.i(TAG, "Service restore scheduled (5s delay + exponential backoff)")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Attempting service restore (attempt #$runAttemptCount)")

        // 既に有効なら成功
        val restored = DeviceOwnerHelper.ensureAccessibilityServiceEnabled(applicationContext)
        if (restored) {
            Log.i(TAG, "Accessibility service restored successfully")
            // アンインストールブロックも念のため再確認
            DeviceOwnerHelper.blockUninstall(applicationContext)
            return Result.success()
        }

        // まだ復旧できない → リトライ（指数バックオフで再実行）
        if (runAttemptCount < 5) {
            Log.w(TAG, "Restore failed, scheduling retry #${runAttemptCount + 1}")
            return Result.retry()
        }

        // 5回リトライしても駄目なら諦める（WatchdogWorker が引き継ぐ）
        Log.e(TAG, "Restore failed after $runAttemptCount attempts, giving up to WatchdogWorker")
        return Result.failure()
    }
}
