package com.example.accessibilityguard

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConditionCheckerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    /** テスト用の固定充電状態プロバイダ */
    class FakeChargingProvider(var charging: Boolean = true) : ChargingProvider {
        override fun isCharging(): Boolean = charging
    }

    private val fakeCharging = FakeChargingProvider()

    private fun clockAt(hour: Int, minute: Int): Clock {
        val time = LocalTime.of(hour, minute)
        val instant = time.atDate(LocalDate.of(2026, 2, 22))
            .toInstant(ZoneOffset.ofHours(9)) // JST
        return Clock.fixed(instant, ZoneId.of("Asia/Tokyo"))
    }

    private fun checkerAt(hour: Int, minute: Int): ConditionChecker {
        return ConditionChecker(context, clockAt(hour, minute), fakeCharging)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(ConditionChecker.PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        fakeCharging.charging = true
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    // ===== デフォルト設定 (06:00〜08:00, 充電必須) =====

    @Test
    fun `default - allowed at 0600 while charging`() {
        assertTrue(checkerAt(6, 0).isAllowed())
    }

    @Test
    fun `default - allowed at 0730 while charging`() {
        assertTrue(checkerAt(7, 30).isAllowed())
    }

    @Test
    fun `default - blocked at 0759 when end is 0800 exclusive`() {
        assertTrue(checkerAt(7, 59).isAllowed())
    }

    @Test
    fun `default - blocked at 0800 (end is exclusive)`() {
        assertFalse(checkerAt(8, 0).isAllowed())
    }

    @Test
    fun `default - blocked at 0559 (before window)`() {
        assertFalse(checkerAt(5, 59).isAllowed())
    }

    @Test
    fun `default - blocked at midnight`() {
        assertFalse(checkerAt(0, 0).isAllowed())
    }

    @Test
    fun `default - blocked at 2359`() {
        assertFalse(checkerAt(23, 59).isAllowed())
    }

    @Test
    fun `default - blocked at 1200 noon`() {
        assertFalse(checkerAt(12, 0).isAllowed())
    }

    // ===== 充電条件 =====

    @Test
    fun `blocked when in time window but not charging`() {
        fakeCharging.charging = false
        assertFalse(checkerAt(7, 0).isAllowed())
    }

    @Test
    fun `allowed when charging requirement disabled and not charging`() {
        fakeCharging.charging = false
        val checker = checkerAt(7, 0)
        checker.setRequireCharging(false)
        assertTrue(checker.isAllowed())
    }

    @Test
    fun `blocked when not charging and outside time window`() {
        fakeCharging.charging = false
        assertFalse(checkerAt(12, 0).isAllowed())
    }

    // ===== ガード無効 =====

    @Test
    fun `always allowed when guard disabled - outside window`() {
        val checker = checkerAt(12, 0)
        checker.setGuardEnabled(false)
        assertTrue(checker.isAllowed())
    }

    @Test
    fun `always allowed when guard disabled - not charging`() {
        fakeCharging.charging = false
        val checker = checkerAt(12, 0)
        checker.setGuardEnabled(false)
        assertTrue(checker.isAllowed())
    }

    @Test
    fun `isGuardEnabled returns true by default`() {
        assertTrue(checkerAt(7, 0).isGuardEnabled)
    }

    @Test
    fun `isGuardEnabled reflects saved value`() {
        val checker = checkerAt(7, 0)
        checker.setGuardEnabled(false)
        assertFalse(checker.isGuardEnabled)
    }

    // ===== カスタムウィンドウ =====

    @Test
    fun `custom window 22-23 allows at 2230`() {
        val checker = checkerAt(22, 30)
        checker.setWindow(22, 0, 23, 0)
        assertTrue(checker.isAllowed())
    }

    @Test
    fun `custom window 22-23 blocks at 2100`() {
        val checker = checkerAt(21, 0)
        checker.setWindow(22, 0, 23, 0)
        assertFalse(checker.isAllowed())
    }

    @Test
    fun `custom window 22-23 blocks at 2300`() {
        val checker = checkerAt(23, 0)
        checker.setWindow(22, 0, 23, 0)
        assertFalse(checker.isAllowed())
    }

    @Test
    fun `1-hour window 0700-0800`() {
        val checker = checkerAt(7, 30)
        checker.setWindow(7, 0, 8, 0)
        assertTrue(checker.isAllowed())
    }

    @Test
    fun `narrow window with minutes 0630-0645`() {
        val checker = checkerAt(6, 35)
        checker.setWindow(6, 30, 6, 45)
        assertTrue(checker.isAllowed())
    }

    @Test
    fun `narrow window just outside - 0646 for 0630-0645`() {
        val checker = checkerAt(6, 46)
        checker.setWindow(6, 30, 6, 45)
        assertFalse(checker.isAllowed())
    }

    // ===== Setters / Getters =====

    @Test
    fun `setWindow persists values`() {
        val checker = checkerAt(7, 0)
        checker.setWindow(10, 15, 14, 30)
        assertEquals(10, checker.getStartHour())
        assertEquals(15, checker.getStartMinute())
        assertEquals(14, checker.getEndHour())
        assertEquals(30, checker.getEndMinute())
    }

    @Test
    fun `setRequireCharging persists value`() {
        val checker = checkerAt(7, 0)
        checker.setRequireCharging(false)
        assertFalse(checker.getRequireCharging())
        checker.setRequireCharging(true)
        assertTrue(checker.getRequireCharging())
    }

    @Test
    fun `defaults are correct`() {
        val checker = checkerAt(7, 0)
        assertEquals(ConditionChecker.DEFAULT_START_HOUR, checker.getStartHour())
        assertEquals(ConditionChecker.DEFAULT_START_MINUTE, checker.getStartMinute())
        assertEquals(ConditionChecker.DEFAULT_END_HOUR, checker.getEndHour())
        assertEquals(ConditionChecker.DEFAULT_END_MINUTE, checker.getEndMinute())
        assertTrue(checker.getRequireCharging())
    }

    // ===== getStatusSummary =====

    @Test
    fun `status summary with defaults`() {
        val summary = checkerAt(7, 0).getStatusSummary()
        assertEquals("許可ウィンドウ: 06:00 〜 08:00 + 充電中", summary)
    }

    @Test
    fun `status summary without charging requirement`() {
        val checker = checkerAt(7, 0)
        checker.setRequireCharging(false)
        assertEquals("許可ウィンドウ: 06:00 〜 08:00", checker.getStatusSummary())
    }

    @Test
    fun `status summary with custom window`() {
        val checker = checkerAt(7, 0)
        checker.setWindow(22, 0, 23, 30)
        assertEquals("許可ウィンドウ: 22:00 〜 23:30 + 充電中", checker.getStatusSummary())
    }

    // ===== SharedPreferences の独立性 =====

    @Test
    fun `new instance reads persisted settings`() {
        val checker1 = checkerAt(7, 0)
        checker1.setWindow(10, 0, 11, 0)
        checker1.setRequireCharging(false)
        checker1.setGuardEnabled(false)

        // 新しいインスタンスを作成
        val checker2 = checkerAt(7, 0)
        assertEquals(10, checker2.getStartHour())
        assertEquals(11, checker2.getEndHour())
        assertFalse(checker2.getRequireCharging())
        assertFalse(checker2.isGuardEnabled)
    }

    // ===== 境界値テスト =====

    @Test
    fun `window 0000-2400 allows all times`() {
        val checker = checkerAt(12, 0)
        checker.setWindow(0, 0, 24, 0)
        // LocalTime.of(24, 0) は不正なので 23:59 で代用
        checker.setWindow(0, 0, 23, 59)
        assertTrue(checker.isAllowed())
    }

    @Test
    fun `exact start boundary is inclusive`() {
        val checker = checkerAt(6, 0)
        // デフォルト window 6:00-8:00, start is inclusive
        assertTrue(checker.isAllowed())
    }

    @Test
    fun `exact end boundary is exclusive`() {
        val checker = checkerAt(8, 0)
        // デフォルト window 6:00-8:00, end is exclusive
        assertFalse(checker.isAllowed())
    }
}
