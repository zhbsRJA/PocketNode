package io.github.zhbsrja.pocketnode.inference

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import io.github.zhbsrja.pocketnode.data.DownloadManager
import io.github.zhbsrja.pocketnode.data.ModelFormat
import io.github.zhbsrja.pocketnode.data.ModelInfo
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 引擎状态 */
sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val modelId: String, val startedAt: Long) : EngineState
    data class Ready(val modelId: String, val loadMillis: Long) : EngineState
    data class Generating(val modelId: String) : EngineState
    data class Failed(val message: String, val detail: String? = null) : EngineState
}

/**
 * 本地推理引擎（MediaPipe LLM Inference 封装）。
 *
 * 关于技术选型，有几点必须写在代码里，免得以后忘了为什么这么做：
 *
 * 1. **MediaPipe 官方已标为 maintenance-only**，推荐迁移到 LiteRT-LM。
 *    这里先用它是因为集成成本最低 —— 一个 Gradle 依赖，不用编 NDK。
 *    等链路验证完，再评估换 LiteRT-LM（那时可能要动 model format）。
 *
 * 2. **官方明确说不支持模拟器**：「optimized for high-end Android devices
 *    ... does not reliably support device emulators」。所以在模拟器上
 *    大概率会失败 —— 那时候日志就是唯一的线索，这也是为什么这里
 *    每一步都记得很细。
 *
 * 3. **加载是耗时的同步操作**，必须放 IO 线程，而且要给用户「正在加载」
 *    的反馈 —— 一个 500MB 的模型加载要好几秒，界面不响应用户会以为死了。
 */
object LlmEngine {

    private const val TAG = "LlmEngine"

    /** 生成时的最大 token 数。太小答不完整，太大在手机上等太久。 */
    private const val MAX_TOKENS = 1024
    private const val TOP_K = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** MediaPipe 的实例。null 表示未加载。 */
    @Volatile
    private var inference: LlmInference? = null

    @Volatile
    private var loadedModelId: String? = null

    fun isReady(): Boolean = inference != null

    fun loadedModelId(): String? = loadedModelId

    /**
     * 加载模型。已经在加载或已加载同一个模型时直接返回。
     */
    fun load(context: Context, model: ModelInfo) {
        if (inference != null && loadedModelId == model.id) {
            AppLog.i(TAG, "${model.id} 已经加载过了，跳过")
            return
        }
        if (_state.value is EngineState.Loading) {
            AppLog.w(TAG, "正在加载中，忽略重复请求")
            return
        }

        scope.launch {
            val started = System.currentTimeMillis()
            _state.value = EngineState.Loading(model.id, started)

            try {
                // 格式检查：只有 MediaPipe .task 能走这条路
                if (model.format != ModelFormat.MediaPipeTask) {
                    val msg = "模型格式 ${model.format} 不被当前引擎支持（需要 MediaPipeTask）"
                    AppLog.e(TAG, msg)
                    _state.value = EngineState.Failed(msg)
                    return@launch
                }

                val file: File = DownloadManager.localFile(context, model)
                AppLog.i(TAG, "准备加载 ${model.id}")
                AppLog.i(TAG, "  路径     = ${file.absolutePath}")
                AppLog.i(TAG, "  存在     = ${file.exists()}")
                AppLog.i(TAG, "  大小     = ${if (file.exists()) file.length() else 0} 字节")

                if (!file.exists() || file.length() == 0L) {
                    val msg = "模型文件不存在，请先下载"
                    AppLog.e(TAG, msg)
                    _state.value = EngineState.Failed(msg)
                    return@launch
                }

                // 先卸载旧的，避免两份模型同时占内存
                closeQuietly()

                AppLog.i(TAG, "调用 LlmInference.createFromOptions…")
                AppLog.i(TAG, "  后端 = CPU（模拟器上 GPU 驱动不可靠，先用纯 CPU 保证能跑）")

                // 注意：这一版的 API 里，temperature / topK / randomSeed 不在
                // LlmInferenceOptions 上，而在 LlmInferenceSession 的 options 里。
                // 主 options 只有：modelPath / maxTokens / maxNumImages /
                // maxTopK / loraRanks / vision / audio / preferredBackend。
                // （文档写的是 setTopK，实际类里叫 setMaxTopK —— 别照文档抄。）
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .setMaxTopK(TOP_K)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()

                val created = LlmInference.createFromOptions(context, options)
                inference = created
                loadedModelId = model.id

                val cost = System.currentTimeMillis() - started
                AppLog.i(TAG, "${model.id} 加载成功 ✓ 耗时 ${cost}ms")
                _state.value = EngineState.Ready(model.id, cost)

            } catch (t: Throwable) {
                // 这里是最可能出问题的地方（模拟器不支持、native 库加载失败、
                // 内存不够、模型格式不对）。堆栈必须完整记下来。
                AppLog.e(TAG, "${model.id} 加载失败", t)
                inference = null
                loadedModelId = null
                _state.value = EngineState.Failed(
                    message = t.message ?: t.javaClass.simpleName,
                    detail = t.javaClass.name,
                )
            }
        }
    }

