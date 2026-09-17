package io.github.zhbsrja.pocketnode.data

import io.github.zhbsrja.pocketnode.R

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 一个可下载的模型。
 *
 * @param id            稳定标识，用作文件名和界面 key，别改
 * @param name          展示名
 * @param vendor        来源
 * @param parameters    参数量，如 "1.5B"
 * @param quantization  量化方式
 * @param sizeBytes     文件字节数（用于界面展示）。下载时的完整性校验以
 *                      服务器的 Content-Length 为准，这里只是预估，不精确不影响使用
 * @param fileName      存到本地叫什么
 * @param downloadUrl   下载地址
 * @param sha256        期望哈希，为空表示不校验
 * @param minRamGb      建议最低运行内存
 * @param note          一句话说明。**仅导入模型用** —— 用户自己填的文字，没得翻
 * @param noteRes       内置模型说明的字符串资源 ID。0 表示没有（走 note）
 * @param format        模型格式，决定用哪个推理后端加载
 * @param imported      true 表示这是用户自己导入的，不是内置目录里的
 * @param template      对话模板。中转接口收到 OpenAI 的 messages 后要按这个
 *                      拼成模型认识的格式，拼错了模型会乱答（且不报错）
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val vendor: String,
    val parameters: String,
    val quantization: String,
    val sizeBytes: Long,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String? = null,
    val minRamGb: Int = 4,
    val note: String = "",
    @androidx.annotation.StringRes val noteRes: Int = 0,
    val format: ModelFormat = ModelFormat.MediaPipeTask,
    val imported: Boolean = false,
    val template: io.github.zhbsrja.pocketnode.server.ChatTemplate =
        io.github.zhbsrja.pocketnode.server.ChatTemplate.QWEN,
) {
    val sizeLabel: String
        get() {
            val mb = sizeBytes / 1024.0 / 1024.0
            return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
        }

    val specLabel: String
        get() = "$vendor · $parameters · $quantization"
}

/** 模型文件格式。不同格式要用不同的推理后端加载。 */
enum class ModelFormat {
    /** MediaPipe / LiteRT 的 .task 包，用 tasks-genai 加载 */
    MediaPipeTask,

    /** llama.cpp 的 GGUF，需要自己编 JNI（暂未实现） */
    Gguf,

    /** LiteRT-LM 的 .litertlm 包，需要换后端才能用（暂未实现） */
    LiteRtLm,
}

/**
 * 内置模型目录。
 *
 * 模型文件托管在自己的服务器上（154.40.44.75:8899），不用 HuggingFace ——
 * 国内直连 HF 太慢，自建源能保证下载体验。
 *
 * 服务器上跑的是一个支持 HTTP Range 的小服务，所以断点续传是真能用的。
 *
 * ── 选型说明 ──────────────────────────────────────────────────
 * 只列 **.task 格式**的模型，因为当前引擎是 MediaPipe tasks-genai。
 * .litertlm 格式（Qwen3 系列、SmolLM3 等）需要换 LiteRT-LM 后端，
 * 代码里已经在 ModelFormat 里留了位置，但还没实现加载逻辑。
 *
 * Gemma3-1B 虽然也是 .task，但它在 HuggingFace 上属于 gated 仓库，
 * 未登录会返回 401，普通用户下不动，所以不列进来。
 * ──────────────────────────────────────────────────────────────
 */
object ModelCatalog {

    /** 自建模型源的根地址。以后会让用户在设置里改。 */
    const val MODEL_BASE_URL = "http://154.40.44.75:8899"

