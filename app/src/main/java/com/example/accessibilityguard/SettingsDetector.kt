package com.example.accessibilityguard

/**
 * 設定アプリのユーザー補助画面かどうかを判定するロジック。
 * AccessibilityService から分離し、単体テスト可能にしている。
 */
object SettingsDetector {

    const val SETTINGS_PACKAGE = "com.android.settings"

    // ブロック対象のアクティビティ/フラグメント名パターン
    // AOSP, Pixel, Samsung, Xiaomi 等をカバー
    val BLOCKED_CLASS_PATTERNS = listOf(
        // AOSP / Pixel
        "AccessibilitySettings",
        "ToggleAccessibilityServicePreferenceFragment",
        "InvisibleToggleAccessibilityServicePreferenceFragment",
        "AccessibilityShortcutPreferenceFragment",
        // Samsung OneUI
        "AccessibilitySettingsActivity",
        // Xiaomi MIUI
        "MiuiAccessibilitySettings",
    )

    // ウィンドウタイトルによる検出パターン
    val BLOCKED_TITLE_PATTERNS = listOf(
        "ユーザー補助",        // 日本語
        "Accessibility",      // 英語
        "アクセシビリティ",    // 一部デバイス
    )

    /**
     * クラス名がブロック対象かどうか判定。
     * Settings アプリの Window State Changed で取得した className に対して使用。
     */
    fun isBlockedClassName(className: String?): Boolean {
        if (className == null) return false
        return BLOCKED_CLASS_PATTERNS.any { pattern ->
            className.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * ウィンドウタイトルがブロック対象かどうか判定。
     * 設定アプリ内の検索結果から遷移するケースに対応。
     */
    fun isBlockedTitle(title: String?): Boolean {
        if (title == null) return false
        return BLOCKED_TITLE_PATTERNS.any { pattern ->
            title.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * パッケージ名が設定アプリかどうか判定。
     */
    fun isSettingsPackage(packageName: String?): Boolean {
        return packageName == SETTINGS_PACKAGE
    }
}