    /**
     * 生成回复（流式）。
     *
     * @param onPartial 每收到一段增量文本回调一次
     * @param onDone    结束回调，参数是完整文本
     * @param onError   出错回调
     */
    fun generate(
        prompt: String,
        onPartial: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val llm = inference
        if (llm == null) {
            val e = IllegalStateException("模型未加载")
            AppLog.e(TAG, "generate() 时模型未加载", e)
            onError(e)
            return
        }

        val modelId = loadedModelId ?: "?"
        _state.value = EngineState.Generating(modelId)

        scope.launch {
            try {
                AppLog.i(TAG, "开始生成，prompt 长度=${prompt.length}")
                val t0 = System.currentTimeMillis()

                // 用同步接口 + IO 线程，而不是框架的 async 回调：
                // 回调式接口的线程语义不明确，改成自己控制更可预测。
                val result = llm.generateResponse(prompt)

                val cost = System.currentTimeMillis() - t0
                AppLog.i(TAG, "生成完成，输出长度=${result?.length ?: 0}，耗时 ${cost}ms")

                if (result.isNullOrBlank()) {
                    AppLog.w(TAG, "模型返回空结果")
                    onPartial("（模型没有返回内容）")
                    onDone("")
                } else {
                    onPartial(result)
                    onDone(result)
                }
                _state.value = EngineState.Ready(modelId, 0L)

            } catch (t: Throwable) {
                AppLog.e(TAG, "生成失败", t)
                _state.value = EngineState.Failed(t.message ?: t.javaClass.simpleName)
                onError(t)
            }
        }
    }

    /**
     * 流式生成。
     *
     * 和上面 generate() 的区别：这个是真的逐段吐字，用的是
     * generateResponseAsync(prompt, ProgressListener)。
     * ProgressListener 每产生一段增量就回调一次，参数是 (增量文本, 是否结束)。
     *
     * 为什么中转必须用这个而不是 generate()：
     * generateResponse() 是同步阻塞的，手机上生成 100 个字要 10 秒左右。
     * 如果等全部生成完再一次性返回，客户端会**干等 10 秒才看到第一个字**，
     * 而且会被各种 HTTP 客户端判成超时。SSE 流式是 OpenAI 兼容接口的
     * 事实标准，不实现的话很多客户端根本用不了。
     *
     * 线程注意：ProgressListener 的回调**不在主线程**，调用方自己保证
     * 回调里做的事是线程安全的。
     */
    fun generateStreaming(
        prompt: String,
        onPartial: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val llm = inference
        if (llm == null) {
            val e = IllegalStateException("模型未加载")
            AppLog.e(TAG, "generateStreaming() 时模型未加载", e)
            onError(e)
            return
        }

        val modelId = loadedModelId ?: "?"
        _state.value = EngineState.Generating(modelId)
        val t0 = System.currentTimeMillis()
        AppLog.i(TAG, "开始流式生成，prompt 长度=${prompt.length}")

        scope.launch {
            try {
                var chunks = 0
                var acc = StringBuilder()
                llm.generateResponseAsync(prompt) { partial, done ->
                    runCatching {
                        val text = partial ?: ""
                        chunks++
                        // 这段日志是**临时诊断用**的，用来确认 partialResult
                        // 到底是「累计全文」还是「增量片段」——
                        // MediaPipe 文档没写清楚，猜错会导致流式只吐第一个字。
                        // 确认之后可以删掉。
                        AppLog.i(
                            TAG,
                            "  回调 #$chunks done=$done 长度=${text.length} 内容=「${text.take(30)}」",
                        )

                        if (done) {
                            val finalText = if (text.isEmpty()) acc.toString() else text
                            val cost = System.currentTimeMillis() - t0
                            AppLog.i(TAG, "流式生成结束，共 $chunks 段，最终长度=${finalText.length}，耗时 ${cost}ms")
                            onDone(finalText)
                            _state.value = EngineState.Ready(modelId, 0L)
                        } else if (text.isNotEmpty()) {
                            acc.append(text)
                            onPartial(acc.toString())
                        }
                    }.onFailure {
                        AppLog.e(TAG, "流式回调处理出错", it)
                    }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "流式生成失败", t)
                _state.value = EngineState.Failed(t.message ?: t.javaClass.simpleName)
                onError(t)
            }
        }
    }

    /** 卸载模型，释放内存 */
    fun unload() {
        AppLog.i(TAG, "卸载模型 ${loadedModelId ?: "(无)"}")
        closeQuietly()
        loadedModelId = null
        _state.value = EngineState.Idle
    }

    private fun closeQuietly() {
        runCatching { inference?.close() }
            .onFailure { AppLog.w(TAG, "关闭旧实例出错", it) }
        inference = null
    }
}
