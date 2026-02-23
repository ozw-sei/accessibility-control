package com.example.accessibilityguard

/**
 * 設定アプリのユーザー補助画面・Device Admin 管理画面かどうかを判定するロジック。
 * AccessibilityService から分離し、単体テスト可能にしている。
 */
object SettingsDetector {

    const val SETTINGS_PACKAGE = "com.android.settings"
    const val DEFAULT_FREEDOM_PACKAGE = "to.freedom.android2"

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

    // Device Admin 管理画面のアクティビティ/フラグメント名パターン
    // Freedom の Device Admin 無効化 → アンインストールの経路をブロック
    val DEVICE_ADMIN_CLASS_PATTERNS = listOf(
        "DeviceAdminAdd",               // AOSP: 有効化/無効化の確認画面
        "DeviceAdminSettings",          // AOSP: Device Admin 一覧
        "DeviceAdministratorsSettings", // 一部デバイス
    )

    // ウィンドウタイトルによる検出パターン（ユーザー補助画面）
    val BLOCKED_TITLE_PATTERNS = listOf(
        "ユーザー補助",        // 日本語
        "Accessibility",      // 英語
        "アクセシビリティ",    // 一部デバイス
    )

    // Device Admin 画面のタイトルパターン
    val DEVICE_ADMIN_TITLE_PATTERNS = listOf(
        "デバイス管理アプリ",          // 日本語
        "Device admin",               // 英語
        "端末管理アプリ",              // 一部デバイス（旧訳）
    )

    // Freedom 関連のテキストパターン（ノードスキャンで使用）
    val FREEDOM_TEXT_PATTERNS = listOf(
        "freedom",
        "Freedom",
    )

    /**
     * クラス名がブロック対象（ユーザー補助設定）かどうか判定。
     */
    fun isBlockedClassName(className: String?): Boolean {
        if (className == null) return false
        return BLOCKED_CLASS_PATTERNS.any { pattern ->
            className.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * クラス名が Device Admin 管理画面かどうか判定。
     */
    fun isDeviceAdminClassName(className: String?): Boolean {
        if (className == null) return false
        return DEVICE_ADMIN_CLASS_PATTERNS.any { pattern ->
            className.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * ウィンドウタイトルがブロック対象（ユーザー補助設定）かどうか判定。
     */
    fun isBlockedTitle(title: String?): Boolean {
        if (title == null) return false
        return BLOCKED_TITLE_PATTERNS.any { pattern ->
            title.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * ウィンドウタイトルが Device Admin 画面かどうか判定。
     */
    fun isDeviceAdminTitle(title: String?): Boolean {
        if (title == null) return false
        return DEVICE_ADMIN_TITLE_PATTERNS.any { pattern ->
            title.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * テキストに Freedom 関連の文字列が含まれるかどうか判定。
     * Device Admin 画面で Freedom が対象アプリかどうかの判定に使用。
     */
    fun containsFreedomText(text: String?): Boolean {
        if (text == null) return false
        return FREEDOM_TEXT_PATTERNS.any { pattern ->
            text.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * パッケージ名が設定アプリかどうか判定。
     */
    fun isSettingsPackage(packageName: String?): Boolean {
        return packageName == SETTINGS_PACKAGE
    }
}
