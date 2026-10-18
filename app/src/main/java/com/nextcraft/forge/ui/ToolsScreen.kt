package com.nextcraft.forge.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes

private data class ToolEntry(
    val tab: ForgeTab,
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
)

private val developmentTools = listOf(
    ToolEntry(ForgeTab.FILES, Icons.Default.Edit, R.string.tools_files, R.string.tools_files_desc),
    ToolEntry(ForgeTab.TERMINAL, Icons.Default.List, R.string.shell_terminal, R.string.tools_terminal_desc),
    ToolEntry(ForgeTab.PREVIEW, Icons.Default.PlayArrow, R.string.tools_web_preview, R.string.tools_web_preview_desc),
)

private val buildTools = listOf(
    ToolEntry(ForgeTab.ANDROID, Icons.Default.Build, R.string.tools_android_builder, R.string.tools_android_builder_desc),
)

/**
 * Linear-style launcher: big title, quiet section labels, flat rows (no cards), one muted icon per row.
 * It only routes to the existing tool screens, so it adds no logic of its own.
 */
@Composable
fun ToolsScreen(
    onOpen: (ForgeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.tools_tools),
            fontSize = 27.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        )
        ToolsSection(stringResource(R.string.tools_section_development), developmentTools, onOpen)
        ToolsSection(stringResource(R.string.tools_section_build_deploy), buildTools, onOpen)
    }
}

@Composable
private fun ToolsSection(
    label: String,
    entries: List<ToolEntry>,
    onOpen: (ForgeTab) -> Unit,
) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
    entries.forEach { entry ->
        ToolRow(entry = entry, onClick = { onOpen(entry.tab) })
    }
}

@Composable
private fun ToolRow(entry: ToolEntry, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decorative: the row title already names the destination for TalkBack.
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(entry.title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(entry.subtitle),
                fontSize = 14.sp,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = muted.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}
