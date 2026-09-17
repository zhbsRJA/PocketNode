package io.github.zhbsrja.pocketnode.agent

import android.content.Context
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** 智能体执行过的一次动作，用于审计 */
data class AgentAction(
    val time: Long,
    val kind: String,
    val detail: String,
    val ok: Boolean,
)

/**
 * 设备控制闸门。
 *
 * ══════════════════════════════════════════════════════════════
 * 这是整个项目**风险最高的一个文件**，所以设计上遵循三条规则：
 *
 * 1. **默认关闭，且每次启动重新确认状态**
 *    `enabled` 存在 SharedPreferences 里，但默认为 false。
 *    装了 App、给了无障碍权限，什么都不做 —— 必须用户主动打开开关。
 *
 * 2. **所有动作都过同一个闸门**
 *    没有一个方法能绕过 [guard]。想加新能力，只能走这条路径，
 *    不会出现"某个入口忘了检查权限"的情况。
 *
 * 3. **每一次动作都留痕**
 *    写日志 + 进内存环形缓冲。用户能回头看智能体到底做了什么 ——
 *    "它刚才删了什么？"这种问题必须有答案。
 * ══════════════════════════════════════════════════════════════
 *
 * ── 关于中转接口 ────────────────────────────────────────────────
 * **本版本没有把设备控制暴露到 HTTP 接口上。**
 *
 * 原因：中转是明文局域网接口，key 只是防误触。如果它同时能操作设备，
 * 那么同一 WiFi 下知道 key 的任何人都可以点击「确认支付」「发送」
 * 「删除」—— 这已经不是"不方便"，而是直接把手机交出去了。
 *
 * 要开放的话必须是**另一个独立开关**，默认关闭，并有醒目的风险提示。
 * 在那之前，远程调用一律拒绝。
 */
object DeviceController {

    private const val TAG = "DeviceController"
    private const val PREFS = "pocketnode_agent"
    private const val KEY_ENABLED = "agent_control_enabled"
    private const val KEY_ALLOW_REMOTE = "allow_remote_control"

    /**
     * 本地动作的间隔下限。
     *
     * 不是为了防攻击，是为了防"跑飞"：模型一旦陷入循环，能在几秒内
     * 点几百次屏幕，把界面点得乱七八糟甚至触发误操作。
     */
    private const val MIN_ACTION_INTERVAL_MS = 120L

    /**
     * 远程动作的间隔下限。比本地严 5 倍。
     *
     * 本地动作是用户自己在看着，慢一点只是手感问题；
     * 远程动作来自网络另一头，**用户看不到**，必须强制慢下来，
     * 让他有时间发现不对劲并关掉开关。
     */
    private const val MIN_REMOTE_INTERVAL_MS = 600L

    /**
     * 远程控制空闲多久自动上锁。
     *
     * 用户很可能"开完忘了关"。一个忘了关的远程控制接口放在公网上
     * 等于把门开着，自动上锁是最后的兜底。
     */
    private const val REMOTE_IDLE_TIMEOUT_MS = 10 * 60 * 1000L

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 是否允许持有 API Key 的人远程控制。**默认关闭** */
    private val _allowRemote = MutableStateFlow(false)
    val allowRemote: StateFlow<Boolean> = _allowRemote.asStateFlow()

    /** 远程动作计数，界面上显示"别人控制了你手机多少次" */
    private val _remoteActionCount = MutableStateFlow(0L)
    val remoteActionCount: StateFlow<Long> = _remoteActionCount.asStateFlow()

    private val _lastRemoteAt = MutableStateFlow(0L)
    val lastRemoteAt: StateFlow<Long> = _lastRemoteAt.asStateFlow()

    private val _actions = MutableStateFlow<List<AgentAction>>(emptyList())
    val actions: StateFlow<List<AgentAction>> = _actions.asStateFlow()

    /** 连续拒绝计数。用来在界面上提示"开关没开"或"没授权" */
    private val _lastDenyReason = MutableStateFlow<String?>(null)
    val lastDenyReason: StateFlow<String?> = _lastDenyReason.asStateFlow()

    private var context: Context? = null
    private val lastActionAt = AtomicLong(0L)

    fun init(ctx: Context) {
        context = ctx.applicationContext
        // 默认 false。只有明确存过 true 才认为开启
        _enabled.value = prefs()?.getBoolean(KEY_ENABLED, false) ?: false
        // 远程控制**每次启动都强制关闭**，不读持久化的值。
        //
        // 这是刻意的：授权"让公网上的人控制我手机"不应该跨越进程重启还生效。
        // 用户每次用之前都要重新确认一次。麻烦，但这是唯一合理的默认。
        _allowRemote.value = false
        prefs()?.edit()?.putBoolean(KEY_ALLOW_REMOTE, false)?.apply()
        AppLog.w(
            TAG,
            "初始化：本地控制=${if (_enabled.value) "开" else "关"}　远程控制=强制关闭（每次启动重置）",
        )
    }

