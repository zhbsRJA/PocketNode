package io.github.zhbsrja.pocketnode.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zhbsrja.pocketnode.R
import io.github.zhbsrja.pocketnode.data.ModelCatalog
import io.github.zhbsrja.pocketnode.inference.EngineState
import io.github.zhbsrja.pocketnode.inference.LlmEngine
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.launch

private data class Message(
    val text: String,
    val fromUser: Boolean,
    val error: Boolean = false,
)

private const val TAG = "Chat"

/**
 * 对话页。
 *
 * 现在的流程：
 *   用户输入 → 拼成 prompt → LlmEngine.generate → 结果作为 assistant 消息追加
 *
 * 还没有做的事（按重要性排）：
 *   1. 多轮上下文 —— 现在每轮都是独立的，模型不记得上一句。
 *      真做起来要把历史拼进 prompt，还要考虑超出上下文窗口时怎么截断。
 *   2. 流式输出 —— 引擎的同步接口是整段返回的。要逐字显示得换用
 *      generateResponseAsync + ProgressListener。
 *   3. 会话持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val engineState by LlmEngine.state.collectAsState()
    val scope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<Message>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // send() 是普通局部函数不是 @Composable，里面不能调 stringResource。
    // 所以在 Composable 作用域先把这些文案取出来，再带进去。
    val strNotReady = stringResource(R.string.chat_model_not_ready)
    val strGenerating = stringResource(R.string.chat_generating)
    val strEmptyReply = stringResource(R.string.chat_empty_reply)
    val strErrorPrefix = stringResource(R.string.chat_error_prefix)

    // 这里刻意**不**往消息列表里塞“还没有加载模型”这句话。
    // 原因：那样它是一条写死的聊天记录，模型加载好之后还会赖在对话里，
    // 看起来像模型说的话。改成顶部横条（ModelStatusBanner），
    // 它随引擎状态实时出现和消失，不污染对话内容。

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return

        val ready = engineState as? EngineState.Ready
        if (ready == null) {
            messages.add(Message(strNotReady, fromUser = false, error = true))
            input = ""
            return
        }

        val modelName = ModelCatalog.findById(ready.modelId)?.name ?: ready.modelId

        messages.add(Message(text, fromUser = true))
        input = ""
        busy = true

        val thinking = Message(strGenerating, fromUser = false)
        messages.add(thinking)

        AppLog.i(TAG, "发送给模型，长度=${text.length}")

        LlmEngine.generate(
            prompt = text,
            onPartial = { reply ->
                scope.launch {
                    val idx = messages.indexOf(thinking)
                    if (idx >= 0) {
                        messages[idx] = Message(reply, fromUser = false)
                    } else {
                        messages.add(Message(reply, fromUser = false))
                    }
                    busy = false
                }
            },
            onDone = { full ->
                scope.launch {
                    AppLog.i(TAG, "生成结束，长度=${full.length}，模型=$modelName")
                    if (full.isBlank()) {
                        val idx = messages.indexOf(thinking)
                        if (idx >= 0) messages[idx] = Message(strEmptyReply, fromUser = false)
                    }
                    busy = false
                }
            },
            onError = { t ->
                scope.launch {
                    AppLog.e(TAG, "生成失败", t)
                    val idx = messages.indexOf(thinking)
                    val msg = strErrorPrefix + (t.message ?: t.javaClass.simpleName)
                    if (idx >= 0) messages[idx] = Message(msg, fromUser = false, error = true)
                    else messages.add(Message(msg, fromUser = false, error = true))
                    busy = false
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        // 外层 MainScaffold 已处理系统栏 insets，这里不能重复处理
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_chat)) },
                actions = {
                    val label = when (val s = engineState) {
                        is EngineState.Ready -> ModelCatalog.findById(s.modelId)?.parameters ?: stringResource(R.string.chat_state_ready)
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (busy) stringResource(R.string.chat_generating) else stringResource(R.string.chat_input_hint)) },
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
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
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
            // 顶部状态横条。engineState 是 StateFlow，模型一加载好它
            // 立刻消失，不用手动刷新。
            ModelStatusBanner(engineState)

            LazyColumn(
                state = listState,
                // weight(1f) 而不是 fillMaxSize()：上面还有横条这个兄弟节点，
                // 用 fillMaxSize 会按整屏高度量，底部内容会被挤出去且滚不到。
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages) { msg -> MessageBubble(msg) }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val bubbleColor = when {
        message.error -> MaterialTheme.colorScheme.errorContainer
        message.fromUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = when {
        message.error -> MaterialTheme.colorScheme.onErrorContainer
        message.fromUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.fromUser) 18.dp else 4.dp,
                bottomEnd = if (message.fromUser) 4.dp else 18.dp,
            ),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = message.text,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * 顶部的模型状态横条。
 *
 * 为什么做成横条而不是聊天里的一条消息：
 *   它是**状态**，不是**内容**。模型加载好之后必须立刻消失。
 *
 * engineState 是 StateFlow，这里随它实时变化，不需要手动刷新。
 */
@Composable
private fun ModelStatusBanner(state: EngineState) {
    // 就绪和生成中都属于正常状态，不占地方
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
