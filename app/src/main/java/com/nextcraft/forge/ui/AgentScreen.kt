@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
            onBack = { viewModel.projectBack() },
            onRun = { viewModel.selectTab(ForgeTab.ANDROID) },
            onSelectChat = viewModel::openChat,
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
                            text = statusLine.asString(),
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
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = ForgeSpace.minTouch),
                            ) {
                                Text(stringResource(R.string.agent_stop), fontSize = ForgeType.caption)
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        state.pendingToolRequest?.let { req ->
            ToolApprovalSheet(
                request = req,
                onApprove = { viewModel.respondToToolRequest(true) },
                onReject = { viewModel.respondToToolRequest(false) },
            )
        }

        if (state.messages.isEmpty()) {
            // Empty chat: one quiet greeting from the agent, nothing else competing with the composer.
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = tokens.cardContainerColor,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = tokens.cardBorderStroke,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.agent_greeting),
                        fontSize = ForgeType.rowTitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        } else {
            val pendingChanges = state.changedItems.filter { it.accepted == null }
            val lastAssistantId = state.messages.lastOrNull { !it.fromUser }?.id
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        Column(modifier = Modifier.animateItemPlacement(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChatMessageCard(
                                message = message,
                                agentName = state.agentKind.displayTitle(),
                                onRunSnippet = { cmd ->
                                    viewModel.runTerminalCommandInProject(cmd)
                                    viewModel.selectTab(ForgeTab.TERMINAL)
                                },
                            )
                            // Review right where the agent said it was done; the Files tab keeps the full diff view.
                            if (message.id == lastAssistantId && pendingChanges.isNotEmpty() && !state.sessionRunning) {
                                ChangedFilesCard(
                                    changes = pendingChanges,
                                    enabled = !state.sessionRunning,
                                    onOpenFile = { path ->
                                        viewModel.openWorkspaceFile(path)
                                        viewModel.selectProjectPane(ProjectPane.FILES)
                                    },
                                    onKeepFile = viewModel::keepSingleFile,
                                    onRevertFile = viewModel::revertSingleFile,
                                    onKeepAll = viewModel::keepAllChanges,
                                    onRevertAll = viewModel::revertAllChanges,
                                    onEditInTerminal = viewModel::editInTerminal,
                                )
                            }
                        }
                    }
                }
                // "Latest" pill: shown once the reader has scrolled up from the newest message.
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val awayFromEnd = state.messages.size > 1 && lastVisible < state.messages.lastIndex
                // Count only what arrived after the reader scrolled away; remember(awayFromEnd) snapshots the size at that moment.
                val sizeWhenAway = remember(awayFromEnd) { state.messages.size }
                val newCount = (state.messages.size - sizeWhenAway).coerceAtLeast(0)
                if (awayFromEnd) {
                    Surface(
                        shape = RoundedCornerShape(100),
                        // Opaque (composited over the page) and raised: the glass card colour is translucent and let the
                        // last message show through the pill.
                        color = tokens.cardElevatedColor.compositeOver(MaterialTheme.colorScheme.surface),
                        contentColor = tokens.accentText,
                        border = tokens.cardBorderStroke,
                        shadowElevation = 4.dp,
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
                            Text(
                                text = if (newCount > 0) quantityStringResource(R.plurals.chat_new_messages, newCount, newCount) else stringResource(R.string.agent_latest),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = ForgeType.control,
                                modifier = Modifier.forgeLive(),
                            )
                        }
                    }
                }
            }
        }

        // Composer: one capsule, attach + field + round send/stop button. The agent name is in the header.
        val running = state.sessionRunning
        val canSend = promptInput.isNotBlank() || state.pendingAttachments.isNotEmpty()
        val attachPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) viewModel.attachFilesToPrompt(uris)
        }
        // Camera: the photo is written to a FileProvider URI in the app cache, then imported like any picked file.
        val cameraContext = LocalContext.current
        var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
        var attachMenuOpen by remember { mutableStateOf(false) }
        val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = cameraUri?.let(android.net.Uri::parse)
            if (saved && uri != null) viewModel.attachFilesToPrompt(listOf(uri))
            cameraUri = null
        }
        fun launchCamera() {
            runCatching {
                val dir = java.io.File(cameraContext.cacheDir, "camera").apply { mkdirs() }
                val photo = java.io.File.createTempFile("IMG_${System.currentTimeMillis()}_", ".jpg", dir)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    cameraContext,
                    "${cameraContext.packageName}.fileprovider",
                    photo,
                )
                cameraUri = uri.toString()
                cameraLauncher.launch(uri)
            }.onFailure { cameraUri = null }
        }
        val retry = state.retryPrompt?.takeIf { it.chatId == state.activeChatId && !state.sessionRunning }
        if (retry != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).forgeLive(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.agent_turn_failed),
                    fontSize = ForgeType.secondary,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                ForgeTextButton(onClick = viewModel::retryLastPrompt) { Text(stringResource(R.string.action_retry)) }
            }
        }
        if (state.queuedPrompts.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.agent_queued_title, state.queuedPrompts.size),
                    fontSize = ForgeType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.queuedPrompts.forEach { queued ->
                    val label = queued.text.ifBlank { queued.attachments.joinToString { it.displayName } }
                    AttachmentChip(name = label, size = 0L, onRemove = { viewModel.removeQueuedPrompt(queued.id) }, showSize = false)
                }
            }
        }
        if (state.pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.pendingAttachments.forEach { att ->
                    AttachmentChip(name = att.displayName, size = att.sizeBytes, onRemove = { viewModel.removePendingAttachment(att.id) })
                }
            }
        }
        if (!running && state.pendingToolRequest == null) {
            val chips = buildList {
                if (state.messages.isEmpty()) {
                    // Starter ideas fill the composer instead of sending, so the user can edit first.
                    add(QuickChip(stringResource(R.string.starter_explain), ForgeGlyph.SPARK) { promptInput = stringOf(R.string.starter_explain, cameraContext) })
                    add(QuickChip(stringResource(R.string.starter_fix), ForgeGlyph.BUILD) { promptInput = stringOf(R.string.starter_fix, cameraContext) })
                    add(QuickChip(stringResource(R.string.starter_feature), ForgeGlyph.PLUS) { promptInput = stringOf(R.string.starter_feature, cameraContext) })
                    add(QuickChip(stringResource(R.string.starter_tests), ForgeGlyph.CODE) { promptInput = stringOf(R.string.starter_tests, cameraContext) })
                } else {
                    val changed = state.changedItems.count { it.accepted == null }
                    if (changed > 0) {
                        add(QuickChip(quantityStringResource(R.plurals.chip_review_changes, changed, changed), ForgeGlyph.FILE) { viewModel.selectProjectPane(ProjectPane.FILES) })
                        add(QuickChip(stringResource(R.string.chip_explain_changes), ForgeGlyph.SPARK) { viewModel.sendPrompt(stringOf(R.string.prompt_explain_changes, cameraContext)) })
                    }
                    if (state.activeLocalPorts.isNotEmpty()) {
                        add(QuickChip(stringResource(R.string.chip_open_preview), ForgeGlyph.GLOBE) { viewModel.selectProjectPane(ProjectPane.PREVIEW) })
                    }
                    if (state.activeProject?.type == com.nextcraft.forge.model.ProjectType.ANDROID) {
                        add(QuickChip(stringResource(R.string.chip_build_run), ForgeGlyph.BUILD) { viewModel.selectTab(ForgeTab.ANDROID) })
                    } else {
                        add(QuickChip(stringResource(R.string.chip_run_tests), ForgeGlyph.CODE) { viewModel.sendPrompt(stringOf(R.string.prompt_run_tests, cameraContext)) })
                    }
                }
            }
            QuickActionRow(chips, modifier = Modifier.padding(top = 4.dp))
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = tokens.cardContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = tokens.cardBorderStroke,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Attach: photos or any file; it lands in attachments/ inside the workspace.
                Box {
                    IconButton(onClick = { attachMenuOpen = true }) {
                        ForgeIcon(glyph = ForgeGlyph.PLUS, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp, description = stringResource(R.string.agent_attach))
                    }
                    DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_attach_camera)) },
                            onClick = { attachMenuOpen = false; launchCamera() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_attach_file)) },
                            onClick = { attachMenuOpen = false; attachPicker.launch(arrayOf("*/*")) },
                        )
                    }
                }
                TextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.agent_message_placeholder, state.agentKind.displayTitle()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                    // Enter stays a newline (messages are multi-line); send is the round button.
                    keyboardOptions = ForgeFieldKind.TEXT.keyboardOptions(ImeAction.Default),
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                val haptics = LocalHapticFeedback.current
                val reduceMotion = rememberReduceMotion()
                val sendScale by animateFloatAsState(
                    targetValue = if (running || canSend) 1f else 0.86f,
                    animationSpec = if (reduceMotion) snap() else ForgeMotion.springSnappy(),
                    label = "sendScale",
                )
                val sendBg by animateColorAsState(
                    targetValue = when {
                        canSend -> MaterialTheme.colorScheme.primary
                        running -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                    },
                    animationSpec = if (reduceMotion) snap() else tween(ForgeMotion.FADE_MS),
                    label = "sendBg",
                )
                Box(
                    modifier = Modifier
                        .forgeMinTouch()
                        .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(sendBg)
                        .clickable(
                            enabled = running || canSend,
                            role = Role.Button,
                            onClickLabel = stringResource(if (running && !canSend) R.string.agent_stop else R.string.agent_send),
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            // While the agent works, sending queues the message; stop is available when nothing is typed.
                            if (canSend) {
                                val text = promptInput.trim()
                                promptInput = ""
                                viewModel.sendPrompt(text)
                            } else if (running) {
                                viewModel.stopActiveTask()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (running && !canSend) Icons.Default.Close else Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(if (running && !canSend) R.string.agent_stop else R.string.agent_send),
                        tint = when {
                            canSend -> MaterialTheme.colorScheme.onPrimary
                            running -> MaterialTheme.colorScheme.onError
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp),
                    )
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
                modifier = Modifier.forgeHeading(),
                fontSize = ForgeType.screenTitle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(state.agentKind.displayTitle(), activeChat?.displayTitle()).joinToString(" · "),
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { chatsOpen = true }) {
            ForgeIcon(glyph = ForgeGlyph.HISTORY, tint = scheme.onSurface, size = 22.dp, description = stringResource(R.string.agent_project_chats))
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
        modifier = Modifier.forgePopIn(),
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
                                Text(stringResource(R.string.agent_current_chat), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
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
            ForgeTextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_close)) }
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
                    Text(stringResource(R.string.agent_google_account), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.Medium)
                    Text(
                        text = authState.message?.asString() ?: stringResource(R.string.agent_required_for_antigravity),
                        fontSize = ForgeType.secondary,
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
                    ForgeTextField(
                        kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done,
                        value = verificationCode,
                        onValueChange = { verificationCode = it },
                        placeholder = { Text(stringResource(R.string.agent_paste_google_verification_code), fontSize = ForgeType.secondary) },
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
internal fun ChatMessageCard(
    message: ChatMessage,
    agentName: String,
    onRunSnippet: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val textColor = scheme.onSurface

    if (message.fromUser) {
        // User bubbles hug their text: no permanent copy button (it forced a 48dp minimum height); a long-press copies.
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val copiedLabel = stringResource(R.string.tool_copy)
        MessageContainer(
            fromUser = true,
            contentColor = textColor,
            onLongClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text))
            },
            longClickLabel = copiedLabel,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message.text.isNotBlank()) {
                    MarkdownText(markdown = message.text, color = textColor, onRunCode = onRunSnippet, selectable = false)
                }
                if (message.attachments.isNotEmpty()) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.attachments.forEach { att -> AttachmentChip(name = att.displayName, size = att.sizeBytes, onRemove = null) }
                    }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Each tool call is one collapsed row ("Bash · command ⌄"); tap to read its detail.
        if (message.workItems.size >= STEP_GROUP_MIN) {
            ToolStepGroup(message.workItems)
        } else {
            message.workItems.forEach { item -> ToolCallRow(item) }
        }
        if (message.workedMillis > 0L) {
            Text(
                text = stringResource(R.string.agent_worked_for, formatWorked(message.workedMillis)),
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
            )
        }
        val streaming = message.id == "streaming-assistant"
        if (message.text.isNotBlank()) {
            MessageContainer(fromUser = false, contentColor = textColor) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    // 44dp: the copy button is a 48dp touch box; 32dp let it cover the end of the first lines.
                    Column(modifier = Modifier.padding(end = 44.dp)) {
                        MarkdownText(markdown = message.text, color = textColor, onRunCode = onRunSnippet, selectable = false)
                        if (streaming) {
                            // Blinking caret says "still arriving" without a spinner.
                            val blink = rememberBlink()
                            Box(
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(width = 8.dp, height = 16.dp)
                                    .graphicsLayer { alpha = blink }
                                    .background(scheme.primary, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                    if (!streaming) CopyMessageButton(message.text, Modifier.align(Alignment.TopEnd))
                }
            }
        } else if (message.workItems.isEmpty()) {
            val blink = rememberBlink()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                Box(
                    modifier = Modifier
                        .size(width = 8.dp, height = 16.dp)
                        .graphicsLayer { alpha = blink }
                        .background(scheme.primary, RoundedCornerShape(2.dp)),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.agent_thinking),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.forgeLive(),
                )
            }
        }
    }
}

