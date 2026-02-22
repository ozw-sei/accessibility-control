package com.example.accessibilityguard

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * 設定アプリのユーザー補助画面を監視し、
 * 許可条件外であれば HOME に戻すことでアクセスをブロックする。
 *
 * ブロック対象:
 * - ユーザー補助設定のメイン画面
 * - 個々のユーザー補助サービスの詳細画面
 * - 設定アプリ内の検索結果から「ユーザー補助」へ遷移するケース
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GuardA11y"
    }

    private lateinit var conditionChecker: ConditionChecker
    private var lastBlockedTime = 0L
    private val BLOCK_COOLDOWN_MS = 1000L // Toast 連打防止

    override fun onServiceConnected() {
        super.onServiceConnected()
        conditionChecker = ConditionChecker(this)
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return
        if (!SettingsDetector.isSettingsPackage(packageName)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(event)
            }
        }
    }

    /**
     * アクティビティ/フラグメント遷移を検知
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: return

        if (SettingsDetector.isBlockedClassName(className) && !conditionChecker.isAllowed()) {
            blockAndGoHome()
            Log.i(TAG, "Blocked window state: $className")
            return
        }

        // SubSettings は汎用アクティビティなので、ノードツリーからタイトルを取得して判定する
        // Pixel 等ではユーザー補助設定が SubSettings 内のフラグメントとして表示される
        if (className.contains("SubSettings") && !conditionChecker.isAllowed()) {
            try {
                val rootNode = rootInActiveWindow ?: return
                val title = getWindowTitle(rootNode)
                rootNode.recycle()
                if (title != null && SettingsDetector.isBlockedTitle(title)) {
                    blockAndGoHome()
                    Log.i(TAG, "Blocked SubSettings with title: $title")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error checking SubSettings: ${e.message}")
            }
        }
    }

    /**
     * コンテンツ変化を検知（設定検索からの遷移対応）
     * ウィンドウのタイトルに「ユーザー補助」が含まれるかチェック
     */
    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        // パフォーマンスのため、頻繁な呼び出しは無視
        val now = System.currentTimeMillis()
        if (now - lastBlockedTime < 300) return

        try {
            val rootNode = rootInActiveWindow ?: return
            val title = getWindowTitle(rootNode)
            rootNode.recycle()

            if (title != null) {
                if (SettingsDetector.isBlockedTitle(title) && !conditionChecker.isAllowed()) {
                    blockAndGoHome()
                    Log.i(TAG, "Blocked content with title: $title")
                }
            }
        } catch (e: Exception) {
            // AccessibilityNodeInfo の操作は例外が出やすいので握りつぶす
            Log.d(TAG, "Error checking content: ${e.message}")
        }
    }

    /**
     * 現在のウィンドウからツールバーのタイトルテキストを抽出。
     * 複数の方法でタイトルを取得し、汎用的な値（"SubSettings" 等）は除外する。
     */
    private fun getWindowTitle(root: AccessibilityNodeInfo): String? {
        // 1. windows API からタイトル取得を試みる（API 28+）
        //    ただし汎用タイトルは無視してノードツリーに委ねる
        try {
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val title = window.title?.toString()
                    if (!title.isNullOrBlank()
                        && !title.equals("SubSettings", ignoreCase = true)
                        && !title.equals("設定", ignoreCase = true)
                        && !title.equals("Settings", ignoreCase = true)
                    ) {
                        return title
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. コラプシングツールバーのタイトル TextView を探索（Pixel の設定アプリ）
        try {
            val titleNodes = root.findAccessibilityNodeInfosByViewId(
                "com.android.settings:id/collapsing_toolbar_title"
            )
            if (!titleNodes.isNullOrEmpty()) {
                val text = titleNodes[0].text?.toString()
                titleNodes.forEach { it.recycle() }
                if (!text.isNullOrBlank()) return text
            }
        } catch (_: Exception) {}

        // 3. アクションバーのタイトルを探索（フォールバック）
        try {
            val titleNodes = root.findAccessibilityNodeInfosByViewId(
                "android:id/title"
            )
            if (!titleNodes.isNullOrEmpty()) {
                val text = titleNodes[0].text?.toString()
                titleNodes.forEach { it.recycle() }
                if (!text.isNullOrBlank()) return text
            }
        } catch (_: Exception) {}

        return null
    }

    private fun blockAndGoHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)

        val now = System.currentTimeMillis()
        if (now - lastBlockedTime > BLOCK_COOLDOWN_MS) {
            val checker = conditionChecker
            Toast.makeText(
                this,
                "🔒 ユーザー補助設定はロック中\n" +
                    "許可: ${"%02d:%02d".format(checker.getStartHour(), checker.getStartMinute())}" +
                    "〜${"%02d:%02d".format(checker.getEndHour(), checker.getEndMinute())}" +
                    if (checker.getRequireCharging()) " (充電中)" else "",
                Toast.LENGTH_LONG
            ).show()
        }
        lastBlockedTime = now
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "AccessibilityService destroyed — watchdog should re-enable")
    }
}
