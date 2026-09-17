package io.github.zhbsrja.pocketnode.agent

import io.github.zhbsrja.pocketnode.inference.LlmEngine
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 智能体的一步 */
data class AgentStep(
    val index: Int,
    val screenPreview: String,
    val modelOutput: String,
    val action: String,
    val ok: Boolean,
    val note: String = "",
)

/** 智能体运行状态 */
sealed interface AgentState {
    data object Idle : AgentState
    data class Running(val task: String, val step: Int) : AgentState
    data class Finished(val task: String, val steps: Int, val reason: String) : AgentState
    data class Failed(val task: String, val message: String) : AgentState
}

/**
 * 智能体循环：读屏 → 问模型 → 执行动作 → 再读屏。
 *
 * ══════════════════════════════════════════════════════════════
 * ── 为什么用"固定文本协议"而不是 function calling ──────────────
 *
 * 主流做法是让模型输出 JSON 工具调用。但手机上跑的是 0.5B–3B 的量化模型，
 * **它们输出合法 JSON 的可靠性极低** —— 少个引号、多个逗号就整个解析失败，
 * 而且失败之后模型不知道，会一直重复同样的错误。
 *
 * 所以这里用**极简的行协议**：
 *
 *     ACTION: tap(540, 1200)
 *     ACTION: type(hello world)
 *     ACTION: back()
 *     ACTION: done(已完成)
 *
 * 用正则容错地提取。"ACTION:" 之后的内容哪怕模型多写了引号、句号、
 * 或者换了大小写，都能认出来。**低能力模型上，宽容的解析器比严格的
 * 协议重要得多。**
 *
 * ── 为什么要有步数上限 ──────────────────────────────────────────
 * 小模型很容易陷入循环（反复点同一个按钮）。没有上限的话它会一直点下去，
 * 直到把电池耗光或点到不该点的地方。MAX_STEPS 是硬性保险。
 *
 * ── 已知局限 ────────────────────────────────────────────────────
 * · 没有视觉，只能靠控件树的文字。图标按钮（只有 contentDescription 的）
 *   识别率低
 * · 每轮都要把整个屏幕塞进 prompt，0.5B 的上下文放不下几轮
 * · 模型不会"记住"上一步做了什么 —— 每轮都是全新的
 * ══════════════════════════════════════════════════════════════
 */
object AgentRunner {

    private const val TAG = "AgentRunner"

    /** 步数上限。防止模型陷入循环后无限点下去 */
    const val MAX_STEPS = 15

    /**
     * 单步生成超时。
     *
     * 手机推理慢，但 60 秒还出不来一个字说明是卡住了，
     * 让它继续等只会让用户以为程序死了。
     */
    private const val STEP_TIMEOUT_MS = 60_000L

    /** 执行动作后的等待时间。界面切换需要时间，立刻读屏会读到旧界面 */
    private const val SETTLE_MS = 800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _steps = MutableStateFlow<List<AgentStep>>(emptyList())
    val steps: StateFlow<List<AgentStep>> = _steps.asStateFlow()

    private var appContext: android.content.Context? = null