/**
 * Explicit copy action for a whole message. Long-press selection (MarkdownText) still works for parts of a message,
 * but it is easy to miss and can be lost while the list updates, so copying must not depend on it.
 */
@Composable
private fun CopyMessageButton(text: String, modifier: Modifier = Modifier) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(if (copied) R.string.tool_copied else R.string.tool_copy)
    Box(
        modifier = modifier
            .forgeMinTouch()
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label) {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                copied = true
                scope.launch {
                    kotlinx.coroutines.delay(1_500)
                    copied = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (copied) {
            Icon(Icons.Default.Check, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        } else {
            // Copy glyph (two overlapping rounded sheets); the core icon set has no ContentCopy.
            androidx.compose.foundation.Canvas(modifier = Modifier.size(16.dp).semantics { contentDescription = label }) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx())
                val sheet = androidx.compose.ui.geometry.Size(size.width * 0.62f, size.height * 0.68f)
                val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.34f, 0f), size = sheet, cornerRadius = radius, style = stroke)
                drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.30f), size = sheet, cornerRadius = radius, style = stroke)
            }
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
internal fun ToolCallRow(item: ActivityItem) {
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
        // Kind decides glyph and tint: Think = accent, Write/Edit = warning, Bash/Read = quiet. Status still shows as a dot.
        val kind = toolKind(item)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                ForgeIcon(glyph = kind.glyph, tint = kind.tint(scheme, tokens), size = 18.dp)
                if (!item.isComplete) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(tokens.warningText),
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (item.detail.isBlank()) item.title else "${item.title} · ${item.detail.lineSequence().first()}",
                fontSize = ForgeType.control,
                color = if (kind == ToolKind.THINK) scheme.onSurface else scheme.onSurfaceVariant,
                fontFamily = if (item.isCommand) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val reduceMotion = rememberReduceMotion()
            val rotation by animateFloatAsState(
                targetValue = if (open) 180f else 0f,
                animationSpec = if (reduceMotion) snap() else ForgeMotion.springSnappy(),
                label = "chevron",
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedVisibility(
            visible = open && item.detail.isNotBlank(),
            enter = expandVertically(tween(ForgeMotion.EXPAND_MS)) + fadeIn(tween(ForgeMotion.FADE_MS)),
            exit = shrinkVertically(tween(ForgeMotion.FADE_MS)) + fadeOut(tween(ForgeMotion.FADE_MS / 2)),
        ) {
            Text(
                text = item.detail,
                fontSize = ForgeType.secondary,
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
@OptIn(ExperimentalFoundationApi::class)
private fun MessageContainer(
    fromUser: Boolean,
    contentColor: Color,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    if (fromUser) {
        // Wraps its text and sits at the end: a short message must not get a full-width bubble of empty space.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = LocalForgeThemeTokens.current.cardElevatedColor,
                contentColor = contentColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .then(
                        if (onLongClick != null) {
                            Modifier.combinedClickable(onLongClickLabel = longClickLabel, onLongClick = onLongClick, onClick = {})
                        } else Modifier,
                    ),
            ) { content() }
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) { content() }
    }
}

/** Tool-call kinds, derived from the activity title, each with its own glyph and tint. */
internal enum class ToolKind(val glyph: ForgeGlyph) {
    THINK(ForgeGlyph.SPARK), BASH(ForgeGlyph.TERMINAL), READ(ForgeGlyph.FILE), WRITE(ForgeGlyph.EDIT), OTHER(ForgeGlyph.BUILD);

    fun tint(scheme: androidx.compose.material3.ColorScheme, tokens: ForgeThemeTokens): Color = when (this) {
        THINK -> scheme.primary
        WRITE -> tokens.warningText
        BASH, READ, OTHER -> scheme.onSurfaceVariant
    }
}

internal fun toolKind(item: ActivityItem): ToolKind {
    val t = item.title.lowercase()
    return when {
        "think" in t || "reason" in t -> ToolKind.THINK
        item.isCommand || "bash" in t || "shell" in t || "run" in t || "exec" in t -> ToolKind.BASH
        "write" in t || "edit" in t || "create" in t || "patch" in t -> ToolKind.WRITE
        "read" in t || "view" in t || "search" in t || "grep" in t || "list" in t -> ToolKind.READ
        else -> ToolKind.OTHER
    }
}

/** Small capsule naming an attached file; [onRemove] null makes it read-only (sent messages). */
@Composable
private fun AttachmentChip(name: String, size: Long, onRemove: (() -> Unit)?, showSize: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(tokens.cardElevatedColor)
            .padding(start = 12.dp, end = if (onRemove == null) 12.dp else 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = ForgeType.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        if (showSize) Spacer(modifier = Modifier.width(6.dp))
        if (showSize) Text(
            text = if (size < 1024 * 1024) "${(size / 1024).coerceAtLeast(1)} KB" else String.format(java.util.Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0)),
            fontSize = ForgeType.caption,
            color = scheme.onSurfaceVariant,
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(ForgeSpace.minTouch)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.agent_remove_attachment), tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** String lookup for click handlers, which run outside composition and cannot call stringResource. */
private fun stringOf(@androidx.annotation.StringRes id: Int, context: android.content.Context): String = context.getString(id)
