package com.example.accessibilityguard

import org.junit.Assert.*
import org.junit.Test

/**
 * SettingsDetector のパターンマッチングテスト。
 * Android 依存なしの純粋な JVM テスト。
 */
class SettingsDetectorTest {

    // ===== isSettingsPackage =====

    @Test
    fun `isSettingsPackage - standard settings`() {
        assertTrue(SettingsDetector.isSettingsPackage("com.android.settings"))
    }

    @Test
    fun `isSettingsPackage - null`() {
        assertFalse(SettingsDetector.isSettingsPackage(null))
    }

    @Test
    fun `isSettingsPackage - other package`() {
        assertFalse(SettingsDetector.isSettingsPackage("com.google.chrome"))
    }

    @Test
    fun `isSettingsPackage - similar but different`() {
        assertFalse(SettingsDetector.isSettingsPackage("com.android.settings.intelligence"))
    }

    @Test
    fun `isSettingsPackage - empty string`() {
        assertFalse(SettingsDetector.isSettingsPackage(""))
    }

    // ===== isBlockedClassName - AOSP / Pixel =====

    @Test
    fun `blocked - AccessibilitySettings`() {
        assertTrue(SettingsDetector.isBlockedClassName(
            "com.android.settings.accessibility.AccessibilitySettings"
        ))
    }

    @Test
    fun `blocked - ToggleAccessibilityServicePreferenceFragment`() {
        assertTrue(SettingsDetector.isBlockedClassName(
            "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"
        ))
    }

    @Test
    fun `blocked - InvisibleToggleAccessibilityServicePreferenceFragment`() {
        assertTrue(SettingsDetector.isBlockedClassName(
            "com.android.settings.accessibility.InvisibleToggleAccessibilityServicePreferenceFragment"
        ))
    }

    @Test
    fun `blocked - AccessibilityShortcutPreferenceFragment`() {
        assertTrue(SettingsDetector.isBlockedClassName(
            "com.android.settings.accessibility.AccessibilityShortcutPreferenceFragment"
        ))
    }

    // ===== isBlockedClassName - Samsung =====

    @Test
    fun `blocked - Samsung AccessibilitySettingsActivity`() {
        assertTrue(SettingsDetector.isBlockedClassName(
            "com.samsung.accessibility.AccessibilitySettingsActivity"
        ))
    }

    // ===== isBlockedClassName - Xiaomi =====

    @Test
    fun `blocked - Xiaomi MiuiAccessibilitySettings`() {
        assertTrue(SettingsDetector.isBlockedClassName(
            "com.android.settings.MiuiAccessibilitySettings"
        ))
    }

    // ===== isBlockedClassName - case insensitivity =====

    @Test
    fun `blocked - case insensitive match`() {
        assertTrue(SettingsDetector.isBlockedClassName(
            "com.android.settings.ACCESSIBILITYSETTINGS"
        ))
    }

    @Test
    fun `blocked - mixed case`() {
        assertTrue(SettingsDetector.isBlockedClassName(
            "com.android.settings.accessibilitysettings"
        ))
    }

    // ===== isBlockedClassName - safe screens =====

    @Test
    fun `not blocked - null`() {
        assertFalse(SettingsDetector.isBlockedClassName(null))
    }

    @Test
    fun `not blocked - empty`() {
        assertFalse(SettingsDetector.isBlockedClassName(""))
    }

    @Test
    fun `not blocked - DisplaySettings`() {
        assertFalse(SettingsDetector.isBlockedClassName(
            "com.android.settings.display.DisplaySettings"
        ))
    }

    @Test
    fun `not blocked - WifiSettings`() {
        assertFalse(SettingsDetector.isBlockedClassName(
            "com.android.settings.wifi.WifiSettings"
        ))
    }

    @Test
    fun `not blocked - BluetoothSettings`() {
        assertFalse(SettingsDetector.isBlockedClassName(
            "com.android.settings.bluetooth.BluetoothSettings"
        ))
    }

    @Test
    fun `not blocked - generic settings main`() {
        assertFalse(SettingsDetector.isBlockedClassName(
            "com.android.settings.Settings"
        ))
    }

    @Test
    fun `not blocked - SubSettings (generic container)`() {
        assertFalse(SettingsDetector.isBlockedClassName(
            "com.android.settings.SubSettings"
        ))
    }

    @Test
    fun `not blocked - battery settings`() {
        assertFalse(SettingsDetector.isBlockedClassName(
            "com.android.settings.fuelgauge.BatterySettings"
        ))
    }

    @Test
    fun `not blocked - app info`() {
        assertFalse(SettingsDetector.isBlockedClassName(
            "com.android.settings.applications.InstalledAppDetails"
        ))
    }

    // ===== isBlockedTitle - Japanese =====

    @Test
    fun `blocked title - Japanese accessibility`() {
        assertTrue(SettingsDetector.isBlockedTitle("ユーザー補助"))
    }

    @Test
    fun `blocked title - Japanese accessibility with prefix`() {
        assertTrue(SettingsDetector.isBlockedTitle("設定 > ユーザー補助"))
    }

    @Test
    fun `blocked title - katakana accessibility`() {
        assertTrue(SettingsDetector.isBlockedTitle("アクセシビリティ"))
    }

    // ===== isBlockedTitle - English =====

    @Test
    fun `blocked title - English Accessibility`() {
        assertTrue(SettingsDetector.isBlockedTitle("Accessibility"))
    }

    @Test
    fun `blocked title - English lowercase`() {
        assertTrue(SettingsDetector.isBlockedTitle("accessibility"))
    }

    @Test
    fun `blocked title - English in breadcrumb`() {
        assertTrue(SettingsDetector.isBlockedTitle("Settings > Accessibility"))
    }

    // ===== isBlockedTitle - safe titles =====

    @Test
    fun `not blocked title - null`() {
        assertFalse(SettingsDetector.isBlockedTitle(null))
    }

    @Test
    fun `not blocked title - empty`() {
        assertFalse(SettingsDetector.isBlockedTitle(""))
    }

    @Test
    fun `not blocked title - Display`() {
        assertFalse(SettingsDetector.isBlockedTitle("Display"))
    }

    @Test
    fun `not blocked title - Battery`() {
        assertFalse(SettingsDetector.isBlockedTitle("Battery"))
    }

    @Test
    fun `not blocked title - Wi-Fi`() {
        assertFalse(SettingsDetector.isBlockedTitle("Wi-Fi"))
    }

    @Test
    fun `not blocked title - Sound`() {
        assertFalse(SettingsDetector.isBlockedTitle("Sound"))
    }

    @Test
    fun `not blocked title - Japanese display`() {
        assertFalse(SettingsDetector.isBlockedTitle("ディスプレイ"))
    }

    @Test
    fun `not blocked title - Japanese network`() {
        assertFalse(SettingsDetector.isBlockedTitle("ネットワークとインターネット"))
    }

    // ===== 部分一致に対する安全性 =====

    @Test
    fun `not blocked class - partial match in unrelated class`() {
        // "AccessibilitySettings" が含まれていない限り安全
        assertFalse(SettingsDetector.isBlockedClassName(
            "com.android.settings.SecuritySettings"
        ))
    }

    @Test
    fun `blocked class - contains as substring`() {
        // "AccessibilitySettings" が部分文字列として含まれるケース → ブロック
        assertTrue(SettingsDetector.isBlockedClassName(
            "com.android.settings.SubAccessibilitySettingsFragment"
        ))
    }
}
