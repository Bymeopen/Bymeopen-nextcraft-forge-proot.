@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.runtime.AntigravityAuthState
import com.nextcraft.forge.runtime.AntigravityAuthStatus
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

@Composable
fun AgentScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var promptInput by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val agyAuthState by viewModel.antigravityAuth.state.collectAsState()
    val scope = rememberCoroutineScope()
    val tokens = LocalForgeThemeTokens.current
    // hasOfficialCredential() may spawn `su` and block for seconds; never call it during composition.
    val hasAgyCredential by produceState(
        initialValue = true,
        state.agentKind,
        agyAuthState.status,
    ) {
        value = if (state.agentKind == AgentKind.ANTIGRAVITY) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                viewModel.antigravityAuth.hasOfficialCredential()
            }
        } else {
            true
        }
    }

    var previousMessageCount by remember { mutableStateOf(state.messages.size) }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            val lastIdx = state.messages.lastIndex
            if (state.messages.size != previousMessageCount) {
                previousMessageCount = state.messages.size
                listState.animateScrollToItem(lastIdx)
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= lastIdx - 1) {
                    listState.scrollToItem(lastIdx)
                }
            }
        } else {
            previousMessageCount = 0
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AgentHeader(
            state = state,
            onSelectChat = viewModel::selectChat,
            onCreateChat = { viewModel.createChat() },
            onDeleteChat = viewModel::deleteChat,
            onSelectAgent = viewModel::selectAgent,
            onClearChat = viewModel::clearActiveChat,
        )

        if (state.agentKind == AgentKind.ANTIGRAVITY &&
            agyAuthState.status != AntigravityAuthStatus.SIGNED_IN &&
            !hasAgyCredential
        ) {
            AntigravityOAuthBanner(
                authState = agyAuthState,
                onStartLogin = { scope.launch { viewModel.antigravityAuth.beginLogin() } },
                onSubmitCode = { code -> runCatching { viewModel.antigravityAuth.submitCode(code) } },
                onCancel = { viewModel.antigravityAuth.cancel() },
            )
        }

        state.activeStatusLine?.let { statusLine ->
            Surface(
                color = tokens.cardElevatedColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (state.sessionRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = statusLine,
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.accentText,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.sessionRunning) {
                            OutlinedButton(
                                onClick = viewModel::stopActiveTask,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text(stringResource(R.string.agent_stop), fontSize = 11.sp)
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        state.pendingToolRequest?.let { req ->
            ToolApprovalCard(
                request = req,
                onApprove = { viewModel.respondToToolRequest(true) },
                onReject = { viewModel.respondToToolRequest(false) },
            )
        }

        if (state.messages.isEmpty()) {
            EmptyAgentWelcome(
                state = state,
                onQuickPrompt = { quick ->
                    viewModel.sendPrompt(quick)
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    ChatMessageCard(
                        message = message,
                        agentName = state.agentKind.displayTitle(),
                        onRunSnippet = { cmd ->
                            viewModel.runTerminalCommand(cmd)
                            viewModel.selectTab(ForgeTab.TERMINAL)
                        },
                    )
                }
            }
        }

        // Linear-style composer: one rounded surface with the input, a provider chip and a round send/stop button.
        val running = state.sessionRunning
        val canSend = promptInput.isNotBlank()
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = tokens.cardContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = tokens.cardBorderStroke,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                TextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.agent_ask_to_build_refactor, state.agentKind.displayTitle()),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The provider and model are configured in Settings, so the chip is the shortcut there.
                    AssistChip(
                        onClick = { viewModel.selectTab(ForgeTab.SETTINGS) },
                        label = {
                            Text(
                                state.agentKind.displayTitle(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        shape = RoundedCornerShape(100),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    running -> MaterialTheme.colorScheme.error
                                    canSend -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                                },
                            )
                            .clickable(
                                enabled = running || canSend,
                                role = Role.Button,
                                onClickLabel = stringResource(if (running) R.string.agent_stop else R.string.agent_send),
                            ) {
                                if (running) {
                                    viewModel.stopActiveTask()
                                } else {
                                    val text = promptInput.trim()
                                    if (text.isNotEmpty()) {
                                        promptInput = ""
                                        viewModel.sendPrompt(text)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (running) Icons.Default.Close else Icons.Default.Send,
                            contentDescription = stringResource(if (running) R.string.agent_stop else R.string.agent_send),
                            tint = when {
                                running -> MaterialTheme.colorScheme.onError
                                canSend -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Large title + action pill (agent switcher, new chat) and pill tabs for the chats of the project. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentHeader(
    state: ForgeUiState,
    onSelectChat: (String) -> Unit,
    onCreateChat: () -> Unit,
    onDeleteChat: (String) -> Unit,
    onSelectAgent: (AgentKind) -> Unit,
    onClearChat: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeLargeTitle(
                title = stringResource(R.string.agent_title),
                subtitle = listOfNotNull(state.activeProject?.name, state.agentKind.displayTitle()).joinToString(" · "),
                modifier = Modifier.weight(1f),
            )
            ForgeActionPill {
                ForgePillIcon(
                    icon = Icons.Default.Add,
                    description = stringResource(R.string.agent_new_chat),
                    onClick = onCreateChat,
                )
                Box {
                    ForgePillIcon(
                        icon = Icons.Default.MoreVert,
                        description = stringResource(R.string.agent_more),
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Text(
                            text = stringResource(R.string.agent_switch_agent),
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        AgentKind.entries.forEach { kind ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(kind.displayTitle(), fontWeight = FontWeight.SemiBold)
                                        Text(kind.displaySubtitle(), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                                    }
                                },
                                leadingIcon = if (kind == state.agentKind) {
                                    { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.a11y_selected)) }
                                } else {
                                    null
                                },
                                enabled = !state.sessionRunning,
                                onClick = {
                                    menuOpen = false
                                    onSelectAgent(kind)
                                },
                            )
                        }
                        if (state.messages.isNotEmpty()) {
                            Divider(color = scheme.outlineVariant, thickness = 0.5.dp)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.agent_clear_chat)) },
                                enabled = !state.sessionRunning,
                                onClick = {
                                    menuOpen = false
                                    onClearChat()
                                },
                            )
                        }
                    }
                }
            }
        }
        if (state.chats.isNotEmpty()) {
            val titles = state.chats.map { it.displayTitle() }
            val selectedIndex = state.chats.indexOfFirst { it.id == state.activeChatId }.coerceAtLeast(0)
            // Long-press the active chat's tab to delete it (only when more than one chat exists).
            ForgePillTabs(
                items = titles,
                selectedIndex = selectedIndex,
                onSelect = { onSelectChat(state.chats[it].id) },
                onLongPress = if (state.chats.size > 1) {
                    { index -> if (index == selectedIndex) onDeleteChat(state.chats[index].id) }
                } else {
                    null
                },
                longPressLabel = stringResource(R.string.agent_delete_chat),
            )
        }
    }
}

@Composable
private fun AntigravityOAuthBanner(
    authState: AntigravityAuthState,
    onStartLogin: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    var verificationCode by rememberSaveable { mutableStateOf("") }
    val busy = authState.status == AntigravityAuthStatus.STARTING || authState.status == AntigravityAuthStatus.COMPLETING

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.agent_google_account), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = authState.message ?: stringResource(R.string.agent_required_for_antigravity),
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            authState.authorizationUrl?.let { url ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgePrimaryButton(
                        text = stringResource(R.string.agent_open_google_sign_in),
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(url)) },
                        shape = RoundedCornerShape(100),
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.agent_copy_link)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it },
                        placeholder = { Text(stringResource(R.string.agent_paste_google_verification_code), fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                    ForgePrimaryButton(
                        text = stringResource(R.string.agent_verify),
                        enabled = verificationCode.isNotBlank(),
                        onClick = {
                            onSubmitCode(verificationCode)
                            verificationCode = ""
                        },
                    )
                }
            }
            when {
                authState.status == AntigravityAuthStatus.SIGNED_OUT || authState.status == AntigravityAuthStatus.ERROR ->
                    ForgePrimaryButton(
                        text = stringResource(R.string.agent_sign_in_with_google),
                        onClick = onStartLogin,
                        modifier = Modifier.fillMaxWidth(),
                    )
                busy -> OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(100),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(stringResource(R.string.agent_cancel)) }
            }
        }
    }
}

