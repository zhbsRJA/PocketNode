package io.github.zhbsrja.pocketnode.server

import android.content.Context
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 一个 API Key。
 *
 * @param id        内部标识，和 value 分开。value 可以改，id 不变，
 *                  这样界面上正在编辑的那条不会因为改了值就找不到
 * @param value     实际的 key 字符串，客户端填的就是它
 * @param label     备注。多把 key 的时候必须能区分谁是谁
 * @param createdAt 创建时间
 * @param lastUsedAt 最后一次使用时间。用来判断哪把 key 已经没人用了，可以删
 */
data class ApiKey(
    val id: String,
    val value: String,
    val label: String,
    val createdAt: Long,
    val lastUsedAt: Long = 0L,
) {
    /** 打码展示。日志和界面默认都用这个，不要到处打完整 key */
    val masked: String
        get() = if (value.length <= 12) "***" else value.take(10) + "…" + value.takeLast(4)
}

/**
 * API Key 管理。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么支持多把 key + 自定义值：
 *
 * **自定义值解决的是「重装后服务中断」的问题。**
 * 原来 key 是安装时随机生成的，存 App 私有目录。卸载重装 → key 变了 →
 * 所有已经配置好的客户端全部 401，得挨个改。
 *
 * 允许用户手填之后，重装后再建一个一模一样的，对面完全无感。
 *
 * **多把 key 解决的是「分不清谁在用」的问题。**
 * 一台设备一把，出问题了能单独撤销某一把，不影响其他设备。
 * ══════════════════════════════════════════════════════════════
 *
 * ── 安全边界（必须写清楚，别骗自己）──────────────────────────
 * 这是局域网接口，Key 的作用是「防误触 + 区分设备」，**不是加密手段**。
 * 明文 HTTP 下 key 在链路上是裸奔的 —— 抓包就能拿走。
 * 开放公网必须配 TLS 隧道（Tailscale / Cloudflare Tunnel）。
 *
 * 正因为如此，这里**不自作聪明地限制 key 的格式**：
 * 用户想填什么就填什么（只要长度够）。安全靠的是网络层和「别暴露出去」，
 * 不是靠 key 看起来够随机。
 */
object ApiKeyStore {

    private const val TAG = "ApiKeyStore"
    private const val PREFS = "pocketnode_server"
    private const val KEY_LIST = "api_keys"
    private const val LEGACY_KEY = "api_key"

    /** 前缀。让客户端一眼认出来这是谁的 key，也方便在访问日志里检索 */
    const val PREFIX = "sk-pn-"

    /**
     * 最短长度。
     *
     * 12 位。这个数字是取舍的结果：
     *   · 太短（比如 4 位）会被随手猜到 —— 同一个 WiFi 下有人扫端口的话
     *   · 太长会劝退用户自己编 —— 而这个功能的重点就是「让用户自己定」
     * 12 位十六进制 ≈ 2^48 种可能，局域网范围内暴力猜不现实。
     */
    const val MIN_LENGTH = 12

    /** 长度上限。防止有人粘进去一整篇文章 */
    private const val MAX_LENGTH = 200

    private val _keys = MutableStateFlow<List<ApiKey>>(emptyList())
    val keys: StateFlow<List<ApiKey>> = _keys.asStateFlow()

    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        _keys.value = load()

