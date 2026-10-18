@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nextcraft.forge.runtime.AndroidBuildIssue
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidLogLevel
import kotlinx.coroutines.delay

@Composable
fun AndroidBuildScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val build = state.androidBuild
    val isBuilding = build.phase == AndroidBuildPhase.BUILDING
    var activeConsoleTab by rememberSaveable { mutableStateOf("gradle") } // "gradle" | "issues" | "logcat"
    val gradleScrollState = rememberScrollState()
    val tokens = LocalForgeThemeTokens.current
    val clipboard = LocalClipboardManager.current
    var logCopied by remember { mutableStateOf(false) }

    LaunchedEffect(logCopied) {
        if (logCopied) {
            delay(1500)
            logCopied = false
        }
    }

    // BUG-UI-008 Fix: Auto-scroll Gradle output as build log streams in
    LaunchedEffect(build.log.length, isBuilding) {
        if (isBuilding && build.log.isNotEmpty()) {
            gradleScrollState.animateScrollTo(gradleScrollState.maxValue)
        }
    }

    // BUG-UI-026 Fix: Auto-switch to Diagnostics when a build fails with parsed compiler/resource issues
    LaunchedEffect(build.phase, build.issues.size) {
        if (build.phase == AndroidBuildPhase.BUILDING) {
            activeConsoleTab = "gradle"
        } else if (build.phase == AndroidBuildPhase.FAILED && build.issues.isNotEmpty()) {
            activeConsoleTab = "issues"
        }
    }

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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = build.message ?: build.stage.displayLabel(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val (badgeBg, badgeFg) = when (build.phase) {
                        AndroidBuildPhase.SUCCEEDED -> tokens.diffAddedBackground to tokens.successText
                        AndroidBuildPhase.FAILED -> tokens.diffRemovedBackground to tokens.dangerText
                        AndroidBuildPhase.BUILDING -> tokens.inlineCodeBackground to tokens.accentText
                        else -> tokens.cardElevatedColor to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        color = badgeBg,
                        contentColor = badgeFg,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = build.phase.displayBadge(),
                            color = badgeFg,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
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

        // BUG-UI-008 Fix: Horizontally scrollable FilterChip row so 3 console tabs never overflow on narrow phones
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
            if (activeConsoleTab == "gradle" && build.log.isNotBlank()) {
                AssistChip(
                    onClick = {
                        clipboard.setText(AnnotatedString(build.log))
                        logCopied = true
                    },
                    label = { Text(if (logCopied) "Copied!" else "Copy Log") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                )
            }
        }

        when (activeConsoleTab) {
            "gradle" -> {
                val consoleShape = RoundedCornerShape(12.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(consoleShape)
                        .background(tokens.terminalBackground)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, consoleShape)
                        .verticalScroll(gradleScrollState)
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
                onClearLogcat = viewModel::clearProjectLogcat,
                onSetMinimumLevel = viewModel::setLogcatMinimumLevel,
                onSetQuery = viewModel::setLogcatQuery,
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
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { onOpenFile(path) },
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
    onClearLogcat: () -> Unit,
    onSetMinimumLevel: (AndroidLogLevel) -> Unit,
    onSetQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val logcat = state.androidLogcat
    val listState = rememberLazyListState()
    val tokens = LocalForgeThemeTokens.current

    val filteredLines = remember(logcat.lines, logcat.minimumLevel, logcat.query) {
        val q = logcat.query.trim()
        logcat.lines.filter { line ->
            line.level.ordinal >= logcat.minimumLevel.ordinal &&
                (q.isEmpty() || line.text.contains(q, ignoreCase = true))
        }
    }

    val lastLogId = filteredLines.lastOrNull()?.id ?: 0L
    LaunchedEffect(lastLogId) {
        if (filteredLines.isNotEmpty() && logcat.running) {
            listState.scrollToItem(filteredLines.lastIndex)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = logcat.message ?: "UID-scoped Logcat (${logcat.packageName ?: "app"})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (logcat.lines.isNotEmpty()) {
                IconButton(
                    onClick = onClearLogcat,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear logcat",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val levels = listOf(
                AndroidLogLevel.VERBOSE to "ALL",
                AndroidLogLevel.INFO to "I+",
                AndroidLogLevel.WARNING to "W+",
                AndroidLogLevel.ERROR to "E+",
            )
            levels.forEach { (level, label) ->
                FilterChip(
                    selected = logcat.minimumLevel == level,
                    onClick = { onSetMinimumLevel(level) },
                    label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                )
            }
            OutlinedTextField(
                value = logcat.query,
                onValueChange = onSetQuery,
                placeholder = { Text("Filter tag/text…", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                },
                trailingIcon = if (logcat.query.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onSetQuery("") },
                            modifier = Modifier.size(22.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear logcat filter",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.width(200.dp),
            )
        }

        val logcatShape = RoundedCornerShape(12.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(logcatShape)
                .background(tokens.terminalBackground)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, logcatShape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filteredLines, key = { it.id }) { line ->
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

private fun normalizePreviewUrlInput(raw: String): String {
    val clean = raw.trim()
    if (clean.isEmpty()) return ""
    if (clean.startsWith(":")) {
        return "http://127.0.0.1$clean"
    }
    if (clean.all { it.isDigit() }) {
        return "http://127.0.0.1:$clean"
    }
    val lower = clean.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        return clean
    }
    return "http://$clean"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var urlInput by rememberSaveable(state.previewUrl) { mutableStateOf(state.previewUrl) }
    var lastLoadedUrl by remember { mutableStateOf(state.previewUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val tokens = LocalForgeThemeTokens.current
    val context = LocalContext.current
    val webViewBgArgb = tokens.terminalBackground.toArgb()

    val navigateToInputUrl = {
        val normalized = normalizePreviewUrlInput(urlInput)
        val scheme = Uri.parse(normalized).scheme?.lowercase()
        if (normalized.isNotEmpty() && (scheme == "http" || scheme == "https")) {
            urlInput = normalized
            lastLoadedUrl = normalized
            viewModel.updatePreviewUrl(normalized)
            webViewRef?.loadUrl(normalized)
        }
    }

    // BUG-UI-008 Fix: Automatically navigate WebView when state.previewUrl changes externally (e.g. PreviewStarted event or port scan)
    LaunchedEffect(state.previewUrl) {
        if (state.previewUrl.isNotBlank() && state.previewUrl != lastLoadedUrl) {
            lastLoadedUrl = state.previewUrl
            webViewRef?.loadUrl(state.previewUrl)
        }
    }

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
                        shape = RoundedCornerShape(14.dp),
                        label = { Text("Local Dev Server URL (Host NetNS)") },
                        placeholder = { Text("http://127.0.0.1:3000 or :5173") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigateToInputUrl() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { navigateToInputUrl() },
                        shape = RoundedCornerShape(12.dp),
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
                    IconButton(
                        onClick = {
                            val target = normalizePreviewUrlInput(urlInput).ifBlank { state.previewUrl }
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Open in external browser")
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
                                    lastLoadedUrl = portUrl
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
            factory = { ctx ->
                WebView(ctx).apply {
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
                    lastLoadedUrl = state.previewUrl
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