    /**
     * 用户拨动开关。
     *
     * 这里**不做任何"帮用户打开无障碍"的跳转逻辑** —— 那是 UI 的事。
     * 开关只管"允不允许"，服务连没连上是另一回事。
     * 分开的好处是：用户可以先开开关再去授权，也可以先授权再开开关，
     * 两种顺序都不会出问题。
     */
    fun setEnabled(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        _enabled.value = value
        AppLog.w(
            TAG,
            if (value) "⚠️ 用户开启了「允许智能体控制设备」"
            else "用户关闭了「允许智能体控制设备」",
        )
    }

    /**
     * 开关「允许 API Key 拥有者控制此设备」。
     *
     * ⚠️ 打开后果：任何拿到 API Key 的人（如果端口暴露在公网，就是全世界）
     * 都能读取你手机屏幕内容、点击任何按钮、输入任意文字。
     * 包括读你的短信验证码、打开银行 App、点确认转账。
     *
     * 代码层面能做到的防线只有三条，且都不足以对抗一个泄露的 key：
     *   1. 动作间隔 ≥600ms（看得见、来得及关）
     *   2. 10 分钟无操作自动关闭
     *   3. 每次动作留日志
     *
     * **真正的安全靠的是网络层：用 TLS 隧道（Tailscale / Cloudflare Tunnel），
     * 不要在路由器上做端口转发直连公网。** 明文 HTTP 的 key 在链路上是裸奔的。
     */
    fun setAllowRemote(value: Boolean) {
        _allowRemote.value = value
        prefs()?.edit()?.putBoolean(KEY_ALLOW_REMOTE, value)?.apply()
        if (value) {
            _lastRemoteAt.value = System.currentTimeMillis()
            AppLog.w(TAG, "⚠️⚠️ 用户开启了「允许 API Key 拥有者控制此设备」—— 远程控制已生效")
        } else {
            AppLog.w(TAG, "用户关闭了远程控制")
        }
    }

    /** 网络层调用前先问一句：现在允许远程动手吗 */
    fun remoteAllowed(): Boolean = _enabled.value && _allowRemote.value && isServiceConnected()

    /** 界面上显示"为什么远程不能用" */
    fun remoteBlockerReason(): String? = when {
        !_enabled.value -> "「允许智能体控制设备」未打开"
        !_allowRemote.value -> "「允许 API Key 拥有者控制此设备」未打开"
        !isServiceConnected() -> "无障碍服务未连接"
        else -> null
    }

    /** 当前前台应用的包名。调用方必须自己先过闸门 —— 这是个纯读取工具 */
    fun currentPackage(): String? =
        PocketNodeAccessibilityService.instance?.currentPackageName()

    /**
     * 无障碍服务是否已授权并连接。**响应式版本** —— 界面订阅它，
     * 服务异步绑定完成的瞬间会自动刷新，不用轮询或等生命周期回调。
     */
    val serviceConnected: kotlinx.coroutines.flow.StateFlow<Boolean> =
        PocketNodeAccessibilityService.connected

    /** 无障碍服务是否已授权并连接（一次性快照，给非界面逻辑用） */
    fun isServiceConnected(): Boolean = PocketNodeAccessibilityService.isConnected()

    /** 真正可以动手了（开关开 + 服务在） */
    fun isReady(): Boolean = _enabled.value && isServiceConnected()

    /** 界面用：当前为什么不满足条件 */
    fun blockerReason(): String? = when {
        !_enabled.value -> "开关未打开"
        !isServiceConnected() -> "无障碍服务未授权"
        else -> null
    }

    fun clearActions() {
        _actions.value = emptyList()
    }

    // ══════════════════════ 闸门 ══════════════════════

