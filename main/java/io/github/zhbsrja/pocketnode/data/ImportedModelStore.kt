package io.github.zhbsrja.pocketnode.data

import android.content.Context
import io.github.zhbsrja.pocketnode.util.AppLog
import io.github.zhbsrja.pocketnode.util.ModelStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户自己导入的模型的持久化存储。
 *
 * 内置目录（ModelCatalog.builtIn）是写死在代码里的，导入的模型是运行期
 * 产生的，必须落到磁盘，否则重启就没了。
 *
 * 为什么用 SharedPreferences 存 JSON 而不是每条一个文件：
 * 数据量极小（几条到几十条），而且**总是整体读写** —— 不存在只改一条的场景。
 * 一个 key 存一个 JSON 数组，读写都是原子的，不会出现半截数据。
 * 上 Room 或者 DataStore 对这种量级是杀鸡用牛刀。
 */
object ImportedModelStore {

    private const val TAG = "ImportedModelStore"
    private const val PREFS = "pocketnode_imported"
    private const val KEY_LIST = "models"

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        _models.value = load()
        AppLog.i(TAG, "已加载 ${_models.value.size} 个导入的模型")
    }

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): List<ModelInfo> {
        val raw = prefs()?.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        }.onFailure {
            // 解析失败不能崩 —— 大不了当作没有导入过。
            // 但要留日志，不然用户会奇怪自己的模型去哪了。
            AppLog.e(TAG, "解析导入记录失败，按空处理", it)
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<ModelInfo>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs()?.edit()?.putString(KEY_LIST, arr.toString())?.apply()
        _models.value = list
        AppLog.i(TAG, "已保存 ${list.size} 个导入记录")
    }

    /**
     * 生成下一个默认名字：「模型 1」「模型 2」……
     *
     * 编号取「已有导入数量 + 1」，而不是找最大编号 +1 ——
     * 因为用户可能把模型删了，找最大编号会留下空号（模型1、模型3）。
     * 用户可能不喜欢重名，但重名不影响功能。
     */
    fun nextDefaultName(): String = "模型 ${_models.value.size + 1}"

    /**
     * 登记一个刚导入的模型。
     *
     * @param name 用户填的名字，空白则用默认名
     * @param note 用户填的简介，空白就是空
     */
    fun register(
        name: String,
        note: String,
        fileName: String,
        sizeBytes: Long,
    ): ModelInfo {
        val existing = _models.value
        val model = ModelInfo(
            id = "imported-${System.currentTimeMillis()}",
            name = name.trim().ifBlank { "模型 ${existing.size + 1}" },
            vendor = "本地导入",
            parameters = "—",
            quantization = "—",
            sizeBytes = sizeBytes,
            fileName = fileName,
            downloadUrl = "",              // 导入的模型没有下载地址
            minRamGb = 0,
            note = note.trim(),
            format = ModelFormat.MediaPipeTask,
            imported = true,
        )
        save(existing + model)
        AppLog.i(TAG, "已登记导入模型: ${model.name} (${model.id})")
        return model
    }

    /** 用户改名字或简介 */
    fun update(modelId: String, name: String, note: String) {
        val updated = _models.value.map {
            if (it.id == modelId) it.copy(name = name.trim(), note = note.trim()) else it
        }
        save(updated)
    }

    /** 从记录里移除（不删文件，文件由 DownloadManager 负责） */
    fun remove(modelId: String) {
        save(_models.value.filterNot { it.id == modelId })
    }

    /** 删掉所有指向已经不存在的文件的记录 */
    fun prune(context: Context) {
        val dir = ModelStorage.modelsDir(context)
        val alive = _models.value.filter { File(dir, it.fileName).exists() }
        if (alive.size != _models.value.size) {
            AppLog.w(TAG, "清理 ${_models.value.size - alive.size} 条失效记录")
            save(alive)
        }
    }

    // ── JSON 序列化 ──

    private fun toJson(m: ModelInfo): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("name", m.name)
        put("note", m.note)
        put("fileName", m.fileName)
        put("sizeBytes", m.sizeBytes)
    }

    private fun fromJson(o: JSONObject): ModelInfo = ModelInfo(
        id = o.getString("id"),
        name = o.getString("name"),
        vendor = "本地导入",
        parameters = "—",
        quantization = "—",
        sizeBytes = o.optLong("sizeBytes", 0L),
        fileName = o.getString("fileName"),
        downloadUrl = "",
        minRamGb = 0,
        note = o.optString("note", ""),
        format = ModelFormat.MediaPipeTask,
        imported = true,
    )
}
