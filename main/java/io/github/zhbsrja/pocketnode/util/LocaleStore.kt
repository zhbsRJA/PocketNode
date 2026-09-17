package io.github.zhbsrja.pocketnode.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 应用内可选语言。
 *
 * @param tag        BCP-47 语言标签。AUTO 用空串表示"跟随系统"
 * @param selfName   用**该语言自己**写的名字。写菜单时就用这个 ——
 *                   用户看不懂英文界面的时候，你给他看 "Chinese" 是没用的，
 *                   得写 "简体中文"。这是惯例。
 */
enum class AppLang(val tag: String, val selfName: String) {
    AUTO("", "系统语言（Auto）"),
    ZH("zh-CN", "简体中文"),
    EN("en", "English"),
}

/**
 * 语言设置。
 *
 * ── 两套机制，必须分开处理 ──────────────────────────────────────
 *
 * **Android 13（API 33）及以上**：系统提供了「每应用语言」的官方支持。
 * 调 `LocaleManager.setApplicationLocales()` 就完事，**系统会自己重建
 * Activity 并让所有资源按新语言加载**，不需要我们做任何事。
 *
 * **Android 12 及以下**：没这个东西。只能自己来 ——
 * 把语言存下来，在 `Activity.attachBaseContext()` 里用
 * `createConfigurationContext()` 包一层 Context，
 * 这样该 Activity 内取到的字符串资源就是对应语言的。
 * 然后手动 `recreate()` 让界面刷新。
 *
 * 两条路都必须走，因为 minSdk 是 28。
 *
 * ── 为什么默认是 AUTO 而不是某个具体语言 ─────────────────────────
 * 用户手机是英文的多半想用英文，是中文的用中文。跟着系统走是唯一
 * 不会一上来就冒犯人的默认值。用户手动改过之后才记具体语言。
 */
object LocaleStore {

    private const val TAG = "LocaleStore"
    private const val PREFS = "pocketnode_locale"
    private const val KEY = "lang"

    private val _current = MutableStateFlow(AppLang.AUTO)
    val current: StateFlow<AppLang> = _current.asStateFlow()

    private var appContext: Context? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        val saved = prefs()?.getString(KEY, null)
        _current.value = AppLang.entries.firstOrNull { it.tag == saved } ?: AppLang.AUTO
        AppLog.i(TAG, "语言设置: ${_current.value.name} (tag='${_current.value.tag}')")

        // 启动时重新应用一遍。
        //
        // 为什么必须做：用户在系统里"清除数据"或重装应用后，
        // **系统会清掉 per-app locale**，但我们自己的偏好还在。
        // 这时如果只读不应用，就会出现"设置里写着 English，界面却是中文"
        // 这种自相矛盾的状态 —— 用户会觉得设置没保存。
        //
        // onFailure 不崩：某些 ROM 的 LocaleManager 会抛，退化成"下次手动切"。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && _current.value != AppLang.AUTO) {
            runCatching {
                ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags(_current.value.tag)
            }.onFailure { AppLog.w(TAG, "重新应用语言失败", it) }
        }
    }

    /**
     * 改语言。
     *
     * 33+ 走系统 API，系统自己重建界面；
     * 12- 只能存下来，由调用方 recreate() 生效。
     */
    fun set(ctx: Context, lang: AppLang) {
        _current.value = lang
        prefs()?.edit()?.putString(KEY, lang.tag)?.apply()
        AppLog.i(TAG, "切换语言 → ${lang.name}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val lm = ctx.getSystemService(LocaleManager::class.java)
                lm.applicationLocales =
                    if (lang.tag.isEmpty()) LocaleList.getEmptyLocaleList()
                    else LocaleList.forLanguageTags(lang.tag)
            }.onFailure { AppLog.e(TAG, "设置每应用语言失败", it) }
        }
        // 12 及以下由调用方 recreate()
    }

    /**
     * 12 及以下用：把语言塞进 Configuration，包出一个新的 Context。
     *
     * 在 Activity.attachBaseContext() 里调用，这样该 Activity 拿到的一切
     * 资源（包括 strings.xml）都是对应语言的。
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val lang = _current.value
        if (lang == AppLang.AUTO) return base

        val locale = Locale.forLanguageTag(lang.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(config)
    }

    /** 菜单里显示的文字。AUTO 显示"系统语言（Auto）"，其余显示该语言自己的名字 */
    fun displayName(lang: AppLang): String = lang.selfName

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
