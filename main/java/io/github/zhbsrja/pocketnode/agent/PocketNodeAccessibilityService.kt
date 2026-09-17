package io.github.zhbsrja.pocketnode.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.asStateFlow
import io.github.zhbsrja.pocketnode.util.AppLog

/**
 * 无障碍服务。**这个类是整个"智能体控制设备"功能的手**。
 *
 * ── 为什么必须是无障碍服务 ──────────────────────────────────────
 * 想让一个普通 App 去点别的 App 的界面，Android 里只有这一条路。
 * 别的方式（adb shell input、root、注入）要么需要电脑，要么需要
 * 解锁 Bootloader。无障碍服务是唯一官方支持、无需 root 的方案，
 * 代价是用户必须到系统设置里手动授权，而且系统会一直显示提示。
 *
 * ── 职责边界 ────────────────────────────────────────────────────
 * 这类只负责「连上系统、提供手势能力」，
 * **所有权限判断都在 DeviceController 里**，不在这里。
 * 这么分是因为：服务连不连得上由系统决定，而"允不允许动手"
 * 由用户开关决定，两件事独立。混在一起会导致状态难追踪。
 */
class PocketNodeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PocketNodeA11y"

        /**
         * 当前连接的服务实例。null 表示用户没授权，或者被系统回收了。
         *
         * 用 @Volatile 是因为它会被 Binder 线程写、被任意线程读，
         * 不加的话别的线程可能读到旧值，表现为"明明授权了却提示没授权"。
         */
        @Volatile
        var instance: PocketNodeAccessibilityService? = null
            private set

        /**
         * 连接状态做成 StateFlow，而不是让调用方去读 instance 变量。
         *
         * 为什么必须这样：服务绑定是**异步**的 —— 进程重启后系统可能
         * 要几百毫秒才回调 onServiceConnected。用「读一次快照」的方式
         * 判断，就会在那一刻读到 false，而且之后连上了界面也不会更新，
         * 表现是"服务明明连上了，界面还显示未授权"。
         *
         * StateFlow 让界面订阅它，连上的瞬间自动重组。
         */
        private val _connected = kotlinx.coroutines.flow.MutableStateFlow(false)
        val connected: kotlinx.coroutines.flow.StateFlow<Boolean> = _connected.asStateFlow()

        fun isConnected(): Boolean = instance != null

        private fun setConnected(value: Boolean) {
            _connected.value = value
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setConnected(true)
        AppLog.i(TAG, "无障碍服务已连接 —— 手势能力此时才真正可用")
    }

    /**
     * 事件回调。
     *
     * 我们**刻意不在这里做任何事**。
     *
     * 常见做法是监听窗口变化来维护一个"当前界面"的状态，但那会让
     * 服务和业务逻辑耦合，而且高频事件（一次滑动几十个）在模拟器上
     * 会明显拖慢系统。
     *
     * 需要界面信息时按需读（[rootInActiveWindow]），只在真正要用的
     * 那一刻取一次快照 —— 简单，也不会成为性能瓶颈。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 故意留空
    }

    override fun onInterrupt() {
        AppLog.w(TAG, "服务被系统中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AppLog.w(TAG, "无障碍服务已断开")
        instance = null
        setConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AppLog.i(TAG, "无障碍服务销毁")
        instance = null
        setConnected(false)
        super.onDestroy()
    }

    // ══════════════════════ 能力实现 ══════════════════════
    // 下面这些函数**不做权限判断**，调用方必须先过 DeviceController 的闸门。

    /** 在 (x, y) 点一下 */
    fun performTap(x: Float, y: Float, durationMs: Long = 60): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0L, durationMs)
    }

    /** 长按 */
    fun performLongPress(x: Float, y: Float, durationMs: Long = 700): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0L, durationMs)
    }

    /** 滑动 */
    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, 0L, durationMs)
    }

    private fun dispatch(path: Path, startMs: Long, durationMs: Long): Boolean {
        return try {
            val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            // 坐标在屏幕外、路径为空、服务刚被撤销 —— 都可能走到这里
            AppLog.e(TAG, "dispatchGesture 失败", t)
            false
        }
    }

    /** 全局返回 */
    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** 回桌面 */
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 最近任务 */
    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** 展开通知栏 */
    fun performOpenNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * 往当前聚焦的输入框里写文字。
     *
     * 用 ACTION_SET_TEXT 而不是模拟按键：模拟按键要处理中文输入法的
     * 候选词、联想、编码，几乎不可能可靠。SET_TEXT 是直接把文本塞进控件，
     * 对绝大多数输入框都有效，而且不受输入法状态影响。
     *
     * ⚠️ 对某些自定义实现的输入框（尤其是游戏、Flutter/RN 写的界面）
     * 可能无效 —— 那时只能退化成模拟按键。
     */
    fun performTypeText(text: String): Boolean {
        val root = rootInActiveWindow ?: run {
            AppLog.w(TAG, "performTypeText: 拿不到窗口根节点")
            return false
        }
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
            AppLog.w(TAG, "performTypeText: 没有聚焦的输入框")
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 当前前台应用的包名 */
    fun currentPackageName(): String? =
        rootInActiveWindow?.packageName?.toString()

    /**
     * 把当前界面拍成一份文字快照。
     *
     * 给智能体看的。只保留**可交互或带文字**的节点 ——
     * 完整控件树动辄上千个节点（各种容器、分割线），
     * 塞给模型除了浪费上下文没有任何意义。
     *
     * @param maxNodes 上限，防止超大列表页把 prompt 撑爆
     */
    fun describeScreen(maxNodes: Int = 80): String {
        val root = rootInActiveWindow ?: return "(拿不到当前界面)"
        val sb = StringBuilder()
        var count = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= maxNodes) return

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = text.ifEmpty { desc }
            val clickable = node.isClickable
            val editable = node.isEditable
            val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""

            // 无文字、不可点、不可编辑的纯容器直接跳过，但要看它的子节点
            if (label.isNotEmpty() || clickable || editable) {
                val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                val cx = rect.centerX()
                val cy = rect.centerY()
                sb.append("  ".repeat(depth))
                if (label.isNotEmpty()) sb.append('"').append(label.take(60)).append('"')
                if (editable) sb.append("[可输入]")
                if (clickable) sb.append("[可点]")
                if (id.isNotEmpty()) sb.append(" #").append(id)
                sb.append(" @").append(cx).append(',').append(cy)
                sb.append('\n')
                count++
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i), if (label.isEmpty() && !clickable) depth else depth + 1)
            }
        }

        walk(root, 0)
        return if (count == 0) "(界面上没有可识别的控件)" else sb.toString()
    }
}
