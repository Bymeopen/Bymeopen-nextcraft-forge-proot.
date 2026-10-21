@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.runtime.CLAUDE_EFFORT_LEVELS

/*
 * Claude-style chat chrome: a header with a menu button (chat drawer on the left) and actions on the right, a usage
 * sheet, and a model/effort sheet opened from the chip in the composer.
 */

/** Header of the conversation: menu, chat title with project, a spinner while this chat works, changes and a menu. */
@Composable
internal fun ChatTopBar(
    state: ForgeUiState,
    onOpenDrawer: () -> Unit,
    onOpenChanges: () -> Unit,
    onRename: () -> Unit,
    onUsage: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val activeChat = state.chats.firstOrNull { it.id == state.activeChatId }
    val pending = state.changedItems.count { it.accepted == null }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.agent_project_chats), tint = scheme.onSurface)
        }
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = activeChat?.displayTitle() ?: stringResource(R.string.agent_title),
                modifier = Modifier.forgeHeading(),
                fontSize = ForgeType.cardTitle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(state.activeProject?.name, state.agentKind.displayTitle()).joinToString(" · "),
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.activeChatRunning) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(4.dp))
        }
        // Changes made by the agent: opens the workspace diff.
        IconButton(onClick = onOpenChanges) {
            Box(contentAlignment = Alignment.TopEnd) {
                ForgeIcon(glyph = ForgeGlyph.CODE, tint = scheme.onSurface, size = 22.dp, description = stringResource(R.string.chat_menu_changes))
                if (pending > 0) {
                    Surface(shape = RoundedCornerShape(50), color = scheme.primary, modifier = Modifier.padding(start = 14.dp)) {
                        Text(
                            text = pending.toString(),
                            fontSize = ForgeType.caption,
                            color = scheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp),
                        )
                    }
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_menu_more), tint = scheme.onSurface)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_rename)) }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_usage)) }, onClick = { menuOpen = false; onUsage() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_clear)) },
                    enabled = !state.activeChatRunning,
                    onClick = { menuOpen = false; onClear() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.agent_delete_chat)) },
                    enabled = state.chats.size > 1 && !state.activeChatRunning,
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** Left drawer: the project's chats, newest first, with a spinner on the ones that are working. */
@Composable
internal fun ChatDrawerContent(
    state: ForgeUiState,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = state.activeProject?.name ?: stringResource(R.string.agent_title),
            fontSize = ForgeType.screenTitle,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        ForgePrimaryButton(
            text = stringResource(R.string.agent_new_chat),
            onClick = {
                viewModel.createChat()
                onClose()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.chats.sortedByDescending { it.updatedAtMillis }, key = { it.id }) { chat ->
                ChatDrawerRow(
                    chat = chat,
                    current = chat.id == state.activeChatId,
                    running = chat.id in state.runningChatIds,
                    canDelete = state.chats.size > 1 && chat.id !in state.runningChatIds,
                    onOpen = {
                        viewModel.selectChat(chat.id)
                        onClose()
                    },
                    onDelete = { viewModel.deleteChat(chat.id) },
                )
            }
        }
    }
}

@Composable
private fun ChatDrawerRow(
    chat: ProjectChat,
    current: Boolean,
    running: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (running) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.displayTitle(),
                fontSize = ForgeType.rowTitle,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                color = if (current) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = formatRelativeTime(chat.updatedAtMillis), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
        }
        if (canDelete) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.agent_delete_chat), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun RenameChatDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename_title), fontWeight = FontWeight.SemiBold) },
        text = {
            ForgeTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                kind = ForgeFieldKind.NAME,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            ForgeTextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.chat_rename_save)) }
        },
        dismissButton = { ForgeTextButton(onClick = onDismiss) { Text(stringResource(R.string.projects_cancel)) } },
    )
}

/** "Usage": how full the model's context window is for this chat. Plan limits live in the Claude account. */
@Composable
internal fun ChatUsageSheet(state: ForgeUiState, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val used = estimateContextTokens(state.messages)
    val limit = contextWindowTokens(state.providerProfile.model)
    val fraction = (used.toFloat() / limit).coerceIn(0f, 1f)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.cardContainerColor,
    ) {
        Column(modifier = Modifier.padding(horizontal = ForgeSpace.card).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.chat_usage_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
            Surface(shape = RoundedCornerShape(16.dp), color = tokens.cardElevatedColor, border = tokens.cardBorderStroke, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.chat_usage_context), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.chat_usage_percent, (fraction * 100).toInt()),
                            fontSize = ForgeType.secondary,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.chat_usage_used, formatTokenCount(used), formatTokenCount(limit)),
                        fontSize = ForgeType.secondary,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Text(stringResource(R.string.chat_usage_note), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
        }
    }
}

