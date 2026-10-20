@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nextcraft.forge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.DiffLineType
import com.nextcraft.forge.model.RiskLevel
import com.nextcraft.forge.model.ToolRequest

/*
 * Batch B (chat): grouped tool steps, inline diff review, approval sheet and quick-action chips.
 * Everything here is stateless apart from expand/collapse, so it renders in the phone-size screenshot harness.
 */

/** Three or more tool calls in one reply fold into a single "N steps" row; the live step shows while it runs. */
internal const val STEP_GROUP_MIN = 3

@Composable
internal fun ToolStepGroup(items: List<ActivityItem>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val live = items.lastOrNull()?.isComplete == false
    val kinds = items.map { toolKind(it) }.distinct().take(3)
    val expandLabel = stringResource(if (open) R.string.a11y_collapse else R.string.a11y_expand)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = expandLabel) { open = !open }
                .heightIn(min = ForgeSpace.minTouch)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Kind glyphs overlap like an avatar stack so the row says what happened before it is opened.
            Box(modifier = Modifier.width(22.dp + 14.dp * (kinds.size - 1).coerceAtLeast(0))) {
                kinds.forEachIndexed { index, kind ->
                    Box(
                        modifier = Modifier
                            .padding(start = 14.dp * index)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(tokens.cardElevatedColor),
                        contentAlignment = Alignment.Center,
                    ) { ForgeIcon(kind.glyph, tint = kind.tint(scheme, tokens), size = 14.dp) }
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            val last = items.last()
            Text(
                text = if (live) {
                    if (last.detail.isBlank()) last.title else "${last.title} · ${last.detail.lineSequence().first()}"
                } else {
                    quantityStringResource(R.plurals.chat_steps, items.size, items.size)
                },
                fontSize = ForgeType.control,
                color = if (live) scheme.onSurface else scheme.onSurfaceVariant,
                fontFamily = if (live && last.isCommand) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (live) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(tokens.warningText))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Chevron(open)
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(androidx.compose.animation.core.tween(ForgeMotion.EXPAND_MS)) + fadeIn(androidx.compose.animation.core.tween(ForgeMotion.FADE_MS)),
            exit = shrinkVertically(androidx.compose.animation.core.tween(ForgeMotion.FADE_MS)) + fadeOut(androidx.compose.animation.core.tween(ForgeMotion.FADE_MS / 2)),
        ) {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                items.forEach { ToolCallRow(it) }
            }
        }
    }
}

/** Files the agent changed in this turn, with an inline diff per file and keep / revert right in the chat. */
@Composable
internal fun ChangedFilesCard(
    changes: List<ChangeItem>,
    enabled: Boolean,
    onOpenFile: (String) -> Unit,
    onKeepFile: (String) -> Unit,
    onRevertFile: (String) -> Unit,
    onKeepAll: () -> Unit,
    onRevertAll: () -> Unit,
    modifier: Modifier = Modifier,
    onEditInTerminal: (String) -> Unit = {},
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val adds = changes.sumOf { it.additions }
    val dels = changes.sumOf { it.deletions }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(ForgeGlyph.FILE, tint = scheme.primary, size = 18.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = quantityStringResource(R.plurals.chat_files_changed, changes.size, changes.size),
                    fontSize = ForgeType.rowTitle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                DiffCounts(adds, dels)
            }
            changes.forEach { change ->
                ChangedFileRow(change, enabled, onOpenFile, onKeepFile, onRevertFile, onEditInTerminal)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRevertAll,
                    enabled = enabled,
                    shape = RoundedCornerShape(100),
                    modifier = Modifier.weight(1f).heightIn(min = ForgeSpace.minTouch + 4.dp),
                ) { Text(stringResource(R.string.chat_revert_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Button(
                    onClick = onKeepAll,
                    enabled = enabled,
                    shape = RoundedCornerShape(100),
                    modifier = Modifier.weight(1f).heightIn(min = ForgeSpace.minTouch + 4.dp),
                ) { Text(stringResource(R.string.chat_keep_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun DiffCounts(additions: Int, deletions: Int) {
    val tokens = LocalForgeThemeTokens.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.diff_added, additions), fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = tokens.successText)
        Text(stringResource(R.string.diff_removed, deletions), fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = tokens.dangerText)
    }
}

private const val DIFF_PREVIEW_LINES = 24

@Composable
private fun ChangedFileRow(
    change: ChangeItem,
    enabled: Boolean,
    onOpenFile: (String) -> Unit,
    onKeepFile: (String) -> Unit,
    onRevertFile: (String) -> Unit,
    onEditInTerminal: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val name = change.path.substringAfterLast('/')
    val dir = change.path.substringBeforeLast('/', "")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.a11y_collapse else R.string.a11y_expand)) { open = !open }
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = ForgeType.control, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dir.isNotEmpty()) {
                    Text(dir, fontSize = ForgeType.caption, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            DiffCounts(change.additions, change.deletions)
            Spacer(modifier = Modifier.width(2.dp))
            Chevron(open)
        }
        AnimatedVisibility(visible = open) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                if (change.binary || change.diffLines.isEmpty()) {
                    Text(
                        stringResource(if (change.binary) R.string.chat_diff_binary else R.string.chat_diff_none),
                        fontSize = ForgeType.secondary,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                } else {
                    DiffCodeView(lines = change.diffLines, path = change.path, previewLines = DIFF_PREVIEW_LINES)
                }
                // Wraps instead of scrolling: with four actions the last one ("Batalkan") was cut off at 393dp.
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { onEditInTerminal(change.path) }, modifier = Modifier.heightIn(min = ForgeSpace.minTouch + 4.dp)) { Text(stringResource(R.string.files_edit_in_terminal)) }
                    TextButton(onClick = { onOpenFile(change.path) }, modifier = Modifier.heightIn(min = ForgeSpace.minTouch + 4.dp)) { Text(stringResource(R.string.chat_open_in_files)) }
                    TextButton(onClick = { onKeepFile(change.path) }, enabled = enabled, modifier = Modifier.heightIn(min = ForgeSpace.minTouch + 4.dp)) { Text(stringResource(R.string.chat_keep)) }
                    TextButton(
                        onClick = { onRevertFile(change.path) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = ForgeSpace.minTouch + 4.dp),
                    ) { Text(stringResource(R.string.chat_revert), color = tokens.dangerText) }
                }
            }
        }
    }
}

