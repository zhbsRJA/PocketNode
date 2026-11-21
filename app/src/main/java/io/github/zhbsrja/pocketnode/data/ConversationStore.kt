package io.github.zhbsrja.pocketnode.data

import android.content.Context
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条消息 */
data class ChatMessage(
    val role: String,          // "user" / "assistant"
    val content: String,
    val at: Long = System.currentTimeMillis(),
    val error: Boolean = false,
)

/**
 * 一个会话。
 *
 * @param id        文件名用的标识，时间戳生成
 * @param title     侧边栏显示的名字。首条用户消息自动截取，用户可以改
 * @param messages  按时间顺序
 * @param createdAt 创建时间
 * @param updatedAt 最后修改时间，用于排序
 */
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 会话存储。
 *
 * ══════════════════════════════════════════════════════════════
 * ── 为什么用「一个会话一个 JSON 文件」而不是数据库 ──────────────
 *
 * Room 更"正规"，但这里有个决定性因素：**导出和导入**。
 *
 * 用户的诉求是"选定聊天记录导出"和"导入"。如果用数据库，
 * 导出要把记录序列化成某种中间格式 —— 那个格式就成了第二套
 * 数据结构，导入导出各要一套转换代码，还要处理版本兼容。
 *
 * 用文件的话：**存储格式 = 传输格式 = 导出格式**，三合一。
 * 导出就是复制文件，导入就是读文件，没有任何转换。
 *
 * 代价是没有索引和查询能力。但会话数量撑死几百个，全量扫描
 * 一毫秒的事，用不上数据库。
 *
 * ── 存在哪 ──────────────────────────────────────────────────────
 *
 * App 私有目录（files/conversations/）。
 *
 * **不放在外部存储**，因为：
 *   · 会话内容是最私密的东西（用户和模型的全部对话）
 *   · 放外部存储的话，任何有存储权限的应用都能读
 *   · 想导出的话有专门的导出功能，不需要靠"暴露目录"来实现
 * ══════════════════════════════════════════════════════════════
 */
object ConversationStore {

    private const val TAG = "ConversationStore"
    private const val DIR = "conversations"

    /**
     * 侧边栏标题的最大长度。
     *
     * 取首条用户消息的前 20 字。太长侧边栏一行放不下，
     * 太短又分不清是哪个会话。
     */
    private const val TITLE_MAX = 20

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private var context: Context? = null

    // ────────────────────────── 初始化 ──────────────────────────

    fun init(ctx: Context) {
        context = ctx.applicationContext
        _conversations.value = loadAll()
        AppLog.i(TAG, "已加载 ${_conversations.value.size} 个会话")
    }

    private fun dir(): File? = context?.let {
        File(it.filesDir, DIR).apply { mkdirs() }
    }

    private fun fileOf(id: String): File? = dir()?.let { File(it, "$id.json") }

