package io.github.zhbsrja.pocketnode.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * 模型文件的存放位置。
 *
 * ── 为什么不用 App 私有目录 ──────────────────────────────────────
 * 私有目录（/data/data/<包名>/files/models）有几个问题：
 *   1. 用户完全看不到，删不掉也没法手动往里放
 *   2. 卸载 App 就全没了，几百 MB 白下
 *   3. 想用电脑传个模型进去，得 adb 或者 run-as，麻烦
 *
 * 所以放到外部存储的 /storage/emulated/0/PNAI/，用文件管理器就能看到。
 *
 * ── 为什么必须申请 MANAGE_EXTERNAL_STORAGE ──────────────────────
 * Android 10 起有分区存储限制，App 不能随便写外部存储的任意目录。
 * 三条路里：
 *   - SAF 文件夹授权：只能拿到 URI，而 MediaPipe 的 LlmInference
 *     要的是**真实文件路径**（String / File），给 URI 它不认。
 *     非要用就得把文件再复制一份到私有目录 —— 那放 PNAI 就没意义了。
 *   - App 专属外部目录（Android/data/<包名>/）：无需权限，能用真路径，
 *     但路径又长又丑，而且 Android 11+ 部分文件管理器默认不显示它。
 *   - MANAGE_EXTERNAL_STORAGE：能拿到真实路径，代价是用户要去
 *     系统设置里手动开一次「所有文件访问权限」。
 *
 * 这个 App 不上架 Google Play（上架会因为这条权限被拒），所以选第三条。
 *
 * ── 降级策略 ────────────────────────────────────────────────────
 * 万一用户不给权限，不能让 App 直接不能用 —— 退回私有目录，
 * 功能照常，只是文件藏起来了。界面上会提示去授权。
 */
object ModelStorage {

    private const val TAG = "ModelStorage"

    /** 外部存储里放模型的目录名 */
    const val DIR_NAME = "PNAI"

    /**
     * 模型目录。
     *
     * 有权限就用外部目录，没有就退回私有目录 —— 保证任何时候都有个
     * 能用的路径，不会因为权限没给就让下载功能整个失效。
     */
    fun modelsDir(context: Context): File {
        if (hasAllFilesAccess()) {
            val ext = File(Environment.getExternalStorageDirectory(), DIR_NAME)
            if (ensureDir(ext)) return ext
            AppLog.w(TAG, "外部目录不可写，回退到私有目录：${ext.absolutePath}")
        } else {
            AppLog.i(TAG, "没有「所有文件访问权限」，使用私有目录")
        }
        val priv = File(context.filesDir, "models")
        ensureDir(priv)
        return priv
    }

    /** 当前是否在用外部目录（界面据此决定要不要提示授权） */
    fun usingExternal(): Boolean = hasAllFilesAccess()

    private fun ensureDir(dir: File): Boolean {
        if (!dir.exists() && !dir.mkdirs()) {
            AppLog.e(TAG, "创建目录失败: ${dir.absolutePath}")
            return false
        }
        return dir.canWrite()
    }

    /**
     * 是否已获得「所有文件访问权限」。
     *
     * Android 11（API 30）起才有 isExternalStorageManager 这个判断。
     * 11 以下外部存储本来就是可写的（只要给了 WRITE_EXTERNAL_STORAGE），
     * 所以直接返回 true。
     */
    fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    /**
     * 跳到系统设置页让用户授权。
     *
     * 注意这个设置页**没法用代码直接授予** —— 必须用户自己点。
     * 这是 Android 故意的：这类权限太敏感，不给 App 自助的机会。
     *
     * @param packageName 用 context.packageName 传进来
     */
    fun buildPermissionIntent(packageName: String): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
    }

    /**
     * 把私有目录里已有的模型搬到外部目录。
     *
     * 为什么要迁移：老版本的用户（或者没授权时下的）模型在私有目录里，
     * 授权之后如果不搬，会出现「文件明明在，列表却显示未下载」——
     * 因为查找路径变了。
     *
     * 搬完删源文件，避免占双份空间（模型动辄几百 MB）。
     */
    fun migrateFromPrivate(context: Context) {
        if (!hasAllFilesAccess()) return
        val src = File(context.filesDir, "models")
        if (!src.exists()) return
        val dst = File(Environment.getExternalStorageDirectory(), DIR_NAME)
        ensureDir(dst)

        val files = src.listFiles() ?: return
        if (files.isEmpty()) return

        AppLog.i(TAG, "开始迁移 ${files.size} 个文件到 ${dst.absolutePath}")
        for (f in files) {
            if (!f.isFile) continue
            val target = File(dst, f.name)
            if (target.exists()) {
                AppLog.i(TAG, "  跳过（目标已存在）: ${f.name}")
                f.delete()
                continue
            }
            val srcLen = f.length()   // 先记下来 —— delete() 之后再读就是 0 了
            val ok = runCatching { f.copyTo(target, overwrite = true) }
                .onFailure { AppLog.e(TAG, "  复制失败: ${f.name}", it) }
                .isSuccess
            if (ok && target.length() == srcLen) {
                f.delete()
                AppLog.i(TAG, "  已迁移: ${f.name} (${srcLen} 字节)")
            } else {
                AppLog.w(TAG, "  迁移失败，保留原文件: ${f.name}")
            }
        }
        AppLog.i(TAG, "迁移完成")
    }
}
