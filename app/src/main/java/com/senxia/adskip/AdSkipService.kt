package com.senxia.adskip

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 跳广告核心服务（严格模式）。
 *
 * 设计原则：宁可漏跳，绝不误点。
 * 五层防御，任何一层不过都不点击：
 *   1. 系统/警告页面包名黑名单（com.miui.* / com.android.* / packageinstaller 等）
 *   2. 冷启动时间窗：只有 App 刚切换后的 6 秒内才允许点「开屏跳过」
 *   3. 按钮文本拒绝词表：命中「关闭/知道了/允许/取消/安装…」一律不点
 *   4. 强信号全匹配：只认「跳过」「跳过 5s」「Skip Ad」这类短文案，
 *      不再用包含匹配（包含匹配会把 "Xiaomi" 里的 x 都命中）
 *   5. 节点尺寸约束：跳过按钮通常是小按钮，排除整屏宽的大控件
 *
 * 摇一摇广告：仅在同时检测到「摇一摇」文案 + 强信号跳过按钮时才抢点。
 */
class AdSkipService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPackage = ""
    private var splashDeadline = 0L
    private var lastSkipTime = 0L
    private var shakeRound = 0

    companion object {
        private const val TAG = "AdSkip"
        private const val SPLASH_WINDOW_MS = 6_000L       // 冷启动后 6 秒内才可能是开屏广告
        private const val MIN_INTERVAL_MS = 600L          // 两次点击最小间隔
        private const val SHAKE_MAX_ROUNDS = 5
        private const val MAX_NODE_WIDTH_RATIO = 0.50     // 按钮宽度 <= 屏宽 50%
        private const val MAX_NODE_HEIGHT_RATIO = 0.20    // 按钮高度 <= 屏高 20%
        private const val MAX_TREE_DEPTH = 25

        // ===== 第 1 层：绝不能自动点击的系统应用包名 =====
        private val NEVER_CLICK_EXACT = setOf(
            "android", "system",
            "com.android.systemui", "com.android.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.vpndialogs", "com.android.updater", "com.android.vending",
            "com.android.inputmethod.latin",
            "com.lbe.security.miui", "com.miui.permission",
            "com.miui.securitycenter", "com.miui.securitycore", "com.miui.securityadd",
            "com.miui.guardprovider", "com.miui.packageinstaller", "com.miui.global.packageinstaller",
            "com.miui.home", "com.miui.notification", "com.miui.powerkeeper",
            "com.miui.core", "com.miui.system", "com.miui.rom", "com.miui.daemon",
            "com.xiaomi.account", "com.xiaomi.finddevice", "com.miui.findmy",
            "com.xiaomi.misettings", "com.xiaomi.xmsf",
            "com.android.thememanager", "com.xiaomi.market", "com.xiaomi.mipicks",
            "com.android.providers.downloads", "com.android.providers.downloads.ui",
        )
        private val NEVER_CLICK_PREFIXES = listOf(
            "com.miui.", "com.xiaomi.", "com.android.", "com.google.android.",
            "com.lbe.", "miui.systemui.", "com.miui.perm",
        )
        private val NEVER_CLICK_SUBSTRINGS = listOf("packageinstaller", "permissioncontroller")

        // ===== 第 3 层：拒绝词表（系统对话框/警告页按钮）=====
        private val DENY_TEXT = setOf(
            "知道了", "我知道了", "我已知晓", "取消", "允许", "始终允许", "一律允许", "拒绝",
            "确定", "确认", "下一步", "安装", "仍要安装", "继续安装", "暂不", "以后再说", "下次再说",
            "去设置", "设置", "信任", "同意", "接受风险", "我已了解并愿意承担风险",
            "关闭", "进入应用", "不再提示", "不感兴趣", "残忍拒绝", "放弃", "返回", "好的",
            "立即开启", "开启", "立即设置", "跳过引导", "跳过设置",
        )

        // ===== 第 4 层：强信号跳过文案（短文本、全匹配）=====
        private val STRONG_SKIP = listOf(
            Regex("""^跳过\s*(广告)?\s*\d{0,2}\s*[sS秒]?$"""),          // 跳过 / 跳过5 / 跳过广告5秒
            Regex("""^\d{1,2}\s*[sS秒]?\s*(后)?\s*(跳过|跳过广告)$"""), // 5s跳过 / 5秒后跳过
            Regex("""^跳过\s*[>|›»]+$"""),                              // 跳过 > / 跳过 ›
            Regex("""^Skip$""", RegexOption.IGNORE_CASE),
            Regex("""^Skip\s*Ad$""", RegexOption.IGNORE_CASE),
            Regex("""^跳過\s*(廣告)?\s*\d{0,2}\s*[sS秒]?$"""),
            Regex("""^略过$"""),
        )

        // 摇一摇广告提示文案（仅用于识别场景，绝不作为点击依据）
        private val SHAKE_TEXTS = listOf("摇一摇", "摇动手机", "晃动手机", "摇一摇跳转", "摇一摇或点击")

        // viewId：必须「广告 SDK 前缀」+「skip 词」同时命中
        private val AD_ID_MARKERS = listOf(
            "tt_splash", "splash_ad", "ad_splash", "ksad_", "gdt_", "mimo_",
            "bytedance", "kwad", "pangle", "ad_", "splash",
        )
        private val SKIP_ID_WORDS = listOf("skip", "jump", "close_ad", "ad_close", "btn_skip", "tv_skip")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接（严格模式）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // 第 1 层：系统页面直接放行，一个字都不点
        if (isNeverClickPackage(pkg)) {
            Log.d(TAG, "[$pkg] 系统页面，跳过扫描")
            return
        }

        // 第 2 层：记录冷启动时间窗
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != lastPackage) {
            lastPackage = pkg
            splashDeadline = System.currentTimeMillis() + SPLASH_WINDOW_MS
            shakeRound = 0
            Log.d(TAG, "[$pkg] 进入，开屏时间窗开启 ${SPLASH_WINDOW_MS}ms")
        }

        val inSplashWindow = System.currentTimeMillis() <= splashDeadline
        scanAndClick(pkg, inSplashWindow)
    }

    private fun isNeverClickPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p.isBlank()) return true
        return p in NEVER_CLICK_EXACT ||
                NEVER_CLICK_PREFIXES.any { p.startsWith(it) } ||
                NEVER_CLICK_SUBSTRINGS.any { p.contains(it) }
    }

    private fun scanAndClick(pkg: String, inSplashWindow: Boolean) {
        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString() ?: ""
        if (isNeverClickPackage(rootPkg)) return

        // 摇一摇广告：任意时刻可识别，但必须有强信号跳过按钮才点
        if (hasShakeHint(root) && shakeRound < SHAKE_MAX_ROUNDS) {
            shakeRound++
            val btn = findStrictSkipButton(root)
            if (btn != null) {
                Log.i(TAG, "[$pkg] 摇一摇广告，抢点跳过（第 $shakeRound 轮）")
                doClick(btn, pkg)
            } else {
                handler.postDelayed({ scanAndClick(pkg, inSplashWindow) }, 150L)
            }
            return
        }

        // 开屏广告：只在冷启动时间窗内扫描
        if (!inSplashWindow) return
        val btn = findStrictSkipButton(root)
        if (btn != null) doClick(btn, pkg)
    }

    /** 严格查找：遍历节点树，逐层过滤，返回唯一一个可点的跳过按钮 */
    private fun findStrictSkipButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return searchTree(root, 0)
    }

    private fun searchTree(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_TREE_DEPTH) return null

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 第 3 层：命中拒绝词，这个节点及其分支一律不看
        if (isDenied(text) || isDenied(desc)) {
            return null
        }

        // 第 4 层：强信号判定
        val textHit = text.isNotEmpty() && STRONG_SKIP.any { it.matches(text) }
        val descHit = desc.isNotEmpty() && STRONG_SKIP.any { it.matches(desc) }
        val idHit = viewId.isNotEmpty() &&
                AD_ID_MARKERS.any { viewId.contains(it) } &&
                SKIP_ID_WORDS.any { viewId.contains(it) }

        if ((textHit || descHit || idHit) && node.isEnabled) {
            // 第 5 层：尺寸约束
            if (isSmallButton(node)) {
                val clickable = findClickable(node)
                // 最终点击节点也不能是「大控件」，防止向上找到整屏容器
                if (clickable != null && isSmallButton(clickable)) return clickable
            }
        }

        for (i in 0 until node.childCount) {
            val r = searchTree(node.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun isDenied(s: String): Boolean {
        if (s.isEmpty()) return false
        return DENY_TEXT.any { s.contains(it) }
    }

    private fun isSmallButton(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return true   // 拿不到尺寸则不以此否决
        val dm = resources.displayMetrics
        return r.width() <= dm.widthPixels * MAX_NODE_WIDTH_RATIO &&
                r.height() <= dm.heightPixels * MAX_NODE_HEIGHT_RATIO
    }

    /** 节点自身不可点击时，向上找可点击父节点（限 8 层） */
    private fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
        }
        return null
    }

    private fun hasShakeHint(root: AccessibilityNodeInfo): Boolean {
        for (t in SHAKE_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            if (!nodes.isNullOrEmpty()) return true
        }
        return false
    }

    private fun doClick(node: AccessibilityNodeInfo, pkg: String) {
        // 点击前最后一次文本校验，防止 findClickable 向上找到了别的按钮
        val text = node.text?.toString()?.trim() ?: ""
        if (isDenied(text)) {
            Log.d(TAG, "[$pkg] 点击前校验命中拒绝词，放弃")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSkipTime < MIN_INTERVAL_MS) return
        try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastSkipTime = now
                Log.i(TAG, "[$pkg] 已点击跳过")
            }
        } catch (e: Exception) {
            Log.w(TAG, "点击失败: ${e.message}")
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
