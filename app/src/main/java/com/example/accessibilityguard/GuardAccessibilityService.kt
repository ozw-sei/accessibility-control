package com.example.accessibilityguard

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * 設定アプリのユーザー補助画面と Freedom の Device Admin 管理画面を監視し、
 * 許可条件外であれば HOME に戻すことでアクセスをブロックする。
 *
 * ブロック対象:
 * - ユーザー補助設定のメイン画面
 * - 個々のユーザー補助サービスの詳細画面
 * - 設定アプリ内の検索結果から「ユーザー補助」へ遷移するケース
 * - Freedom の Device Admin 無効化/アンインストール画面
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GuardA11y"
    }

    private lateinit var conditionChecker: ConditionChecker
    private var lastBlockedTime = 0L
    private val BLOCK_COOLDOWN_MS = 1000L // Toast 連打防止

    // SubSettings のタイトル取得リトライ用
    private val handler = Handler(Looper.getMainLooper())
    private val RETRY_DELAYS_MS = longArrayOf(150, 400, 800, 1500, 3000)

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

        // ユーザー補助設定画面のブロック
        if (SettingsDetector.isBlockedClassName(className) && !conditionChecker.isAllowed()) {
            cancelPendingRetries()
            blockAndGoHome()
            Log.i(TAG, "Blocked window state: $className")
            return
        }

        // Device Admin 管理画面のブロック（Freedom 関連）
        // DeviceAdminAdd は Freedom の「無効にしてアンインストール」確認画面
        // 条件に関わらず Freedom の保護は常時有効
        if (SettingsDetector.isDeviceAdminClassName(className)) {
            cancelPendingRetries()
            if (tryBlockDeviceAdmin("DeviceAdmin class: $className")) {
                return
            }
            // ノードツリーがまだ描画されていない場合、リトライ
            scheduleDeviceAdminRetries()
            return
        }

        // SubSettings は汎用アクティビティなので、ノードツリーからタイトルを取得して判定する
        // Pixel 等ではユーザー補助設定が SubSettings 内のフラグメントとして表示される
        if (className.contains("SubSettings") && !conditionChecker.isAllowed()) {
            cancelPendingRetries()
            if (!tryBlockByTitle("SubSettings immediate")) {
                // タイトルがまだ描画されていない場合、リトライをスケジュール
                scheduleSubSettingsRetries()
            }
        }
    }

    /**
     * 現在のノードツリーからタイトルを取得し、ブロック対象ならブロックする。
     * タイトル取得に失敗した場合はノードツリーのテキストを直接スキャンする。
     * @return ブロックに成功した場合 true
     */
    private fun tryBlockByTitle(source: String): Boolean {
        try {
            val rootNode = rootInActiveWindow ?: return false

            // 1. タイトルベースの判定
            val title = getWindowTitle(rootNode)
            if (title != null && SettingsDetector.isBlockedTitle(title)) {
                rootNode.recycle()
                blockAndGoHome()
                Log.i(TAG, "Blocked ($source) via title: $title")
                return true
            }

            // 2. タイトルが取得できない/ブロック対象でない場合、
            //    ノードツリーからブロック対象テキストを直接検索
            val blockedText = findBlockedTextInNodes(rootNode)
            rootNode.recycle()
            if (blockedText != null) {
                blockAndGoHome()
                Log.i(TAG, "Blocked ($source) via node scan: $blockedText")
                return true
            }

            Log.d(TAG, "Title check ($source): ${title ?: "(null)"}, node scan: not found")
        } catch (e: Exception) {
            Log.d(TAG, "Error checking title ($source): ${e.message}")
        }
        return false
    }

    /**
     * ノードツリーからブロック対象のテキストを検索する。
     * findAccessibilityNodeInfosByText を使って効率的に検索。
     * @return 見つかったブロック対象テキスト、なければ null
     */
    private fun findBlockedTextInNodes(root: AccessibilityNodeInfo): String? {
        for (pattern in SettingsDetector.BLOCKED_TITLE_PATTERNS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByText(pattern)
                if (!nodes.isNullOrEmpty()) {
                    // ヘッダー要素（heading ロールやツールバー等）のテキストのみ対象にする
                    // リスト内のアイテムテキストを誤検出しないようにする
                    for (node in nodes) {
                        val text = node.text?.toString() ?: continue
                        // テキストがブロック対象パターンにマッチし、
                        // かつヘッダー的な要素（heading、ツールバー内）であるか確認
                        if (SettingsDetector.isBlockedTitle(text) && isHeaderNode(node)) {
                            nodes.forEach { it.recycle() }
                            return text
                        }
                    }
                    nodes.forEach { it.recycle() }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * ノードがヘッダー/タイトル的な要素かどうかを判定する。
     * リストアイテムのテキストを誤検出しないためのフィルタ。
     */
    private fun isHeaderNode(node: AccessibilityNodeInfo): Boolean {
        try {
            // heading として宣言されている
            if (node.isHeading) return true

            // View ID がタイトル系
            val viewId = node.viewIdResourceName ?: ""
            if (viewId.contains("title", ignoreCase = true)
                && (viewId.contains("collapsing") || viewId.contains("toolbar")
                    || viewId.contains("action_bar") || viewId.contains("header"))
            ) {
                return true
            }

            // 親を辿ってツールバーやヘッダー内かチェック（最大5階層）
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                val parentClass = parent.className?.toString() ?: ""
                val parentId = parent.viewIdResourceName ?: ""
                if (parentClass.contains("Toolbar", ignoreCase = true)
                    || parentClass.contains("ActionBar", ignoreCase = true)
                    || parentId.contains("toolbar", ignoreCase = true)
                    || parentId.contains("action_bar", ignoreCase = true)
                    || parentId.contains("collapsing", ignoreCase = true)
                ) {
                    parent.recycle()
                    return true
                }
                val nextParent = parent.parent
                parent.recycle()
                parent = nextParent
                depth++
            }
            parent?.recycle()
        } catch (_: Exception) {}
        return false
    }

    /**
     * SubSettings のタイトル取得を遅延リトライする。
     * TYPE_WINDOW_STATE_CHANGED の時点ではフラグメントのタイトル UI が
     * まだ描画されていないことがあるため、複数回のリトライで対応する。
     */
    private fun scheduleSubSettingsRetries() {
        Log.d(TAG, "Scheduling SubSettings retries")
        for ((index, delayMs) in RETRY_DELAYS_MS.withIndex()) {
            val retryRunnable = Runnable {
                // リトライ時に条件が変わっている可能性もチェック
                if (conditionChecker.isAllowed()) {
                    Log.d(TAG, "Retry #${index + 1}: condition now allowed, skipping")
                    return@Runnable
                }
                // 現在のウィンドウがまだ設定アプリか確認
                try {
                    val rootNode = rootInActiveWindow
                    if (rootNode == null) {
                        Log.d(TAG, "Retry #${index + 1}: no root node")
                        return@Runnable
                    }
                    val currentPackage = rootNode.packageName?.toString()
                    if (currentPackage == null || !SettingsDetector.isSettingsPackage(currentPackage)) {
                        rootNode.recycle()
                        Log.d(TAG, "Retry #${index + 1}: no longer in settings ($currentPackage)")
                        return@Runnable
                    }
                    rootNode.recycle()
                } catch (e: Exception) {
                    Log.d(TAG, "Retry #${index + 1}: error checking package: ${e.message}")
                    return@Runnable
                }

                if (tryBlockByTitle("SubSettings retry #${index + 1}")) {
                    // ブロック成功 → 以降のリトライをキャンセル
                    cancelPendingRetries()
                }
            }
            handler.postDelayed(retryRunnable, delayMs)
        }
        // 最後のリトライの Runnable を保持（キャンセル用の目印）
        // 実際には全 Runnable をキャンセルするため handler.removeCallbacksAndMessages を使う
    }

    /**
     * 保留中のリトライをすべてキャンセル
     */
    private fun cancelPendingRetries() {
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * コンテンツ変化を検知（設定検索からの遷移対応）
     * ウィンドウのタイトルに「ユーザー補助」が含まれるかチェック
     */
    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        // パフォーマンスのため、頻繁な呼び出しは無視
        val now = System.currentTimeMillis()
        if (now - lastBlockedTime < 300) return

        if (conditionChecker.isAllowed()) return

        try {
            val rootNode = rootInActiveWindow ?: return
            val title = getWindowTitle(rootNode)

            if (title != null && SettingsDetector.isBlockedTitle(title)) {
                rootNode.recycle()
                cancelPendingRetries()
                blockAndGoHome()
                Log.i(TAG, "Blocked content via title: $title")
                return
            }

            // タイトルで検出できない場合、ノードツリーを直接スキャン
            val blockedText = findBlockedTextInNodes(rootNode)
            rootNode.recycle()
            if (blockedText != null) {
                cancelPendingRetries()
                blockAndGoHome()
                Log.i(TAG, "Blocked content via node scan: $blockedText")
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
        //    android:id/title は汎用的でリストアイテムにもマッチするため、
        //    ActionBar/Toolbar 内のノードのみ対象にする
        try {
            val titleNodes = root.findAccessibilityNodeInfosByViewId(
                "android:id/title"
            )
            if (!titleNodes.isNullOrEmpty()) {
                for (titleNode in titleNodes) {
                    val text = titleNode.text?.toString()
                    if (!text.isNullOrBlank() && isHeaderNode(titleNode)) {
                        titleNodes.forEach { it.recycle() }
                        return text
                    }
                }
                titleNodes.forEach { it.recycle() }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Device Admin 画面で Freedom 関連のコンテンツが表示されているかチェックし、
     * 表示されていればブロックする。
     * Freedom の Device Admin 無効化は常時ブロック（許可ウィンドウ内でも）。
     * @return ブロックに成功した場合 true
     */
    private fun tryBlockDeviceAdmin(source: String): Boolean {
        try {
            val rootNode = rootInActiveWindow ?: return false
            val found = scanForFreedomText(rootNode)
            rootNode.recycle()

            if (found) {
                blockAndGoHome(isFreedomProtection = true)
                Log.i(TAG, "Blocked Freedom Device Admin ($source)")
                return true
            }
            Log.d(TAG, "DeviceAdmin screen but no Freedom text ($source)")
        } catch (e: Exception) {
            Log.d(TAG, "Error checking DeviceAdmin ($source): ${e.message}")
        }
        return false
    }

    /**
     * ノードツリーから Freedom 関連テキストを検索する。
     */
    private fun scanForFreedomText(root: AccessibilityNodeInfo): Boolean {
        for (pattern in SettingsDetector.FREEDOM_TEXT_PATTERNS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByText(pattern)
                if (!nodes.isNullOrEmpty()) {
                    nodes.forEach { it.recycle() }
                    return true
                }
            } catch (_: Exception) {}
        }
        // パッケージ名での検索もフォールバックとして行う
        try {
            val nodes = root.findAccessibilityNodeInfosByText(SettingsDetector.DEFAULT_FREEDOM_PACKAGE)
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Device Admin 画面の Freedom テキスト検出をリトライする。
     * 画面描画が遅れてノードツリーがまだ空の場合に対応。
     */
    private fun scheduleDeviceAdminRetries() {
        Log.d(TAG, "Scheduling DeviceAdmin retries")
        for ((index, delayMs) in RETRY_DELAYS_MS.withIndex()) {
            val retryRunnable = Runnable {
                try {
                    val rootNode = rootInActiveWindow
                    if (rootNode == null) {
                        Log.d(TAG, "DeviceAdmin retry #${index + 1}: no root node")
                        return@Runnable
                    }
                    val currentPackage = rootNode.packageName?.toString()
                    if (currentPackage == null || !SettingsDetector.isSettingsPackage(currentPackage)) {
                        rootNode.recycle()
                        Log.d(TAG, "DeviceAdmin retry #${index + 1}: no longer in settings")
                        return@Runnable
                    }
                    rootNode.recycle()
                } catch (e: Exception) {
                    Log.d(TAG, "DeviceAdmin retry #${index + 1}: error: ${e.message}")
                    return@Runnable
                }

                if (tryBlockDeviceAdmin("DeviceAdmin retry #${index + 1}")) {
                    cancelPendingRetries()
                }
            }
            handler.postDelayed(retryRunnable, delayMs)
        }
    }

    private fun blockAndGoHome() {
        blockAndGoHome(isFreedomProtection = false)
    }

    private fun blockAndGoHome(isFreedomProtection: Boolean) {
        performGlobalAction(GLOBAL_ACTION_HOME)

        val now = System.currentTimeMillis()
        if (now - lastBlockedTime > BLOCK_COOLDOWN_MS) {
            val message = if (isFreedomProtection) {
                "🔒 Freedom の保護設定は変更できません"
            } else {
                val checker = conditionChecker
                "🔒 ユーザー補助設定はロック中\n" +
                    "許可: ${"%02d:%02d".format(checker.getStartHour(), checker.getStartMinute())}" +
                    "〜${"%02d:%02d".format(checker.getEndHour(), checker.getEndMinute())}" +
                    if (checker.getRequireCharging()) " (充電中)" else ""
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        lastBlockedTime = now
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        cancelPendingRetries()
        super.onDestroy()
        Log.w(TAG, "AccessibilityService destroyed — watchdog should re-enable")
    }
}
