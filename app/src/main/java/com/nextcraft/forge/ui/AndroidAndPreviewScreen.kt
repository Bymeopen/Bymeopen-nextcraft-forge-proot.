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

@Composable
fun AndroidBuildScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val build = state.androidBuild
    val isBuilding = build.phase == AndroidBuildPhase.BUILDING
    var activeConsoleTab by rememberSaveable { mutableStateOf("gradle") } // "gradle" | "issues" | "logcat"
    val tokens = LocalForgeThemeTokens.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Android APK Builder (SDK 35 • Bionic aapt2)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = build.message ?: build.stage.displayLabel(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val (badgeBg, badgeFg) = when (build.phase) {
                        AndroidBuildPhase.SUCCEEDED -> tokens.diffAddedBackground to tokens.successText
                        AndroidBuildPhase.FAILED -> tokens.diffRemovedBackground to tokens.dangerText
                        AndroidBuildPhase.BUILDING -> tokens.inlineCodeBackground to tokens.accentText
                        else -> tokens.cardElevatedColor to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        color = badgeBg,
                        contentColor = badgeFg,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = build.phase.displayBadge(),
                            color = badgeFg,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                if (isBuilding) {
                    LinearProgressIndicator(
                        progress = build.stage.progressFraction(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.runAndroidBuild("assembleDebug", installAfterBuild = true) },
                        enabled = !isBuilding,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Build & Install APK")
                    }
                    OutlinedButton(
                        onClick = { viewModel.runAndroidBuild("assembleDebug", installAfterBuild = false) },
                        enabled = !isBuilding,
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Assemble Debug")
                    }
                    if (build.apkPath != null && !isBuilding) {
                        OutlinedButton(onClick = { viewModel.installBuiltApk() }) {
                            Text("Install Last APK")
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.runAndroidBuild("clean", installAfterBuild = false) },
                        enabled = !isBuilding,
                    ) {
                        Text("Clean")
                    }
                    if (isBuilding) {
                        OutlinedButton(onClick = viewModel::stopActiveTask) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = activeConsoleTab == "gradle",
                onClick = { activeConsoleTab = "gradle" },
                label = { Text("Gradle Output") },
            )
            FilterChip(
                selected = activeConsoleTab == "issues",
                onClick = { activeConsoleTab = "issues" },
                label = { Text("Diagnostics (${build.issues.size})") },
            )
            FilterChip(
                selected = activeConsoleTab == "logcat",
                onClick = { activeConsoleTab = "logcat" },
                label = { Text("Live Logcat (${state.androidLogcat.lines.size})") },
            )
        }

        when (activeConsoleTab) {
            "gradle" -> {
                val consoleShape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(consoleShape)
                        .background(tokens.terminalBackground)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, consoleShape)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = build.log.ifBlank { "No Gradle build output yet. Tap 'Build & Install APK' to compile." },
                            color = tokens.terminalText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }

            "issues" -> BuildIssuesList(
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
                text = "Zero compiler or AAPT2 resource issues detected.",
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
                            Text("Ask Agent to Fix")
                        }
                        issue.filePath?.let { path ->
                            OutlinedButton(onClick = { onOpenFile(path) }) {
                                Text("Open File")
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
                    Text("Pause")
                }
            } else {
                Button(onClick = onStartLogcat) {
                    Text("Stream Logcat")
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
    val webViewBgArgb = tokens.terminalBackground.toArgb()

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = tokens.topBarContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        label = { Text("Local Dev Server URL (Host NetNS)") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val clean = urlInput.trim()
                            val scheme = android.net.Uri.parse(clean).scheme?.lowercase()
                            if (clean.isNotEmpty() && (scheme == "http" || scheme == "https")) {
                                viewModel.updatePreviewUrl(clean)
                                webViewRef?.loadUrl(clean)
                            }
                        },
                    ) {
                        Text("Go")
                    }
                    IconButton(
                        onClick = {
                            viewModel.scanLocalPreviewPorts()
                            webViewRef?.reload()
                        },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan & Reload")
                    }
                }

                if (state.activeLocalPorts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Open Ports:",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.successText,
                            fontWeight = FontWeight.Bold,
                        )
                        state.activeLocalPorts.forEach { port ->
                            val portUrl = "http://127.0.0.1:$port"
                            AssistChip(
                                onClick = {
                                    urlInput = portUrl
                                    viewModel.updatePreviewUrl(portUrl)
                                    webViewRef?.loadUrl(portUrl)
                                },
                                label = { Text(":$port", fontFamily = FontFamily.Monospace) },
                            )
                        }
                    }
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
