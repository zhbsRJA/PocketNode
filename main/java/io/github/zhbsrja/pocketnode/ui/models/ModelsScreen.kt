package io.github.zhbsrja.pocketnode.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.zhbsrja.pocketnode.util.AppLog
import io.github.zhbsrja.pocketnode.util.ModelStorage
import io.github.zhbsrja.pocketnode.R
import io.github.zhbsrja.pocketnode.data.DownloadManager
import io.github.zhbsrja.pocketnode.data.DownloadState
import io.github.zhbsrja.pocketnode.data.ImportedModelStore
import io.github.zhbsrja.pocketnode.data.ModelCatalog
import io.github.zhbsrja.pocketnode.data.ModelFormat
import io.github.zhbsrja.pocketnode.data.ModelInfo
import io.github.zhbsrja.pocketnode.download.DownloadService
import io.github.zhbsrja.pocketnode.inference.EngineState
import io.github.zhbsrja.pocketnode.inference.LlmEngine

/**
 * 模型页。
 *
 * 列表 = 内置目录 + 用户导入的。导入入口在右上角，**是页面级的，不挂在卡片上** ——
 * 挂在卡片上就得先决定「这个文件填到哪个槽位」，但用户导入的往往是个
 * 全新的模型，跟内置那些没有对应关系。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(modifier: Modifier = Modifier) {
    val downloads by DownloadManager.states.collectAsState()
    val importProgress by DownloadManager.importProgress.collectAsState()
    val importError by DownloadManager.importError.collectAsState()
    val engine by LlmEngine.state.collectAsState()
    val models by ModelCatalog.all.collectAsState()

    // 选完文件后先弹窗问名字，确认了再真正开始复制
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    // 存储权限状态。授权与否会改变模型目录，所以要做成一个可观察的状态，
    // 用户从系统设置页回来时重新扫描一遍。
    val context = LocalContext.current
    var hasStorageAccess by remember { mutableStateOf(ModelStorage.hasAllFilesAccess()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = ModelStorage.hasAllFilesAccess()
                if (now != hasStorageAccess) {
                    hasStorageAccess = now
                    // 刚拿到权限：先把私有目录里的模型搬过去，
                    // 再重新扫描 —— 顺序反了会漏掉刚搬过去的文件。
                    if (now) {
                        ModelStorage.migrateFromPrivate(context)
                    }
                    DownloadManager.refreshLocalState()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingUri = uri
    }

    Scaffold(
        modifier = modifier,
        // 外层 MainScaffold 已经处理过系统栏 insets 了，这里不能再处理一次，
        // 否则 insets 翻倍。详见 MainScaffold 里的说明。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models_title)) },
                actions = {
                    // 导入中被禁用，避免同时拷两个大文件
                    TextButton(
                        onClick = {
                            // 用 */* 而不是具体 MIME：.task / .gguf 没有注册的
                            // MIME 类型，限定类型的话用户在文件管理器里
                            // 根本看不到自己的模型文件。
                            filePicker.launch(arrayOf("*/*"))
                        },
                        enabled = importProgress == null,
                    ) {
                        Text(stringResource(R.string.models_import), fontWeight = FontWeight.Medium)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

            // 存储位置：没授权时给个提示条，说明文件现在藏哪了、点哪去改
            if (!hasStorageAccess) {
                StorageBanner(
                    currentPath = ModelStorage.modelsDir(context).absolutePath,
                    onGrant = {
                        val intent = ModelStorage.buildPermissionIntent(context.packageName)
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                // 部分厂商 ROM 砍掉了「按包名跳转」的那个设置页，
                                // 退回通用的「所有文件访问」列表页，让用户自己找。
                                AppLog.w("ModelsScreen", "跳转失败，改用通用设置页", it)
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                    },
                )
            }

            // 导入进度条：独立于列表，因为这时模型还没登记进来
            importProgress?.let { p ->
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.models_importing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    val frac = p.fraction
                    if (frac != null) {
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(humanBytes(p.bytesCopied))
                            if (p.totalBytes > 0) append(" / ${humanBytes(p.totalBytes)}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(
                // weight(1f) 而不是 fillMaxSize()：这个 LazyColumn 上面还有
                // 提示条和导入进度条两个兄弟节点。用 fillMaxSize() 它会按
                // 「整屏高度」来量，而不是「剩下的高度」，底部内容会被挤到
                // 屏幕外面且滚不到 —— 平板上尤其明显。
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(models, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        download = downloads[model.id] ?: DownloadState.NotDownloaded,
                        engine = engine,
                    )
                }
            }
        }
    }

    // ── 命名弹窗 ──
    pendingUri?.let { uri ->
        NameImportDialog(
            defaultName = ImportedModelStore.nextDefaultName(),
            fileName = queryDisplayName(uri),
            onConfirm = { name, note ->
                DownloadManager.importFromUri(uri, name, note)
                pendingUri = null
            },
            onDismiss = { pendingUri = null },
        )
    }

    // ── 导入失败提示 ──
    importError?.let { msg ->
        AlertDialog(
            onDismissRequest = { DownloadManager.clearImportError() },
            title = { Text(stringResource(R.string.models_import_failed)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { DownloadManager.clearImportError() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}

/**
 * 存储位置提示条。
 *
 * 只在用户没给「所有文件访问权限」时出现 —— 这时候模型被迫存在
 * App 私有目录里，用户看不见也管不了，得说清楚怎么回事、怎么改。
 */
@Composable
private fun StorageBanner(currentPath: String, onGrant: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.models_private_title),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.models_private_current, currentPath),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.models_private_desc, PNAI_DIR),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onGrant) {
                Text(stringResource(R.string.models_grant))
            }
        }
    }
}

