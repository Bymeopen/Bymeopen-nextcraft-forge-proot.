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
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowBack
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
            onBack = { viewModel.selectTab(ForgeTab.PROJECTS) },
            onRun = { viewModel.selectTab(ForgeTab.ANDROID) },
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
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
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
                // "Latest" pill: shown once the reader has scrolled up from the newest message.
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val awayFromEnd = state.messages.size > 1 && lastVisible < state.messages.lastIndex
                if (awayFromEnd) {
                    Surface(
                        shape = RoundedCornerShape(100),
                        color = tokens.cardElevatedColor,
                        contentColor = tokens.accentText,
                        border = tokens.cardBorderStroke,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(100))
                            .clickable(role = Role.Button) { scope.launch { listState.animateScrollToItem(state.messages.lastIndex) } },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.agent_latest), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
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
    onBack: () -> Unit,
    onRun: () -> Unit,
    onSelectChat: (String) -> Unit,
    onCreateChat: () -> Unit,
    onDeleteChat: (String) -> Unit,
    onSelectAgent: (AgentKind) -> Unit,
    onClearChat: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var chatsOpen by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val activeChat = state.chats.firstOrNull { it.id == state.activeChatId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 14.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back to Projects: the project name is the title, so this header reads as "inside a project".
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.a11y_back), tint = scheme.onSurface)
        }
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = state.activeProject?.name ?: stringResource(R.string.agent_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(state.agentKind.displayTitle(), activeChat?.displayTitle()).joinToString(" · "),
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ForgeActionPill {
            ForgePillIcon(
                icon = Icons.Default.PlayArrow,
                description = stringResource(R.string.launch_build_and_install_apk),
                onClick = onRun,
            )
            ForgePillIcon(
                icon = Icons.Default.Refresh,
                description = stringResource(R.string.agent_project_chats),
                onClick = { chatsOpen = true },
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

    if (chatsOpen) {
        ProjectChatsDialog(
            state = state,
            onDismiss = { chatsOpen = false },
            onCreateChat = {
                chatsOpen = false
                onCreateChat()
            },
            onSelectChat = {
                chatsOpen = false
                onSelectChat(it)
            },
            onDeleteChat = onDeleteChat,
        )
    }
}

/** "Project chats": one primary New chat action, then every chat of the project with the current one checked. */
@Composable
private fun ProjectChatsDialog(
    state: ForgeUiState,
    onDismiss: () -> Unit,
    onCreateChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = tokens.cardContainerColor,
        title = { Text(stringResource(R.string.agent_project_chats), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ForgePrimaryButton(
                    text = stringResource(R.string.agent_new_chat),
                    onClick = onCreateChat,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.chats.forEach { chat ->
                    val current = chat.id == state.activeChatId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (current) scheme.primary.copy(alpha = 0.12f) else tokens.cardElevatedColor)
                            .clickable(role = Role.Button) { onSelectChat(chat.id) }
                            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(chat.displayTitle(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (current) {
                                Text(stringResource(R.string.agent_current_chat), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            }
                        }
                        if (current) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.a11y_selected), tint = scheme.primary)
                        } else if (state.chats.size > 1) {
                            IconButton(onClick = { onDeleteChat(chat.id) }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.agent_delete_chat), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_close)) }
        },
    )
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
    val scheme = MaterialTheme.colorScheme
    val textColor = scheme.onSurface

    if (message.fromUser) {
        MessageContainer(fromUser = true, contentColor = textColor) {
            Column(modifier = Modifier.padding(12.dp)) {
                MarkdownText(markdown = message.text, color = textColor, onRunCode = onRunSnippet)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Each tool call is one collapsed row ("Bash · command ⌄"); tap to read its detail.
        message.workItems.forEach { item -> ToolCallRow(item) }
        if (message.workedMillis > 0L) {
            Text(
                text = stringResource(R.string.agent_worked_for, formatWorked(message.workedMillis)),
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
            )
        }
        if (message.text.isNotBlank()) {
            MessageContainer(fromUser = false, contentColor = textColor) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    MarkdownText(markdown = message.text, color = textColor, onRunCode = onRunSnippet)
                }
            }
        } else if (message.workItems.isEmpty()) {
            Text(
                text = stringResource(R.string.agent_thinking),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private fun formatWorked(millis: Long): String {
    val totalSec = millis / 1000
    val m = totalSec / 60
    val sec = totalSec % 60
    return if (m > 0) "${m}m ${sec}s" else "${sec}s"
}

/** One collapsed tool call: status glyph, "Title · detail" on one line, chevron; expands to the full detail. */
@Composable
private fun ToolCallRow(item: ActivityItem) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.a11y_collapse else R.string.a11y_expand)) { open = !open }
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForgeStatusIcon(
                status = if (item.isComplete) ForgeStatus.DONE else ForgeStatus.RUNNING,
                iconSize = 15.dp,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (item.detail.isBlank()) item.title else "${item.title} · ${item.detail.lineSequence().first()}",
                fontSize = 14.sp,
                color = scheme.onSurfaceVariant,
                fontFamily = if (item.isCommand) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (open && item.detail.isNotBlank()) {
            Text(
                text = item.detail,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = tokens.terminalText,
                fontFamily = if (item.isCommand) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.terminalBackground)
                    .padding(10.dp),
            )
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
