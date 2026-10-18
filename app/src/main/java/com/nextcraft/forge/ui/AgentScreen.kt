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

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatTabsRow(
            state = state,
            onSelectChat = viewModel::selectChat,
            onCreateChat = { viewModel.createChat() },
            onDeleteChat = viewModel::deleteChat,
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
                                Text("Stop", fontSize = 11.sp)
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
                            "Ask ${state.agentKind.displayTitle()} to build, refactor, or fix…",
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
                                onClickLabel = if (running) "Stop" else "Send",
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
                            contentDescription = if (running) "Stop" else "Send",
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

@Composable
private fun ChatTabsRow(
    state: ForgeUiState,
    onSelectChat: (String) -> Unit,
    onCreateChat: () -> Unit,
    onDeleteChat: (String) -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    Surface(
        color = tokens.topBarContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.chats.forEach { chat ->
                    val selected = chat.id == state.activeChatId
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectChat(chat.id) },
                        label = {
                            Text(
                                text = chat.displayTitle(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = if (state.chats.size > 1 && selected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete chat",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { onDeleteChat(chat.id) },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
                AssistChip(
                    onClick = onCreateChat,
                    label = { Text("New Chat") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
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
    var verificationCode by rememberSaveable { mutableStateOf("") }

    ForgeThemedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = tokens.accentText,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Antigravity CLI Google Account Sign-In",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            authState.message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            authState.authorizationUrl?.let { url ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        },
                    ) {
                        Text("Open Google Sign-In")
                    }
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(url)) },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Link")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it },
                        label = { Text("Paste Google Verification Code") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            if (verificationCode.isNotBlank()) {
                                onSubmitCode(verificationCode)
                                verificationCode = ""
                            }
                        },
                    ) {
                        Text("Verify")
                    }
                }
            }
            if (authState.status == AntigravityAuthStatus.SIGNED_OUT ||
                authState.status == AntigravityAuthStatus.ERROR
            ) {
                Button(onClick = onStartLogin) {
                    Text("Sign In with Google (Official PTY OAuth)")
                }
            } else if (authState.status == AntigravityAuthStatus.STARTING ||
                authState.status == AntigravityAuthStatus.COMPLETING
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
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
    val shape = RoundedCornerShape(tokens.cardCornerRadius)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "High-Risk Tool Approval: ${request.toolName} (${request.risk.name})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = request.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            request.commandPreview?.takeIf { it.isNotBlank() }?.let { cmd ->
                val cmdShape = RoundedCornerShape(8.dp)
                Text(
                    text = cmd,
                    style = ForgeCodeTextStyle,
                    color = tokens.terminalText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cmdShape)
                        .background(tokens.terminalBackground)
                        .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), cmdShape)
                        .padding(8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = onReject,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Deny")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Approve Execution")
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
    val suggestions = listOf(
        "Inspect this workspace and summarize its architecture.",
        "Create a responsive Jetpack Compose screen with clean state management.",
        "Run the test suite or verify scripts inside the Ubuntu Root Chroot.",
        "Audit the codebase for concurrency, security, and edge-case bugs.",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.agentKind.displayTitle(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Active Workspace: /workspace/${state.activeProject?.slug ?: "sandbox"} • Model: ${
                if (state.agentKind == AgentKind.ANTIGRAVITY) state.antigravityModel else state.providerProfile.model
            }",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { prompt ->
                ForgeThemedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onQuickPrompt(prompt) },
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
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
                            .clickable { expandTools = !expandTools }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${message.workItems.size} tool steps",
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
                    text = "Thinking…",
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
