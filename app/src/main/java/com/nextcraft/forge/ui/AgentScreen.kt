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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.runtime.AntigravityAuthState
import com.nextcraft.forge.runtime.AntigravityAuthStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val scheme = MaterialTheme.colorScheme

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

    // BUG-UI-004 Fix: Track if user is near the bottom so streaming deltas don't yank scroll position
    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total == 0) {
                true
            } else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    val lastMessageLength = state.messages.lastOrNull()?.text?.length ?: 0
    val lastWorkItemsSize = state.messages.lastOrNull()?.workItems?.size ?: 0
    LaunchedEffect(lastMessageLength, lastWorkItemsSize) {
        if (state.messages.isNotEmpty() && isNearBottom) {
            listState.scrollToItem(state.messages.lastIndex)
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
                contentColor = scheme.onSurface,
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
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.sessionRunning) tokens.accentText else tokens.successText),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
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
                                Text("Stop", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Divider(color = scheme.outlineVariant)
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
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

                if (!isNearBottom) {
                    Surface(
                        shape = RoundedCornerShape(100),
                        color = scheme.primaryContainer,
                        contentColor = scheme.onPrimaryContainer,
                        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.5f)),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp)
                            .clip(RoundedCornerShape(100))
                            .clickable {
                                scope.launch {
                                    if (state.messages.isNotEmpty()) {
                                        listState.animateScrollToItem(state.messages.lastIndex)
                                    }
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom",
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Latest",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        Divider(color = scheme.outlineVariant)
        Surface(
            color = tokens.topBarContainerColor,
            contentColor = scheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    shape = RoundedCornerShape(20.dp),
                    placeholder = {
                        Text(
                            text = "Ask ${state.agentKind.displayTitle()} to build, refactor, or fix…",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (state.sessionRunning) {
                    Button(
                        onClick = viewModel::stopActiveTask,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.error,
                            contentColor = scheme.onError,
                        ),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Stop")
                    }
                } else {
                    Button(
                        onClick = {
                            val text = promptInput.trim()
                            if (text.isNotEmpty()) {
                                promptInput = ""
                                viewModel.sendPrompt(text)
                            }
                        },
                        enabled = promptInput.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
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
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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

private data class QuickPromptCardInfo(
    val title: String,
    val subtitle: String,
    val prompt: String,
    val icon: ImageVector,
)

@Composable
private fun EmptyAgentWelcome(
    state: ForgeUiState,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val activeModelLabel = if (state.agentKind == AgentKind.ANTIGRAVITY) {
        state.antigravityModel.ifBlank { "default (auto)" }
    } else {
        state.providerProfile.model.ifBlank { "default" }
    }
    val cards = remember {
        listOf(
            QuickPromptCardInfo(
                title = "Architecture Overview",
                subtitle = "Map modules, entry points & data flow",
                prompt = "Inspect this workspace and summarize its architecture.",
                icon = Icons.Default.Star,
            ),
            QuickPromptCardInfo(
                title = "UI / Compose Feature",
                subtitle = "Build responsive screens & clean state",
                prompt = "Create a responsive Jetpack Compose screen with clean state management.",
                icon = Icons.Default.Edit,
            ),
            QuickPromptCardInfo(
                title = "Build & Verify",
                subtitle = "Run tests or verification scripts in Chroot",
                prompt = "Run the test suite or verify scripts inside the Ubuntu Root Chroot.",
                icon = Icons.Default.PlayArrow,
            ),
            QuickPromptCardInfo(
                title = "Deep Bug Audit",
                subtitle = "Find & fix concurrency, UI/UX & edge-case bugs",
                prompt = "Audit the codebase for concurrency, security, and edge-case bugs.",
                icon = Icons.Default.Build,
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Expressive Hero Studio Header Card
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = state.agentKind.displayTitle(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = state.agentKind.displaySubtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = tokens.cardElevatedColor,
                        border = tokens.elevatedBorderStroke,
                    ) {
                        Text(
                            text = "/workspace/${state.activeProject?.slug ?: "sandbox"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = tokens.accentText,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = tokens.cardElevatedColor,
                        border = tokens.elevatedBorderStroke,
                    ) {
                        Text(
                            text = "Model: $activeModelLabel",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // 2x2 Bento Quick-Action Grid
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "QUICK STUDIO ACTIONS",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            cards.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEach { item ->
                        ForgeThemedCard(
                            modifier = Modifier.weight(1f),
                            onClick = { onQuickPrompt(item.prompt) },
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(scheme.primaryContainer.copy(alpha = 0.75f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = scheme.onPrimaryContainer,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
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
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme

    if (message.fromUser) {
        // BUG-UI-015 Fix: Right-aligned distinct user prompt bubble
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            val userShape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 6.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp,
            )
            Card(
                modifier = Modifier.fillMaxWidth(0.88f),
                shape = userShape,
                border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.4f)),
                colors = CardDefaults.cardColors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "You",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onPrimaryContainer,
                        )
                        if (message.text.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(message.text))
                                    copied = true
                                    scope.launch {
                                        delay(1400)
                                        copied = false
                                    }
                                },
                                modifier = Modifier.size(22.dp),
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Default.Check else Icons.Default.Share,
                                    contentDescription = "Copy prompt",
                                    tint = scheme.onPrimaryContainer.copy(alpha = 0.78f),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                    MarkdownText(
                        markdown = message.text,
                        color = scheme.onPrimaryContainer,
                        onRunCode = onRunSnippet,
                    )
                }
            }
        }
        return
    }

    // Assistant Studio Response Card
    ForgeThemedCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = scheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = agentName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = tokens.accentText,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (message.workItems.isNotEmpty()) {
                        val completed = message.workItems.count { it.isComplete }
                        val total = message.workItems.size
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = tokens.cardElevatedColor,
                            border = tokens.elevatedBorderStroke,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { expandTools = !expandTools },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (completed == total) tokens.successText else tokens.warningText,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "$completed/$total tools",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Icon(
                                    imageVector = if (expandTools) {
                                        Icons.Default.KeyboardArrowUp
                                    } else {
                                        Icons.Default.KeyboardArrowDown
                                    },
                                    contentDescription = null,
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                    if (message.text.isNotBlank()) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(message.text))
                                copied = true
                                scope.launch {
                                    delay(1400)
                                    copied = false
                                }
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = if (copied) Icons.Default.Check else Icons.Default.Share,
                                contentDescription = "Copy response",
                                tint = if (copied) tokens.successText else scheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            if (expandTools && message.workItems.isNotEmpty()) {
                ToolActivityList(items = message.workItems)
            }

            if (message.text.isNotBlank()) {
                MarkdownText(
                    markdown = message.text,
                    color = scheme.onSurface,
                    onRunCode = onRunSnippet,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(tokens.accentText),
                    )
                    Text(
                        text = "Thinking & analyzing workspace…",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolActivityList(items: List<ActivityItem>) {
    val tokens = LocalForgeThemeTokens.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.cardElevatedColor)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (item.isComplete) tokens.successText else tokens.warningText,
                    modifier = Modifier
                        .size(15.dp)
                        .padding(top = 2.dp),
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
