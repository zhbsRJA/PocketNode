package io.github.zhbsrja.pocketnode.navigation

import io.github.zhbsrja.pocketnode.R

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航的四个入口。
 *
 * 为什么用 sealed class 而不是一堆字符串常量：
 * 路由名、标题、图标绑在同一个对象上，加页面时编译器会逼你把所有 when 分支补全，
 * 不会出现「加了个页面但忘了加导航项」这种问题。
 *
 * 图标用的是 Compose 自带的核心图标集（material-icons-core），
 * 没有引入 material-icons-extended —— 那个库已经停止更新（停在 1.7.x），
 * 跟新版 Compose 搭配有兼容风险。等界面定下来再换成自定义矢量图。
 */
sealed class Destination(
    val route: String,
    /**
     * 标题的字符串资源 ID，不是字符串本身。
     *
     * 写成资源 ID 才能跟着语言设置变 —— 硬编码 Kotlin 字符串的话，
     * 用户切成英文后底边栏还是中文（顶栏却变了），看起来像坏了。
     */
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    /** 对话：会话列表 + 聊天界面 */
    data object Chat : Destination(
        route = "chat",
        labelRes = R.string.nav_chat,
        icon = Icons.AutoMirrored.Filled.List,
    )

    /** 模型：本地模型的下载、选择、加载状态 */
    data object Models : Destination(
        route = "models",
        labelRes = R.string.nav_models,
        icon = Icons.Default.Build,
    )

    /** 服务：中转服务开关、Key 管理、用量统计 */
    data object Service : Destination(
        route = "service",
        labelRes = R.string.nav_service,
        // 原来是 Settings 图标，加设置页之后让给它了。
        // Share 表示"把能力分享出去"，比齿轮更贴这页的语义。
        icon = Icons.Default.Share,
    )

    /** 设置：系统权限引导、后台保活 */
    data object Settings : Destination(
        route = "settings",
        labelRes = R.string.nav_settings,
        icon = Icons.Default.Settings,
    )

    companion object {
        /** 底部导航栏按这个顺序渲染 */
        val bottomBar: List<Destination> = listOf(Chat, Models, Service, Settings)

        /** 冷启动落在哪一页 */
        val start: Destination = Chat
    }
}
