package io.github.zhbsrja.pocketnode.data

import android.content.Context
import android.net.Uri
import io.github.zhbsrja.pocketnode.util.AppLog
import io.github.zhbsrja.pocketnode.util.ModelStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 单个模型的下载状态 */
sealed interface DownloadState {

    /** 本地没有这个文件 */
    data object NotDownloaded : DownloadState

    /**
     * 本地有下了一半的文件。
     *
     * 为什么单独立一个状态，而不是混进 NotDownloaded：
     * 用户取消下载、或者 App 被杀之后，磁盘上会留一个几百 MB 的残file。
     * 如果显示成「未下载」，界面上就只有「下载」按钮 —— 用户没法删掉它，
     * 也不知道那几百 MB 去哪了。单独一个状态才能给出「继续」和「删除」
     * 两个正确的选项。
     */
    data class Partial(val bytesOnDisk: Long) : DownloadState

    /** 正在下载 */
    data class Downloading(
        val bytesRead: Long,
        val totalBytes: Long,
        /** 字节/秒。刚开始算不出来时为 0 */
        val speedBps: Long,
        val resumed: Boolean,
    ) : DownloadState {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** 下完了，正在校验哈希 */
    data object Verifying : DownloadState

    /** 完成 */
    data class Done(val file: File) : DownloadState

    /** 失败。message 会同时写进日志和界面 */
    data class Failed(val message: String) : DownloadState
}

/** 导入本地文件时的进度 */
data class ImportProgress(
    val bytesCopied: Long,
    val totalBytes: Long,     // 可能为 -1（来源没报告大小）
    val fileName: String,
) {
    val fraction: Float?
        get() = if (totalBytes > 0) (bytesCopied.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/** 不依赖 Context 的字节格式化，供日志使用 */
internal fun humanBytesStatic(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 模型文件下载器。
 *
 * 设计取舍说明：
 *
 * 1. **不用 WorkManager**。WorkManager 适合「必须完成、可延迟」的任务；
 *    这里是用户主动点击、要盯着进度条看的，用前台协程更直接，
 *    而且下一步要接前台服务保活，那时再包一层就行。
 *
 * 2. **支持断点续传**。模型动辄 1-2 GB，手机网络下断一次就要重来
 *    是完全不能接受的。靠 HTTP 的 Range 头 + 本地已有的部分文件实现。
 *
 * 3. **状态放在 StateFlow 里**。UI 只订阅、不持有下载逻辑，
 *    所以切页面、旋转屏幕都不会中断下载。
 *
 * 4. **每一步都打日志**。真实网络环境千奇百怪（代理、CDN 返回怪东西、
 *    服务器不认 Range、磁盘满），出错时没有日志就只能猜。
 */
object DownloadManager {

    private const val TAG = "Download"

    /** 进度更新节流间隔。太频繁会疯狂重组 UI，反而卡 */
    private const val PROGRESS_THROTTLE_MS = 200L

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_SIZE = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    /**
     * 导入进度。**独立于 DownloadState**，因为导入时模型还没登记进列表，
     * 没有 id 可以挂状态。界面上用一条独立的进度条呈现。
     */
    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    private val _importError = MutableStateFlow<String?>(null)

    /** 正在跑的下载任务，用于取消 */
    private val jobs = mutableMapOf<String, Job>()

    @Volatile
    private var appContext: Context? = null

    /** 在 Application 里调一次 */
    fun init(context: Context) {
        appContext = context.applicationContext
        AppLog.i(TAG, "DownloadManager 初始化，模型目录: ${modelsDir(appContext!!).absolutePath}")
        // 扫一遍本地已有的文件，把状态标成已下载
        refreshLocalState()
    }

    /**
     * 模型存放目录。
     *
     * 实际路径由 ModelStorage 决定：
     *   有「所有文件访问权限」→ /storage/emulated/0/PNAI     （用户可见）
     *   没有权限             → /data/data/<包名>/files/models（私有）
     *
     * 所以这里**不能缓存结果** —— 用户可能中途去设置里授权，
     * 授权完回来路径就变了。每次现算最安全。
     */
    fun modelsDir(context: Context): File = ModelStorage.modelsDir(context)

    fun localFile(context: Context, model: ModelInfo): File =
        File(modelsDir(context), model.fileName)

    fun stateOf(modelId: String): DownloadState =
        _states.value[modelId] ?: DownloadState.NotDownloaded

    /** 扫描本地文件，把已有文件的模型标成 Done 或 Partial */
    fun refreshLocalState() {
        val ctx = appContext ?: return
        val map = mutableMapOf<String, DownloadState>()
        for (m in ModelCatalog.allNow) {
            val f = localFile(ctx, m)
            if (f.exists() && f.length() > 0) {
                if (f.length() == m.sizeBytes) {
                    map[m.id] = DownloadState.Done(f)
                } else {
                    // 大小对不上 = 没下完。标成 Partial 而不是未下载，
                    // 这样界面上能给出「继续下载」和「删除」两个选项，
                    // 用户不至于对着几百 MB 的残file 干瞪眼。
                    AppLog.i(TAG, "${m.id} 发现未完成文件 ${f.length()} / ${m.sizeBytes} 字节")
                    map[m.id] = DownloadState.Partial(f.length())
                }
            }
        }
        _states.update { it + map }
    }

    /**
     * 开始下载。已经在跑就忽略。
     */
    fun start(model: ModelInfo) {
        val ctx = appContext ?: run {
            AppLog.e(TAG, "start() 在没有 init 的情况下被调用，忽略")
            return
        }
        if (jobs[model.id]?.isActive == true) {
            AppLog.w(TAG, "${model.id} 已在下载中，忽略重复请求")
            return
        }

        AppLog.i(TAG, "开始下载 ${model.id}")
        AppLog.i(TAG, "  url      = ${model.downloadUrl}")
        AppLog.i(TAG, "  期望大小 = ${model.sizeBytes} 字节")
        setState(model.id, DownloadState.Downloading(0, model.sizeBytes, 0, resumed = false))

        jobs[model.id] = scope.launch {
            try {
                download(ctx, model)
            } catch (c: CancellationException) {
                // 用户主动取消不是错误。这里必须单独捕获 ——
                // 如果混进下面的 Throwable 分支，界面会显示「下载失败：Job was cancelled」，
                // 用户会以为是自己的网络出问题了。
                AppLog.i(TAG, "${model.id} 下载已取消")
                val partial = localFile(ctx, model).length()
                setState(model.id, DownloadState.Partial(partial))
                throw c   // 必须重新抛出，否则协程的取消状态会被吞掉
            } catch (t: Throwable) {
                // 任何没预料到的异常都要留下完整堆栈
                AppLog.e(TAG, "${model.id} 下载异常", t)
                setState(model.id, DownloadState.Failed(t.message ?: t.javaClass.simpleName))
            } finally {
                jobs.remove(model.id)
            }
        }
    }

    /**
     * 取消下载。已下载的部分保留，下次可续传。
     *
     * 注意这里**不直接改状态**：cancel() 只是发信号，
     * 真正的收尾（把状态设成 Partial）由协程的 CancellationException 分支做。
     * 两边都改会打架 —— 取消是异步的，这里设了状态之后协程可能还在跑，
     * 反而会闪出一个错误的中间态。
     */
    fun cancel(modelId: String) {
        val job = jobs[modelId]
        if (job == null) {
            AppLog.w(TAG, "cancel($modelId)：没有正在跑的任务")
            return
        }
        AppLog.i(TAG, "发送取消信号 $modelId")
        job.cancel()   // 循环里的 ensureActive() 会在下一轮抛 CancellationException
    }

    /**
     * 从系统文件选择器导入模型文件。
     *
     * 流程：复制文件 → 写进 ImportedModelStore → 刷新状态。
     *
     * 为什么「先复制完再登记」而不是反过来：
     * 如果先登记，界面上会立刻出现一个「模型 3」，但文件还没拷完，
     * 用户可能马上去点「加载到内存」—— 加载一个半截文件必然崩。
     * 复制期间用一个独立的进度状态挡住，拷完了再让它出现在列表里。
     *
     * 进度不挂在某个模型 id 上，因为这时候模型还不存在。
     */
    fun importFromUri(uri: Uri, name: String, note: String) {
        val ctx = appContext ?: run {
            AppLog.e(TAG, "importFromUri: 未初始化")
            return
        }

        scope.launch {
            var target: File? = null
            try {
                val dir = ModelStorage.modelsDir(ctx)
                val fileName = "imported-${System.currentTimeMillis()}.task"
                target = File(dir, fileName)

                val total = runCatching {
                    ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                }.getOrDefault(-1L)
                AppLog.i(TAG, "开始导入 uri=$uri 来源大小=$total 字节 → $fileName")

                _importProgress.value = ImportProgress(0, total, fileName)

                var copied = 0L
                var lastReport = 0L
                val ctxCoroutine = currentCoroutineContext()

                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(BUFFER_SIZE)
                        while (true) {
                            // 和下载循环同样的道理：读文件是阻塞的，
                            // 不主动检查就不会响应取消。
                            ctxCoroutine.ensureActive()

                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            copied += n

                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 300) {
                                _importProgress.value = ImportProgress(copied, total, fileName)
                                lastReport = now
                            }
                        }
                    }
                } ?: throw IllegalStateException("打不开选中的文件，可能已被移动或没有读取权限")

                if (copied == 0L) {
                    target.delete()
                    _importProgress.value = null
                    AppLog.w(TAG, "选中的文件是空的")
                    return@launch
                }

                AppLog.i(TAG, "复制完成 $copied 字节，登记到模型列表")
                val model = ImportedModelStore.register(
                    name = name,
                    note = note,
                    fileName = fileName,
                    sizeBytes = copied,
                )

                _importProgress.value = null
                refreshLocalState()
                AppLog.i(TAG, "导入成功 ✓ ${model.name}（${humanBytesStatic(copied)}）")

            } catch (c: CancellationException) {
                AppLog.i(TAG, "导入被取消")
                target?.delete()          // 导入中途取消就把半截文件删掉，
                _importProgress.value = null   // 反正它还没出现在列表里，留着没用
                throw c
            } catch (t: Throwable) {
                AppLog.e(TAG, "导入失败", t)
                target?.delete()
                _importProgress.value = null
                _importError.value = t.message ?: t.javaClass.simpleName
            }
        }
    }

    /** 导入过程中的进度，界面用它显示进度条 */
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    /** 导入失败的提示，界面读走后置空 */
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun clearImportError() {
        _importError.value = null
    }

    /** 删除已下载的文件 */
    fun delete(model: ModelInfo) {
        val ctx = appContext ?: return
        cancel(model.id)   // 万一还在下，先停掉，否则删完又被写回来
        val f = localFile(ctx, model)
        AppLog.i(TAG, "删除本地文件 ${f.absolutePath}")
        val ok = f.delete()
        AppLog.i(TAG, "删除结果: $ok")

        // 导入的模型还要把登记记录一起清掉。
        // 只删文件不清记录的话，列表里会留一个「模型 2」，
        // 点加载却提示文件不存在 —— 这种幽灵条目最让人困惑。
        if (model.imported) {
            ImportedModelStore.remove(model.id)
            AppLog.i(TAG, "已移除导入记录 ${model.id}")
        }

        setState(model.id, DownloadState.NotDownloaded)
    }

    // ────────────────────────── 实际下载 ──────────────────────────

    private suspend fun download(context: Context, model: ModelInfo) = withContext(Dispatchers.IO) {
        val target = localFile(context, model)
        val existing = if (target.exists()) target.length() else 0L
        val canResume = existing > 0 && existing < model.sizeBytes

        if (canResume) {
            AppLog.i(TAG, "${model.id} 发现未完成文件 $existing 字节，尝试断点续传")
        } else if (existing >= model.sizeBytes) {
            AppLog.i(TAG, "${model.id} 文件已完整，跳过下载")
            setState(model.id, DownloadState.Done(target))
            return@withContext
        }

        val conn = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PocketNode/0.1")
            if (canResume) {
                // 告诉服务器「我从第 N 字节开始要」
                setRequestProperty("Range", "bytes=$existing-")
            }
        }

        try {
            AppLog.i(TAG, "${model.id} 发起请求…")
            val code = conn.responseCode
            AppLog.i(TAG, "${model.id} HTTP $code  contentLength=${conn.contentLength}")

            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                val msg = "服务器返回 HTTP $code"
                AppLog.e(TAG, "${model.id} $msg")
                setState(model.id, DownloadState.Failed(msg))
                return@withContext
            }

            // 206 才说明服务器接受了续传；返回 200 意味着它无视 Range 从头给了，
            // 这时必须从头写，否则文件会错位。
            val serverAcceptedRange = code == HttpURLConnection.HTTP_PARTIAL
            val startAt = if (serverAcceptedRange) existing else 0L
            if (canResume && !serverAcceptedRange) {
                AppLog.w(TAG, "${model.id} 服务器不支持断点续传，从头下载")
            }

            val totalBytes = when {
                serverAcceptedRange -> existing + conn.contentLength
                conn.contentLength > 0 -> conn.contentLength.toLong()
                else -> model.sizeBytes
            }
            AppLog.i(TAG, "${model.id} 开始写入，起点=$startAt 总计=$totalBytes")

            var downloaded = startAt
            var lastReport = 0L
            var windowStart = System.currentTimeMillis()
            var windowBytes = downloaded

            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(startAt)
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    // 拿到协程上下文，用来检查取消。
                    // 关键点：input.read() 是阻塞调用，协程取消不会打断它，
                    // 所以必须在每一轮循环开头主动检查 —— 否则用户点了「取消」，
                    // job.cancel() 只是设了个标志，这个循环照样闷头下到结束。
                    val ctx = currentCoroutineContext()
                    while (true) {
                        ctx.ensureActive()   // 被取消时抛 CancellationException

                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        downloaded += n

                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_THROTTLE_MS) {
                            val elapsed = (now - windowStart).coerceAtLeast(1)
                            val speed = ((downloaded - windowBytes) * 1000 / elapsed)
                            setState(
                                model.id,
                                DownloadState.Downloading(downloaded, totalBytes, speed, serverAcceptedRange),
                            )
                            windowStart = now
                            windowBytes = downloaded
                            lastReport = now
                        }
                    }
                }
            }

            // 收尾状态
            setState(model.id, DownloadState.Downloading(downloaded, totalBytes, 0, serverAcceptedRange))
            val actual = target.length()
            AppLog.i(TAG, "${model.id} 下载结束，文件实际大小 $actual 字节")

            // 完整性校验。
            //
            // 关键取舍：**以服务器给的 Content-Length 为准，不是目录里声明的大小**。
            // 声明值是静态元数据，容易写错或过时（这个 bug 就踩过一次：
            // 目录写 56 MiB，实际文件是 56,699,878 字节，差几 KB 就误判失败）。
            // Content-Length 是这次传输的真实长度，才该拿来判断「下全了没」。
            val authoritative = when {
                totalBytes > 0 -> totalBytes
                else -> model.sizeBytes
            }
            if (authoritative > 0 && actual < authoritative) {
                val msg = "文件不完整：$actual / $authoritative 字节（来源：服务器 Content-Length）"
                AppLog.e(TAG, "${model.id} $msg")
                setState(model.id, DownloadState.Failed(msg))
                return@withContext
            }
            if (model.sizeBytes > 0 && actual != model.sizeBytes) {
                // 只警告不失败：说明目录里的元数据该更新了
                AppLog.w(
                    TAG,
                    "${model.id} 目录声明大小(${model.sizeBytes}) 与实际($actual) 不一致，" +
                        "不影响使用，建议更新 ModelCatalog"
                )
            }

            // 哈希校验
            if (!model.sha256.isNullOrBlank()) {
                setState(model.id, DownloadState.Verifying)
                AppLog.i(TAG, "${model.id} 开始校验 SHA256…")
                val actualHash = sha256(target)
                if (!actualHash.equals(model.sha256, ignoreCase = true)) {
                    val msg = "SHA256 不匹配：期望 ${model.sha256}，实际 $actualHash"
                    AppLog.e(TAG, "${model.id} $msg")
                    setState(model.id, DownloadState.Failed(msg))
                    return@withContext
                }
                AppLog.i(TAG, "${model.id} SHA256 校验通过")
            }

            AppLog.i(TAG, "${model.id} 下载完成 ✓")
            setState(model.id, DownloadState.Done(target))

        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun setState(modelId: String, state: DownloadState) {
        _states.update { it + (modelId to state) }
    }
}
