package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.runtime.RuntimeSetupSnapshot
import com.nextcraft.forge.runtime.RuntimeSetupStatus

private const val MIB = 1_048_576.0

/** "12.3 / 71.9 MB" when both sizes are known, otherwise null. */
internal fun formatTransfer(downloadedBytes: Long?, totalBytes: Long?): String? {
    if (downloadedBytes == null || totalBytes == null || totalBytes <= 0L) return null
    return "%.1f / %.1f MB".format(downloadedBytes / MIB, totalBytes / MIB)
}

/**
 * Shared presentation for runtime download / install / uninstall progress: current step, percentage,
 * transferred size and the latest terminal lines, so a long install never looks frozen.
 */
@Composable
internal fun RuntimeProgressPanel(snapshot: RuntimeSetupSnapshot, modifier: Modifier = Modifier) {
    if (snapshot.status == RuntimeSetupStatus.IDLE) return
    val running = snapshot.status == RuntimeSetupStatus.RUNNING
    val failed = snapshot.status == RuntimeSetupStatus.ERROR
    val percent = (snapshot.progress.coerceIn(0f, 1f) * 100).toInt()
    val transfer = formatTransfer(snapshot.downloadedBytes, snapshot.totalBytes)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
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
                Text(
                    text = snapshot.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!snapshot.indeterminate || !running) {
                    Text(
                        text = "%d%%".format(percent),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (running && snapshot.indeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = snapshot.progress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
            }
            transfer?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (failed) {
                Text(
                    text = snapshot.errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val recentLines = snapshot.logs.takeLast(3).filter { it.isNotBlank() }
            if (recentLines.isNotEmpty()) {
                Text(
                    text = recentLines.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