private const val PNAI_DIR = "PNAI"

/** 从 content Uri 里取出文件名，纯粹为了在弹窗里让用户确认选对了文件 */
@Composable
private fun queryDisplayName(uri: Uri): String {
    val context = LocalContext.current
    // 文案在外层取 —— remember 的 lambda 不是 @Composable 作用域，
    // 里面调 stringResource 会编译不过。
    val fallback = stringResource(R.string.models_unknown_file)
    return remember(uri, fallback) {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: fallback
    }
}

/**
 * 导入前的命名弹窗。
 *
 * 名字预填「模型 N」（N = 已有导入数量 + 1），简介留空。
 * 两个都可以不填，直接确认也能用 —— 不让用户为了试一下模型被迫想名字。
 */
@Composable
private fun NameImportDialog(
    defaultName: String,
    fileName: String,
    onConfirm: (name: String, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.models_import_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.models_import_selected, fileName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.models_name)) },
                    placeholder = { Text(defaultName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.models_note)) },
                    placeholder = { Text(stringResource(R.string.models_note_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, note) }) { Text(stringResource(R.string.models_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    download: DownloadState,
    engine: EngineState,
) {
    val context = LocalContext.current
    val isLoaded = engine is EngineState.Ready && engine.modelId == model.id
    val isLoading = engine is EngineState.Loading && engine.modelId == model.id

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (model.imported) stringResource(R.string.models_local_import) else model.specLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!model.imported) {
                    Text(
                        model.sizeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 简介：内置模型和导入模型走同一个字段，导入的没填就不显示
            // 内置模型的说明走资源（可翻译），导入模型的是用户自己填的原文。
            val noteText = if (model.noteRes != 0) stringResource(model.noteRes) else model.note
            if (noteText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    noteText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))

            when (download) {

                is DownloadState.NotDownloaded -> {
                    if (model.imported) {
                        // 理论上不该出现：导入的模型一定伴随文件存在。
                        // 真出现了说明文件被外部删了，给条出路而不是留个死卡片。
                        Text(
                            stringResource(R.string.models_file_lost),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { DownloadManager.delete(model) }) { Text(stringResource(R.string.models_remove)) }
                    } else {
                        Button(onClick = { DownloadService.start(context, model.id) }) {
                            Text(stringResource(R.string.models_download))
                        }
                    }
                }

                // 下了一半：继续 + 删除。
                // 用户点了取消、或者 App 被杀，磁盘上会留残file，
                // 不给删除入口的话那几百 MB 就变成"看不见但占着地方"。
                is DownloadState.Partial -> Column {
                    Text(
                        stringResource(R.string.models_partial, humanBytes(download.bytesOnDisk)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!model.imported) {
                            Button(onClick = { DownloadService.start(context, model.id) }) {
                                Text(stringResource(R.string.models_resume))
                            }
                        }
                        TextButton(onClick = { DownloadManager.delete(model) }) {
                            Text(stringResource(R.string.models_delete_file))
                        }
                    }
                }

                is DownloadState.Downloading -> DownloadingBlock(model, download)

                is DownloadState.Verifying -> Text(
                    stringResource(R.string.models_verifying),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                is DownloadState.Done -> DownloadedBlock(
                    model = model,
                    isLoaded = isLoaded,
                    isLoading = isLoading,
                    engine = engine,
                    onLoad = { LlmEngine.load(context, model) },
                    onUnload = { LlmEngine.unload() },
                    onDelete = {
                        if (isLoaded) LlmEngine.unload()
                        DownloadManager.delete(model)
                    },
                )

                is DownloadState.Failed -> Column {
                    Text(
                        stringResource(R.string.models_failed, download.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!model.imported) {
                            Button(onClick = { DownloadService.start(context, model.id) }) { Text(stringResource(R.string.models_retry)) }
                        }
                        TextButton(onClick = { DownloadManager.delete(model) }) { Text(stringResource(R.string.models_delete_file)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedBlock(
    model: ModelInfo,
    isLoaded: Boolean,
    isLoading: Boolean,
    engine: EngineState,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
) {
    // 删除是不可逆的，必须先问一次。误点一下就要重下 500MB~3.7GB
    var confirmDelete by remember { mutableStateOf(false) }

    Column {
        Text(
            text = if (isLoaded) stringResource(R.string.models_loaded) else stringResource(R.string.models_ready),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!model.imported) {
            Text(
                model.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        when {
            isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                Text("  " + stringResource(R.string.models_loading), style = MaterialTheme.typography.bodySmall)
            }

            isLoaded -> {
                val cost = (engine as? EngineState.Ready)?.loadMillis ?: 0L
                Column {
                    if (cost > 0) {
                        Text(
                            stringResource(R.string.models_load_cost, cost),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 两个按钮竖排。
                    //
                    // 原因：原来只有「卸载」和「删除」两个词并排，用户分不清
                    // 哪个动内存、哪个动磁盘 —— 一个可逆一个不可逆，搞错了
                    // 要么白删了 1.5GB 要重下，要么以为卸载了就删干净了。
                    // 名字改长之后横排会挤成一团，所以改成竖排占满宽度。
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 都是用同一种按钮、同样的 fillMaxWidth + 内容居中，
                        // 这样两个标签的基线才是对齐的。
                        // 之前一个 OutlinedButton 一个 TextButton，内边距不同，
                        // 文字看起来就偏了。
                        OutlinedButton(
                            onClick = onUnload,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.models_unload)) }

                        // 红色实心：这是破坏性操作，视觉上必须比上面那个重
                        Button(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text(stringResource(R.string.models_delete)) }
                    }

                    if (confirmDelete) {
                        AlertDialog(
                            onDismissRequest = { confirmDelete = false },
                            title = { Text(stringResource(R.string.models_delete_confirm_title)) },
                            text = {
                                Text(
                                    if (model.imported) {
                                        stringResource(R.string.models_delete_confirm_body_nosize, model.name)
                                    } else {
                                        stringResource(R.string.models_delete_confirm_body, model.name, model.sizeLabel)
                                    },
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmDelete = false
                                    onDelete()
                                }) {
                                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.models_go_chat),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            model.format != ModelFormat.MediaPipeTask -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.models_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDelete) { Text(stringResource(R.string.models_delete_file)) }
            }

            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoad) { Text(stringResource(R.string.models_load)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.models_delete_file)) }
            }
        }

        if (engine is EngineState.Failed && !isLoaded) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.models_last_failed, engine.message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DownloadingBlock(model: ModelInfo, state: DownloadState.Downloading) {
    Column {
        LinearProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${(state.fraction * 100).toInt()}%　" +
                        "${humanBytes(state.bytesRead)} / ${humanBytes(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    buildString {
                        if (state.speedBps > 0) append(formatSpeed(state.speedBps))
                        else append(stringResource(R.string.models_processing))
                        if (state.resumed) append(stringResource(R.string.models_resumable))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { DownloadManager.cancel(model.id) }) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

// ────────────────────────── 格式化助手 ──────────────────────────

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatSpeed(bps: Long): String = when {
    bps >= 1024 * 1024 -> String.format("%.1f MB/s", bps / 1024.0 / 1024)
    else -> String.format("%.0f KB/s", bps / 1024.0)
}
