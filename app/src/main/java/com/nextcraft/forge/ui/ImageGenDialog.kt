package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.network.GeminiImageModels

/** Nano Banana: describe a picture (or, with an image attached, how to change it) and it lands in the chat. */
@Composable
internal fun ImageGenerationDialog(state: ForgeUiState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val hasKey = remember(state.geminiKeyVersion) { viewModel.hasGeminiImageKey() }
    // The key uses plain remember: rememberSaveable would write it into the saved-instance Bundle.
    var key by remember { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var modelIndex by rememberSaveable { mutableStateOf(0) }
    val editing = state.pendingAttachments.firstOrNull { it.mimeType.startsWith("image/") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.image_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!hasKey) {
                    Text(stringResource(R.string.image_key_desc), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(R.string.image_key_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it.take(2000) },
                        label = { Text(stringResource(R.string.image_prompt_hint)) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (editing != null) {
                        Text(
                            stringResource(R.string.image_editing, editing.displayName),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ForgePillTabs(
                        items = listOf(stringResource(R.string.image_model_fast), stringResource(R.string.image_model_pro)),
                        selectedIndex = modelIndex,
                        onSelect = { modelIndex = it },
                    )
                }
            }
        },
        confirmButton = {
            if (!hasKey) {
                TextButton(enabled = key.isNotBlank(), onClick = { viewModel.saveGeminiImageKey(key); key = "" }) {
                    Text(stringResource(R.string.image_key_save))
                }
            } else {
                TextButton(
                    enabled = prompt.isNotBlank() && !state.imageGenerating,
                    onClick = {
                        viewModel.generateImage(prompt, GeminiImageModels.all[modelIndex.coerceIn(0, GeminiImageModels.all.lastIndex)])
                        prompt = ""
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.image_generate)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) } },
    )
}