/** Short model (and effort) text for the composer chip, e.g. "Fable 5.1 Low". */
@Composable
internal fun chatModelChipLabel(state: ForgeUiState): String = when (state.agentKind) {
    AgentKind.CLAUDE_CODE -> {
        val model = claudeModelLabel(state.providerProfile.model.ifBlank { "default" })
            .removePrefix("Claude ").removeSuffix(" (latest)")
        val effort = state.claudeEffort.takeIf { it.isNotBlank() }?.let { claudeEffortText(it) }
        listOfNotNull(model, effort).joinToString(" ")
    }
    AgentKind.ANTIGRAVITY -> state.antigravityModel.takeIf { it.isNotBlank() }?.let(::antigravityModelLabel) ?: "Antigravity"
    AgentKind.DEEPSEEK_HARNESS -> state.providerProfile.model.ifBlank { "DeepSeek" }
}

/** Model list and effort row opened from the composer chip. */
@Composable
internal fun ChatModelSheet(state: ForgeUiState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val agent = state.agentKind
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.cardContainerColor,
    ) {
        Column(modifier = Modifier.padding(horizontal = ForgeSpace.card).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.chat_model_sheet_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
            when (agent) {
                AgentKind.CLAUDE_CODE -> {
                    val levels = listOf("") + CLAUDE_EFFORT_LEVELS
                    Text(stringResource(R.string.agent_reasoning_depth), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                    ForgePillTabs(
                        items = levels.map { claudeEffortText(it) },
                        selectedIndex = levels.indexOf(state.claudeEffort).coerceAtLeast(0),
                        onSelect = { viewModel.setClaudeEffort(levels[it]) },
                    )
                    ModelList(
                        options = providerModelOptions(state.providerProfile.kind, state.discoveredModels, state.providerProfile.model),
                        selectedId = state.providerProfile.model,
                        onSelect = { id -> viewModel.updateProviderConfig(baseUrl = state.providerProfile.baseUrl, model = id) },
                    )
                }
                AgentKind.ANTIGRAVITY -> {
                    val efforts = listOf("low", "medium", "high")
                    Text(stringResource(R.string.agent_reasoning_depth), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                    ForgePillTabs(
                        items = listOf(stringResource(R.string.agent_effort_low), stringResource(R.string.agent_effort_medium), stringResource(R.string.agent_effort_high)),
                        selectedIndex = efforts.indexOf(state.antigravityEffort).coerceAtLeast(0),
                        onSelect = { viewModel.setAntigravityConfig(state.antigravityModel, efforts[it]) },
                    )
                    ModelList(
                        options = antigravityModelOptions(state.antigravityModels, state.antigravityModel),
                        selectedId = state.antigravityModel,
                        onSelect = viewModel::selectAntigravityModel,
                    )
                }
                AgentKind.DEEPSEEK_HARNESS -> ModelList(
                    options = providerModelOptions(state.providerProfile.kind, state.discoveredModels, state.providerProfile.model),
                    selectedId = state.providerProfile.model,
                    onSelect = { id -> viewModel.updateProviderConfig(baseUrl = state.providerProfile.baseUrl, model = id) },
                )
            }
        }
    }
}

@Composable
private fun ModelList(options: List<ModelOption>, selectedId: String, onSelect: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        items(options, key = { it.id }) { option ->
            RadioRow(
                title = option.labelRes?.let { stringResource(it) } ?: option.label,
                subtitle = option.id,
                mono = true,
                selected = option.id == selectedId,
                onClick = { onSelect(option.id) },
                horizontalPadding = 0.dp,
            )
        }
    }
}

/** Rough token count of a chat (about four characters per token); the real count is only known to the model. */
internal fun estimateContextTokens(messages: List<ChatMessage>): Int =
    messages.sumOf { message -> message.text.length + message.workItems.sumOf { it.title.length + it.detail.length } } / 4

/** The window assumed for a model id: the `[1m]` variants have one million tokens, everything else 200k. */
internal fun contextWindowTokens(model: String): Int = if (model.contains("[1m]", ignoreCase = true)) 1_000_000 else 200_000

/** "442.8k", "1M", "200k": compact token counts for the usage sheet. */
internal fun formatTokenCount(tokens: Int): String = when {
    tokens >= 1_000_000 -> if (tokens % 1_000_000 == 0) "${tokens / 1_000_000}M" else "%.1fM".format(java.util.Locale.ROOT, tokens / 1_000_000.0)
    tokens >= 1_000 -> if (tokens % 1_000 == 0) "${tokens / 1_000}k" else "%.1fk".format(java.util.Locale.ROOT, tokens / 1_000.0)
    else -> tokens.toString()
}
