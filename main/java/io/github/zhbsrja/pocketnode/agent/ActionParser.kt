package io.github.zhbsrja.pocketnode.agent

import io.github.zhbsrja.pocketnode.util.AppLog

/**
 * 智能体能执行的一个动作。
 *
 * 做成 sealed interface + 各自的 execute()，而不是「一个字符串 + 一个大 when」：
 * 加新动作时编译器会逼你把所有分支补齐，不会漏。
 */
sealed interface AgentAct {

    /** 人类可读的描述，写进操作日志 */
    fun describe(): String

    /** 执行。走 DeviceController 的闸门，不绕过任何检查 */
    fun execute(): Result<Unit>

    data class Tap(val x: Float, val y: Float) : AgentAct {
        override fun describe() = "tap($x, $y)"
        override fun execute() = DeviceController.tap(x, y)
    }

    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : AgentAct {
        override fun describe() = "swipe($x1,$y1 → $x2,$y2)"
        override fun execute() = DeviceController.swipe(x1, y1, x2, y2)
    }

    data class Type(val text: String) : AgentAct {
        override fun describe() = "type(「${text.take(24)}」)"
        override fun execute() = DeviceController.typeText(text)
    }

    data object Back : AgentAct {
        override fun describe() = "back()"
        override fun execute() = DeviceController.back()
    }

    data object Home : AgentAct {
        override fun describe() = "home()"
        override fun execute() = DeviceController.home()
    }

    /** 结束信号。不执行任何设备操作 */
    data class Done(val summary: String) : AgentAct {
        override fun describe() = "done($summary)"
        override fun execute(): Result<Unit> = Result.success(Unit)
    }
}

/**
 * 从模型的自由文本里提取动作。
 *
 * ══════════════════════════════════════════════════════════════
 * ── 设计原则：宽容优先 ─────────────────────────────────────────
 *
 * 手机上跑的是 0.5B–3B 的量化模型，它们**不会严格按格式输出**。
 * 实测常见的偏差：
 *
 *   "ACTION: tap(540, 1200)"        正常
 *   "ACTION: tap(540,1200)"         没空格
 *   "Action: TAP(540, 1200)"        大小写混
 *   "  ACTION: tap(540, 1200)"      前面有空白
 *   "ACTION: tap(540, 1200)（说明）"  尾巴带中文注释
 *   "ACTION: tap(540, 1200)."       尾巴带句号
 *   "ACTION: tap(540, 1200)\n..."   后面还有别的行
 *
 * **严格解析会把这些全判成失败，然后模型永远卡在第一步。**
 * 所以这里做的是"尽量捞出一个能用的动作"，而不是"验证格式是否正确"。
 *
 * 捞不出来就返回 null，交给上层决定（当前是记一步然后重试）。
 * ══════════════════════════════════════════════════════════════
 */
object ActionParser {

    private const val TAG = "ActionParser"

    /** 匹配 "ACTION:" 后面的整行。不限大小写，允许前后有空白 */
    private val ACTION_LINE = Regex(
        """ACTION\s*[:：]\s*(.+)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    private val TAP = Regex("""tap\s*\(\s*(-?\d+(?:\.\d+)?)\s*[,，]\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val SWIPE = Regex(
        """swipe\s*\(\s*(-?\d+(?:\.\d+)?)\s*[,，]\s*(-?\d+(?:\.\d+)?)\s*[,，]\s*(-?\d+(?:\.\d+)?)\s*[,，]\s*(-?\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val TYPE = Regex("""type\s*\(\s*(.*?)\s*\)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BACK = Regex("""back\s*\(""", RegexOption.IGNORE_CASE)
    private val HOME = Regex("""home\s*\(""", RegexOption.IGNORE_CASE)
    private val DONE = Regex("""done\s*\(?\s*(.*?)\s*\)?\s*$""", RegexOption.IGNORE_CASE)

    /**
     * 解析。返回 null 表示没捞到可用动作。
     *
     * 顺序重要：**先试更具体的**。tap 和 type 都带括号，如果先匹配通用的
     * 会互相抢。swipe 有四个参数，必须在 tap（两个参数）之前判断，
     * 否则 "swipe(1,2,3,4)" 会被 tap 匹配到前两个数。
     */
    fun parse(raw: String): AgentAct? {
        // 取最后一行 ACTION —— 模型可能先"思考"几句再给动作，
        // 最后那个才是它真正的结论
        val lines = ACTION_LINE.findAll(raw).map { it.groupValues[1] }.toList()
        if (lines.isEmpty()) {
            AppLog.w(TAG, "输出里没有 ACTION 标记")
            return null
        }

        // 从最后往前找第一个能解析的
        for (candidate in lines.reversed()) {
            val c = candidate.trim()
            AppLog.i(TAG, "尝试解析: ${c.take(80)}")

            // 结束信号优先 —— 它最短，也最可能被别的正则误吞
            if (DONE.containsMatchIn(c) && !c.contains("(")) {
                return AgentAct.Done(c.removePrefix("done").trim().ifBlank { "未说明" })
            }

            SWIPE.find(c)?.let { m ->
                return AgentAct.Swipe(
                    m.groupValues[1].toFloatOrNull() ?: 0f,
                    m.groupValues[2].toFloatOrNull() ?: 0f,
                    m.groupValues[3].toFloatOrNull() ?: 0f,
                    m.groupValues[4].toFloatOrNull() ?: 0f,
                )
            }

            TAP.find(c)?.let { m ->
                val x = m.groupValues[1].toFloatOrNull()
                val y = m.groupValues[2].toFloatOrNull()
                // 负数或全零的坐标是无意义的 —— 模型有时候会输出 tap(0, 0)，
                // 那是在"占位"而不是真要点左上角
                if (x != null && y != null && x > 0 && y > 0) {
                    return AgentAct.Tap(x, y)
                }
            }

            TYPE.find(c)?.let { m ->
                val text = m.groupValues[1].trim().trim('"', '\'', '「', '」')
                if (text.isNotEmpty() && text != "文字") return AgentAct.Type(text)
            }

            if (BACK.containsMatchIn(c)) return AgentAct.Back
            if (HOME.containsMatchIn(c)) return AgentAct.Home

            // 纯 done(说明)
            if (c.startsWith("done", ignoreCase = true)) {
                val summary = c.substringAfter("done", "").trim().trim('(', ')').ifBlank { "未说明" }
                return AgentAct.Done(summary)
            }
        }

        AppLog.w(TAG, "所有候选都解析失败")
        return null
    }
}
