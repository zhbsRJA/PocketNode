package io.github.zhbsrja.pocketnode.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局日志。
 *
 * 为什么要自己做一套，而不是直接用 android.util.Log：
 *
 * 1. **Logcat 会丢**。缓冲区满了旧日志就被冲掉，App 崩溃重启后现场没了。
 *    这里同时写文件，重启后还能翻。
 *
 * 2. **崩溃要留证据**。默认的崩溃堆栈只打到 Logcat，用户手机上看不到。
 *    这里接管 UncaughtExceptionHandler，崩溃先把堆栈写进文件再交还给系统。
 *
 * 3. **后面要接真东西**。真实网络请求、真实模型加载、真实音频流 ——
 *    这些出错时如果没有「什么时候、在哪一步、参数是什么」的记录，
 *    就只能靠猜。日志是给未来的自己省时间。
 *
 * 用法：
 *   AppLog.init(applicationContext)      // Application.onCreate 里调一次
 *   AppLog.i("Download", "开始下载 $url")
 *   AppLog.e("Download", "下载失败", throwable)
 *
 * 日志文件位置：/data/data/<包名>/files/logs/app.log
 * 导出方式：adb exec-out run-as io.github.zhbsrja.pocketnode cat files/logs/app.log
 */
object AppLog {

    private const val TAG = "PocketNode"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "app.log"
    private const val MAX_FILE_BYTES = 1_000_000L  // 单文件 1 MB 后轮转
    private const val MAX_BACKUPS = 3              // 保留 3 个历史文件
    private const val RING_CAPACITY = 500          // 内存里保留最近 500 条，供界面展示

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLog-Writer").apply { isDaemon = true }
    }

    private val initialized = AtomicBoolean(false)
    private var logFile: File? = null

    /** 内存环形缓冲：界面要展示日志时直接读这里，不碰磁盘 */
    private val ring = ArrayDeque<String>(RING_CAPACITY)
    private val ringLock = Any()

    /**
     * 初始化。必须在 Application.onCreate 调用，越早越好 ——
     * 这样连启动阶段的异常也能被记下来。
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        logFile = File(dir, LOG_FILE)

        // 每次启动画一条分隔线，方便区分不同会话
        i("AppLog", "════════ 应用启动 ════════")
        i("AppLog", "日志文件: ${logFile?.absolutePath}")

        installCrashHandler()
    }

    fun d(tag: String, message: String) = log('D', tag, message, null)
    fun i(tag: String, message: String) = log('I', tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = log('W', tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log('E', tag, message, t)

    /** 读回内存里的最近日志，给界面用 */
    fun recent(): List<String> = synchronized(ringLock) { ring.toList() }

    /** 清空日志文件（界面上加个按钮，调试时方便） */
    fun clearFile() {
        writeExecutor.execute {
            runCatching { logFile?.writeText("") }
        }
    }

    /** 当前日志文件路径，导出时用 */
    fun logFilePath(): String? = logFile?.absolutePath

    // ────────────────────────── 内部实现 ──────────────────────────

    private fun log(level: Char, tag: String, message: String, throwable: Throwable?) {
        val line = buildLine(level, tag, message, throwable)

        // 1. 走系统日志（开发时在 Android Studio 的 Logcat 里能实时看到）
        when (level) {
            'D' -> Log.d(tag, message)
            'I' -> Log.i(tag, message)
            'W' -> Log.w(tag, message, throwable)
            else -> Log.e(tag, message, throwable)
        }

        // 2. 进内存环形缓冲
        synchronized(ringLock) {
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }

        // 3. 落盘。放单线程队列里异步做，避免在 UI 线程上碰 IO。
        writeExecutor.execute {
            runCatching {
                val f = logFile ?: return@runCatching
                if (f.length() > MAX_FILE_BYTES) rotate(f)
                f.appendText(line + "\n")
            }.onFailure {
                // 日志系统自己坏了不能把它算进业务日志，
                // 否则会无限递归。这里只打到 Logcat。
                Log.e(TAG, "写日志文件失败", it)
            }
        }
    }

    private fun buildLine(level: Char, tag: String, message: String, throwable: Throwable?): String {
        val time = timeFormat.format(Date())
        val base = "$time $level/$tag: $message"
        if (throwable == null) return base
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return base + "\n" + sw.toString().trimEnd()
    }

    private fun rotate(current: File) {
        val dir = current.parentFile ?: return
        // app.log.2 → app.log.3，app.log.1 → app.log.2，app.log → app.log.1
        runCatching {
            File(dir, "$LOG_FILE.$MAX_BACKUPS").takeIf { it.exists() }?.delete()
            for (i in MAX_BACKUPS - 1 downTo 1) {
                val from = File(dir, "$LOG_FILE.$i")
                if (from.exists()) from.renameTo(File(dir, "$LOG_FILE.${i + 1}"))
            }
            current.renameTo(File(dir, "$LOG_FILE.1"))
        }
        current.writeText("")
    }

    /**
     * 接管未捕获异常：先写日志，再交还给默认处理器（让它照常弹崩溃对话框）。
     *
     * 注意要 rethrow 给原处理器 —— 否则应用会「静默死亡」，
     * 反而更难发现问题出在哪。
     */
    private fun installCrashHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                e("Crash", "线程 [${thread.name}] 未捕获异常", throwable)
            }
            // 崩溃日志必须同步落盘 —— 异步队列来不及写就被系统杀进程了
            runCatching {
                val f = logFile
                if (f != null) {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    f.appendText(
                        "${timeFormat.format(Date())} E/Crash: (同步写入)\n" +
                            sw.toString().trimEnd() + "\n"
                    )
                }
            }
            default?.uncaughtException(thread, throwable)
        }
    }
}
