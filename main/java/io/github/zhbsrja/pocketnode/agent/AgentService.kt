package io.github.zhbsrja.pocketnode.agent

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
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 智能体保活的前台服务。
 *
 * ══════════════════════════════════════════════════════════════
 * ── 为什么必须有这个 ───────────────────────────────────────────
 *
 * Android 从 8.0 起会**冻结后台进程**。App 一旦不在前台，进程被挂起，
 * 里面的协程、定时器、回调全部停摆。
 *
 * 对智能体来说这是致命的：**它的任务本身就要离开当前 App** ——
 * 用户说"打开设置"，智能体执行 home() 或直接跳转，PocketNode 立刻
 * 退到后台，然后循环就在下一行 delay() 处冻结，永远醒不过来。
 *
 * 表现就是用户描述的那样："回到桌面就不干活了"。
 *
 * 唯一合规的办法是前台服务 —— 挂一条常驻通知，让系统知道
 * "这东西在后台干活，别冻它"。所有正经的自动化工具都这么干。
 * ══════════════════════════════════════════════════════════════
 *
 * ── 和 AgentRunner 的分工 ────────────────────────────────────────
 * 这个服务**不跑循环逻辑**，只做两件事：
 *   1. 把进程钉住，别被冻
 *   2. 把当前状态画到通知上，让用户随时知道智能体在干什么
 *
 * 循环还在 AgentRunner 里跑。这样分开的好处是 AgentRunner 不依赖
 * Context，可以被测试，也可以在没有服务的场景下（比如降级）用。
 *
 * ── 一个必须承认的局限 ──────────────────────────────────────────
 * 前台服务能防**系统冻结**，但防不住**厂商杀后台**（MIUI/EMUI 等
 * 从最近任务划掉就直接 force-stop）。那种情况下智能体也会中断。
 * 用户在设置页开了电池白名单和自启动能大幅改善，但不是 100%。
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "pocketnode.agent"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            AppLog.i(TAG, "启动智能体保活服务")
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先挂通知再干活 —— 5 秒内没进前台会被系统判定为 ANR 类问题直接杀掉
        startForeground(NOTIFICATION_ID, buildNotification("准备中"))

        if (watchJob?.isActive != true) {
            watchJob = scope.launch {
                AgentRunner.state.collectLatest { st ->
                    when (st) {
                        is AgentState.Idle -> { /* 还没开始，保持通知 */ }
                        is AgentState.Running -> notify(
                            buildNotification(
                                getString(R.string.agent_running, st.step, AgentRunner.MAX_STEPS),
                            ),
                        )
                        is AgentState.Finished -> {
                            notify(buildNotification(getString(R.string.agent_finished, st.steps, st.reason)))
                            // 停一会儿让用户看到结果，再收掉服务。
                            // 立刻停的话通知一闪而过，用户根本不知道发生了什么。
                            kotlinx.coroutines.delay(2500)
                            stopSelf()
                        }
                        is AgentState.Failed -> {
                            notify(buildNotification(getString(R.string.agent_failed, st.message)))
                            kotlinx.coroutines.delay(2500)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.agent_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.agent_notif_channel_desc) },
        )
    }

    override fun onDestroy() {
        AppLog.i(TAG, "保活服务销毁")
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
