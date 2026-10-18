@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.providersForAgent
import com.nextcraft.forge.runtime.AntigravityAuthStatus
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import kotlinx.coroutines.launch

@Composable
fun SettingsScreenModern(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val agyAuth by viewModel.antigravityAuth.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val tokens = LocalForgeThemeTokens.current

    var baseUrlInput by rememberSaveable(state.providerProfile.kind) {
        mutableStateOf(state.providerProfile.baseUrl)
    }
    var modelInput by rememberSaveable(state.providerProfile.kind, state.providerProfile.model) {
        mutableStateOf(state.providerProfile.model)
    }
    var openRouterOrderInput by rememberSaveable(state.providerProfile.kind) {
        mutableStateOf(state.providerProfile.openRouterProviderOrder)
    }
    var keyLabelInput by rememberSaveable { mutableStateOf("") }
    // Secrets use plain remember: rememberSaveable would write them into the saved-instance Bundle.
    var keySecretInput by remember { mutableStateOf("") }

    var agyModelInput by rememberSaveable(state.antigravityModel) {
        mutableStateOf(state.antigravityModel)
    }
    var agyEffortInput by rememberSaveable(state.antigravityEffort) {
        mutableStateOf(state.antigravityEffort)
    }
    var agyCodeInput by remember { mutableStateOf("") }

    var githubTokenInput by remember { mutableStateOf("") }
    var newRepoName by rememberSaveable { mutableStateOf("") }
    var newRepoDesc by rememberSaveable { mutableStateOf("") }
    var newRepoPrivate by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Coding Agent Engine
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Coding Agent Engine (Root Chroot)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                AgentKind.entries.forEach { kind ->
                    val selected = state.agentKind == kind
                    ForgeElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        selected = selected,
                        onClick = { viewModel.selectAgent(kind) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(kind.displayTitle(), fontWeight = FontWeight.Bold)
                                Text(
                                    text = kind.displaySubtitle(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = tokens.successText,
                                )
                            }
                        }
                    }
                }

                if (state.agentKind == AgentKind.ANTIGRAVITY) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Antigravity CLI Configuration",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = agyModelInput,
                        onValueChange = { agyModelInput = it },
                        label = { Text("Antigravity Model (blank = default auto)") },
                        placeholder = { Text("default (auto) — e.g. gemini-2.5-pro") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "" to "default (auto)",
                            "gemini-2.5-pro" to "gemini-2.5-pro",
                            "gemini-2.5-flash" to "gemini-2.5-flash",
                        ).forEach { (modelVal, label) ->
                            FilterChip(
                                selected = agyModelInput.trim() == modelVal,
                                onClick = {
                                    agyModelInput = modelVal
                                    viewModel.setAntigravityConfig(modelVal, agyEffortInput)
                                },
                                label = { Text(label, fontFamily = FontFamily.Monospace) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("low", "medium", "high").forEach { effort ->
                            FilterChip(
                                selected = agyEffortInput == effort,
                                onClick = {
                                    agyEffortInput = effort
                                    viewModel.setAntigravityConfig(agyModelInput, effort)
                                },
                                label = { Text("Effort: ${effort.uppercase()}") },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.setAntigravityConfig(agyModelInput, agyEffortInput) },
                        ) {
                            Text("Save Antigravity Model")
                        }
                        val hasAgyCredential by produceState(initialValue = false, agyAuth.status) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                viewModel.antigravityAuth.hasOfficialCredential()
                            }
                        }
                        val authInFlight = agyAuth.status == AntigravityAuthStatus.STARTING ||
                            agyAuth.status == AntigravityAuthStatus.AWAITING_CODE ||
                            agyAuth.status == AntigravityAuthStatus.COMPLETING
                        if (agyAuth.status == AntigravityAuthStatus.SIGNED_IN || hasAgyCredential) {
                            OutlinedButton(
                                onClick = { scope.launch { runCatching { viewModel.antigravityAuth.logout() } } },
                            ) {
                                Text("Sign Out Google OAuth")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { scope.launch { viewModel.antigravityAuth.beginLogin() } },
                                enabled = !authInFlight,
                            ) {
                                Text("Sign In with Google")
                            }
                        }
                        if (authInFlight) {
                            OutlinedButton(
                                onClick = { viewModel.antigravityAuth.cancel() },
                            ) {
                                Text("Cancel Sign-In")
                            }
                        }
                        OutlinedButton(
                            onClick = viewModel::validateAntigravityConnection,
                            enabled = !state.apiBusy,
                        ) {
                            Text("Validate CLI")
                        }
                    }

                    agyAuth.accountEmail?.takeIf { it.isNotBlank() }?.let { email ->
                        Text(
                            text = "Signed in as $email",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.successText,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    agyAuth.message?.takeIf { it.isNotBlank() }?.let { authMsg ->
                        Text(
                            text = authMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (agyAuth.status == AntigravityAuthStatus.ERROR) {
                                tokens.dangerText
                            } else {
                                tokens.accentText
                            },
                        )
                    }
                    agyAuth.authorizationUrl?.takeIf { it.isNotBlank() }?.let { authUrl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                                    }
                                },
                            ) {
                                Text("Open Google Sign-In")
                            }
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(authUrl)) },
                            ) {
                                Text("Copy OAuth URL")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = agyCodeInput,
                                onValueChange = { agyCodeInput = it },
                                label = { Text("Google Verification Code") },
                                placeholder = { Text("Paste authorization code…") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    runCatching {
                                        viewModel.antigravityAuth.submitCode(agyCodeInput)
                                        agyCodeInput = ""
                                    }
                                },
                                enabled = agyCodeInput.isNotBlank(),
                            ) {
                                Text("Verify")
                            }
                        }
                    }

                    if (state.apiBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    state.apiStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.accentText,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // 2. AI Provider & Keystore Multi-Key Vault
        val availableProviders = providersForAgent(state.agentKind)
        if (availableProviders.isNotEmpty()) {
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Model Provider & Android Keystore Vault",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        availableProviders.forEach { kind ->
                            FilterChip(
                                selected = state.providerProfile.kind == kind,
                                onClick = { viewModel.updateProviderKind(kind) },
                                label = { Text(kind.displayTitle()) },
                            )
                        }
                    }
                    Text(
                        text = state.providerProfile.kind.displaySubtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (!state.providerProfile.kind.fixedBaseUrl &&
                        state.providerProfile.kind != ProviderKind.CLAUDE
                    ) {
                        OutlinedTextField(
                            value = baseUrlInput,
                            onValueChange = { baseUrlInput = it },
                            label = { Text("API Base URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("Model Identifier") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (state.providerProfile.kind == ProviderKind.LLM_ROUTER) {
                        OutlinedTextField(
                            value = openRouterOrderInput,
                            onValueChange = { openRouterOrderInput = it },
                            label = { Text("OpenRouter Provider Order (comma-separated, optional)") },
                            placeholder = { Text("anthropic,google-vertex,deepseek") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Allow Provider Fallbacks", fontWeight = FontWeight.Medium)
                                Text(
                                    text = "Automatically fail over to backup OpenRouter upstreams when rate-limited",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.providerProfile.openRouterAllowFallbacks,
                                onCheckedChange = { allow ->
                                    viewModel.updateProviderConfig(
                                        baseUrl = baseUrlInput,
                                        model = modelInput,
                                        openRouterOrder = openRouterOrderInput,
                                        openRouterAllowFallbacks = allow,
                                    )
                                },
                            )
                        }
                    }

                    if (state.providerProfile.kind == ProviderKind.CUSTOM ||
                        state.providerProfile.kind == ProviderKind.KIMI
                    ) {
                        Text(
                            text = "Wire Protocol Format:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                "anthropic-messages" to "Anthropic Messages",
                                "openai-completions" to "OpenAI Chat",
                                "openai-responses" to "OpenAI Responses",
                            ).forEach { (apiFormat, label) ->
                                FilterChip(
                                    selected = state.providerProfile.dshApi == apiFormat,
                                    onClick = {
                                        viewModel.updateProviderConfig(
                                            baseUrl = baseUrlInput,
                                            model = modelInput,
                                            openRouterOrder = openRouterOrderInput,
                                            dshApi = apiFormat,
                                        )
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                viewModel.updateProviderConfig(
                                    baseUrl = baseUrlInput,
                                    model = modelInput,
                                    openRouterOrder = openRouterOrderInput,
                                )
                            },
                        ) {
                            Text("Save Config")
                        }
                        OutlinedButton(
                            onClick = viewModel::discoverProviderModels,
                            enabled = !state.apiBusy,
                        ) {
                            Text("Discover Models")
                        }
                        OutlinedButton(
                            onClick = viewModel::validateProviderConnection,
                            enabled = !state.apiBusy,
                        ) {
                            Text("Validate")
                        }
                    }

                    if (state.apiBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    state.apiStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.accentText,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    if (state.discoveredModels.isNotEmpty()) {
                        Text(
                            text = "Discovered Models (tap to select):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.discoveredModels.take(30).forEach { m ->
                                AssistChip(
                                    onClick = {
                                        modelInput = m.id
                                        viewModel.updateProviderConfig(
                                            baseUrl = baseUrlInput,
                                            model = m.id,
                                            openRouterOrder = openRouterOrderInput,
                                        )
                                    },
                                    label = { Text(m.displayName, fontFamily = FontFamily.Monospace) },
                                )
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "API Keys for ${state.providerProfile.kind.displayTitle()} (Auto-Rotation Pool)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.savedApiKeys.forEach { keyInfo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.selectActiveApiKey(keyInfo.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (keyInfo.isActive) Icons.Default.CheckCircle else Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (keyInfo.isActive) tokens.successText else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (keyInfo.isActive) "${keyInfo.name} (Active)" else keyInfo.name,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (keyInfo.isActive) FontWeight.Bold else FontWeight.Normal,
                            )
                            IconButton(onClick = { viewModel.removeApiKey(keyInfo.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete key", modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = keyLabelInput,
                            onValueChange = { keyLabelInput = it },
                            label = { Text("Label") },
                            placeholder = { Text("Primary") },
                            singleLine = true,
                            modifier = Modifier.weight(0.35f),
                        )
                        OutlinedTextField(
                            value = keySecretInput,
                            onValueChange = { keySecretInput = it },
                            label = { Text("API Key / Token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(0.65f),
                        )
                    }
                    Button(
                        onClick = {
                            if (keySecretInput.isNotBlank()) {
                                viewModel.addApiKey(keyLabelInput, keySecretInput)
                                keyLabelInput = ""
                                keySecretInput = ""
                            }
                        },
                        enabled = keySecretInput.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Key to Android Keystore")
                    }
                }
            }
        }

        // 3. GitHub Integration & Two-Way Repository Sync
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "GitHub Integration & Repository Sync",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                state.githubStatusMessage?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.accentText,
                        fontWeight = FontWeight.Medium,
                    )
                }

                state.githubDeviceCode?.let { dc ->
                    ForgeElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "One-Time Device Code: ${dc.userCode}",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(dc.userCode))
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)))
                                        }
                                    },
                                ) {
                                    Text("Copy Code & Open GitHub")
                                }
                                OutlinedButton(
                                    onClick = { clipboard.setText(AnnotatedString(dc.userCode)) },
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy Code")
                                }
                            }
                        }
                    }
                }

                if (state.githubLogin.isBlank()) {
                    Button(
                        onClick = { viewModel.startGitHubDeviceSignIn() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sign In with GitHub (OAuth Device Flow)")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = githubTokenInput,
                            onValueChange = { githubTokenInput = it },
                            label = { Text("Or Paste GitHub Personal Access Token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                if (githubTokenInput.isNotBlank()) {
                                    viewModel.connectGitHubWithToken(githubTokenInput)
                                    githubTokenInput = ""
                                }
                            },
                        ) {
                            Text("Connect")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Connected as @${state.githubLogin}",
                            fontWeight = FontWeight.Bold,
                            color = tokens.successText,
                        )
                        OutlinedButton(onClick = viewModel::disconnectGitHub) {
                            Text("Disconnect")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = state.autoSyncGitHub,
                            onCheckedChange = viewModel::setAutoSyncGitHub,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Auto-commit & push workspace after every Agent turn")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.syncProjectWithGitHub() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Push Workspace")
                        }
                        OutlinedButton(
                            onClick = { viewModel.pullProjectFromGitHub() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Pull Latest")
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Create & Push New GitHub Repository",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = newRepoName,
                        onValueChange = { newRepoName = it },
                        label = { Text("Repository Name") },
                        placeholder = { Text(state.activeProject?.slug ?: "my-forge-repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newRepoDesc,
                        onValueChange = { newRepoDesc = it },
                        label = { Text("Description (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = newRepoPrivate,
                            onCheckedChange = { newRepoPrivate = it },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (newRepoPrivate) "Private Repository" else "Public Repository")
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                val targetName = newRepoName.ifBlank { state.activeProject?.slug.orEmpty() }
                                if (targetName.isNotBlank()) {
                                    viewModel.createGitHubRepository(
                                        name = targetName,
                                        description = newRepoDesc,
                                        isPrivate = newRepoPrivate,
                                        pushActiveProject = true,
                                    )
                                    newRepoName = ""
                                    newRepoDesc = ""
                                }
                            },
                        ) {
                            Text("Create & Push")
                        }
                    }

                    if (state.githubRepos.isNotEmpty()) {
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = "Your Repositories (tap Clone to import into Workspace):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.githubRepos.take(10).forEach { repo ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(repo.fullName, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                    if (repo.description.isNotBlank()) {
                                        Text(
                                            text = repo.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                OutlinedButton(onClick = { viewModel.cloneGitHubRepository(repo) }) {
                                    Text("Clone")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Ubuntu 24.04 Root Chroot & Development Toolchains
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            val snap = state.setupSnapshot
            val isProvisioning = snap.status == RuntimeSetupStatus.RUNNING
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Ubuntu 24.04 Root Chroot & Dev Stacks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                DevStack.entries.forEach { stack ->
                    val selected = stack in state.selectedDevStacks
                    val installed = stack in state.installedDevStacks
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = selected,
                            enabled = !isProvisioning,
                            onCheckedChange = { viewModel.toggleDevStack(stack) },
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (installed) "${stack.displayLabel()} ✓" else stack.displayLabel(),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stack.displayDescription(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (snap.status != RuntimeSetupStatus.IDLE) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isProvisioning) {
                            LinearProgressIndicator(
                                progress = snap.progress.coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            text = "${snap.status.name} (${(snap.progress * 100).toInt()}%) — ${snap.errorMessage ?: snap.message}",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (snap.status) {
                                RuntimeSetupStatus.ERROR -> tokens.dangerText
                                RuntimeSetupStatus.COMPLETE -> tokens.successText
                                else -> tokens.accentText
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (snap.logs.isNotEmpty() && (isProvisioning || snap.status == RuntimeSetupStatus.ERROR)) {
                            val logShape = RoundedCornerShape(10.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(logShape)
                                    .background(tokens.terminalBackground)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, logShape)
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                snap.logs.takeLast(6).forEach { line ->
                                    Text(
                                        text = line,
                                        color = tokens.terminalText,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isProvisioning) {
                        OutlinedButton(
                            onClick = viewModel::pauseRuntimeSetup,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Pause Provisioning")
                        }
                    } else {
                        Button(
                            onClick = viewModel::startRuntimeSetup,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Apply / Provision Stacks")
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::repairRootfs,
                        enabled = !isProvisioning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Repair Chroot")
                    }
                }
            }
        }

        // 5. Security, Language & Appearance (4-Mode Multi-Theme Engine)
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Security, Language & Appearance",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = state.interactiveHighRiskApprovals,
                        onCheckedChange = viewModel::setInteractiveHighRiskApprovals,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Interactive High-Risk Command Approvals", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Prompt before executing destructive shell or system commands",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Interface Language",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = state.languageCode.equals(lang.code, ignoreCase = true),
                            onClick = { viewModel.setLanguageCode(lang.code) },
                            label = { Text("${lang.nativeName} (${lang.displayName})") },
                        )
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                val activeThemeMode = ForgeThemeMode.fromStored(state.themeMode)
                Text(
                    text = "Theme Mode (${activeThemeMode.title})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ForgeThemeMode.entries.chunked(2).forEach { rowModes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowModes.forEach { mode ->
                                val selected = mode == activeThemeMode
                                val (canvasSwatch, accentSwatch) = when (mode) {
                                    ForgeThemeMode.SYSTEM -> Color(0xFF1E293B) to Color(0xFF38BDF8)
                                    ForgeThemeMode.DARK -> Color(0xFF090C10) to Color(0xFF3B82F6)
                                    ForgeThemeMode.GLASS -> Color(0xFF060B16) to Color(0xFF38BDF8)
                                    ForgeThemeMode.WHITE -> Color(0xFFFFFFFF) to Color(0xFFFF385C)
                                }
                                ForgeElevatedCard(
                                    modifier = Modifier.weight(1f),
                                    selected = selected,
                                    onClick = { viewModel.setThemeMode(mode.id) },
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(11.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(14.dp)
                                                        .clip(CircleShape)
                                                        .background(canvasSwatch)
                                                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .size(14.dp)
                                                        .clip(CircleShape)
                                                        .background(accentSwatch),
                                                )
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                } else {
                                                    tokens.cardContainerColor
                                                },
                                            ) {
                                                Text(
                                                    text = mode.badge,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    } else {
                                                        tokens.accentText
                                                    },
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = mode.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (selected) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = tokens.successText,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = mode.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Application Self-Updater
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "NextCraft Forge Updates",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                state.updateStatusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.accentText,
                        fontWeight = FontWeight.Medium,
                    )
                }
                state.updateInfo?.let { info ->
                    ForgeElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Release v${info.versionName} (code ${info.versionCode})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (info.notes.isNotBlank()) {
                                Text(
                                    text = info.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { viewModel.checkForAppUpdates() }) {
                        Text("Check for App Updates")
                    }
                    if (state.updateInfo != null) {
                        Button(onClick = { viewModel.downloadAndInstallAppUpdate() }) {
                            Text("Download & Install v${state.updateInfo.versionName}")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
