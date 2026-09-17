package io.github.zhbsrja.pocketnode.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.zhbsrja.pocketnode.R
import io.github.zhbsrja.pocketnode.util.AppLang
import io.github.zhbsrja.pocketnode.util.LocaleStore
import io.github.zhbsrja.pocketnode.util.AppLog

/**
 * 设置页。
 *
 * 这一页**全是"跳出去让用户自己开"的入口**，不是开关。
 *
 * 为什么不做成开关：
 * 电池优化白名单和自启动权限都是**系统级权限，App 无权自己授予** ——
 * 只有两个例外（电池优化的系统弹窗可以请求），其余的厂商自启动管理
 * 连统一的 API 都没有，只能跳到各家自己的设置页。
 *
 * 所以这一页的定位是「导航 + 解释」，不是「控制」。副标题必须说清楚
 * 这一点，否则用户会以为点了按钮就开好了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 电池优化状态。系统回调不驱动 Compose，所以用生命周期观察者
    // 在回到前台时重读 —— 用户去设置里改完切回来，这里必须刷新。
    var ignoringBattery by remember { mutableStateOf(isIgnoringBatteryOptimization(context)) }
    val lang by LocaleStore.current.collectAsState()
    var langPickerOpen by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery = isIgnoringBatteryOptimization(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        // 外层 MainScaffold 已处理系统栏 insets，这里不能重复处理
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.settings_bg_header),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.settings_bg_desc_1),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            stringResource(R.string.settings_bg_desc_2),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            stringResource(R.string.settings_bg_desc_3),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            // ── ① 忽略电池优化 ──
            item {
                SettingItem(
                    title = stringResource(R.string.settings_battery_title),
                    subtitle = if (ignoringBattery) {
                        stringResource(R.string.settings_battery_on)
                    } else {
                        stringResource(R.string.settings_battery_off)
                    },
                    done = ignoringBattery,
                    buttonText = stringResource(if (ignoringBattery) R.string.settings_battery_btn_on else R.string.settings_battery_btn_off),
                    enabled = !ignoringBattery,
                    onAction = {
                        // 这个 Intent 会弹一个系统对话框直接问用户，比跳到列表页
                        // 少两步操作。需要清单里声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (!launch(context, intent)) {
                            // 部分 ROM 没有这个对话框，退回电池优化列表页
                            launch(
                                context,
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }

            // ── ② 自启动 ──
            item {
                SettingItem(
                    title = stringResource(R.string.settings_autostart_title),
                    subtitle = stringResource(R.string.settings_autostart_desc),
                    done = false,
                    buttonText = stringResource(R.string.settings_autostart_btn),
                    enabled = true,
                    onAction = {
                        // 有厂商配置就用厂商页；没有（或者启动失败）就直接跳系统设置。
                        // 不猜、不试近似页面 —— 跳错页面比跳设置页更让人困惑。
                        val oem = autostartIntent(context)
                        val ok = oem != null && launch(context, oem)
                        if (!ok) {
                            AppLog.w("SettingsScreen", "无厂商配置，跳系统设置")
                            launch(
                                context,
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }

            // ── ③ 语言 ──
            item {
                SettingItem(
                    title = stringResource(R.string.settings_language_title),
                    subtitle = stringResource(R.string.settings_language_desc),
                    done = false,
                    // 默认显示「系统语言（Auto）」。手动改过之后显示该语言
                    // **用自己语言写的名字** —— 用户看不懂当前界面语言时，
                    // 写 'English' 比写 '英语' 有用得多。
                    buttonText = LocaleStore.displayName(lang),
                    enabled = true,
                    onAction = { langPickerOpen = true },
                )
            }

            item {
                Text(
                    stringResource(R.string.settings_manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }

            // ── 说明 ──
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.settings_why_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_why_1),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_why_2),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }


    if (langPickerOpen) {
        AlertDialog(
            onDismissRequest = { langPickerOpen = false },
            title = { Text(stringResource(R.string.settings_language_title)) },
            text = {
                Column {
                    AppLang.entries.forEach { l ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    LocaleStore.set(context, l)
                                    langPickerOpen = false
                                    // 12 及以下没有系统级「每应用语言」，得自己重建
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                        (context as? android.app.Activity)?.recreate()
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                LocaleStore.displayName(l),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (l == lang) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { langPickerOpen = false }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun SettingItem(
    title: String,
    subtitle: String,
    done: Boolean,
    buttonText: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (done) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAction, enabled = enabled) { Text(buttonText) }
        }
    }
}

// ────────────────────────── 系统能力探测 ──────────────────────────

/**
 * 是否已在电池优化白名单里。
 *
 * Android 6（API 23）才有这个概念，更早的版本没有电池优化，直接算已忽略。
 */