        // 迁移老数据：上一个版本只存了一把 key（KEY_LIST 不存在时读 LEGACY_KEY）。
        // 不迁移的话，老用户升级完发现 key 没了，所有客户端断连。
        if (_keys.value.isEmpty()) {
            val legacy = prefs()?.getString(LEGACY_KEY, null)
            if (!legacy.isNullOrBlank()) {
                AppLog.i(TAG, "发现旧版单 key，迁移成列表")
                save(listOf(newKey(legacy, "默认（从旧版迁移）")))
                prefs()?.edit()?.remove(LEGACY_KEY)?.apply()
            } else {
                AppLog.i(TAG, "没有 key，生成一把默认的")
                save(listOf(newKey(generate(), "默认")))
            }
        }
        AppLog.i(TAG, "已加载 ${_keys.value.size} 把 key")
    }

    // ────────────────────────── 读写 ──────────────────────────

    /** 新建一把。name 为空则用默认名 */
    fun add(value: String, label: String): Result<ApiKey> {
        val v = value.trim()
        validate(v)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (_keys.value.any { it.value == v }) {
            return Result.failure(IllegalArgumentException("这个 key 已经存在了"))
        }
        val key = newKey(v, label.trim().ifBlank { "Key ${_keys.value.size + 1}" })
        save(_keys.value + key)
        AppLog.i(TAG, "新建 key: ${key.label} (${key.masked})")
        return Result.success(key)
    }

    /** 改一把 key 的值或备注 */
    fun update(id: String, value: String, label: String): Result<Unit> {
        val v = value.trim()
        validate(v)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (_keys.value.any { it.id != id && it.value == v }) {
            return Result.failure(IllegalArgumentException("这个 key 和另一把重复了"))
        }
        val old = _keys.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("找不到这把 key"))
        save(_keys.value.map {
            if (it.id == id) it.copy(value = v, label = label.trim().ifBlank { it.label }) else it
        })
        AppLog.i(TAG, "修改 key: ${old.label} → ${label}")
        return Result.success(Unit)
    }

    fun remove(id: String) {
        val k = _keys.value.firstOrNull { it.id == id } ?: return
        // 不允许把最后一把删掉 —— 删完之后接口就没人能调了，
        // 而且界面上会出现"没有 key 但地址还在"的怪状态。
        if (_keys.value.size <= 1) {
            AppLog.w(TAG, "拒绝删除：至少要保留一把 key")
            return
        }
        save(_keys.value.filterNot { it.id == id })
        AppLog.w(TAG, "删除 key: ${k.label} (${k.masked})")
    }

    /**
     * 校验请求携带的 key。
     *
     * 遍历所有 key 比较。用 MessageDigest.isEqual 做定长时间比较 ——
     * 避免因为前缀匹配长度不同而提前返回。局域网场景下时序攻击不现实，
     * 但用错的方法写认证是坏习惯，而换成定长比较的成本是零。
     */
    fun isValid(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val list = _keys.value
        if (list.isEmpty()) return false
        val c = candidate.toByteArray(Charsets.UTF_8)
        val hit = list.firstOrNull { MessageDigest.isEqual(c, it.value.toByteArray(Charsets.UTF_8)) }
        if (hit != null) {
            // 记一下最后使用时间，界面能看出哪把 key 已经没人用了
            touch(hit.id)
            return true
        }
        return false
    }

    private var touchCounter = 0
    private fun touch(id: String) {
        // 每次请求都写磁盘太重，攒够 20 次写一次。
        // 这个数据只用来在界面上做参考，不精确没关系。
        if (++touchCounter % 20 != 0) {
            _keys.value = _keys.value.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it }
            return
        }
        save(_keys.value.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it })
    }

    /** 随机生成一个建议值，界面上「随机生成」按钮用 */
    fun generate(): String {
        val buf = ByteArray(24)
        SecureRandom().nextBytes(buf)
        return PREFIX + buf.joinToString("") { "%02x".format(it) }
    }

    fun mask(key: String): String =
        if (key.length <= 12) "***" else key.take(10) + "…" + key.takeLast(4)

    // ────────────────────────── 内部 ──────────────────────────

    /**
     * 格式校验。返回 null 表示通过，否则是错误说明。
     *
     * **故意不限制字符集。** 用户可以填 `我的电脑2026`，也可以填
     * 标准随机串。安全性不靠 key 的复杂度 —— 见类注释里的边界说明。
     * 只拦两种情况：太短（容易被猜）和含空白（会破坏 HTTP 头）。
     */
    private fun validate(v: String): String? = when {
        v.length < MIN_LENGTH -> "太短了，至少要 $MIN_LENGTH 位"
        v.length > MAX_LENGTH -> "太长了，最多 $MAX_LENGTH 位"
        v.any { it.isWhitespace() } -> "不能包含空格或换行"
        else -> null
    }

    private fun newKey(value: String, label: String) = ApiKey(
        id = "k" + System.currentTimeMillis() + (1000..9999).random(),
        value = value,
        label = label,
        createdAt = System.currentTimeMillis(),
    )

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): List<ApiKey> {
        val raw = prefs()?.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ApiKey(
                    id = o.getString("id"),
                    value = o.getString("value"),
                    label = o.optString("label", "Key"),
                    createdAt = o.optLong("createdAt", 0L),
                    lastUsedAt = o.optLong("lastUsedAt", 0L),
                )
            }
        }.onFailure {
            AppLog.e(TAG, "解析 key 列表失败，按空处理", it)
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<ApiKey>) {
        val arr = JSONArray()
        list.forEach { k ->
            arr.put(
                JSONObject()
                    .put("id", k.id)
                    .put("value", k.value)
                    .put("label", k.label)
                    .put("createdAt", k.createdAt)
                    .put("lastUsedAt", k.lastUsedAt),
            )
        }
        prefs()?.edit()?.putString(KEY_LIST, arr.toString())?.apply()
        _keys.value = list
    }
}
