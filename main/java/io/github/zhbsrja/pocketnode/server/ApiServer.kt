package io.github.zhbsrja.pocketnode.server

import fi.iki.elonen.NanoHTTPD
import io.github.zhbsrja.pocketnode.agent.DeviceController
import io.github.zhbsrja.pocketnode.data.ModelCatalog
import io.github.zhbsrja.pocketnode.data.ModelInfo
import io.github.zhbsrja.pocketnode.inference.EngineState
import io.github.zhbsrja.pocketnode.inference.LlmEngine
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 一条访问记录，界面上用来显示"谁在用" */
data class RequestLog(
    val time: Long,
    val method: String,
    val path: String,
    val remoteIp: String,
    val status: Int,
    val durationMs: Long,
    val note: String = "",
)

/** 服务器运行状态 */
data class ServerStatus(
    val running: Boolean = false,
    val port: Int = 8080,
    val requestCount: Long = 0,
    val errorCount: Long = 0,
    val lastError: String? = null,
)

/**
 * OpenAI 兼容的中转服务器。
 *
 * ── 它做什么 ────────────────────────────────────────────────────
 * 把手机上跑的本地模型包装成一个 HTTP 接口，别人用任何 OpenAI 客户端
 * （ChatBox、NextChat、Python 的 openai 库……）填上地址和 key 就能用。
 *
 * ── 为什么自己实现而不是用现成的 ─────────────────────────────────
 * 「手机上跑模型」这件事没有现成方案，而 OpenAI 的接口协议是事实标准，
 * 实现了它，用户就不用装任何专门客户端。
 *
 * ── 安全边界（必须明确，写在这里防止以后忘记） ────────────────────
 *   · 明文 HTTP，没有 TLS
 *   · 有 API Key，但那是防误触，不是防攻击
 *   · 没有速率限制、没有请求体大小限制
 *   · 推理是 CPU 单线程的，一个请求就能让手机发烫掉电
 *
 * 因此**只能在局域网用**。绝对不要把端口转发到公网。
 */
