@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

/** One selectable model: [id] is what the agent receives, [label] is what the user reads, [badge] a short tier hint. */
data class ModelOption(val id: String, val label: String, val badge: String = "")

/**
 * Searchable model list shared by every agent (Claude Code, DeepSeek Harness, Antigravity). Typing an id that is not in
 * the list offers it as a custom model, so models the lists do not know yet stay usable.
 */
@Composable
fun ModelPickerDialog(
    title: String,
    options: List<ModelOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val filtered = remember(options, needle) {
        if (needle.isEmpty()) options else options.filter {
            it.id.contains(needle, ignoreCase = true) || it.label.contains(needle, ignoreCase = true)
        }
    }
    val offerCustom = needle.isNotEmpty() && options.none { it.id.equals(needle, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.model_picker_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.model_picker_loading), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (offerCustom) {
                        item(key = "custom:$needle") {
                            ModelRow(
                                label = stringResource(R.string.model_picker_use_custom, needle),
                                id = "",
                                badge = "",
                                selected = false,
                                onClick = { onSelect(needle) },
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { option ->
                        ModelRow(
                            label = option.label,
                            id = option.id,
                            badge = option.badge,
                            selected = option.id == selectedId,
                            onClick = { onSelect(option.id) },
                        )
                    }
                    if (filtered.isEmpty() && !offerCustom) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.model_picker_empty),
                                fontSize = ForgeType.control,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_picker_close)) } },
        dismissButton = onRefresh?.let { refresh ->
            { TextButton(onClick = refresh, enabled = !loading) { Text(stringResource(R.string.model_picker_refresh)) } }
        },
    )
}

@Composable
private fun ModelRow(label: String, id: String, badge: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = ForgeType.body, fontWeight = FontWeight.Medium)
            if (id.isNotEmpty() && id != label) {
                Text(id, fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
            }
        }
        if (badge.isNotEmpty()) {
            Text(badge, fontSize = ForgeType.caption, color = scheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
