package io.github.zhbsrja.pocketnode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.zhbsrja.pocketnode.ui.MainScaffold
import io.github.zhbsrja.pocketnode.ui.theme.PocketNodeTheme
import io.github.zhbsrja.pocketnode.util.AppLog

/**
 * 应用入口。
 *
 * 除了挂界面，还负责一件容易被忽略的事：**申请通知权限**。
 *
 * 为什么这件事必须在入口做：
 * 后台下载靠前台服务保活，而前台服务必须挂一条常驻通知。Android 13 起
 * 通知变成了运行时权限（POST_NOTIFICATIONS），**没授权的话前台服务
 * 起不来** —— 表现是「点了下载，切后台就停」。这个坑很隐蔽，
 * 因为不授权也不会崩，只是功能静默失效。
 */
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("MainActivity", "通知权限申请结果: $granted")
            if (!granted) {
                AppLog.w("MainActivity", "用户拒绝通知权限 —— 后台下载将无法保活")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        askNotificationPermissionIfNeeded()

        setContent {
            PocketNodeTheme {
                MainScaffold()
            }
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        // Android 13（API 33）以下不需要运行时申请，装了就有
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AppLog.i("MainActivity", "API ${Build.VERSION.SDK_INT} < 33，无需申请通知权限")
            return
        }

        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        if (granted) {
            AppLog.i("MainActivity", "通知权限已授予")
        } else {
            AppLog.i("MainActivity", "申请通知权限")
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
