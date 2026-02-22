package com.example.accessibilityguard

import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import java.time.Clock
import java.time.LocalTime

/**
 * 「ユーザー補助設定を開いてよい条件」を判定するクラス。
 * デフォルト: 充電中 かつ 06:00〜08:00
 *
 * テスト時は [clock] と [chargingProvider] を差し替え可能。
 */
class ConditionChecker(
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val chargingProvider: ChargingProvider = SystemChargingProvider(context),
) {

    companion object {
        const val PREF_NAME = "guard_prefs"
        const val KEY_START_HOUR = "start_hour"
        const val KEY_START_MINUTE = "start_minute"
        const val KEY_END_HOUR = "end_hour"
        const val KEY_END_MINUTE = "end_minute"
        const val KEY_REQUIRE_CHARGING = "require_charging"
        const val KEY_GUARD_ENABLED = "guard_enabled"

        const val DEFAULT_START_HOUR = 6
        const val DEFAULT_START_MINUTE = 0
        const val DEFAULT_END_HOUR = 8
        const val DEFAULT_END_MINUTE = 0
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** ガード自体が有効かどうか */
    val isGuardEnabled: Boolean
        get() = prefs.getBoolean(KEY_GUARD_ENABLED, true)

    /** 現在アクセスを許可してよいか */
    fun isAllowed(): Boolean {
        if (!isGuardEnabled) return true

        val now = LocalTime.now(clock)
        val start = LocalTime.of(getStartHour(), getStartMinute())
        val end = LocalTime.of(getEndHour(), getEndMinute())
        val inTimeWindow = !now.isBefore(start) && now.isBefore(end)

        val chargingOk = if (getRequireCharging()) isCharging() else true
        return inTimeWindow && chargingOk
    }

    fun isCharging(): Boolean = chargingProvider.isCharging()

    // --- Getters ---
    fun getStartHour() = prefs.getInt(KEY_START_HOUR, DEFAULT_START_HOUR)
    fun getStartMinute() = prefs.getInt(KEY_START_MINUTE, DEFAULT_START_MINUTE)
    fun getEndHour() = prefs.getInt(KEY_END_HOUR, DEFAULT_END_HOUR)
    fun getEndMinute() = prefs.getInt(KEY_END_MINUTE, DEFAULT_END_MINUTE)
    fun getRequireCharging() = prefs.getBoolean(KEY_REQUIRE_CHARGING, true)

    // --- Setters ---
    fun setWindow(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
        prefs.edit()
            .putInt(KEY_START_HOUR, startHour)
            .putInt(KEY_START_MINUTE, startMinute)
            .putInt(KEY_END_HOUR, endHour)
            .putInt(KEY_END_MINUTE, endMinute)
            .apply()
    }

    fun setRequireCharging(required: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_CHARGING, required).apply()
    }

    fun setGuardEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GUARD_ENABLED, enabled).apply()
    }

    fun getStatusSummary(): String {
        return buildString {
            append("許可ウィンドウ: %02d:%02d 〜 %02d:%02d".format(
                getStartHour(), getStartMinute(),
                getEndHour(), getEndMinute()
            ))
            if (getRequireCharging()) {
                append(" + 充電中")
            }
        }
    }
}

/** 充電状態の取得を抽象化（テスト時にモック可能） */
interface ChargingProvider {
    fun isCharging(): Boolean
}

class SystemChargingProvider(private val context: Context) : ChargingProvider {
    override fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }
}
