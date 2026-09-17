package io.github.zhbsrja.pocketnode.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.zhbsrja.pocketnode.navigation.AppNavHost
import io.github.zhbsrja.pocketnode.navigation.Destination

/**
 * App 主框架：底部导航栏 + 内容区。
 *
 * 三个标签页的切换语义是「平级切换」，不是「层层深入」，所以用
 * popUpTo(start) + saveState + restoreState 这套组合：
 *   - 反复点同一个标签不会往返回栈里塞重复项（launchSingleTop）
 *   - 切走再切回来，页面滚动位置之类的状态还在（restoreState）
 */
@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.bottomBar.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navController.switchTab(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 关键：告诉子树「系统栏 insets 已经由这层消费掉了」。
                // 不写这行的话，下面每个页面自己的 Scaffold 会**再应用一次**
                // insets —— 手机竖屏还看不出来，平板横屏高度本来就小，
                // 扣两遍之后内容区可能直接归零，表现就是「完全划不了」。
                .consumeWindowInsets(innerPadding),
        )
    }
}

/** 切换底部标签页：复用已有实例、丢弃已建的中间层级、记住各页状态 */
private fun NavHostController.switchTab(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