internal data class QuickChip(val label: String, val glyph: ForgeGlyph, val onClick: () -> Unit)

/** One row of pill shortcuts above the composer: what to do next, based on what just happened. */
@Composable
internal fun QuickActionRow(chips: List<QuickChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .forgePressScale(interaction, pressedScale = 0.95f)
                    .clip(RoundedCornerShape(100))
                    .background(tokens.cardElevatedColor)
                    .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = chip.onClick)
                    .heightIn(min = ForgeSpace.minTouch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(chip.glyph, tint = scheme.primary, size = 16.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(chip.label, fontSize = ForgeType.control, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/**
 * Tool approval as a bottom sheet. It cannot be dismissed by swiping, tapping outside or Back: the agent is waiting,
 * so the answer must be an explicit Deny or Approve.
 */
@Composable
internal fun ToolApprovalSheet(request: ToolRequest, onApprove: () -> Unit, onReject: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
        containerColor = LocalForgeThemeTokens.current.cardContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        ToolApprovalContent(request, onApprove, onReject)
    }
}

@Composable
internal fun ToolApprovalContent(request: ToolRequest, onApprove: () -> Unit, onReject: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val (riskLabel, riskTint) = when (request.risk) {
        RiskLevel.HIGH -> R.string.approval_risk_high to scheme.error
        RiskLevel.REVIEW -> R.string.approval_risk_review to tokens.warningText
        RiskLevel.SAFE -> R.string.approval_risk_safe to scheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForgeIcon(ForgeGlyph.WARNING, tint = riskTint, size = 20.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_approval_title), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(riskLabel) + " · " + request.toolName,
                    fontSize = ForgeType.secondary,
                    fontWeight = FontWeight.Medium,
                    color = riskTint,
                )
            }
        }
        Text(request.explanation, fontSize = ForgeType.body, color = scheme.onSurfaceVariant)
        if (request.affectedPaths.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.approval_paths).uppercase(), fontSize = ForgeType.caption, letterSpacing = ForgeType.sectionTracking, color = scheme.onSurfaceVariant)
                request.affectedPaths.take(5).forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ForgeIcon(ForgeGlyph.FILE, tint = scheme.onSurfaceVariant, size = 14.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        // File name first: a long path would otherwise ellipsize away the one part that matters.
                        Column {
                            Text(path.substringAfterLast('/'), fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val dir = path.substringBeforeLast('/', "")
                            if (dir.isNotEmpty()) Text(dir, fontSize = ForgeType.caption, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                val extra = request.affectedPaths.size - 5
                if (extra > 0) Text(stringResource(R.string.chat_diff_more, extra), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
            }
        }
        request.commandPreview?.takeIf { it.isNotBlank() }?.let { cmd ->
            Text(
                text = cmd,
                style = ForgeCodeTextStyle,
                color = tokens.terminalText,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.terminalBackground)
                    .padding(12.dp),
            )
        }
        Text(stringResource(R.string.approval_waiting_hint), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onReject,
                shape = RoundedCornerShape(100),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.agent_deny), fontWeight = FontWeight.Medium) }
            Button(
                onClick = onApprove,
                shape = RoundedCornerShape(100),
                colors = if (request.risk == RiskLevel.HIGH) {
                    ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.agent_approve_execution), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
