package io.github.zhbsrja.pocketnode.ui.service

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.zhbsrja.pocketnode.agent.AgentRunner
import io.github.zhbsrja.pocketnode.agent.AgentState
import io.github.zhbsrja.pocketnode.agent.DeviceController
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zhbsrja.pocketnode.R
import io.github.zhbsrja.pocketnode.inference.EngineState
import io.github.zhbsrja.pocketnode.inference.LlmEngine
import io.github.zhbsrja.pocketnode.server.ApiKey
import io.github.zhbsrja.pocketnode.server.ApiKeyStore
import io.github.zhbsrja.pocketnode.server.ApiServer
import io.github.zhbsrja.pocketnode.server.ServerManager

/**
 * 服务页：把本地模型变成别人能调的接口。
 *
 * 信息分三块，按重要性从上到下：
 *   1. 开不开、地址是什么、key 是什么 —— 用户唯一要抄走的东西
 *   2. 用量统计和访问记录 —— 知道谁在用、有没有出错
 *   3. 说明 —— 讲清楚这是局域网接口，别往公网暴露
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val running by ServerManager.running.collectAsState()
    val port by ServerManager.port.collectAsState()
    val keys by ApiKeyStore.keys.collectAsState()
    // 哪一把处于「显示明文」状态。用 id 而不是下标 ——
    // 删掉一把之后下标会整体前移，用下标会显示错行。
    var revealedId by remember { mutableStateOf<String?>(null) }
    // 正在编辑的 key。null = 没开弹窗；ApiKey(id="") = 新建
    var editing by remember { mutableStateOf<ApiKey?>(null) }
    val status by ApiServer.status.collectAsState()
    val logs by ApiServer.logs.collectAsState()
    val engine by LlmEngine.state.collectAsState()

    var errorText by remember { mutableStateOf<String?>(null) }

    val addresses = remember(running) { ServerManager.lanAddresses() }
    val primary = addresses.firstOrNull()

    Scaffold(
        modifier = modifier,
        // 外层 MainScaffold 已处理系统栏 insets，这里不能重复处理
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.svc_title)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── 开关 ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.svc_switch_title), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (running) stringResource(R.string.svc_running, port) else stringResource(R.string.svc_stopped),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (running) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Switch(
                                checked = running,
                                onCheckedChange = { want ->
                                    errorText = if (want) {
                                        ServerManager.start(context)
                                    } else {
                                        ServerManager.stop()
                                        null
                                    }
                                },
                            )
                        }

                        errorText?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.svc_start_failed, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        // 引擎没加载的话，服务起来了也答不了 —— 提前说清楚
                        if (engine !is EngineState.Ready) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.svc_no_model),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // ── 智能体控制设备 ──
            item { AgentControlCard() }

            // ── 智能体动作记录 + 测试 ──
            item { AgentLogCard() }

            // ── 连接信息 ──
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionTitle(stringResource(R.string.svc_conn_info))

                        if (!running) {
                            Text(
                                stringResource(R.string.svc_conn_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (primary == null) {
                            Text(
                                stringResource(R.string.svc_no_lan),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            CopyableField(
                                label = stringResource(R.string.svc_base_url),
                                value = "http://$primary:$port/v1",
                                onCopy = {
                                    clipboard.setText(AnnotatedString("http://$primary:$port/v1"))
                                },
                            )

                            Spacer(Modifier.height(14.dp))

                            // ── API Key 列表 ──
                            // 支持多把 + 自定义值：多把是为了区分设备、单独撤销；
                            // 自定义值是为了重装 App 后能建一个一模一样的，
                            // 已经配好的客户端不用改。
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionTitle("API Key", Modifier.weight(1f))
                                TextButton(onClick = {
                                    editing = ApiKey(id = "", value = "", label = "", createdAt = 0L)
                                }) { Text(stringResource(R.string.svc_new)) }
                            }

                            Spacer(Modifier.height(4.dp))

                            keys.forEach { k ->
                                KeyRow(
                                    apiKey = k,
                                    revealed = revealedId == k.id,
                                    onToggleReveal = {
                                        revealedId = if (revealedId == k.id) null else k.id
                                    },
                                    onCopy = { clipboard.setText(AnnotatedString(k.value)) },
                                    onEdit = { editing = k },
                                    onDelete = { ApiKeyStore.remove(k.id) },
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            // 多个地址时（同时连了 WiFi 和热点），全列出来
                            if (addresses.size > 1) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.svc_other_addrs, addresses.drop(1).joinToString("  ")),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ── 用量 ──
            if (running) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SectionTitle(stringResource(R.string.svc_usage))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Stat(stringResource(R.string.svc_requests), status.requestCount.toString(), Modifier.weight(1f))
                                Stat(stringResource(R.string.svc_errors), status.errorCount.toString(), Modifier.weight(1f))
                            }
                            status.lastError?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.svc_last_error, it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            // ── 访问记录 ──
            if (running && logs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle(stringResource(R.string.svc_recent), Modifier.weight(1f))
                        TextButton(onClick = { ApiServer.resetStats() }) { Text(stringResource(R.string.svc_clear)) }
                    }
                }
                items(logs.take(15), key = { it.time.toString() + it.path }) { entry ->
                    RequestRow(entry)
                }
            }

            // ── 新建 / 编辑 key 的弹窗 ──
            // 放在 item 里只是为了能读到 ServiceScreen 作用域里的 editing。
            // AlertDialog 是独立窗口，写在哪个 item 里都不影响它显示的位置。
            item {
                editing?.let { k ->
                    KeyEditDialog(
                        initial = k,
                        onDismiss = { editing = null },
                        onConfirm = { v, l ->
                            val r = if (k.id.isEmpty()) ApiKeyStore.add(v, l)
                            else ApiKeyStore.update(k.id, v, l)
                            if (r.isSuccess) editing = null
                        },
                    )
                }
            }

            // ── 说明 ──
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionTitle(stringResource(R.string.svc_howto))
                        Text(
                            stringResource(R.string.svc_howto_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.svc_sec_1) +
                                stringResource(R.string.svc_sec_2) +
                                stringResource(R.string.svc_sec_3),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「允许智能体控制设备」卡片。
 *
 * 默认关闭。这个开关和系统无障碍授权是**两件事**，必须分开表达：
 *   · 系统授权 = 技术上能不能控制
 *   · 这个开关 = 我们允不允许控制
 *
 * 两者都满足才真正生效。界面要同时显示这两个状态，否则用户会遇到
 * stringResource(R.string.svc_faq_1)或者stringResource(R.string.svc_faq_2)。
 */
