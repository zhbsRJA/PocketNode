package io.github.zhbsrja.pocketnode.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zhbsrja.pocketnode.R
import io.github.zhbsrja.pocketnode.data.ChatMessage
import io.github.zhbsrja.pocketnode.data.Conversation
import io.github.zhbsrja.pocketnode.data.ConversationStore
import io.github.zhbsrja.pocketnode.data.ModelCatalog
import io.github.zhbsrja.pocketnode.data.SearchClient
import io.github.zhbsrja.pocketnode.inference.EngineState
import io.github.zhbsrja.pocketnode.inference.LlmEngine
import kotlinx.coroutines.launch

/**
 * 对话页。
 *
 * ── 布局 ────────────────────────────────────────────────────────
 * 外面套一层 ModalNavigationDrawer（左侧滑出的抽屉），里面是原来的
 * 聊天界面。顶栏左上角加了个菜单按钮打开抽屉。
 *
 * ── 会话的保存时机 ──────────────────────────────────────────────
 * **每加一条消息就存一次**（整体覆盖写）。
 *
 * 不做"退出时保存"，因为退出方式太多了：返回键、划掉任务、被系统杀、
 * 崩溃——总有一个你覆盖不到。**边聊边存最笨也最可靠**，会话文件就
 * 几百 KB，写的开销可以忽略。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engineState by LlmEngine.state.collectAsState()
    val conversations by ConversationStore.conversations.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()

    // 当前打开的会话。null 表示还没开始聊
    var currentId by remember { mutableStateOf<String?>(null) }
    // 当前会话的消息。用 mutableStateListOf 而不是从 store 里读 ——
    // 这样生成过程中逐字更新不会触发磁盘写
    val messages = remember { mutableStateListOf<ChatMessage>() }

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 联网搜索开关。
    //
    // **手动开，不让模型自己决定要不要搜。** 手机上跑的是 0.5B–3B 的小模型，
    // 做不了可靠的 function calling —— 让它判断"这问题要不要搜索"，
    // 十次里有五次会错，而且错法难预测。手动开关行为完全可预期。
    var webSearch by remember { mutableStateOf(false) }

    // 三点菜单是否展开
    var menuOpen by remember { mutableStateOf(false) }

    // 文案提到这里 —— send() 不是 @Composable 作用域，里面调不了 stringResource
    val strNotReady = stringResource(R.string.chat_model_not_ready)
    val strGenerating = stringResource(R.string.chat_generating)
    val strEmptyReply = stringResource(R.string.chat_empty_reply)
    val strErrorPrefix = stringResource(R.string.chat_error_prefix)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    /** 把当前消息落盘。每次改动后调用 */
    fun persist() {
        val id = currentId ?: return
        val conv = ConversationStore.find(id) ?: return
        ConversationStore.save(
            conv.copy(
                title = if (conv.messages.isEmpty() && messages.isNotEmpty()) {
                    ConversationStore.titleFrom(messages.first().content)
                } else conv.title,
                messages = messages.toList(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun openConversation(c: Conversation) {
        currentId = c.id
        messages.clear()
        messages.addAll(c.messages)
    }

    fun newConversation() {
        currentId = null
        messages.clear()
        input = ""
    }

    /** 抽出生成逻辑，因为搜索是异步的，得在里面回调 */
    fun generateReply(prompt: String, thinkingIdx: Int) {

        // 用流式接口 —— 逐字出字，而不是等全部生成完一次性蹦出来。
        //
        // 手机上生成 100 个字要十秒左右。非流式的话用户要盯着屏幕
        // 干等十秒什么反应都没有，会以为死机了。流式的话第一个字
        // 一两秒就出来，体感完全不同。
        LlmEngine.generateStreaming(
            prompt = prompt,
            onPartial = { accumulated ->
                // ⚠️ 这里收到的是**累计全文**，不是增量片段。
                // MediaPipe 的 ProgressListener 给的就是"到目前为止的完整输出"。
                // 直接替换整条消息内容即可 —— 不要自己算差值再拼接，
                // 那样会得到重复叠加的文字。
                scope.launch {
                    if (thinkingIdx < messages.size) {
                        messages[thinkingIdx] = ChatMessage("assistant", accumulated)
                    }
                }
            },
            onDone = { full ->
                scope.launch {
                    if (full.isBlank() && thinkingIdx < messages.size) {
                        messages[thinkingIdx] = ChatMessage("assistant", strEmptyReply, error = true)
                    }
                    busy = false
                    persist()
                }
            },
            onError = { t ->
                scope.launch {
                    val msg = strErrorPrefix + (t.message ?: t.javaClass.simpleName)
                    if (thinkingIdx < messages.size) {
                        messages[thinkingIdx] = ChatMessage("assistant", msg, error = true)
                    }
                    busy = false
                    persist()
                }
            },
        )
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return

        val ready = engineState as? EngineState.Ready
        if (ready == null) {
            errorText = strNotReady
            return
        }
        errorText = null

        // 第一次发消息时才真正创建会话文件 ——
        // 否则用户点十次"新建对话"会在磁盘上留十个空文件
        if (currentId == null) {
            val c = ConversationStore.create(ConversationStore.titleFrom(text))
            currentId = c.id
        }

        val modelName = ModelCatalog.findById(ready.modelId)?.name ?: ready.modelId

        messages.add(ChatMessage("user", text))
        input = ""
        busy = true
        persist()

        val thinkingIdx = messages.size
        messages.add(ChatMessage("assistant", strGenerating))

        // 开了搜索就先搜一轮，把结果拼进 prompt。
        // 搜索在独立协程里做，因为它要几秒 —— 不能卡住 UI 线程。
        scope.launch {
            var prompt = text
            if (webSearch) {
                val results = SearchClient.search(text)
                when {
                    results == null -> {
                        // 搜索失败不阻塞对话 —— 明确告诉用户这次没搜到，
                        // 然后照常回答。比静默降级好，用户知道结果里没有时效信息。
                        errorText = context.getString(R.string.chat_search_failed)
                    }
                    results.isEmpty() -> {
                        errorText = context.getString(R.string.chat_search_empty)
                    }
                    else -> {
                        // 搜索结果放在前面、用户问题放在后面。
                        // 用小模型时"离问题最近的内容影响最大"，
                        // 所以把问题压在最后，让它成为模型最关注的输入。
                        prompt = SearchClient.formatForPrompt(results) +
                            "用户的问题：" + text
                    }
                }
            }
            generateReply(prompt, thinkingIdx)
        }
    }

    // ── 导入：用户选一个 json 文件 ──
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { ConversationStore.import(uri) }
                    .onSuccess { n -> errorText = context.getString(R.string.chat_import_ok, n) }
                    .onFailure { e -> errorText = context.getString(R.string.chat_import_fail, e.message ?: "") }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = conversations,
                currentId = currentId,
                onPick = { c -> openConversation(c); scope.launch { drawerState.close() } },
                onNew = { newConversation(); scope.launch { drawerState.close() } },
                onDelete = { id ->
                    ConversationStore.delete(id)
                    if (currentId == id) newConversation()
                },
                onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                onExport = { ids, uri ->
                    scope.launch {
                        runCatching { ConversationStore.exportTo(uri, ids) }
                            .onSuccess { n -> errorText = context.getString(R.string.chat_export_ok, n) }
                            .onFailure { e -> errorText = context.getString(R.string.chat_export_fail, e.message ?: "") }
                    }
                },
                closeDrawer = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            modifier = modifier,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.chat_menu))
                        }
                    },
                    title = { Text(stringResource(R.string.nav_chat)) },
                    actions = {
                        val label = when (val s = engineState) {
                            is EngineState.Ready -> ModelCatalog.findById(s.modelId)?.parameters
                                ?: stringResource(R.string.chat_state_ready)
                            is EngineState.Loading -> stringResource(R.string.chat_state_loading)
                            is EngineState.Generating -> stringResource(R.string.chat_state_generating)
                            is EngineState.Failed -> stringResource(R.string.chat_state_failed)
                            is EngineState.Idle -> stringResource(R.string.chat_state_idle)
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = when (engineState) {
                                is EngineState.Failed -> MaterialTheme.colorScheme.error
                                is EngineState.Ready -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    },
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // ── 三点菜单 ──
                        //
                        // 放左下角而不是顶栏，因为这里是"输入相关"的开关所在：
                        // 联网搜索影响的是"下一条消息怎么发"，不是页面级的操作。
                        // GPT 也是这个位置，用户有肌肉记忆。
                        //
                        // 用 Box 包住按钮和菜单 —— DropdownMenu 需要一个锚点，
                        // 它会自动对齐到这个 Box 的位置。
                        Box {
                            IconButton(
                                onClick = { menuOpen = !menuOpen },
                                modifier = Modifier.padding(bottom = 4.dp),
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.chat_more),
                                    // 开着联网搜索时图标变色 —— 菜单收起来之后
                                    // 用户还能看出"搜索是开着的"
                                    tint = if (webSearch) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            DropdownMenu(
                                expanded = menuOpen,
                                // 点外面自动关。这是 DropdownMenu 自带的行为，
                                // 不用自己写 —— 自己写反而容易漏掉返回键和
                                // 触摸外部这两种关闭路径
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.chat_web_search_label))
                                    },
                                    onClick = {
                                        webSearch = !webSearch
                                        menuOpen = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = null,
                                            tint = if (webSearch) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    trailingIcon = {
                                        // 只有开着的时候显示勾 —— 没开就什么都不显示，
                                        // 比显示一个灰色勾更清楚
                                        if (webSearch) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                )
                            }
                        }

                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    if (busy) stringResource(R.string.chat_generating)
                                    else stringResource(R.string.chat_input_hint),
                                )
                            },
                            maxLines = 4,
                            enabled = !busy,
                            shape = RoundedCornerShape(24.dp),
                        )
                        FilledIconButton(
                            onClick = { send() },
                            enabled = !busy && input.isNotBlank(),
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                ModelStatusBanner(engineState)

                errorText?.let { t ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(onClick = { errorText = null }) {
                                Text(stringResource(R.string.common_ok))
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages) { msg -> MessageBubble(msg) }
                }
            }
        }
    }
}

