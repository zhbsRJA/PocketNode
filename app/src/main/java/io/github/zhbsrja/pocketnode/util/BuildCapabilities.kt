package io.github.zhbsrja.pocketnode.util

import io.github.zhbsrja.pocketnode.BuildConfig

/**
 * 按渠道区分能力。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么需要这个：
 *
 * Play 版删掉了两个敏感权限（见 app/src/play/AndroidManifest.xml），
 * 但**代码还在调那些 API**。光删清单不改代码的后果：
 *
 *   · 无障碍：拿到的是 null，如果不判断就 NPE
 *   · 全盘存储：Environment.isExternalStorageManager() 恒为 false，
 *     会静默退回私有目录 —— 不崩但行为不一致
 *
 * 所以这里把「这个渠道有什么能力」集中成一处常量，
 * **所有判断都从这里读**，不在代码各处写 `if (BuildConfig.FLAVOR == ...)`。
 *
 * 集中成一处的好处：加渠道时只改这一个文件，不会漏掉某个角落。
 * ══════════════════════════════════════════════════════════════
 */
object BuildCapabilities {

    /**
     * 当前是不是 Play 渠道。
     *
     * ⚠️ 用 BuildConfig.FLAVOR 而不是 applicationId 判断 ——
     * applicationId 可以被 build 脚本随便改，FLAVOR 是编译期常量，
     * 改不了也不会有歧义。
     */
    private val isPlay: Boolean get() = BuildConfig.FLAVOR == "play"

    /**
     * 是否允许写入全盘存储（/storage/emulated/0/PNAI）。
     *
     * sideload 版：可以，用户在设置里授权后模型存在显眼位置，
     *              卸载 App 也还在，用户能自己管理
     * play 版：    不行，退回 App 专属外部目录
     *              （/storage/emulated/0/Android/data/<包名>/files/models）
     *              这个路径不需要任何权限，但部分文件管理器默认不显示
     */
    val hasAllFilesAccess: Boolean get() = !isPlay

    /**
     * 是否提供智能体控制设备功能。
     *
     * play 版整个功能不显示 —— 包括服务页那两个开关、动作记录、
     * 任务输入框。不留一个"点了没反应"的入口。
     */
    val hasAgentControl: Boolean get() = !isPlay

    /** 界面上的渠道标识，显示在设置页底部，方便用户反馈问题时说明 */
    val channelLabel: String get() = if (isPlay) "Google Play" else "direct"
}