    /**
     * 所有动作的唯一入口检查。
     *
     * 返回 null 表示放行，否则是要展示给用户的拒绝原因。
     *
     * 注意这里**只做检查，不做事**。把权限判断和动作执行分开的好处是：
     * 加新能力时不可能"忘了检查"—— 因为执行动作的唯一途径就是
     * 先拿到 [guard] 的放行。
     */
    private fun guard(kind: String, detail: String, remote: Boolean = false): String? {
        if (!_enabled.value) {
            _lastDenyReason.value = "「允许智能体控制设备」开关未打开"
            AppLog.w(TAG, "拒绝 [$kind] $detail —— 开关未开")
            return _lastDenyReason.value
        }

        // ══ 远程调用多两道闸 ══════════════════════════════════════
        if (remote) {
            if (!_allowRemote.value) {
                _lastDenyReason.value = "「允许 API Key 拥有者控制此设备」未打开"
                AppLog.w(TAG, "拒绝远程 [$kind] $detail —— 远程开关未开")
                return _lastDenyReason.value
            }
            // 空闲超时自动上锁。用户开完忘了关是最大的风险来源
            val idle = System.currentTimeMillis() - _lastRemoteAt.value
            if (_lastRemoteAt.value > 0 && idle > REMOTE_IDLE_TIMEOUT_MS) {
                AppLog.w(TAG, "远程控制空闲 ${idle / 1000}s 超时，自动上锁")
                setAllowRemote(false)
                _lastDenyReason.value = "远程控制空闲超时，已自动关闭"
                return _lastDenyReason.value
            }
        }

        if (!PocketNodeAccessibilityService.isConnected()) {
            _lastDenyReason.value = "无障碍服务未授权或已断开"
            AppLog.w(TAG, "拒绝 [$kind] $detail —— 无障碍服务不可用")
            return _lastDenyReason.value
        }

        // 节流。远程的阈值严得多 —— 见 MIN_REMOTE_INTERVAL_MS 的说明
        val threshold = if (remote) MIN_REMOTE_INTERVAL_MS else MIN_ACTION_INTERVAL_MS
        val now = System.currentTimeMillis()
        val prev = lastActionAt.get()
        if (now - prev < threshold) {
            val reason = "操作过快（间隔需 ≥ ${threshold}ms），已拦截"
            _lastDenyReason.value = reason
            AppLog.w(TAG, "拒绝 [$kind] —— $reason")
            return reason
        }
        lastActionAt.set(now)

        if (remote) {
            _lastRemoteAt.value = now
            _remoteActionCount.update { it + 1 }
        }
        _lastDenyReason.value = null
        return null
    }

    private fun record(kind: String, detail: String, ok: Boolean) {
        _actions.update { (listOf(AgentAction(System.currentTimeMillis(), kind, detail, ok)) + it).take(100) }
        AppLog.i(TAG, "[$kind] $detail → ${if (ok) "成功" else "失败"}")
    }

    // ══════════════════════ 对外能力 ══════════════════════

    fun tap(x: Float, y: Float, remote: Boolean = false): Result<Unit> {
        val deny = guard("tap", "($x, $y)", remote)
        if (deny != null) return Result.failure(SecurityException(deny))
        val ok = PocketNodeAccessibilityService.instance?.performTap(x, y) ?: false
        record("tap", "($x, $y)", ok)
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("点击未生效"))
    }

    fun longPress(x: Float, y: Float, remote: Boolean = false): Result<Unit> {
        val deny = guard("longPress", "($x, $y)", remote)
        if (deny != null) return Result.failure(SecurityException(deny))
        val ok = PocketNodeAccessibilityService.instance?.performLongPress(x, y) ?: false
        record("longPress", "($x, $y)", ok)
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("长按未生效"))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300, remote: Boolean = false): Result<Unit> {
        val deny = guard("swipe", "($x1,$y1)→($x2,$y2)", remote)
        if (deny != null) return Result.failure(SecurityException(deny))
        val ok = PocketNodeAccessibilityService.instance
            ?.performSwipe(x1, y1, x2, y2, durationMs) ?: false
        record("swipe", "($x1,$y1)→($x2,$y2) ${durationMs}ms", ok)
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("滑动未生效"))
    }

    /**
     * 输入文字。
     *
     * 长度上限 2000 字：防止模型输出一长串东西把输入框塞爆，
     * 或者往聊天框里粘贴一堆无意义的内容。
     */
    fun typeText(text: String, remote: Boolean = false): Result<Unit> {
        val clipped = text.take(2000)
        val deny = guard("typeText", "长度 ${clipped.length}", remote)
        if (deny != null) return Result.failure(SecurityException(deny))
        val ok = PocketNodeAccessibilityService.instance?.performTypeText(clipped) ?: false
        record("typeText", "「${clipped.take(40)}${if (clipped.length > 40) "…" else ""}」", ok)
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("输入未生效，可能没有聚焦的输入框"))
    }

    fun back(remote: Boolean = false): Result<Unit> {
        val deny = guard("back", "", remote)
        if (deny != null) return Result.failure(SecurityException(deny))
        val ok = PocketNodeAccessibilityService.instance?.performBack() ?: false
        record("back", "", ok)
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("返回未生效"))
    }

    fun home(remote: Boolean = false): Result<Unit> {
        val deny = guard("home", "", remote)
        if (deny != null) return Result.failure(SecurityException(deny))
        val ok = PocketNodeAccessibilityService.instance?.performHome() ?: false
        record("home", "", ok)
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("回桌面未生效"))
    }

    /** 读当前界面。也要过闸门 —— 屏幕内容本身是隐私 */
    fun readScreen(remote: Boolean = false): Result<String> {
        val deny = guard("readScreen", "", remote)
        if (deny != null) return Result.failure(SecurityException(deny))
        val svc = PocketNodeAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("服务不可用"))
        val text = svc.describeScreen()
        record("readScreen", "${svc.currentPackageName() ?: "?"} ${text.length} 字符", true)
        return Result.success(text)
    }

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