@Composable
private fun ToolApprovalCard(
    request: ToolRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row {
            // Thin red rule carries the warning so the card itself stays calm.
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(scheme.error),
            )
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ForgeStatusIcon(ForgeStatus.FAILED, iconSize = 20.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_approval_title), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = stringResource(R.string.agent_approval_sub, request.toolName, request.risk.name.lowercase()),
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                Text(text = request.explanation, fontSize = 14.sp, color = scheme.onSurfaceVariant)
                request.commandPreview?.takeIf { it.isNotBlank() }?.let { cmd ->
                    Text(
                        text = cmd,
                        style = ForgeCodeTextStyle,
                        color = tokens.terminalText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.terminalBackground)
                            .padding(10.dp),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onReject,
                        shape = RoundedCornerShape(100),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.agent_deny)) }
                    Button(
                        onClick = onApprove,
                        shape = RoundedCornerShape(100),
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.agent_approve_execution), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun EmptyAgentWelcome(
    state: ForgeUiState,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val suggestions = listOf(
        stringResource(R.string.agent_sugg_inspect),
        stringResource(R.string.agent_sugg_compose),
        stringResource(R.string.agent_sugg_tests),
        stringResource(R.string.agent_sugg_audit),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.agent_welcome_hint),
            fontSize = 14.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        suggestions.forEach { prompt ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { onQuickPrompt(prompt) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(text = prompt, fontSize = 15.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChatMessageCard(
    message: ChatMessage,
    agentName: String,
    onRunSnippet: (String) -> Unit,
) {
    var expandTools by remember { mutableStateOf(false) }
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val textColor = scheme.onSurface

    MessageContainer(fromUser = message.fromUser, contentColor = textColor) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (message.fromUser) "You" else agentName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (message.fromUser) scheme.onSurfaceVariant else tokens.accentText,
                )
                if (message.workItems.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(if (expandTools) R.string.a11y_collapse else R.string.a11y_expand),
                            ) { expandTools = !expandTools }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.agent_tool_steps, message.workItems.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor,
                        )
                        Icon(
                            imageVector = if (expandTools) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            if (expandTools && message.workItems.isNotEmpty()) {
                ToolActivityList(items = message.workItems)
            }

            if (message.text.isNotBlank()) {
                MarkdownText(
                    markdown = message.text,
                    color = textColor,
                    onRunCode = onRunSnippet,
                )
            } else if (!message.fromUser) {
                Text(
                    text = stringResource(R.string.agent_thinking),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** User messages sit in a soft rounded surface; agent replies flow on the canvas without a card. */
@Composable
private fun MessageContainer(
    fromUser: Boolean,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    if (fromUser) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = LocalForgeThemeTokens.current.cardElevatedColor,
            contentColor = contentColor,
            modifier = Modifier.fillMaxWidth(),
        ) { content() }
    } else {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) { content() }
    }
}

@Composable
private fun ToolActivityList(items: List<ActivityItem>) {
    val tokens = LocalForgeThemeTokens.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.cardElevatedColor)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                ForgeStatusIcon(
                    status = if (item.isComplete) ForgeStatus.DONE else ForgeStatus.RUNNING,
                    iconSize = 15.dp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    if (item.detail.isNotBlank()) {
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = if (item.isCommand) FontFamily.Monospace else FontFamily.Default,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
