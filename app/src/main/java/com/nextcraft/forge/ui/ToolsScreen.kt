@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.R

private data class ToolEntry(
    val tab: ForgeTab,
    val icon: ImageVector,
    val tint: Color,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
)

private data class ToolSection(@StringRes val label: Int, val entries: List<ToolEntry>)

private val toolSections = listOf(
    ToolSection(
        R.string.tools_section_development,
        listOf(
            ToolEntry(ForgeTab.TOOLCHAINS, Icons.Default.Build, Color(0xFF8B7CF6), R.string.tools_toolchains, R.string.tools_toolchains_desc),
            ToolEntry(ForgeTab.DIAGNOSTICS, Icons.Default.Favorite, Color(0xFFB8E65C), R.string.tools_diagnostics, R.string.tools_diagnostics_desc),
            ToolEntry(ForgeTab.TERMINAL, Icons.Default.List, Color(0xFF3FD0A5), R.string.shell_terminal, R.string.tools_terminal_desc),
            ToolEntry(ForgeTab.FILES, Icons.Default.Edit, Color(0xFFF2C14E), R.string.tools_files, R.string.tools_files_desc),
            ToolEntry(ForgeTab.FILES, Icons.Default.Create, Color(0xFFC77DFF), R.string.tools_editor, R.string.tools_editor_desc),
            ToolEntry(ForgeTab.PREVIEW, Icons.Default.PlayArrow, Color(0xFF5CE0C0), R.string.tools_web_preview, R.string.tools_web_preview_desc),
        ),
    ),
    ToolSection(
        R.string.tools_section_build_deploy,
        listOf(
            ToolEntry(ForgeTab.BUILD_MATRIX, Icons.Default.Menu, Color(0xFF4DD0E1), R.string.tools_build_matrix, R.string.tools_build_matrix_desc),
            ToolEntry(ForgeTab.ANDROID, Icons.Default.Build, Color(0xFF7CC576), R.string.tools_android_builder, R.string.tools_android_builder_desc),
        ),
    ),
    ToolSection(
        R.string.tools_section_infrastructure,
        listOf(
            ToolEntry(ForgeTab.SSH, Icons.Default.Lock, Color(0xFF7C8CF6), R.string.tools_ssh, R.string.tools_ssh_desc),
            ToolEntry(ForgeTab.CICD, Icons.Default.Refresh, Color(0xFF5CC98F), R.string.tools_cicd, R.string.tools_cicd_desc),
        ),
    ),
    ToolSection(
        R.string.tools_section_system,
        listOf(
            ToolEntry(ForgeTab.ROM, Icons.Default.Star, Color(0xFFF2784B), R.string.tools_rom, R.string.tools_rom_desc),
            ToolEntry(ForgeTab.CHECKPOINTS, Icons.Default.DateRange, Color(0xFFE8A64A), R.string.tools_checkpoints, R.string.tools_checkpoints_desc),
        ),
    ),
)

/** Developer Tools launcher: one card per tool with a tinted icon tile, grouped by purpose, with search. */
@Composable
fun ToolsScreen(
    onOpen: (ForgeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(56.dp).background(scheme.primary.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Build, contentDescription = null, tint = scheme.primary) }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tools_header_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    stringResource(R.string.tools_header_subtitle),
                    fontSize = 14.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                searching = !searching
                if (!searching) query = ""
            }) { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.tools_search)) }
        }
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.tools_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        toolSections.forEach { section ->
            val rows = section.entries.filter { entry ->
                needle.isEmpty() ||
                    stringResource(entry.title).contains(needle, ignoreCase = true) ||
                    stringResource(entry.subtitle).contains(needle, ignoreCase = true)
            }
            if (rows.isNotEmpty()) {
                Text(
                    text = stringResource(section.label).uppercase(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.primary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                )
                rows.forEach { entry -> ToolCardRow(entry = entry, onClick = { onOpen(entry.tab) }) }
            }
        }
    }
}

@Composable
private fun ToolCardRow(entry: ToolEntry, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ForgeThemedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(entry.tint.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(entry.icon, contentDescription = null, tint = entry.tint, modifier = Modifier.size(24.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(entry.title), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(entry.subtitle), fontSize = 14.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = muted.copy(alpha = 0.7f))
        }
    }
}
