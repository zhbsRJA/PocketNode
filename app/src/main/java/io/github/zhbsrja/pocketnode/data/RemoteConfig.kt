package io.github.zhbsrja.pocketnode.data

import android.content.Context
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程配置。
 *
 * ══════════════════════════════════════════════════════════════
 * ── 它解决什么问题 ──────────────────────────────────────────────
 *
 * 每次改点东西就要用户重装 APK，这在"十几个朋友用着"的阶段还能忍，
 * 但有两个场景会很难受：
 *
 *   1. **模型清单写错了**。比如字节数填错导致完整性校验假通过 ——
 *      这种错误改一行代码就行，但没云控的话得重新打包、挨个发、
 *      每人手动装。有云控的话，改个 JSON 就完事。
 *
 *   2. **某个模型源挂了**。想临时换个下载地址，没云控只能等用户升级。
 *
 * ── 它不解决什么 ────────────────────────────────────────────────
 *
 * **代码逻辑 bug 修不了**。云控只能改"数据"和"开关"，
 * 改不了"代码怎么执行"。下载循环写反了、崩溃了，只能出新版本。
 *
 * ── 安全边界（重要）─────────────────────────────────────────────
 *
 * 远程配置能改下载地址，等于**谁控制了这个 JSON，谁就能让 App
 * 去下载任意文件**。这里做了三层限制：
 *
 *   1. **只覆盖字段，不执行代码**。JSON 里没有"执行什么"的能力，
 *      最多是让 App 换个 URL 下载。下载完还有字节数校验。
 *   2. **拉不到就用缓存，缓存也没有就用内置值**。网络不通不会
 *      导致 App 不可用。
 *   3. **远程配置改不了设备控制开关**。那是用户在本机做的决定，
 *      不能由服务端远程打开 —— 这条是硬限制，代码里根本不读远程值。
 * ══════════════════════════════════════════════════════════════
 */
object RemoteConfig {

    private const val TAG = "RemoteConfig"

    /**
     * 配置地址。
     *
     * 放在自己的模型服务器上而不是 GitHub —— 国内访问 GitHub 不稳定，
     * 而这个服务器本来就在跑，加个静态文件是零成本。
     */
    private const val CONFIG_URL = "http://154.40.44.75:8899/config.json"

    /** 拉取超时。云控不是关键路径，超时短一点，别拖慢启动 */
    private const val TIMEOUT_MS = 6000