    /** 全量扫描并解析。按更新时间倒序 —— 最近聊的排最上面 */
    private fun loadAll(): List<Conversation> {
        val files = dir()?.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { parse(f.readText()) }
                .onFailure { AppLog.w(TAG, "解析会话失败：${f.name}", it) }
                .getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    // ────────────────────────── 增删改 ──────────────────────────

    fun create(title: String = ""): Conversation {
        val now = System.currentTimeMillis()
        val conv = Conversation(
            id = "c$now",
            title = title.ifBlank { "新对话" },
            messages = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        save(conv)
        AppLog.i(TAG, "新建会话 ${conv.id}")
        return conv
    }

    /**
     * 保存一个会话（整体覆盖写）。
     *
     * 没有做增量保存 —— 一条消息就重写整个文件。理由：
     * 会话撑死几百条消息、几百 KB，全量写的开销可以忽略；
     * 而增量写要维护"哪些是新加的"这个状态，出错概率高得多。
     * **简单可靠 > 省那点 IO**。
     */
    fun save(conv: Conversation) {
        val f = fileOf(conv.id) ?: return
        runCatching { f.writeText(toJson(conv).toString(2)) }
            .onFailure { AppLog.e(TAG, "写会话失败 ${conv.id}", it); return }

        _conversations.update { list ->
            (list.filterNot { it.id == conv.id } + conv).sortedByDescending { it.updatedAt }
        }
    }

    fun delete(id: String) {
        runCatching { fileOf(id)?.delete() }
        _conversations.update { list -> list.filterNot { it.id == id } }
        AppLog.i(TAG, "删除会话 $id")
    }

    /** 改标题（用户在侧边栏重命名） */
    fun rename(id: String, newTitle: String) {
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        save(conv.copy(title = newTitle.trim().ifBlank { conv.title }))
    }

    fun find(id: String): Conversation? = _conversations.value.firstOrNull { it.id == id }

    /**
     * 从首条用户消息生成标题。
     *
     * 为什么不问用户要标题：**用户刚发第一句话的时候，他想要的是
     * "赶紧看到回复"，不是"先给这个对话起个名字"**。自动取前几个字
     * 已经足够区分，想改随时能改。
     */
    fun titleFrom(text: String): String {
        val one = text.replace("\n", " ").trim()
        return if (one.length <= TITLE_MAX) one.ifBlank { "新对话" }
        else one.take(TITLE_MAX) + "…"
    }

    // ────────────────────── 导出 / 导入 ──────────────────────

    /**
     * 导出多个会话。
     *
     * 单个会话导出成一份单独的文件；多个会话打包成**一个 JSON 数组文件**。
     *
     * 为什么不做 zip：json 文件谁都能打开看，用户想手动编辑也行；
     * zip 还得解压，多一步。而且会话都是纯文本，压缩收益不大。
     *
     * @return 生成的文件列表（通常一个）
     */
    suspend fun export(ids: List<String>): List<File> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext emptyList()
        val picked = _conversations.value.filter { it.id in ids }
        if (picked.isEmpty()) return@withContext emptyList()

        val outDir = File(ctx.cacheDir, "export").apply {
            deleteRecursively()   // 清掉上次导出的，避免越积越多
            mkdirs()
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        if (picked.size == 1) {
            val c = picked.first()
            val f = File(outDir, "pocketnode_chat_${safeName(c.title)}_$stamp.json")
            f.writeText(toJson(c).toString(2))
            AppLog.i(TAG, "导出 1 个会话 → ${f.name}")
            listOf(f)
        } else {
            val arr = JSONArray()
            picked.forEach { arr.put(toJson(it)) }
            val f = File(outDir, "pocketnode_chats_${picked.size}_$stamp.json")
            f.writeText(arr.toString(2))
            AppLog.i(TAG, "导出 ${picked.size} 个会话 → ${f.name}")
            listOf(f)
        }
    }

    /**
     * 导出到用户选定的位置（系统文件选择器给的 URI）。
     *
     * 为什么不用"分享"（ACTION_SEND）：
     * 分享要 FileProvider + 临时授权，而且用户没法控制存到哪 ——
     * 微信发出去的文件过几天就失效了。
     *
     * 让他自己选位置（下载目录、网盘同步目录都行），文件就一直在那儿。
     */
    suspend fun exportTo(uri: android.net.Uri, ids: List<String>): Int = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext 0
        val picked = _conversations.value.filter { it.id in ids }
        if (picked.isEmpty()) return@withContext 0

        val text = if (picked.size == 1) {
            toJson(picked.first()).toString(2)
        } else {
            val arr = JSONArray()
            picked.forEach { arr.put(toJson(it)) }
            arr.toString(2)
        }

        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("无法写入选定的位置")

        AppLog.i(TAG, "导出 ${picked.size} 个会话到用户选定位置")
        picked.size
    }

    /**
     * 导入。
     *
     * 支持两种格式：
     *   1. 单个会话对象 `{...}`
     *   2. 会话数组 `[{...}, {...}]`
     *
     * 自动识别，用户从别人那里拿到单个文件或打包文件都能导。
     *
     * @return 成功导入的数量；抛异常表示格式不对
     */
    suspend fun import(uri: android.net.Uri): Int = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext 0
        val text = ctx.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: throw IllegalStateException("无法读取选中的文件")

        val parsed = runCatching {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { parse(it) }
                }
            } else {
                listOf(parse(JSONObject(trimmed)))
            }
        }.getOrElse {
            throw IllegalArgumentException("不是有效的聊天记录文件：${it.message}")
        }

        var n = 0
        parsed.forEach { conv ->
            // 重新分配 id 和时间戳里的冲突部分 —— 导入的会话可能和
            // 本地已有的重名。id 冲突会导致直接覆盖用户的现有对话。
            val fresh = conv.copy(
                id = "c${System.currentTimeMillis()}_$n",
                title = conv.title + "（导入）",
                updatedAt = System.currentTimeMillis(),
            )
            save(fresh)
            n++
        }
        AppLog.i(TAG, "导入了 $n 个会话")
        n
    }

    // ────────────────────── JSON 序列化 ──────────────────────

    private fun toJson(c: Conversation): JSONObject = JSONObject().apply {
        put("format", "pocketnode-chat")
        put("formatVersion", 1)
        put("id", c.id)
        put("title", c.title)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
        put(
            "messages",
            JSONArray().apply {
                c.messages.forEach { m ->
                    put(
                        JSONObject()
                            .put("role", m.role)
                            .put("content", m.content)
                            .put("at", m.at)
                            .put("error", m.error),
                    )
                }
            },
        )
    }

    private fun parse(text: String): Conversation = parse(JSONObject(text))

    private fun parse(o: JSONObject): Conversation {
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val msgs = (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            ChatMessage(
                role = m.optString("role", "user"),
                content = m.optString("content", ""),
                at = m.optLong("at", 0L),
                error = m.optBoolean("error", false),
            )
        }
        val created = o.optLong("createdAt", System.currentTimeMillis())
        return Conversation(
            id = o.optString("id", "c$created"),
            title = o.optString("title", "未命名").ifBlank { "未命名" },
            messages = msgs,
            createdAt = created,
            updatedAt = o.optLong("updatedAt", created),
        )
    }

    /** 文件名里不能有这些字符，替换掉 */
    private fun safeName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\s]"""), "_").take(24).ifBlank { "chat" }
}
