@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nextcraft.forge.runtime.AndroidBuildIssue
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidLogLevel
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import com.nextcraft.forge.runtime.AndroidBuildStage
import com.nextcraft.forge.runtime.AndroidIssueSeverity
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes

private data class StageRow(@StringRes val title: Int, val stages: List<AndroidBuildStage>)

private val stageRows = listOf(
    StageRow(R.string.launch_stage_prepare, listOf(AndroidBuildStage.PREPARING)),
    StageRow(R.string.launch_stage_resolve, listOf(AndroidBuildStage.RESOLVING)),
    StageRow(R.string.launch_stage_compile, listOf(AndroidBuildStage.COMPILING)),
    StageRow(R.string.launch_stage_resources, listOf(AndroidBuildStage.RESOURCES)),
    StageRow(R.string.launch_stage_package, listOf(AndroidBuildStage.PACKAGING, AndroidBuildStage.FINDING_APK)),
    StageRow(R.string.launch_stage_install, listOf(AndroidBuildStage.INSTALLING, AndroidBuildStage.LAUNCHING)),
)

private fun stageRowIndex(stage: AndroidBuildStage): Int = stageRows.indexOfFirst { stage in it.stages }

/** Status of stage row [row] given the last stage the build reached ([lastRow]) and the overall [phase]. */
private fun stageRowStatus(row: Int, lastRow: Int, phase: AndroidBuildPhase): ForgeStatus {
    if (phase == AndroidBuildPhase.IDLE || lastRow < 0) return ForgeStatus.TODO
    return when {
        row < lastRow -> ForgeStatus.DONE
        row == lastRow -> when (phase) {
            AndroidBuildPhase.SUCCEEDED -> ForgeStatus.DONE
            AndroidBuildPhase.FAILED -> ForgeStatus.FAILED
            AndroidBuildPhase.CANCELLED -> ForgeStatus.SKIPPED
            else -> ForgeStatus.RUNNING
        }
        // Rows after the last reached one: skipped on success (e.g. no install), still waiting otherwise.
        else -> if (phase == AndroidBuildPhase.SUCCEEDED) ForgeStatus.SKIPPED else ForgeStatus.TODO
    }
}

@StringRes
private fun AndroidBuildPhase.headline(): Int = when (this) {
    AndroidBuildPhase.IDLE -> R.string.launch_state_idle
    AndroidBuildPhase.PREPARING -> R.string.launch_state_preparing
    AndroidBuildPhase.BUILDING -> R.string.launch_state_building
    AndroidBuildPhase.CANCELLING -> R.string.launch_state_cancelling
    AndroidBuildPhase.SUCCEEDED -> R.string.launch_state_succeeded
    AndroidBuildPhase.FAILED -> R.string.launch_state_failed
    AndroidBuildPhase.CANCELLED -> R.string.launch_state_cancelled
}

private fun AndroidBuildPhase.overallStatus(): ForgeStatus = when (this) {
    AndroidBuildPhase.IDLE -> ForgeStatus.TODO
    AndroidBuildPhase.PREPARING, AndroidBuildPhase.BUILDING, AndroidBuildPhase.CANCELLING -> ForgeStatus.RUNNING
    AndroidBuildPhase.SUCCEEDED -> ForgeStatus.DONE
    AndroidBuildPhase.FAILED -> ForgeStatus.FAILED
    AndroidBuildPhase.CANCELLED -> ForgeStatus.SKIPPED
}

/**
 * Launch screen, Linear style: title + action pill, one status block, then tabs
 * (Stages / Issues / Log / Logcat). A failed build jumps to Issues so the fix is one tap away.
 */