/**
 * 侧边栏。
 *
 * 两种模式：
 *   · 普通 —— 点一下打开那个会话
 *   · 选择 —— 进入后每项左边出现勾选框，可以多选导出
 *
 * 为什么用"长按进入选择模式"而不是每项常驻勾选框：
 * 日常使用里 99% 的操作是"打开某个会话"，勾选框会一直占地方、
 * 而且容易误触。长按是移动端通用的"批量操作"入口。
 */
@Composable
private fun ConversationDrawer(
    conversations: List<Conversation>,
    currentId: String?,
    onPick: (Conversation) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
    onExport: (List<String>, android.net.Uri) -> Unit,
    closeDrawer: () -> Unit,
) {
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null && selected.isNotEmpty()) {
            onExport(selected.toList(), uri)
            selecting = false
            selected.clear()
        }
    }

    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.chat_conversations),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 10.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onNew) {
                    Text(stringResource(R.string.chat_new))
                }
                TextButton(onClick = onImport) {
                    Text(stringResource(R.string.chat_import))
                }
                if (conversations.isNotEmpty()) {
                    TextButton(onClick = {
                        selecting = !selecting
                        selected.clear()
                    }) {
                        Text(
                            if (selecting) stringResource(R.string.common_cancel)
                            else stringResource(R.string.chat_select),
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            if (conversations.isEmpty()) {
                Text(
                    stringResource(R.string.chat_no_conversations),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(conversations, key = { it.id }) { c ->
                    val isSel = c.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (selecting) {
                                    if (isSel) selected.remove(c.id) else selected.add(c.id)
                                } else onPick(c)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selecting) {
                            Text(
                                if (isSel) "☑" else "☐",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isSel) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                c.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (c.id == currentId && !selecting) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                stringResource(R.string.chat_msg_count, c.messages.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!selecting) {
                            IconButton(onClick = { onDelete(c.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.common_delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (selecting) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.chat_selected_count, selected.size),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            // 文件名只是给用户看的，具体内容由 store 决定
                            exportLauncher.launch("pocketnode_chats.json")
                        },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.chat_export))
                    }
                }
            }
        }
    }
}

/**
 * 顶部的模型状态横条。
 *
 * 它是**状态**不是**内容** —— 模型加载好之后必须立刻消失。
 * 写死的聊天记录做不到这点（会一直赖在对话里）。
 */
@Composable
private fun ModelStatusBanner(state: EngineState) {
    if (state is EngineState.Ready || state is EngineState.Generating) return

    val text: String
    val bg: androidx.compose.ui.graphics.Color
    when (state) {
        is EngineState.Idle -> {
            text = stringResource(R.string.chat_banner_idle)
            bg = MaterialTheme.colorScheme.errorContainer
        }
        is EngineState.Loading -> {
            text = stringResource(R.string.chat_banner_loading)
            bg = MaterialTheme.colorScheme.secondaryContainer
        }
        is EngineState.Failed -> {
            text = stringResource(R.string.chat_banner_failed) + state.message
            bg = MaterialTheme.colorScheme.errorContainer
        }
        else -> return
    }

    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val fromUser = msg.role == "user"
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = when {
                msg.error -> MaterialTheme.colorScheme.errorContainer
                fromUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                msg.content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
