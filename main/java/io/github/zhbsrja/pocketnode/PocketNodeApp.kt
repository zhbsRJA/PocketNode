package io.github.zhbsrja.pocketnode

import android.app.Application
import io.github.zhbsrja.pocketnode.data.DownloadManager
import io.github.zhbsrja.pocketnode.data.ImportedModelStore
import io.github.zhbsrja.pocketnode.agent.AgentRunner
import io.github.zhbsrja.pocketnode.agent.DeviceController
import io.github.zhbsrja.pocketnode.server.ApiKeyStore
import io.github.zhbsrja.pocketnode.server.ServerManager
import io.github.zhbsrja.pocketnode.util.AppLog
import io.github.zhbsrja.pocketnode.util.LocaleStore
import io.github.zhbsrja.pocketnode.util.ModelStorage

/**
 * 应用入口。
 *
 * 只做基础组件的初始化，不放业务逻辑。
 *
 * 为什么日志第一个初始化：它是「一出事就得有」的东西，
 * 后面任何一步失败都要靠它留证据。顺序在前的组件如果自己崩了，
 * 日志也还能记下来。
 */
class PocketNodeApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. 日志最先 —— 后面所有初始化失败都能被记录
        AppLog.init(this)
        AppLog.i("PocketNodeApp", "════ Application 启动 ════")

        // 2. 语言。排在所有 UI 相关初始化之前 —— 界面一旦按错的语言
        //    渲染出来，后面再切会导致闪一下，很难看。
        runCatching { LocaleStore.init(this) }
            .onFailure { AppLog.e("PocketNodeApp", "LocaleStore 初始化失败", it) }

        // 2. 导入记录。
        //    必须排在 DownloadManager 前面：DownloadManager.init 会扫描
        //    本地文件来标记模型状态，而它扫的是 ModelCatalog.all ——
        //    这个列表只有在导入记录加载完之后才是完整的。
        //    顺序反了的话，用户导入的模型第一次进页面会不显示，
        //    要等下次启动才冒出来。
        runCatching { ImportedModelStore.init(this) }
            .onFailure { AppLog.e("PocketNodeApp", "ImportedModelStore 初始化失败", it) }

        // 3. 把私有目录里已有的模型搬到 /storage/emulated/0/PNAI/。
        //    必须排在 DownloadManager 扫描之前 —— 否则文件已经在新目录了，
        //    但扫描还去旧目录找，界面上会显示成「未下载」，用户以为模型丢了。
        //    没授权时这个函数直接返回，不做任何事。
        runCatching { ModelStorage.migrateFromPrivate(this) }
            .onFailure { AppLog.e("PocketNodeApp", "模型迁移失败", it) }

        // 4. 下载器（会扫描本地已有的模型文件）
        runCatching { DownloadManager.init(this) }
            .onFailure { AppLog.e("PocketNodeApp", "DownloadManager 初始化失败", it) }

        // 5. 中转服务。key 和端口都要持久化，所以启动时加载。
        runCatching { ApiKeyStore.init(this) }
            .onFailure { AppLog.e("PocketNodeApp", "ApiKeyStore 初始化失败", it) }
        runCatching { ServerManager.init(this) }
            .onFailure { AppLog.e("PocketNodeApp", "ServerManager 初始化失败", it) }

        // 6. 设备控制闸门。默认关闭 —— 只有用户主动开过才为 true。
        runCatching { DeviceController.init(this) }
            .onFailure { AppLog.e("PocketNodeApp", "DeviceController 初始化失败", it) }
        runCatching { AgentRunner.init(this) }
            .onFailure { AppLog.e("PocketNodeApp", "AgentRunner 初始化失败", it) }
        AppLog.i(
            "PocketNodeApp",
            "智能体控制设备 = ${if (DeviceController.enabled.value) "已开启" else "已关闭"}，" +
                "无障碍服务 = ${if (DeviceController.isServiceConnected()) "已连接" else "未连接"}",
        )

        AppLog.i("PocketNodeApp", "模型目录: ${ModelStorage.modelsDir(this).absolutePath}")
        AppLog.i("PocketNodeApp", "初始化完成")
    }
}
