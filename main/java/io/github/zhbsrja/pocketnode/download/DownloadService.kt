package io.github.zhbsrja.pocketnode.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.zhbsrja.pocketnode.MainActivity
import io.github.zhbsrja.pocketnode.R
import io.github.zhbsrja.pocketnode.data.DownloadManager
import io.github.zhbsrja.pocketnode.data.DownloadState
import io.github.zhbsrja.pocketnode.data.ModelCatalog
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 下载保活的前台服务。
 *
 * 为什么必须有这个东西：
 *
 * Android 从 8.0 起严格限制后台进程。App 一进后台，系统几秒内就会冻结它，
 * 模型下载（几百 MB、要几分钟到几十分钟）必然被掐断。
 * 想让下载在息屏、切 App 之后继续跑，**唯一合规的办法就是前台服务** ——
 * 代价是必须挂一条常驻通知，让用户知道"这东西在后台干活"。
 * 这是 Android 的规矩，绕不过去。
 *
 * 实现上有个重要取舍：**服务里不跑下载逻辑本身**。
 * 下载还在 DownloadManager 那个进程内的协程里跑，这个服务只负责
 * 「把进程钉住别被杀」+「把进度画到通知上」。
 *
 * 这么做的理由：
 *   - 不用把状态在 Service 和 UI 之间搬来搬去
 *   - UI 打开时直接读 DownloadManager 的 StateFlow，天然同步
 *   - 服务只做一件事，简单、不容易出 bug
 *
 * 代价：如果系统极端缺内存把整个进程杀了，下载会中断。
 * 但有前台服务的情况下这个优先级很低，而且我们的下载支持断点续传，
 * 重新打开 App 接着下就行 —— 不会白下。
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "pocketnode.download"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "io.github.zhbsrja.pocketnode.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "io.github.zhbsrja.pocketnode.action.CANCEL_DOWNLOAD"
        const val EXTRA_MODEL_ID = "model_id"

        /** 供 UI 调用：开始下载并确保服务在跑 */
        fun start(context: Context, modelId: String) {
            AppLog.i(TAG, "启动下载服务，modelId=$modelId")
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 供通知栏的「取消」按钮调用 */
        fun cancel(context: Context, modelId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "服务创建")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_MODEL_ID)
                if (id != null) {
                    AppLog.i(TAG, "通知栏请求取消 $id")
                    DownloadManager.cancel(id)
                }
            }
            else -> {
                val id = intent?.getStringExtra(EXTRA_MODEL_ID)
                AppLog.i(TAG, "收到启动指令 modelId=$id")
                // 立刻进前台，否则 5 秒内没调 startForeground 会被系统杀掉
                startForeground(NOTIFICATION_ID, buildNotification(null))
                if (id != null) DownloadManager.start(ModelCatalog.findById(id) ?: return START_NOT_STICKY)
                watchDownloads()
            }
        }
        // START_STICKY：被系统回收后自动重建，配合断点续传能接着下
        return START_STICKY
    }

    /**
     * 订阅下载状态，把进度画到通知上；全部结束后自动退出服务。
     */
    private fun watchDownloads() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            DownloadManager.states.collectLatest { states ->
                val active = states.values.filterIsInstance<DownloadState.Downloading>()
                    .plus(states.values.filterIsInstance<DownloadState.Verifying>())

                if (active.isEmpty()) {
                    // 没有活跃任务了。再等两秒，避免刚结束就被下一秒的新任务打断
                    delay(2000)
                    val stillActive = DownloadManager.states.value.values
                        .any { it is DownloadState.Downloading || it is DownloadState.Verifying }
                    if (!stillActive) {
                        AppLog.i(TAG, "所有下载已结束，停止服务")
                        stopForegroundCompat()
                        stopSelf()
                    }
                } else {
                    val st = DownloadManager.states.value.values
                        .first { it is DownloadState.Downloading } as? DownloadState.Downloading
                    notify(buildNotification(st))
                }
            }
        }
    }

    private fun buildNotification(state: DownloadState.Downloading?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载模型")
            .setOngoing(true)
            .setOnlyAlertOnce(true)   // 别每次更新都响一声
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state != null) {
            val pct = (state.fraction * 100).toInt()
            builder.setContentText(
                "$pct%　${humanBytes(state.bytesRead)} / ${humanBytes(state.totalBytes)}" +
                    if (state.speedBps > 0) "　${humanSpeed(state.speedBps)}" else ""
            )
            builder.setProgress(100, pct, false)
        } else {
            builder.setContentText("准备中…")
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "模型下载",
            NotificationManager.IMPORTANCE_LOW,   // LOW：不响、不弹，只静静显示进度
        ).apply {
            description = "显示模型下载进度"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        AppLog.i(TAG, "通知渠道已创建")
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        AppLog.i(TAG, "服务销毁")
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ── 格式化（和界面上那套保持一致）──

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        else -> String.format("%.0f KB", bytes / 1024.0)
    }

    private fun humanSpeed(bps: Long): String = when {
        bps >= 1024 * 1024 -> String.format("%.1f MB/s", bps / 1024.0 / 1024)
        else -> String.format("%.0f KB/s", bps / 1024.0)
    }
}