class ApiServer(private val port: Int) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ApiServer"

        /** 单条消息长度上限。防止有人发个 10MB 的 prompt 把手机卡死 */
        private const val MAX_CONTENT_CHARS = 8000

        /** 单次请求最多几条对话 */
        private const val MAX_TURNS = 40

        private val _status = MutableStateFlow(ServerStatus())
        val status: StateFlow<ServerStatus> = _status.asStateFlow()

        private val _logs = MutableStateFlow<List<RequestLog>>(emptyList())
        val logs: StateFlow<List<RequestLog>> = _logs.asStateFlow()

        fun resetStats() {
            _status.value = ServerStatus()
            _logs.value = emptyList()
        }

        private fun addLog(entry: RequestLog) {
            _logs.update { (listOf(entry) + it).take(50) }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val started = System.currentTimeMillis()
        val path = session.uri.trimEnd('/').ifEmpty { "/" }
        val method = session.method.name
        val ip = session.remoteIpAddress ?: "?"

        val response = try {
            route(session, path)
        } catch (t: Throwable) {
            // 任何未捕获异常都要变成 JSON 错误响应，而不是空连接 ——
            // 客户端拿到空响应只会显示"网络错误"，排查时毫无线索。
            AppLog.e(TAG, "处理 $method $path 时崩了", t)
            _status.update { it.copy(errorCount = it.errorCount + 1, lastError = t.message) }
            jsonError(Response.Status.INTERNAL_ERROR, "服务器内部错误: ${t.message}", "internal_error")
        }

        val cost = System.currentTimeMillis() - started
        _status.update { it.copy(requestCount = it.requestCount + 1) }
        addLog(RequestLog(started, method, path, ip, response.status.requestStatus, cost))

        AppLog.i(TAG, "$method $path ← $ip  ${response.status.requestStatus}  ${cost}ms")
        return response
    }

    private fun route(session: IHTTPSession, path: String): Response {
        // 探活接口不鉴权，方便用户用浏览器确认服务活着
        if (path == "/" || path == "/health") {
            return jsonOk(
                JSONObject()
                    .put("service", "PocketNode")
                    .put("status", "ok")
                    .put("model", LlmEngine.loadedModelId() ?: JSONObject.NULL)
                    .put("engine_ready", LlmEngine.isReady()),
            )
        }

        if (!authorized(session)) {
            AppLog.w(TAG, "鉴权失败: $path ← ${session.remoteIpAddress}")
            return jsonError(Response.Status.UNAUTHORIZED, "缺少或错误的 API Key", "invalid_api_key")
        }

        return when {
            session.method == Method.GET && path == "/v1/models" -> handleModels()
            session.method == Method.POST && path == "/v1/chat/completions" -> handleChat(session)
            session.method == Method.GET && path == "/v1/device/screen" -> handleDeviceScreen()
            session.method == Method.POST && path == "/v1/device/action" -> handleDeviceAction(session)
            else -> jsonError(Response.Status.NOT_FOUND, "没有这个接口: $path", "not_found")
        }
    }

    /**
     * 校验 Authorization: Bearer <key>。
     *
     * 也接受 x-api-key 头 —— 有些客户端（比如某些 Anthropic 风格的）
     * 用这个字段，多兼容一种成本为零。
     */
    private fun authorized(session: IHTTPSession): Boolean {
        val header = session.headers["authorization"]
        val candidate = when {
            header != null && header.startsWith("Bearer ", ignoreCase = true) ->
                header.substring(7).trim()
            else -> session.headers["x-api-key"]?.trim()
        }
        return ApiKeyStore.isValid(candidate)
    }

    // ────────────────────────── /v1/models ──────────────────────────

    private fun handleModels(): Response {
        val arr = JSONArray()
        val loadedId = LlmEngine.loadedModelId()

        // 只列出**引擎能真正加载**的模型。列出下不了的模型会让客户端
        // 以为能选，实际一调就失败，体验更差。
        ModelCatalog.allNow.forEach { m ->
            if (m.format != io.github.zhbsrja.pocketnode.data.ModelFormat.MediaPipeTask) return@forEach
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("object", "model")
                    .put("created", 0)
                    .put("owned_by", "pocketnode")
                    .put("loaded", m.id == loadedId),
            )
        }

        return jsonOk(
            JSONObject()
                .put("object", "list")
                .put("data", arr),
        )
    }

    // ─────────────────────── /v1/chat/completions ───────────────────

    private fun handleChat(session: IHTTPSession): Response {
        // 读 body。NanoHTTPD 要求先 parseBody 才能拿到 postData
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"] ?: return jsonError(
            Response.Status.BAD_REQUEST, "请求体为空", "invalid_request_error",
        )

        val req = runCatching { JSONObject(raw) }.getOrElse {
            return jsonError(
                Response.Status.BAD_REQUEST, "请求体不是合法 JSON", "invalid_request_error",
            )
        }

        val messages = req.optJSONArray("messages")
            ?: return jsonError(
                Response.Status.BAD_REQUEST, "缺少 messages 字段", "invalid_request_error",
            )
        if (messages.length() == 0) {
            return jsonError(Response.Status.BAD_REQUEST, "messages 是空的", "invalid_request_error")
        }
        if (messages.length() > MAX_TURNS) {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "对话轮数太多（${messages.length()} > $MAX_TURNS）。手机上上下文放不下这么多",
                "invalid_request_error",
            )
        }

        // 引擎没就绪就没法生成 —— 明确告诉客户端，而不是让它干等到超时
        if (!LlmEngine.isReady()) {
            return jsonError(
                Response.Status.SERVICE_UNAVAILABLE,
                "模型还没加载。请先在手机上打开 PocketNode，到模型页点「加载到内存」",
                "model_not_loaded",
            )
        }

        val requestedModel = req.optString("model", "")
        val loadedId = LlmEngine.loadedModelId() ?: ""
        if (requestedModel.isNotBlank() && requestedModel != loadedId) {
            AppLog.w(TAG, "客户端请求的模型是 $requestedModel，实际加载的是 $loadedId，按实际的处理")
        }

        // 拆 messages
        var system: String? = null
        val turns = mutableListOf<Pair<String, String>>()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role", "user")
            val content = extractText(m.opt("content"))
            if (content.isBlank()) continue
            when (role) {
                "system", "developer" -> system = content
                "assistant" -> turns += "assistant" to content
                else -> turns += "user" to content
            }
        }
        if (turns.isEmpty()) {
            return jsonError(
                Response.Status.BAD_REQUEST, "没有任何有效的用户消息", "invalid_request_error",
            )
        }

        // 长度兜底。超出就砍最早的对话，保留 system 和最近几轮 ——
        // 手机模型上下文很小，超了会直接报错或者输出乱七八糟的东西。
        val trimmed = trimTurns(system, turns)

        val model = ModelCatalog.allNow.firstOrNull { it.id == loadedId }
        val template = model?.template ?: ChatTemplate.QWEN
        val prompt = template.build(system, trimmed)

        AppLog.i(TAG, "拼接完成：${trimmed.size} 轮对话，prompt ${prompt.length} 字符，模板 ${template.displayName}")

        val stream = req.optBoolean("stream", false)
        return if (stream) streamChat(prompt, loadedId, model) else blockingChat(prompt, loadedId, model)
    }

    /**
     * 从 content 字段取出纯文本。
     *
     * OpenAI 允许 content 是字符串，**也允许是数组**
     * （多模态格式：[{"type":"text","text":"..."}]）。
     * 新版客户端（openai-python 1.x）默认就可能发数组格式，
     * 不处理的话会直接抛 JSONException。
     */
    private fun extractText(content: Any?): String = when (content) {
        null -> ""
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> append(part.optString("text"))
                    // 图片等类型直接忽略：当前引擎是纯文本的
                    else -> {}
                }
            }
        }
        else -> content.toString()
    }

    /** 超出长度上限就砍掉最早的对话 */
    private fun trimTurns(system: String?, turns: List<Pair<String, String>>): List<Pair<String, String>> {
        var budget = MAX_CONTENT_CHARS - (system?.length ?: 0)
        val kept = ArrayDeque<Pair<String, String>>()
        // 从最新往回留，保证最近说的话一定在
        for (t in turns.reversed()) {
            val cost = t.second.length
            if (budget - cost < 0 && kept.isNotEmpty()) break
            budget -= cost
            kept.addFirst(t)
        }
        if (kept.size < turns.size) {
            AppLog.w(TAG, "上下文超长，砍掉 ${turns.size - kept.size} 轮较早的对话")
        }
        return kept.toList()
    }

    /** 非流式：等生成完一次性返回 */
    private fun blockingChat(prompt: String, modelId: String, model: ModelInfo?): Response {
        val latch = CountDownLatch(1)
        var content: String? = null
        var failure: Throwable? = null

        LlmEngine.generate(
            prompt = prompt,
            onPartial = { },
            onDone = { content = it; latch.countDown() },
            onError = { failure = it; latch.countDown() },
        )

        // 手机生成慢，给足时间。超时说明真的卡住了
        if (!latch.await(180, TimeUnit.SECONDS)) {
            return jsonError(
                Response.Status.INTERNAL_ERROR, "生成超时（180 秒）", "timeout",
            )
        }
        failure?.let {
            return jsonError(Response.Status.INTERNAL_ERROR, "生成失败: ${it.message}", "generation_failed")
        }

        val text = content.orEmpty()
        val completion = completionJson(modelId, text, prompt)
        return newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8", completion.toString(),
        )
    }

    /** 流式：SSE，逐段把增量吐给客户端 */
    private fun streamChat(prompt: String, modelId: String, model: ModelInfo?): Response {
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = System.currentTimeMillis() / 1000
        val source = SseSource()
        val started = AtomicBoolean(false)

        // 生成在独立线程跑，主线程把这个 InputStream 交给 NanoHTTPD，
        // 它会边读边往 socket 写（chunked encoding）。这样客户端
        // 能立刻看到第一个字，而不是等全部生成完。
        thread(name = "pn-sse", isDaemon = true) {
            try {
                started.set(true)
                source.emit(sseChunk(id, created, modelId, role = "assistant", content = "", finish = null))

                var full = ""
                LlmEngine.generateStreaming(
                    prompt = prompt,
                    onPartial = { partial ->
                        // partial 是**累计的全文**，不是增量 —— MediaPipe 的
                        // ProgressListener 给的是到目前为止的完整输出。
                        // 必须自己算出增量，否则客户端会看到重复叠加的文字。
                        if (partial.length > full.length) {
                            val delta = partial.substring(full.length)
                            full = partial
                            source.emit(sseChunk(id, created, modelId, null, delta, null))
                        }
                    },
                    onDone = { done ->
                        if (done.length > full.length) {
                            source.emit(sseChunk(id, created, modelId, null, done.substring(full.length), null))
                        }
                        source.emit(sseChunk(id, created, modelId, null, "", "stop"))
                        source.emit("data: [DONE]\n\n")
                        source.finish()
                    },
                    onError = { e ->
                        AppLog.e(TAG, "SSE 生成出错", e)
                        source.emit(
                            "data: " + JSONObject()
                                .put("error", JSONObject().put("message", e.message ?: "生成失败"))
                                .toString() + "\n\n",
                        )
                        source.finish()
                    },
                )
            } catch (t: Throwable) {
                AppLog.e(TAG, "SSE 线程崩了", t)
                source.finish()
            }
        }

        val resp = newChunkedResponse(
            Response.Status.OK, "text/event-stream; charset=utf-8", source,
        )
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
        // 有些反代和客户端靠这个头确认是 SSE
        resp.addHeader("X-Accel-Buffering", "no")
        return resp
    }

    private fun sseChunk(
        id: String,
        created: Long,
        modelId: String,
        role: String?,
        content: String?,
        finish: String?,
    ): String {
        val delta = JSONObject()
        role?.let { delta.put("role", it) }
        if (!content.isNullOrEmpty()) delta.put("content", content)

        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", modelId)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("delta", delta)
                        .put("finish_reason", finish ?: JSONObject.NULL),
                ),
            )
        return "data: $obj\n\n"
    }

    /** 非流式的完整响应体 */
    private fun completionJson(modelId: String, text: String, prompt: String): JSONObject {
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        return JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", modelId)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put(
                            "message",
                            JSONObject().put("role", "assistant").put("content", text),
                        )
                        .put("finish_reason", "stop"),
                ),
            )
            .put(
                "usage",
                JSONObject()
                    // 没有真正的 tokenizer，用字符数近似。
                    // 明确标注是估算，不假装精确。
                    .put("prompt_tokens", prompt.length / 2)
                    .put("completion_tokens", text.length / 2)
                    .put("total_tokens", (prompt.length + text.length) / 2)
                    .put("estimated", true),
            )
    }

    // ────────────────────────── 工具方法 ──────────────────────────

    // ─────────────────────── /v1/device/* ───────────────────────

    /**
     * 读取设备当前界面。
     *
     * ⚠️ 这是**隐私数据**。屏幕上有短信验证码、聊天记录、银行余额。
     * 所以它和写操作一样要过完整的远程闸门（DeviceController.remoteAllowed）。
     */
    private fun handleDeviceScreen(): Response {
        if (!DeviceController.remoteAllowed()) {
            return jsonError(
                Response.Status.FORBIDDEN,
                DeviceController.remoteBlockerReason() ?: "远程控制未开启",
                "device_control_disabled",
            )
        }
        val result = DeviceController.readScreen(remote = true)
        return result.fold(
            onSuccess = {
                jsonOk(
                    JSONObject()
                        .put("package", DeviceController.currentPackage() ?: JSONObject.NULL)
                        .put("screen", it),
                )
            },
            onFailure = { jsonError(Response.Status.INTERNAL_ERROR, it.message ?: "读取失败", "device_error") },
        )
    }

    /**
     * 执行一个设备动作。
     *
     * 请求体：
     *   {"action":"tap","x":100,"y":200}
     *   {"action":"longPress","x":100,"y":200}
     *   {"action":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"durationMs":300}
     *   {"action":"type","text":"要输入的内容"}
     *   {"action":"back"} / {"action":"home"}
     *
     * 所有动作都经过 DeviceController 的闸门：开关检查、无障碍检查、
     * 600ms 节流、10 分钟空闲自动上锁、以及全程留痕。
     */
    private fun handleDeviceAction(session: IHTTPSession): Response {
        if (!DeviceController.remoteAllowed()) {
            return jsonError(
                Response.Status.FORBIDDEN,
                DeviceController.remoteBlockerReason() ?: "远程控制未开启",
                "device_control_disabled",
            )
        }

        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"] ?: return jsonError(
            Response.Status.BAD_REQUEST, "请求体为空", "invalid_request_error",
        )
        val req = runCatching { JSONObject(raw) }.getOrElse {
            return jsonError(Response.Status.BAD_REQUEST, "请求体不是合法 JSON", "invalid_request_error")
        }

        val action = req.optString("action", "")
        val result: Result<Unit> = when (action) {
            "tap" -> DeviceController.tap(
                req.optDouble("x", -1.0).toFloat(),
                req.optDouble("y", -1.0).toFloat(),
                remote = true,
            )
            "longPress" -> DeviceController.longPress(
                req.optDouble("x", -1.0).toFloat(),
                req.optDouble("y", -1.0).toFloat(),
                remote = true,
            )
            "swipe" -> DeviceController.swipe(
                req.optDouble("x1", 0.0).toFloat(),
                req.optDouble("y1", 0.0).toFloat(),
                req.optDouble("x2", 0.0).toFloat(),
                req.optDouble("y2", 0.0).toFloat(),
                req.optLong("durationMs", 300L),
                remote = true,
            )
            "type" -> DeviceController.typeText(req.optString("text", ""), remote = true)
            "back" -> DeviceController.back(remote = true)
            "home" -> DeviceController.home(remote = true)
            else -> return jsonError(
                Response.Status.BAD_REQUEST,
                "不支持的动作: '$action'。可用：tap / longPress / swipe / type / back / home",
                "invalid_request_error",
            )
        }

        return result.fold(
            onSuccess = { jsonOk(JSONObject().put("ok", true).put("action", action)) },
            onFailure = { e ->
                // 被闸门拦住和动作本身失败要区分：前者是策略拒绝（403），
                // 后者是执行出错（500）。客户端能据此决定是重试还是放弃。
                val code = if (e is SecurityException) Response.Status.FORBIDDEN
                else Response.Status.INTERNAL_ERROR
                jsonError(
                    code,
                    e.message ?: "执行失败",
                    if (e is SecurityException) "device_control_denied" else "device_error",
                )
            },
        )
    }

    private fun jsonOk(obj: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", obj.toString())

    private fun jsonError(status: Response.Status, message: String, code: String): Response {
        val obj = JSONObject().put(
            "error",
            JSONObject()
                .put("message", message)
                .put("type", code)
                .put("code", code),
        )
        return newFixedLengthResponse(
            status, "application/json; charset=utf-8", obj.toString(),
        )
    }

    /**
     * SSE 的数据源。
     *
     * 用阻塞队列把「生成线程产生的片段」和「NanoHTTPD 的读取线程」
     * 解耦 —— 生成慢的时候读线程会阻塞在 poll 上等着，不会忙转。
     *
     * 为什么不用 PipedInputStream：它的缓冲区一旦写满，写线程会阻塞，
     * 而客户端断开时读端关闭的行为在不同 JVM 上不一致，容易死锁。
     * 队列语义明确得多。
     */
    private class SseSource : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val finished = AtomicBoolean(false)

        private var current: ByteArray? = null
        private var offset = 0

        fun emit(text: String) {
            if (finished.get()) return
            queue.put(text.toByteArray(Charsets.UTF_8))
        }

        fun finish() {
            if (finished.compareAndSet(false, true)) {
                queue.put(ByteArray(0))   // 空数组当 EOF 哨兵
            }
        }

        override fun read(): Int {
            while (true) {
                val buf = current
                if (buf != null && offset < buf.size) {
                    return buf[offset++].toInt() and 0xFF
                }
                val next = try {
                    queue.poll(500, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                } ?: continue
                if (next.isEmpty()) return -1   // EOF
                current = next
                offset = 0
            }
        }

        override fun available(): Int {
            val buf = current ?: return 0
            return buf.size - offset
        }

        override fun close() {
            finish()
        }
    }
}
