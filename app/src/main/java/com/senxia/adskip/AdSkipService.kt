package com.senxia.adskip

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 跳广告的核心服务，参考 GKD 的思路：
 * 监听窗口变化 → 扫描节点树 → 多策略匹配「跳过/关闭」控件 → 点击。
 *
 * 策略优先级：
 *  1. 精确文本匹配（跳过/关闭/Skip/跳过广告/关闭广告 等）
 *  2. viewId 关键字匹配（含 skip/close/ad 的 id）
 *  3. content-desc 匹配
 *  4. 摇一摇广告：出现「摇一摇」文案时，高频抢点跳过按钮
 */
class AdSkipService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPackage = ""
    private var lastSkipTime = 0L
    private var shakeRound = 0

    companion object {
        private const val TAG = "AdSkip"
        private const val MIN_INTERVAL_MS = 350L      // 同包点击最小间隔，防止疯狂连点
        private const val SHAKE_MAX_ROUNDS = 8        // 摇一摇抢点最大轮次

        // 精确/常见的跳过关键词（越靠前优先级越高）
        private val SKIP_TEXTS = listOf(
            "跳过", "跳过广告", "点击跳过", "跳过 >", "跳过广告", "关闭广告",
            "关闭", " Skip", "Skip", "SKIP", "skip", "跳过 ", "知道了", "x"
        )
        // 摇一摇提示关键词——出现这些说明是摇一摇广告页
        private val SHAKE_TEXTS = listOf("摇一摇", "摇动", "晃动", "shake", "摇一摇跳转")
        // viewId 关键字（小写）
        private val SKIP_ID_KEYWORDS = listOf("skip", "close", "jump", "ad_close", "btn_skip", "tv_skip")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 事件类型、flag、canRetrieveWindowContent 已在 res/xml/accessibility_config.xml 中声明
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // 不处理自己，避免自我循环
        if (pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 切换到新窗口/Activity —— 开屏广告主触发点
                if (pkg != lastPackage) {
                    lastPackage = pkg
                    shakeRound = 0
                }
                scanAndClick(pkg, isShakeScenario = false)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 窗口内容变化 —— 倒计时结束、弹窗渐显、摇一摇文案出现
                scanAndClick(pkg, isShakeScenario = false)
            }
        }
    }

    private fun scanAndClick(pkg: String, isShakeScenario: Boolean) {
        val root = rootInActiveWindow ?: return
        if (root.packageName != pkg) {
            // 有时 root 与事件包不一致，仍尝试扫描当前活跃窗口
        }
        val now = System.currentTimeMillis()
        if (now - lastSkipTime < MIN_INTERVAL_MS && !isShakeScenario) return

        // 先看是否有摇一摇文案
        val hasShake = findByTexts(root, SHAKE_TEXTS)
        if (hasShake != null && !isShakeScenario && shakeRound < SHAKE_MAX_ROUNDS) {
            shakeRound++
            Log.i(TAG, "[$pkg] 检测到摇一摇广告，第 $shakeRound 轮抢点")
            // 立即抢点跳过；若广告页也有跳过按钮，抢在用户摇之前点掉
            val skipNode = findSkipNode(root)
            if (skipNode != null && clickNode(skipNode, pkg)) return
            // 没有跳过按钮：延迟再扫几轮，防止按钮延迟出现
            handler.postDelayed({
                scanAndClick(pkg, isShakeScenario = true)
            }, 120L)
            return
        }

        // 常规跳过/关闭
        val skipNode = findSkipNode(root)
        if (skipNode != null) {
            clickNode(skipNode, pkg)
        }
    }

    /** 多策略查找可点击的跳过节点 */
    private fun findSkipNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 策略 1：文本匹配
        for (text in SKIP_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes != null) {
                for (node in nodes) {
                    val clickable = findClickable(node)
                    if (clickable != null) return clickable
                }
            }
        }

        // 策略 2：遍历整棵树，用 viewId / contentDescription 关键字匹配
        val found = searchTree(root, 0)
        return found
    }

    private fun searchTree(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val idHit = SKIP_ID_KEYWORDS.any { viewId.contains(it) }
        val descHit = SKIP_TEXTS.any { kw -> desc.contains(kw.lowercase().trim()) }

        if ((idHit || descHit) && node.isEnabled) {
            val clickable = findClickable(node)
            if (clickable != null) return clickable
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = searchTree(child, depth + 1)
            if (r != null) return r
        }
        return null
    }

    /** 节点自身不可点击时，向上找可点击父节点 */
    private fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
        }
        return null
    }

    /** 按文本查找（返回首个任意匹配，用于摇一摇探测） */
    private fun findByTexts(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            if (!nodes.isNullOrEmpty()) return nodes[0]
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo, pkg: String): Boolean {
        return try {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) {
                lastSkipTime = System.currentTimeMillis()
                Log.i(TAG, "[$pkg] 已点击跳过")
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "点击失败: ${e.message}")
            false
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "无障碍服务已销毁")
    }
}