private fun isIgnoringBatteryOptimization(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

/**
 * 找厂商的自启动管理页。
 *
 * **没有标准 API。** 这是 Android 生态的一个历史遗留问题：各家 ROM
 * 都自己加了一套后台管理，互不兼容，Google 也没管。只能硬编码组件名一个个试。
 *
 * 做法是：先试厂商自己的 Activity，不行就返回一个应用详情页的 Intent
 * 兜底。用户至少能自己找进去，而不是点了没反应。
 */
/**
 * 厂商自启动管理页组件名表。
 *
 * ── 为什么必须硬编码 ────────────────────────────────────────────
 * Android **没有**自启动权限的标准 API。各家 ROM 自己加了一套后台管理，
 * 包名和 Activity 名互不相同，Google 从来没管过这件事。
 *
 * 所以只能穷举。这份表是社区攒出来的，覆盖国内主流厂商 + 部分海外品牌。
 *
 * ── 匹配规则 ────────────────────────────────────────────────────
 * 按 Build.MANUFACTURER + Build.BRAND 做关键字匹配（都转小写）。
 * **匹配不到就返回 null，调用方直接跳系统设置页。**
 * 不做"猜一个可能相关的页面"这种事 —— 跳错页面比跳设置页更让人困惑。
 *
 * ── 维护提示 ────────────────────────────────────────────────────
 * 厂商改版本时这些组件名会变。表现是"点了没反应"（其实是被我们
 * 的 resolveActivity 挡住了，退回设置页）。遇到就更新这一行。
 */
private val AUTOSTART_TABLE: List<Pair<List<String>, Pair<String, String>>> = listOf(

    // ── 小米 / 红米 / POCO（MIUI、HyperOS）──
    listOf("xiaomi", "redmi", "poco") to
        ("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    listOf("xiaomi", "redmi") to
        ("com.miui.securitycenter" to "com.miui.powercenter.PowerSettings"),

    // ── 华为 / 荣耀 ──
    listOf("huawei") to
        ("com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    listOf("huawei") to
        ("com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    listOf("honor") to
        ("com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    listOf("honor") to
        ("com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),

    // ── OPPO / 一加 / realme ──
    listOf("oppo", "realme") to
        ("com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
    listOf("oppo", "realme") to
        ("com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity"),
    listOf("oppo") to
        ("com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity"),
    listOf("oneplus") to
        ("com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),

    // ── vivo / iQOO ──
    listOf("vivo", "iqoo") to
        ("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
    listOf("vivo", "iqoo") to
        ("com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
    listOf("vivo") to
        ("com.vivo.abe" to "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),

    // ── 魅族（Flyme）──
    listOf("meizu") to
        ("com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC"),
    listOf("meizu") to
        ("com.meizu.safe" to "com.meizu.safe.permission.PermissionMainActivity"),
    listOf("meizu") to
        ("com.meizu.safe" to "com.meizu.safe.powerui.PowerAppPermissionActivity"),

    // ── 努比亚 / 红魔 ──
    listOf("nubia") to
        ("cn.nubia.security2" to "cn.nubia.security2.activity.AutoStartManageActivity"),
    listOf("nubia") to
        ("cn.nubia.secure" to "cn.nubia.secure.activity.AutoStartManageActivity"),
    listOf("nubia") to
        ("com.zte.heartyservice" to "com.zte.heartyservice.setting.ClearAppSettingsActivity"),

    // ── 中兴（ZTE）──
    listOf("zte") to
        ("com.zte.powersave" to "com.zte.powersave.activity.AutoStartActivity"),
    listOf("zte") to
        ("com.zte.heartyservice" to "com.zte.heartyservice.setting.ClearAppSettingsActivity"),

    // ── 三星 ──
    listOf("samsung") to
        ("com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"),
    listOf("samsung") to
        ("com.samsung.android.sm" to "com.samsung.android.sm.ui.battery.BatteryActivity"),

    // ── 联想 ──
    listOf("lenovo") to
        ("com.lenovo.security" to "com.lenovo.security.purebackground.PureBackgroundActivity"),
    listOf("lenovo") to
        ("com.lenovo.powersetting" to "com.lenovo.powersetting.ui.PowerSettingActivity"),

    // ── 华硕 ──
    listOf("asus") to
        ("com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"),
    listOf("asus") to
        ("com.asus.mobilemanager" to "com.asus.mobilemanager.powersaver.PowerSaverSettings"),

    // ── 金立 ──
    listOf("gionee") to
        ("com.gionee.softmanager" to "com.gionee.softmanager.memoryclean.PowerActivity"),

    // ── 乐视 ──
    listOf("letv") to
        ("com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity"),

    // ── 锤子（Smartisan）──
    listOf("smartisan") to
        ("com.smartisanos.security" to "com.smartisanos.security.activity.PermissionActivity"),

    // ── 黑鲨 ──
    listOf("blackshark") to
        ("com.blackshark.security" to "com.blackshark.security.activity.AutoStartActivity"),

    // ── 酷派 ──
    listOf("coolpad") to
        ("com.yulong.android.coolmart" to "com.yulong.android.coolmart.activity.AutoStartActivity"),

    // ── 传音（Tecno / Infinix / itel，非洲和东南亚多）──
    listOf("tecno", "infinix", "itel", "transsion") to
        ("com.transsion.phonemaster" to "com.itelephone.phonemaster.phoneclean.AutoStartActivity"),
    listOf("tecno", "infinix", "itel") to
        ("com.transsion.phonemaster" to "com.transsion.phonemaster.AutoStartActivity"),

    // ── 诺基亚（HMD）──
    listOf("nokia", "hmd") to
        ("com.evenwell.powersaving.g3" to "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"),

    // ── LG ──
    listOf("lge", "lg") to
        ("com.lge.powerapp" to "com.lge.powerapp.activity.MainActivity"),
)

/**
 * 找当前机型对应的自启动管理页。
 *
 * 返回 null 表示**没有这个厂商的配置** —— 调用方应该直接跳系统设置页。
 * 不猜、不凑合，避免把用户送到一个莫名其妙的页面。
 */
private fun autostartIntent(ctx: Context): Intent? {
    val identity = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    AppLog.i("SettingsScreen", "机型识别: MANUFACTURER=${Build.MANUFACTURER} BRAND=${Build.BRAND} MODEL=${Build.MODEL}")

    for ((keywords, component) in AUTOSTART_TABLE) {
        if (keywords.none { identity.contains(it) }) continue
        val i = Intent().apply {
            this.component = ComponentName(component.first, component.second)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 只解析不启动：需要选出"第一个真能用的"，
        // 真启动失败会抛异常，那就没法继续试下一个了。
        if (ctx.packageManager.resolveActivity(i, 0) != null) {
            AppLog.i("SettingsScreen", "命中自启动页: ${component.first}/${component.second}")
            return i
        }
        AppLog.w("SettingsScreen", "组件不存在: ${component.second}")
    }

    AppLog.w("SettingsScreen", "没有匹配 $identity 的自启动页配置")
    return null
}

/** 尝试启动，成功返回 true。失败不抛异常 —— 设置页点了没反应比崩溃好 */
private fun launch(ctx: Context, intent: Intent): Boolean = runCatching {
    ctx.startActivity(intent)
}.onFailure {
    AppLog.w("SettingsScreen", "启动失败: ${intent.component ?: intent.action}", it)
}.isSuccess