    data class Snapshot(
        /** 模型字段覆盖：id → 要改的字段 */
        val modelOverrides: Map<String, JSONObject> = emptyMap(),
        /**
         * 远程新增的模型（id 不在内置目录里的）。
         *
         * ⚠️ 这个能力扩大了信任面：**谁控制 config.json，谁就能往用户
         * 的模型列表里加条目**，包括加一个指向任意地址的下载源。
         *
         * 所以加了两道约束：
         *   1. 新增的模型**必须给出精确 sizeBytes**，没有的话直接不收录 ——
         *      否则用户下载完没法判断完整性，会重演"文件残缺却显示就绪"
         *   2. 下载完仍然要做完整性校验，大小不符就标为失败
         *
         * 换句话说：能加模型，但加了之后下载的文件依然要被验。
         */
        val addedModels: List<ModelInfo> = emptyList(),
        /** 要下架的内置模型 id（比如发现某个模型质量太差） */
        val removedIds: Set<String> = emptySet(),
        /** 功能开关 */
        val flags: Map<String, Boolean> = emptyMap(),
        /** 低于这个 versionCode 就提示升级 */
        val minVersionCode: Int = 0,
        /** 公告文字，为空则不显示 */
        val announcement: String = "",
        /** 配置版本号，只用于日志和排查 */
        val version: Int = 0,
        /** 这次快照是从哪来的：network / cache / none */
        val source: String = "none",
    ) {
        fun flag(name: String, default: Boolean): Boolean = flags[name] ?: default
    }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        // 先用缓存 —— 冷启动时不能等网络，否则界面要空好几秒
        val cached = readCache()
        if (cached != null) {
            _snapshot.value = cached.copy(source = "cache")
            AppLog.i(TAG, "已加载缓存配置 version=${cached.version}")
        }
    }

    /**
     * 后台拉取。**不阻塞启动**，失败静默。
     *
     * 调用时机：Application.onCreate 里 init 之后，用协程异步跑。
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        try {
            AppLog.i(TAG, "拉取远程配置：$CONFIG_URL")
            val conn = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                // 配置要实时，禁用缓存
                setRequestProperty("Cache-Control", "no-cache")
            }

            val code = conn.responseCode
            if (code != 200) {
                AppLog.w(TAG, "远程配置返回 $code，忽略")
                conn.disconnect()
                return@withContext false
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val snap = parse(text) ?: return@withContext false
            _snapshot.value = snap.copy(source = "network")
            writeCache(text)
            AppLog.i(
                TAG,
                "远程配置已应用 version=${snap.version} 覆盖 ${snap.modelOverrides.size} 个模型 " +
                    "开关 ${snap.flags.size} 个",
            )
            true
        } catch (t: Throwable) {
            // 网络问题不该影响 App 使用 —— 用缓存或内置值继续跑
            AppLog.w(TAG, "拉取远程配置失败（用缓存/内置值继续）：${t.message}")
            false
        }
    }

    private fun parse(text: String): Snapshot? = runCatching {
        val root = JSONObject(text)

        val overrides = mutableMapOf<String, JSONObject>()
        val added = mutableListOf<ModelInfo>()
        val removed = mutableSetOf<String>()

        root.optJSONArray("models")?.let { arr: JSONArray ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) continue

                // 标记为下架 —— 内置模型 id + removed:true
                if (o.optBoolean("removed", false)) {
                    removed += id
                    continue
                }

                val known = ModelCatalog.builtIn.any { it.id == id }
                if (known) {
                    overrides[id] = o
                } else {
                    // 新增的模型必须给全字段，缺关键项直接丢弃
                    parseNewModel(o)?.let { added += it }
                        ?: AppLog.w(TAG, "远程模型 $id 字段不全，已忽略")
                }
            }
        }

        val flags = mutableMapOf<String, Boolean>()
        root.optJSONObject("flags")?.let { o ->
            o.keys().forEach { k -> flags[k] = o.optBoolean(k) }
        }

        Snapshot(
            modelOverrides = overrides,
            addedModels = added,
            removedIds = removed,
            flags = flags,
            minVersionCode = root.optInt("minVersionCode", 0),
            announcement = root.optString("announcement", ""),
            version = root.optInt("version", 0),
        )
    }.onFailure {
        AppLog.e(TAG, "远程配置 JSON 解析失败（格式不对就当没有）", it)
    }.getOrNull()

    /**
     * 解析一个远程新增的模型。
     *
     * **sizeBytes 和 downloadUrl 是硬性要求**，缺任何一个就返回 null 丢弃。
     *
     * 为什么卡这么死：这两个字段一个决定"下载完怎么判断完整"，
     * 一个决定"从哪下"。缺了任何一个，这个条目就是个坑 ——
     * 用户点了下载，下完了也不知道对不对。
     *
     * 宁可少收一个模型，也不要让用户下到坏文件。
     */
    private fun parseNewModel(o: JSONObject): ModelInfo? {
        val id = o.optString("id", "").ifBlank { return null }
        val url = o.optString("downloadUrl", "").ifBlank { return null }
        val size = o.optLong("sizeBytes", 0L)
        if (size <= 0) return null

        return ModelInfo(
            id = id,
            name = o.optString("name", id),
            vendor = o.optString("vendor", "Remote"),
            parameters = o.optString("parameters", "—"),
            quantization = o.optString("quantization", "—"),
            sizeBytes = size,
            // 没给文件名就用 id 拼一个。用户看不出区别，但下载器需要一个名字
            fileName = o.optString("fileName", "$id.task"),
            downloadUrl = url,
            sha256 = o.optString("sha256", "").ifBlank { null },
            minRamGb = o.optInt("minRamGb", 4),
            // 远程模型的说明只能是纯文本 —— 资源 id 是编译期的东西，
            // 运行时变不出来。所以支持多语言映射，让推送方按语言给不同文字。
            note = o.optString("note", ""),
            noteI18n = parseNoteI18n(o.optJSONObject("note_i18n")),
            format = ModelFormat.MediaPipeTask,
            imported = false,
        )
    }

    /**
     * 解析多语言说明 { "zh": "...", "en": "..." }。
     *
     * 只收非空值 —— 配置里给了 "zh": "" 的话，落到界面上就是一片空白，
     * 还不如让它回退到别的语言。
     */
    private fun parseNoteI18n(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        o.keys().forEach { k ->
            val v = o.optString(k, "").trim()
            if (v.isNotEmpty()) out[k.lowercase()] = v
        }
        return out
    }

    /** 把一个远程覆盖应用到内置模型上。字段缺省则保留原值。 */
    fun applyOverride(base: ModelInfo): ModelInfo {
        val o = _snapshot.value.modelOverrides[base.id] ?: return base
        return base.copy(
            name = o.optString("name", base.name),
            sizeBytes = if (o.has("sizeBytes")) o.optLong("sizeBytes", base.sizeBytes) else base.sizeBytes,
            downloadUrl = o.optString("downloadUrl", base.downloadUrl),
            sha256 = if (o.has("sha256")) o.optString("sha256").ifBlank { null } else base.sha256,
            noteRes = base.noteRes,   // 说明文字走资源，远程改不了（要跟语言走）
            note = if (o.has("note")) o.optString("note") else base.note,
        )
    }

    /** 读取远程下发的说明文字（如果有）。空串表示没有覆盖。 */
    fun overrideNote(modelId: String): String =
        _snapshot.value.modelOverrides[modelId]?.optString("note", "") ?: ""

    // ── 缓存 ──
    //
    // 缓存的意义：网络不通或服务器挂了的时候，至少还能用上次拿到的配置。
    // 存文件而不是 SharedPreferences，因为这是一整块 JSON，整体读写。

    private fun cacheFile(): File? = context?.let { File(it.filesDir, "remote_config.json") }

    private fun writeCache(text: String) {
        runCatching { cacheFile()?.writeText(text) }
            .onFailure { AppLog.w(TAG, "写配置缓存失败", it) }
    }

    private fun readCache(): Snapshot? {
        val f = cacheFile() ?: return null
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { parse(f.readText()) }.getOrNull()
    }

    fun clearCache() {
        runCatching { cacheFile()?.delete() }
        _snapshot.value = Snapshot()
        AppLog.w(TAG, "配置缓存已清除")
    }
}