@Composable
fun AndroidBuildScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val build = state.androidBuild
    val busy = build.phase == AndroidBuildPhase.PREPARING ||
        build.phase == AndroidBuildPhase.BUILDING ||
        build.phase == AndroidBuildPhase.CANCELLING
    val canStop = build.phase == AndroidBuildPhase.PREPARING || build.phase == AndroidBuildPhase.BUILDING
    val tokens = LocalForgeThemeTokens.current
    var activeTab by rememberSaveable { mutableStateOf(0) } // 0 stages, 1 issues, 2 log, 3 logcat
    var menuOpen by remember { mutableStateOf(false) }

    // Stage events carry only the active stage, so remember the last real one to place success/failure.
    var lastRow by remember { mutableStateOf(-1) }
    LaunchedEffect(build.stage, build.phase) {
        val index = stageRowIndex(build.stage)
        if (build.phase == AndroidBuildPhase.IDLE) lastRow = -1 else if (index >= 0) lastRow = index
    }
    LaunchedEffect(build.phase) {
        if (build.phase == AndroidBuildPhase.FAILED && build.issues.isNotEmpty()) activeTab = 1
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeLargeTitle(
                title = stringResource(R.string.launch_launch),
                subtitle = state.activeProject?.name,
                modifier = Modifier.weight(1f),
            )
            ForgeActionPill {
                if (canStop) {
                    ForgePillIcon(
                        icon = Icons.Default.Close,
                        description = stringResource(R.string.launch_cancel_build),
                        onClick = viewModel::stopActiveTask,
                        tint = MaterialTheme.colorScheme.error,
                    )
                } else {
                    ForgePillIcon(
                        icon = Icons.Default.PlayArrow,
                        description = stringResource(R.string.launch_build_and_install_apk),
                        enabled = !busy,
                        onClick = { viewModel.runAndroidBuild("assembleDebug", installAfterBuild = true) },
                    )
                }
                Box {
                    ForgePillIcon(
                        icon = Icons.Default.MoreVert,
                        description = stringResource(R.string.launch_more_build_actions),
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.launch_assemble_debug_no_install)) },
                            enabled = !busy,
                            onClick = {
                                menuOpen = false
                                viewModel.runAndroidBuild("assembleDebug", installAfterBuild = false)
                            },
                        )
                        if (build.apkPath != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.launch_install_last_apk)) },
                                enabled = !busy,
                                onClick = {
                                    menuOpen = false
                                    viewModel.installBuiltApk()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.launch_clean)) },
                            enabled = !busy,
                            onClick = {
                                menuOpen = false
                                viewModel.runAndroidBuild("clean", installAfterBuild = false)
                            },
                        )
                    }
                }
            }
        }

        // Status block
        val errorCount = build.issues.count { it.severity == AndroidIssueSeverity.ERROR }
        val warningCount = build.issues.count { it.severity == AndroidIssueSeverity.WARNING }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeStatusIcon(build.phase.overallStatus(), iconSize = 22.dp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(build.phase.headline()), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (build.phase == AndroidBuildPhase.FAILED && build.issues.isNotEmpty()) {
                        stringResource(R.string.launch_errors_warnings, errorCount, warningCount)
                    } else {
                        build.message ?: build.stage.displayLabel()
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (build.phase == AndroidBuildPhase.BUILDING) {
            LinearProgressIndicator(
                progress = build.stage.progressFraction(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(3.dp),
            )
        }
        if (build.phase == AndroidBuildPhase.FAILED && build.issues.isNotEmpty()) {
            ForgePrimaryButton(
                text = stringResource(R.string.launch_fix_with_agent),
                onClick = {
                    viewModel.sendPrompt(build.issues.joinToString("\n\n") { it.formatForAgentPrompt() })
                    viewModel.selectTab(ForgeTab.AGENT)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        } else if (build.phase == AndroidBuildPhase.SUCCEEDED && build.apkPath != null) {
            ForgePrimaryButton(
                text = stringResource(R.string.launch_install_apk),
                onClick = viewModel::installBuiltApk,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        ForgePillTabs(
            items = listOf(
                stringResource(R.string.launch_tab_stages),
                if (build.issues.isEmpty()) {
                    stringResource(R.string.launch_tab_issues)
                } else {
                    stringResource(R.string.launch_tab_issues_count, build.issues.size)
                },
                stringResource(R.string.launch_tab_log),
                stringResource(R.string.launch_tab_logcat),
            ),
            selectedIndex = activeTab,
            onSelect = { activeTab = it },
        )

        when (activeTab) {
            0 -> Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                stageRows.forEachIndexed { index, row ->
                    val status = stageRowStatus(index, lastRow, build.phase)
                    val muted = status == ForgeStatus.TODO || status == ForgeStatus.SKIPPED
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ForgeStatusIcon(status, iconSize = 20.dp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = stringResource(row.title),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            1 -> BuildIssuesList(
                issues = build.issues,
                onAskAgentToFix = { issue ->
                    viewModel.sendPrompt(issue.formatForAgentPrompt())
                    viewModel.selectTab(ForgeTab.AGENT)
                },
                onOpenFile = { path ->
                    viewModel.openWorkspaceFile(path)
                    viewModel.selectTab(ForgeTab.FILES)
                },
                modifier = Modifier.weight(1f),
            )

            2 -> {
                val consoleShape = RoundedCornerShape(12.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(consoleShape)
                        .background(tokens.terminalBackground)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = build.log.ifBlank { stringResource(R.string.launch_no_build_output) },
                            color = tokens.terminalText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }

            else -> LogcatPanel(
                state = state,
                onStartLogcat = viewModel::startProjectLogcat,
                onStopLogcat = viewModel::stopProjectLogcat,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BuildIssuesList(
    issues: List<AndroidBuildIssue>,
    onAskAgentToFix: (AndroidBuildIssue) -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    if (issues.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.launch_zero_compiler_or_aapt2),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(issues) { issue ->
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = tokens.dangerText,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = issue.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    issue.filePath?.let { path ->
                        Text(
                            text = buildString {
                                append(path)
                                issue.line?.let { append(":").append(it) }
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.accentText,
                        )
                    }
                    Text(
                        text = issue.detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    issue.suggestion?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAskAgentToFix(issue) }) {
                            Text(stringResource(R.string.launch_ask_agent_to_fix))
                        }
                        issue.filePath?.let { path ->
                            OutlinedButton(onClick = { onOpenFile(path) }) {
                                Text(stringResource(R.string.launch_open_file))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogcatPanel(
    state: ForgeUiState,
    onStartLogcat: () -> Unit,
    onStopLogcat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logcat = state.androidLogcat
    val tokens = LocalForgeThemeTokens.current
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = logcat.message ?: "UID-scoped Logcat (${logcat.packageName ?: "app"})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (logcat.running) {
                OutlinedButton(onClick = onStopLogcat) {
                    Text(stringResource(R.string.launch_pause))
                }
            } else {
                Button(onClick = onStartLogcat) {
                    Text(stringResource(R.string.launch_stream_logcat))
                }
            }
        }

        val logcatShape = RoundedCornerShape(10.dp)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(logcatShape)
                .background(tokens.terminalBackground)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, logcatShape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(logcat.lines, key = { it.id }) { line ->
                val color = when (line.level) {
                    AndroidLogLevel.FATAL, AndroidLogLevel.ERROR -> tokens.dangerText
                    AndroidLogLevel.WARNING -> tokens.warningText
                    AndroidLogLevel.INFO -> tokens.accentText
                    else -> tokens.terminalText
                }
                Text(
                    text = line.text,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var urlInput by rememberSaveable(state.previewUrl) { mutableStateOf(state.previewUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val tokens = LocalForgeThemeTokens.current
    val colors = MaterialTheme.colorScheme
    val webViewBgArgb = tokens.terminalBackground.toArgb()

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    fun openUrl(raw: String) {
        val clean = raw.trim()
        val uriScheme = android.net.Uri.parse(clean).scheme?.lowercase()
        if (clean.isNotEmpty() && (uriScheme == "http" || uriScheme == "https")) {
            viewModel.updatePreviewUrl(clean)
            webViewRef?.loadUrl(clean)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeLargeTitle(
                title = stringResource(R.string.launch_preview),
                subtitle = stringResource(R.string.launch_local_dev_server),
                modifier = Modifier.weight(1f),
            )
            ForgeActionPill {
                ForgePillIcon(
                    icon = Icons.Default.Refresh,
                    description = stringResource(R.string.launch_scan_ports_and_reload),
                    onClick = {
                        viewModel.scanLocalPreviewPorts()
                        webViewRef?.reload()
                    },
                )
            }
        }

        // Address bar: one rounded surface with a text-style "Open" action.
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = tokens.cardContainerColor,
            contentColor = colors.onSurface,
            border = tokens.cardBorderStroke,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 6.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    placeholder = { Text("http://127.0.0.1:3000") },
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.launch_open),
                    color = tokens.accentText,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .clickable(role = Role.Button) { openUrl(urlInput) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }

        if (state.activeLocalPorts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.activeLocalPorts.forEach { port ->
                    val portUrl = "http://127.0.0.1:$port"
                    AssistChip(
                        onClick = {
                            urlInput = portUrl
                            openUrl(portUrl)
                        },
                        shape = RoundedCornerShape(100),
                        label = { Text(":$port", fontFamily = FontFamily.Monospace) },
                    )
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp)),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(webViewBgArgb)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean = request.url.scheme?.lowercase() !in setOf("http", "https")
                    }
                    loadUrl(state.previewUrl)
                    webViewRef = this
                }
            },
            update = { webView ->
                webView.setBackgroundColor(webViewBgArgb)
                webViewRef = webView
            },
        )
    }
}
