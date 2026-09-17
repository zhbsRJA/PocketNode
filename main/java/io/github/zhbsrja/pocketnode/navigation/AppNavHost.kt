package io.github.zhbsrja.pocketnode.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.zhbsrja.pocketnode.ui.chat.ChatScreen
import io.github.zhbsrja.pocketnode.ui.models.ModelsScreen
import io.github.zhbsrja.pocketnode.ui.service.ServiceScreen
import io.github.zhbsrja.pocketnode.ui.settings.SettingsScreen

/**
 * 页面容器。
 *
 * 现在只有底部导航的三个顶层页面。以后聊天详情、模型详情这类「二级页面」
 * 也挂这里，但它们不带底部导航栏 —— 那种情况通常要再套一层 NavHost，
 * 等真需要的时候再拆。
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.start.route,
        modifier = modifier,
    ) {
        composable(Destination.Chat.route) { ChatScreen() }
        composable(Destination.Models.route) { ModelsScreen() }
        composable(Destination.Service.route) { ServiceScreen() }
        composable(Destination.Settings.route) { SettingsScreen() }
    }
}