    /**
     * 按「设备能力」从低到高排列。
     *
     * 没有做运行时自动推荐 —— 那需要读内存和 SoC 型号，等模型下载量上来
     * 再做。现在先靠 note 里的说明让用户自己判断。
     */
    val builtIn: List<ModelInfo> = listOf(

        // ── 入门档：4GB 内存以上，任何能装这个 App 的机器都能跑 ──
        ModelInfo(
            id = "qwen2.5-0.5b",
            name = "Qwen2.5 0.5B Instruct",
            vendor = "Qwen",
            parameters = "0.5B",
            quantization = "Q8",
            sizeBytes = 546_660_344L,   // 精确值，从服务器实测得到
            fileName = "qwen2.5-0.5b-instruct-q8.task",
            downloadUrl = "$MODEL_BASE_URL/qwen2.5-0.5b-q8.task",
            minRamGb = 2,
            noteRes = R.string.model_note_qwen05b,
        ),

        // ── 主力档：6–8GB 内存，多数中端机 ──
        ModelInfo(
            id = "qwen2.5-1.5b",
            name = "Qwen2.5 1.5B Instruct",
            vendor = "Qwen",
            parameters = "1.5B",
            quantization = "Q8",
            sizeBytes = 1_495L * 1024 * 1024,
            fileName = "qwen2.5-1.5b-instruct-q8.task",
            downloadUrl = "$MODEL_BASE_URL/qwen2.5-1.5b-q8.task",
            minRamGb = 6,
            noteRes = R.string.model_note_qwen15b,
        ),

        // ── 推理档：6–8GB 内存，慢但会思考 ──
        ModelInfo(
            id = "deepseek-r1-1.5b",
            name = "DeepSeek-R1-Distill-Qwen 1.5B",
            vendor = "DeepSeek",
            parameters = "1.5B",
            quantization = "Q8",
            sizeBytes = 1_749L * 1024 * 1024,
            fileName = "deepseek-r1-qwen-1.5b-q8.task",
            downloadUrl = "$MODEL_BASE_URL/deepseek-r1-qwen-1.5b-q8.task",
            minRamGb = 6,
            noteRes = R.string.model_note_deepseek,
        ),

        // ── 旗舰档：12GB 内存以上，骁龙 8 系 / 天玑 9 系 ──
        ModelInfo(
            id = "phi-4-mini",
            name = "Phi-4-mini Instruct",
            vendor = "Microsoft",
            parameters = "3.8B",
            quantization = "Q8",
            sizeBytes = 3_729L * 1024 * 1024,
            fileName = "phi-4-mini-q8.task",
            downloadUrl = "$MODEL_BASE_URL/phi-4-mini-q8.task",
            minRamGb = 12,
            noteRes = R.string.model_note_phi4,
            template = io.github.zhbsrja.pocketnode.server.ChatTemplate.PHI,
        ),
    )

    fun findById(id: String): ModelInfo? =
        builtIn.firstOrNull { it.id == id } ?: ImportedModelStore.models.value.firstOrNull { it.id == id }

    /**
     * 同步读取完整列表。
     *
     * 为什么不直接用下面的 all.value —— 因为 all 是 stateIn 出来的，
     * 它的 map 跑在协程里，**是异步的**。初始化时同步调用 all.value
     * 拿到的往往还是 initialValue（只有内置模型），导入的模型会漏掉。
     * 这个坑很隐蔽：表现是「导入的模型显示文件已丢失，重启一下又好了」。
     *
     * 凡是「立刻就要用」的场景（扫描文件、查状态）都走这个属性。
     */
    val allNow: List<ModelInfo>
        get() = builtIn + ImportedModelStore.models.value

    /**
     * 界面用的完整列表，响应式版本。
     *
     * 用 stateIn 缓存而不是每次现算 —— 这个列表被 LazyColumn 当 key 用，
     * 每次重新构造 List 实例会导致无意义的整表重组。
     *
     * scope 用应用级：ModelCatalog 是单例，本来就和进程同生命周期。
     */
    val all: StateFlow<List<ModelInfo>> = ImportedModelStore.models
        .map { imported -> builtIn + imported }
        .stateIn(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            started = SharingStarted.Eagerly,
            initialValue = builtIn,
        )
}