    fun init(ctx: android.content.Context) {
        appContext = ctx.applicationContext
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun clearSteps() {
        _steps.value = emptyList()
    }

    fun stop(reason: String = "用户手动停止") {
        job?.cancel()
        job = null
        val cur = _state.value
        if (cur is AgentState.Running) {
            _state.value = AgentState.Finished(cur.task, _steps.value.size, reason)
        }
        AppLog.i(TAG, "已停止：$reason")
    }

    /**
     * 开始执行一个任务。
     *
     * @param task 自然语言描述，比如"打开设置把亮度调到最低"
     */
    fun run(task: String) {
        if (isRunning()) {
            AppLog.w(TAG, "已有任务在跑，忽略")
            return
        }
        if (!DeviceController.isReady()) {
            val why = DeviceController.blockerReason() ?: "设备控制不可用"
            AppLog.w(TAG, "无法启动：$why")
            _state.value = AgentState.Failed(task, why)
            return
        }
        if (!LlmEngine.isReady()) {
            _state.value = AgentState.Failed(task, "模型尚未加载")
            return
        }

        _steps.value = emptyList()
        _state.value = AgentState.Running(task, 0)
        AppLog.i(TAG, "════ 开始任务：$task ════")

        // 先把保活服务拉起来，再开始循环。
        //
        // 顺序不能反：智能体第一步很可能就是 home() 或跳转到别的 App，
        // 那一刻本进程立刻进后台。如果服务还没起，循环当场被冻住。
        appContext?.let { ctx ->
            runCatching { AgentService.start(ctx) }
                .onFailure { AppLog.e(TAG, "启动保活服务失败，任务可能在后台被冻结", it) }
        }

        job = scope.launch {
            try {
                loop(task)
            } catch (c: kotlinx.coroutines.CancellationException) {
                // 用户点停止，正常路径
                throw c
            } catch (t: Throwable) {
                AppLog.e(TAG, "任务崩了", t)
                _state.value = AgentState.Failed(task, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private suspend fun loop(task: String) {
        var step = 0

        while (step < MAX_STEPS) {
            step++
            _state.value = AgentState.Running(task, step)

            // ① 读屏
            val screen = DeviceController.readScreen(remote = false).getOrElse { e ->
                _state.value = AgentState.Failed(task, "读屏失败 ${e.message}")
                return
            }
            val pkg = DeviceController.currentPackage() ?: "?"
            AppLog.i(TAG, "第 $step 步 · 当前 $pkg · 屏幕 ${screen.length} 字符")

            // ② 问模型
            val prompt = buildPrompt(task, pkg, screen, step)
            val raw = generateOnce(prompt)
            if (raw == null) {
                record(step, screen, "", "-", false, "生成超时或失败")
                _state.value = AgentState.Failed(task, "模型没有响应")
                return
            }
            AppLog.i(TAG, "模型输出: ${raw.take(160).replace('\n', ' ')}")

            // ③ 解析动作
            val act = ActionParser.parse(raw)
            if (act == null) {
                // 解析不出来不算致命 —— 记一步，把屏幕重新喂给它再试。
                // 模型偶尔会先"思考"一段再输出动作，下一轮通常就正常了。
                record(step, screen, raw, "(无法解析)", false, "输出里没有可识别的 ACTION")
                delay(SETTLE_MS)
                continue
            }

            // ④ 结束信号
            if (act is AgentAct.Done) {
                record(step, screen, raw, "done", true, act.summary)
                _state.value = AgentState.Finished(task, step, "模型宣告完成：${act.summary}")
                AppLog.i(TAG, "任务完成，共 $step 步")
                return
            }

            // ⑤ 执行
            val result = act.execute()
            val ok = result.isSuccess
            record(step, screen, raw, act.describe(), ok, result.exceptionOrNull()?.message ?: "")
            if (!ok) {
                AppLog.w(TAG, "动作执行失败: ${result.exceptionOrNull()?.message}")
            }

            // ⑥ 等界面稳定。立刻读屏会读到切换前的旧界面，
            //    模型基于旧界面又做一个错误决策，一步错步步错。
            delay(SETTLE_MS)
        }

        _state.value = AgentState.Finished(task, MAX_STEPS, "达到步数上限 $MAX_STEPS")
        AppLog.w(TAG, "达到步数上限，停止")
    }

    /** 同步等一次生成。用 generate 而不是 streaming —— 这里只需要最终结果 */
    private suspend fun generateOnce(prompt: String): String? =
        withTimeoutOrNull(STEP_TIMEOUT_MS) {
            kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                LlmEngine.generate(
                    prompt = prompt,
                    onPartial = { },
                    onDone = { if (cont.isActive) cont.resumeWith(Result.success(it)) },
                    onError = { if (cont.isActive) cont.resumeWith(Result.success(null)) },
                )
            }
        }

    private fun record(index: Int, screen: String, out: String, action: String, ok: Boolean, note: String) {
        _steps.update {
            (it + AgentStep(index, screen.take(200), out.take(300), action, ok, note)).take(50)
        }
    }

    /**
     * 拼 prompt。
     *
     * 要点：
     *   1. **指令必须极短**。"你是一个手机操作助手"这种铺陈对 0.5B 是噪音，
     *      它只会稀释真正重要的信息
     *   2. **把动作格式放在最前面**。模型对开头的内容注意力更高
     *   3. **只给最近几步的操作历史**。全塞进去会挤爆小模型的上下文
     */
    private fun buildPrompt(task: String, pkg: String, screen: String, step: Int): String {
        val history = _steps.value.takeLast(3).joinToString("\n") {
            "  第${it.index}步 ${it.action} -> ${if (it.ok) "成功" else "失败"}"
        }

        return buildString {
            appendLine("可用动作（每行一个 只能选一个）：")
            appendLine("ACTION: tap(x, y)        点击坐标")
            appendLine("ACTION: type(文字)        输入文字")
            appendLine("ACTION: swipe(x1,y1,x2,y2)  滑动")
            appendLine("ACTION: back()           返回")
            appendLine("ACTION: home()           回桌面")
            appendLine("ACTION: done(说明)         任务完成")
            appendLine()
            appendLine("任务：$task")
            appendLine("当前应用：$pkg   第 $step 步")
            if (history.isNotEmpty()) {
                appendLine("最近操作：")
                appendLine(history)
            }
            appendLine()
            appendLine("屏幕内容：")
            // 截断：小模型的上下文放不下整屏控件树
            appendLine(screen.take(2500))
            appendLine()
            append("下一步动作：ACTION: ")
        }
    }
}
