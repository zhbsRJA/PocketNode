package io.github.zhbsrja.pocketnode.data

import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** 一条搜索结果 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

/**
 * 联网搜索。
 *
 * ══════════════════════════════════════════════════════════════
 * ── 为什么走自己的服务器中转，不直接调搜索 API ──────────────────
 *
 * 1. **大陆直连不了 Google / DuckDuckGo**。Bing 能连但结果质量一般，
 *    而且 HTML 结构经常变，客户端解析随时会坏。
 * 2. **API key 不能放在客户端**。Brave / Serper 这些都要 key，
 *    打进 APK 等于公开 —— 反编译就拿到了，别人拿去刷你的额度。
 * 3. **服务器在境外，直连搜索服务没有障碍**，而且解析逻辑放服务端
 *    改起来不用发版（配合远程配置，连 URL 都能改）。
 *
 * 代价是搜索这一步依赖你的服务器。服务器挂了搜索就用不了 ——
 * 但**不搜索时聊天完全不受影响**，这是可接受的降级。
 * ══════════════════════════════════════════════════════════════
 *
 * ── 和模型的关系 ────────────────────────────────────────────────
 *
 * **不是让模型自己决定要不要搜**，而是用户手动开开关。
 *
 * 原因：手机上跑的是 0.5B–3B 的小模型，**做不了可靠的工具调用**
 * （function calling）。让它自己判断"这个问题要不要搜"，
 * 它十次里有五次会判断错，而且错的方式很难预测。
 *
 * 做成手动开关，行为完全可预期：开了就搜，搜完把结果塞进 prompt。
 */
object SearchClient {

    private const val TAG = "SearchClient"

    /** 搜索接口。走自己的服务器中转 */
    private const val ENDPOINT = "http://154.40.44.75:8899/search"

    private const val TIMEOUT_MS = 10_000

    /**
     * 搜索。
     *
     * @return 结果列表。失败返回 null（和"搜到 0 条"区分开 ——
     *         前者说明搜索功能坏了，后者说明确实没结果）
     */
    suspend fun search(query: String, max: Int = 5): List<SearchResult>? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val url = URL("$ENDPOINT?q=${URLEncoder.encode(query, "UTF-8")}&n=$max")
            AppLog.i(TAG, "搜索：$query")

            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }

            val code = conn.responseCode
            if (code != 200) {
                AppLog.w(TAG, "搜索接口返回 $code")
                conn.disconnect()
                return@withContext null
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val arr = JSONArray(text)
            val out = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SearchResult(
                    title = o.optString("title", "").trim(),
                    url = o.optString("url", "").trim(),
                    snippet = o.optString("snippet", "").trim(),
                )
            }.filter { it.title.isNotBlank() || it.snippet.isNotBlank() }

            AppLog.i(TAG, "搜索到 ${out.size} 条")
            out
        } catch (t: Throwable) {
            AppLog.w(TAG, "搜索失败：${t.message}")
            null
        }
    }

    /**
     * 把搜索结果拼成给模型看的文本。
     *
     * ── 截断很狠，是故意的 ────────────────────────────────────────
     * 小模型的上下文只有几千 token。五条搜索结果每条约 200 字就吃掉
     * 1000 字，再加上屏幕内容/对话历史，很容易爆掉 —— 爆掉的表现
     * 不是报错，是模型开始胡言乱语或者直接截断。
     *
     * 所以每条摘录只留 180 字、总共最多 5 条。**宁可信息少，
     * 也不要让上下文溢出**。
     */
    fun formatForPrompt(results: List<SearchResult>): String {
        if (results.isEmpty()) return ""
        return buildString {
            appendLine("以下是联网搜索到的资料：")
            results.forEachIndexed { i, r ->
                appendLine("[${i + 1}] ${r.title}")
                if (r.snippet.isNotBlank()) {
                    appendLine("    ${r.snippet.take(180)}")
                }
            }
            appendLine()
            appendLine("请优先依据以上资料回答。如果资料不够，直接说明资料不足，不要编造。")
        }
    }
}