@Composable
private fun AgentControlCard() {
    val context = LocalContext.current
    val enabled by DeviceController.enabled.collectAsState()
    val allowRemote by DeviceController.allowRemote.collectAsState()
    val remoteCount by DeviceController.remoteActionCount.collectAsState()
    var pendingRemote by remember { mutableStateOf(false) }

    // 订阅连接状态，而不是在 ON_RESUME 时读一次快照。
    //
    // 为什么必须订阅：服务绑定是**异步**的。用快照的话，进程重启后
    // 界面渲染的那一刻服务还没绑上，读到的就是 false，而且之后连上了
    // 界面也不会更新 —— 表现是"服务明明连上了，界面还显示未授权"。
    val connected by DeviceController.serviceConnected.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // 开启时用不同的底色 —— 这是个危险开关，状态必须一眼看出来
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.svc_agent_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            enabled && connected -> stringResource(R.string.svc_agent_on_conn)
                            enabled && !connected -> stringResource(R.string.svc_agent_on_noconn)
                            !enabled && connected -> stringResource(R.string.svc_agent_off_conn)
                            else -> stringResource(R.string.svc_agent_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(checked = enabled, onCheckedChange = { DeviceController.setEnabled(it) })
            }

            // ── 远程控制开关（默认关，每次启动强制重置）──
            if (enabled && connected) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.svc_remote_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (allowRemote) stringResource(R.string.svc_remote_on, remoteCount)
                            else stringResource(R.string.svc_agent_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (allowRemote) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Switch(
                        checked = allowRemote,
                        onCheckedChange = { want ->
                            if (want) {
                                pendingRemote = true
                            } else {
                                DeviceController.setAllowRemote(false)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (!connected) {
                Text(
                    stringResource(R.string.svc_need_a11y),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.svc_a13_1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.svc_a13_2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        // 直跳「本应用详情页」。受限设置在那里，比无障碍列表页更有用 ——
                        // 用户八成是卡在这一步，而不是没找到无障碍入口。
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) {
                        Text(stringResource(R.string.svc_open_app_info))
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) {
                        Text(stringResource(R.string.svc_a11y_settings))
                    }
                }
            }

            if (enabled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.svc_agent_warn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.svc_agent_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.svc_agent_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (allowRemote) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.svc_remote_warn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.svc_remote_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { DeviceController.setAllowRemote(false) }) {
                    Text(stringResource(R.string.svc_revoke))
                }
            }
        }
    }

    // ── 开启前的强制确认 ──
    if (pendingRemote) {
        AlertDialog(
            onDismissRequest = { pendingRemote = false },
            title = { Text(stringResource(R.string.svc_remote_confirm_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.svc_remote_confirm_lead),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.svc_risk_1),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.svc_risk_2),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.svc_risk_3), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.svc_remote_risk),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.svc_remote_protect),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    DeviceController.setAllowRemote(true)
                    pendingRemote = false
                }) { Text(stringResource(R.string.svc_remote_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemote = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * 智能体动作记录 + 手动测试。
 *
 * 为什么要这块：
 *   1. **审计**。stringResource(R.string.svc_log_hint_title)必须有答案。所有动作都记在
 *      DeviceController 的环形缓冲里，这里只是展示。
 *   2. **验证**。授权流程出问题时，光看开关状态判断不了到底是
 *      没授权、闸门拦了、还是动作本身失败。跑一次测试全清楚了。
 *
 * 测试按钮刻意只做**无副作用**的动作：
 *   · 读界面 —— 纯读取
 *   · 回桌面 —— 全局动作，不会点坏任何东西
 * 不做stringResource(R.string.svc_tap_center)这种测试：万一落在某个按钮上，
 * 用户会莫名其妙触发一次真实操作。
 */
@Composable
private fun AgentLogCard() {
    val enabled by DeviceController.enabled.collectAsState()
    val actions by DeviceController.actions.collectAsState()
    val denyReason by DeviceController.lastDenyReason.collectAsState()
    var lastResult by remember { mutableStateOf<String?>(null) }
    var taskInput by remember { mutableStateOf("") }
    // 这个卡片也要判断无障碍是否连上 —— 没连上时执行按钮要置灰
    val connected by DeviceController.serviceConnected.collectAsState()
    val agentState by AgentRunner.state.collectAsState()
    val steps by AgentRunner.steps.collectAsState()
    val agentRunning = agentState is AgentState.Running

    // fold 的 lambda、onClick 的 lambda 都不是 @Composable 作用域，
    // 里面不能直接调 stringResource。先在这里取出来。
    val sReadOk = stringResource(R.string.svc_read_ok, 0).substringBefore("0")
    val sReadFail = stringResource(R.string.svc_read_fail, "")
    val sFail = stringResource(R.string.svc_fail, "")
    val sBackHome = stringResource(R.string.svc_back_home)

    if (!enabled) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(stringResource(R.string.svc_agent_log), Modifier.weight(1f))
                if (actions.isNotEmpty()) {
                    TextButton(onClick = { DeviceController.clearActions() }) { Text(stringResource(R.string.svc_clear)) }
                }
            }

            // ── 智能体任务 ──
            // 让模型自己读屏 → 决策 → 执行。这是"智能体控制设备"的完整形态，
            // 上面那两个按钮只是单步测试。
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = taskInput,
                onValueChange = { taskInput = it },
                label = { Text(stringResource(R.string.agent_task_label)) },
                placeholder = { Text(stringResource(R.string.agent_task_hint)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !agentRunning,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { AgentRunner.run(taskInput.trim()) },
                    enabled = !agentRunning && taskInput.isNotBlank() && connected,
                ) { Text(stringResource(R.string.agent_run)) }
                if (agentRunning) {
                    OutlinedButton(onClick = { AgentRunner.stop() }) {
                        Text(stringResource(R.string.agent_stop))
                    }
                }
            }

            // 运行状态
            when (val st = agentState) {
                is AgentState.Running -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.agent_running, st.step, AgentRunner.MAX_STEPS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is AgentState.Finished -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.agent_finished, st.steps, st.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is AgentState.Failed -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.agent_failed, st.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {}
            }

            // 每一步的决策过程
            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.agent_steps),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                steps.forEach { s ->
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        Text(
                            "  #" + s.index + " " + s.action,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (s.ok) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                        )
                        if (s.note.isNotBlank()) {
                            Text(
                                "     " + s.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 测试按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    lastResult = DeviceController.readScreen().fold(
                        onSuccess = { sReadOk + it.length + " " + it.take(400) },
                        onFailure = { sReadFail + it.message },
                    )
                }) { Text(stringResource(R.string.svc_read_screen)) }

                OutlinedButton(onClick = {
                    lastResult = DeviceController.home().fold(
                        onSuccess = { sBackHome },
                        onFailure = { sFail + it.message },
                    )
                }) { Text(stringResource(R.string.svc_test_home)) }
            }

            lastResult?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            denyReason?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.svc_blocked, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (actions.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.svc_no_actions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                actions.take(12).forEach { a ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            a.kind,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (a.ok) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.widthIn(min = 74.dp),
                        )
                        Text(
                            a.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一把 key 的展示行。
 *
 * 默认打码，点「显示」才看明文 —— 别人扫到你屏幕的时候不至于直接拿走 key。
 */
@Composable
private fun KeyRow(
    apiKey: ApiKey,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(apiKey.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (revealed) apiKey.value else apiKey.masked,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (apiKey.lastUsedAt > 0) {
                        Text(
                            stringResource(R.string.svc_last_used) + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(apiKey.lastUsedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onClick = onToggleReveal) { Text(if (revealed) stringResource(R.string.svc_hide) else stringResource(R.string.svc_show)) }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.svc_copy)) }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.svc_edit)) }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 新建 / 编辑 key 的弹窗。
 *
 * 值可以手填 —— 这正是这个功能的重点：重装 App 后填一个和以前一样的，
 * 已经配置好的客户端完全不用动。
 * 不想编就点「随机生成」。
 */
@Composable
private fun KeyEditDialog(
    initial: ApiKey,
    onDismiss: () -> Unit,
    onConfirm: (value: String, label: String) -> Unit,
) {
    val isNew = initial.id.isEmpty()
    var value by remember { mutableStateOf(if (isNew) ApiKeyStore.generate() else initial.value) }
    var label by remember { mutableStateOf(if (isNew) "" else initial.label) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) stringResource(R.string.svc_key_new_title) else stringResource(R.string.svc_key_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.svc_key_label)) },
                    placeholder = { Text(stringResource(R.string.svc_key_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = { Text(stringResource(R.string.svc_key_value)) },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { value = ApiKeyStore.generate(); error = null }) {
                    Text(stringResource(R.string.svc_key_random))
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.svc_key_tip) +
                        stringResource(R.string.svc_key_min, ApiKeyStore.MIN_LENGTH),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value, label) }) { Text(if (isNew) stringResource(R.string.svc_key_create) else stringResource(R.string.svc_key_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 可复制的一行。
 *
 * 做成整块可点而不是只给个小图标 —— 手机上点小图标很容易点偏，
 * 而这两个值（地址、key）是用户唯一要抄走的东西，必须一次点中。
 */
@Composable
private fun CopyableField(
    label: String,
    value: String,
    onCopy: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            TextButton(onClick = onCopy) { Text(stringResource(R.string.svc_copy)) }
            trailing?.invoke()
        }
    }
}

@Composable
private fun RequestRow(entry: io.github.zhbsrja.pocketnode.server.RequestLog) {
    val ok = entry.status in 200..299
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${entry.method} ${entry.path}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "${entry.remoteIp} · ${entry.durationMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                entry.status.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}
