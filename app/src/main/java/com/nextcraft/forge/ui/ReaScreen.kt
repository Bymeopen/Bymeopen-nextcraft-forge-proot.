package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.ReaCheck

/** REA (Reverse Engine Anything): install it into the chroot and see what the environment can do (rea doctor). */
@Composable
fun ReaScreen(viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val rea by viewModel.rea.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.rea.refresh() }
    val installed = rea.installedVersion != null
    ToolScaffold(
        title = stringResource(R.string.tools_rea),
        subtitle = stringResource(R.string.tools_rea_desc),
        onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
    ) {
        ToolCard {
            Text(stringResource(R.string.rea_notice), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
        }
        ToolSectionLabel(stringResource(R.string.rea_section_install))
        ToolCard {
            KeyValueRow(
                stringResource(R.string.rea_status),
                rea.installedVersion?.let { stringResource(R.string.rea_installed_version, it) } ?: stringResource(R.string.rea_not_installed),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ForgePrimaryButton(
                    text = stringResource(if (installed) R.string.rea_reinstall else R.string.rea_install),
                    onClick = viewModel.rea::install,
                    enabled = !rea.busy,
                    modifier = Modifier.weight(1f),
                )
                if (installed) {
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = viewModel.rea::uninstall,
                        enabled = !rea.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.rea_remove)) }
                }
            }
            if (rea.busy) {
                OutlinedButton(shape = RoundedCornerShape(100), onClick = viewModel.rea::cancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tool_stop))
                }
            }
            rea.message?.let { Text(it.asString(), fontSize = ForgeType.secondary, color = tokens.accentText) }
            LogBox(text = rea.log, placeholder = stringResource(R.string.rea_log_placeholder))
        }

        ToolSectionLabel(stringResource(R.string.rea_section_doctor))
        ToolCard {
            ForgePrimaryButton(
                text = stringResource(R.string.rea_run_doctor),
                onClick = viewModel.rea::runDoctor,
                enabled = installed && !rea.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            rea.mode?.let { mode ->
                Text(
                    text = stringResource(R.string.rea_mode, reaModeLabel(mode)),
                    fontSize = ForgeType.control,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.accentText,
                )
            }
            if (rea.checks.isEmpty() && rea.mode == null) {
                Text(stringResource(R.string.rea_doctor_hint), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
            }
            rea.checks.forEach { check -> ReaCheckRow(check) }
        }
    }
}

@Composable
private fun reaModeLabel(mode: String): String = stringResource(
    when (mode) {
        "full-dynamic" -> R.string.rea_mode_full
        "emulated-dynamic" -> R.string.rea_mode_emulated
        else -> R.string.rea_mode_static
    },
)

@Composable
private fun ReaCheckRow(check: ReaCheck) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val symbol = when (check.status) {
        "ok" -> "\u2705"
        "warn" -> "\u26A0\uFE0F"
        else -> "\u274C"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(symbol, fontSize = ForgeType.body)
        Column(modifier = Modifier.weight(1f)) {
            Text(check.label, fontSize = ForgeType.body, fontWeight = FontWeight.Medium)
            if (check.detail.isNotBlank()) {
                Text(check.detail, fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
            }
            if (check.hint.isNotBlank() && check.status != "ok") {
                Text(check.hint, fontSize = ForgeType.secondary, color = tokens.accentText)
            }
        }
    }
}
