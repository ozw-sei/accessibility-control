package com.example.accessibilityguard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WatchdogWorker のテスト。
 * WorkManager の TestListenableWorkerBuilder を使用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchdogWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // ガードを有効にしておく
        val prefs = context.getSharedPreferences(
            ConditionChecker.PREF_NAME, Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
    }

    @Test
    fun `worker returns success when guard enabled`() = runBlocking {
        val worker = TestListenableWorkerBuilder<WatchdogWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `worker returns success when guard disabled`() = runBlocking {
        val prefs = context.getSharedPreferences(
            ConditionChecker.PREF_NAME, Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(ConditionChecker.KEY_GUARD_ENABLED, false).commit()

        val worker = TestListenableWorkerBuilder<WatchdogWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `worker never returns failure or retry`() = runBlocking {
        // Worker はどんな状態でも success を返すべき（定期実行を止めないため）
        val worker = TestListenableWorkerBuilder<WatchdogWorker>(context).build()
        val result = worker.doWork()
        assertNotEquals(ListenableWorker.Result.failure(), result)
        assertNotEquals(ListenableWorker.Result.retry(), result)
    }
}
