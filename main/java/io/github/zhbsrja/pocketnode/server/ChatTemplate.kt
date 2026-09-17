package io.github.zhbsrja.pocketnode.server

/**
 * 各家的对话模板。
 *
 * ── 为什么必须有这个 ────────────────────────────────────────────
 * MediaPipe 的 generateResponse(prompt) 把 prompt **原样**喂给模型，
 * 不做任何包装。而指令微调过的模型是靠特殊标记来区分「谁说的话」的 ——
 * 直接发一句 "你好"，模型会把它当成一段待续写的文本，而不是一句提问，
 * 输出会变成莫名其妙的续写。
 *
 * 所以中转收到 OpenAI 格式的 messages 之后，必须按模型家族拼成对应的模板。
 *
 * ⚠️ 这些模板是**手工维护**的。新加模型时要确认它的模板，拼错了不会报错，
 *    只会「答得很怪」—— 这种问题极难排查，所以宁可在这里写清楚。
 */
enum class ChatTemplate(
    val displayName: String,
) {
    /** Qwen 系列（含 DeepSeek-R1 蒸馏版，它是基于 Qwen 的） */
    QWEN("Qwen / DeepSeek-R1"),

    /** Microsoft Phi 系列 */
    PHI("Phi"),

    /** Google Gemma 系列 */
    GEMMA("Gemma"),

    /** 不套模板，直接拼接。给不支持对话格式的模型兜底 */
    PLAIN("纯文本（不套模板）"),
    ;

    /**
     * 把对话拼成模型能看懂的 prompt。
     *
     * @param system 系统提示，可为空
     * @param turns  按时间顺序的 (role, content)，role 只会是 user / assistant
     */
    fun build(system: String?, turns: List<Pair<String, String>>): String = when (this) {

        QWEN -> buildString {
            if (!system.isNullOrBlank()) {
                append("<|im_start|>system\n").append(system.trim()).append("<|im_end|>\n")
            }
            turns.forEach { (role, content) ->
                append("<|im_start|>").append(role).append('\n')
                append(content.trim())
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }

        PHI -> buildString {
            if (!system.isNullOrBlank()) {
                append("<|im_start|>system<|im_sep|>").append(system.trim()).append("<|im_end|>")
            }
            turns.forEach { (role, content) ->
                append("<|im_start|>").append(role).append("<|im_sep|>")
                append(content.trim())
                append("<|im_end|>")
            }
            append("<|im_start|>assistant<|im_sep|>")
        }

        GEMMA -> buildString {
            // Gemma 没有独立的 system 角色，官方推荐把系统提示塞进第一轮 user
            var systemUsed = false
            turns.forEach { (role, content) ->
                val speaker = if (role == "assistant") "model" else "user"
                append("<start_of_turn>").append(speaker).append('\n')
                if (!systemUsed && speaker == "user" && !system.isNullOrBlank()) {
                    append(system.trim()).append("\n\n")
                    systemUsed = true
                }
                append(content.trim())
                append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }

        PLAIN -> buildString {
            if (!system.isNullOrBlank()) append(system.trim()).append("\n\n")
            turns.forEach { (role, content) ->
                append(if (role == "assistant") "Assistant: " else "User: ")
                append(content.trim())
                append('\n')
            }
            append("Assistant: ")
        }
    }
}
