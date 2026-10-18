@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.providersForAgent
import com.nextcraft.forge.runtime.AntigravityAuthStatus
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role

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
        ForgeLargeTitle(title = stringResource(R.string.settings_settings), subtitle = stringResource(R.string.settings_preferences_configuration), horizontalPadding = 6.dp)

        ForgeSectionLabel(stringResource(R.string.settings_general), horizontalPadding = 6.dp)
        // 5. Security, Language & Appearance (4-Mode Multi-Theme Engine)
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_security_appearance),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                ForgeToggleRow(
                    title = stringResource(R.string.settings_high_risk_command_approvals),
                    subtitle = stringResource(R.string.settings_prompt_before_executing_destructive),
                    checked = state.interactiveHighRiskApprovals,
                    onCheckedChange = viewModel::setInteractiveHighRiskApprovals,
                )
                ForgeToggleRow(
                    title = stringResource(R.string.settings_auto_approve_all_tools),
                    subtitle = stringResource(R.string.settings_auto_approve_all_tools_subtitle),
                    checked = state.autoApproveAllTools,
                    onCheckedChange = viewModel::setAutoApproveAllTools,
                )

                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.settings_interface_language),
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
                    text = stringResource(R.string.settings_theme_mode, activeThemeMode.title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ForgeThemeMode.entries.forEach { mode ->
                        val selected = mode == activeThemeMode
                        ForgeElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            selected = selected,
                            onClick = { viewModel.setThemeMode(mode.id) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = mode.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
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
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = mode.subtitle,
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
                                        contentDescription = stringResource(R.string.a11y_selected),
                                        tint = tokens.successText,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        ForgeSectionLabel(stringResource(R.string.settings_ai), horizontalPadding = 6.dp)
        // AI: coding agent engine
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_coding_agent_engine),
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
                                    contentDescription = stringResource(R.string.a11y_selected),
                                    tint = tokens.successText,
                                )
                            }
                        }
                    }
                }

                if (state.agentKind == AgentKind.ANTIGRAVITY) {
                    var agyVerificationCode by rememberSaveable { mutableStateOf("") }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = stringResource(R.string.settings_antigravity_cli_configuration),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = agyModelInput,
                        onValueChange = { agyModelInput = it },
                        label = { Text(stringResource(R.string.settings_antigravity_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                                label = { Text(stringResource(R.string.settings_effort, effort.uppercase())) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.setAntigravityConfig(agyModelInput, agyEffortInput) },
                        ) {
                            Text(stringResource(R.string.settings_save_antigravity_model))
                        }
                        val hasAgyCredential by produceState(initialValue = false, agyAuth.status) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                viewModel.antigravityAuth.hasOfficialCredential()
                            }
                        }
                        if (agyAuth.status == AntigravityAuthStatus.SIGNED_IN || hasAgyCredential) {
                            OutlinedButton(
                                onClick = { scope.launch { runCatching { viewModel.antigravityAuth.logout() } } },
                            ) {
                                Text(stringResource(R.string.settings_sign_out_google_oauth))
                            }
                        } else {
                            OutlinedButton(
                                onClick = { scope.launch { viewModel.antigravityAuth.beginLogin() } },
                                enabled = agyAuth.status != AntigravityAuthStatus.STARTING && agyAuth.status != AntigravityAuthStatus.COMPLETING,
                            ) {
                                Text(stringResource(R.string.settings_sign_in_with_google))
                            }
                        }
                        OutlinedButton(
                            onClick = viewModel::validateAntigravityConnection,
                            enabled = !state.apiBusy,
                        ) {
                            Text(stringResource(R.string.settings_validate))
                        }
                    }
                    if (agyAuth.status == AntigravityAuthStatus.STARTING ||
                        agyAuth.status == AntigravityAuthStatus.COMPLETING ||
                        state.apiBusy
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    agyAuth.message?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.apiStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.accentText,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    agyAuth.authorizationUrl?.let { url ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.agent_open_google_sign_in))
                            }
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(url)) },
                            ) {
                                Text(stringResource(R.string.agent_copy_link))
                            }
                            OutlinedButton(
                                onClick = { viewModel.antigravityAuth.cancel() },
                            ) {
                                Text(stringResource(R.string.agent_cancel))
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = agyVerificationCode,
                                onValueChange = { agyVerificationCode = it },
                                label = { Text(stringResource(R.string.agent_paste_google_verification_code)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    if (agyVerificationCode.isNotBlank()) {
                                        runCatching { viewModel.antigravityAuth.submitCode(agyVerificationCode.trim()) }
                                        agyVerificationCode = ""
                                    }
                                },
                                enabled = agyVerificationCode.isNotBlank(),
                            ) {
                                Text(stringResource(R.string.agent_verify))
                            }
                        }
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
                        text = stringResource(R.string.settings_model_provider_key_vault),
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
                            label = { Text(stringResource(R.string.settings_api_base_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text(stringResource(R.string.settings_model_identifier)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (state.providerProfile.kind == ProviderKind.LLM_ROUTER) {
                        OutlinedTextField(
                            value = openRouterOrderInput,
                            onValueChange = { openRouterOrderInput = it },
                            label = { Text(stringResource(R.string.settings_openrouter_provider_order_comma)) },
                            placeholder = { Text("anthropic,google-vertex,deepseek") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                            Text(stringResource(R.string.settings_save_config))
                        }
                        OutlinedButton(
                            onClick = viewModel::discoverProviderModels,
                            enabled = !state.apiBusy,
                        ) {
                            Text(stringResource(R.string.settings_discover_models))
                        }
                        OutlinedButton(
                            onClick = viewModel::validateProviderConnection,
                            enabled = !state.apiBusy,
                        ) {
                            Text(stringResource(R.string.settings_validate))
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
                            text = stringResource(R.string.settings_discovered_models_tap_to),
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
                        text = stringResource(R.string.settings_api_keys_for_auto, state.providerProfile.kind.displayTitle()),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.savedApiKeys.forEach { keyInfo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) { viewModel.selectActiveApiKey(keyInfo.id) }
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
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_delete_key), modifier = Modifier.size(18.dp))
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
                            label = { Text(stringResource(R.string.settings_label)) },
                            placeholder = { Text("Primary") },
                            singleLine = true,
                            modifier = Modifier.weight(0.35f),
                        )
                        OutlinedTextField(
                            value = keySecretInput,
                            onValueChange = { keySecretInput = it },
                            label = { Text(stringResource(R.string.settings_api_key_token)) },
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
                        Text(stringResource(R.string.settings_add_key_to_android))
                    }
                }
            }
        }

        ForgeSectionLabel(stringResource(R.string.settings_github), horizontalPadding = 6.dp)
        // 3. GitHub Integration & Two-Way Repository Sync
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_repository_sync),
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
                                text = stringResource(R.string.settings_one_time_device_code, dc.userCode),
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(dc.userCode))
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)))
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.settings_copy_code_open_github))
                                }
                                OutlinedButton(
                                    onClick = { clipboard.setText(AnnotatedString(dc.userCode)) },
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.settings_copy_code))
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
                        Text(stringResource(R.string.settings_sign_in_with_github))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = githubTokenInput,
                            onValueChange = { githubTokenInput = it },
                            label = { Text(stringResource(R.string.settings_or_paste_github_personal)) },
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
                            Text(stringResource(R.string.settings_connect))
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_connected_as, state.githubLogin),
                            fontWeight = FontWeight.Bold,
                            color = tokens.successText,
                        )
                        OutlinedButton(onClick = viewModel::disconnectGitHub) {
                            Text(stringResource(R.string.settings_disconnect))
                        }
                    }

                    ForgeToggleRow(
                        title = stringResource(R.string.settings_auto_commit_push),
                        subtitle = stringResource(R.string.settings_commit_and_push_the),
                        checked = state.autoSyncGitHub,
                        onCheckedChange = viewModel::setAutoSyncGitHub,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.syncProjectWithGitHub() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.settings_push_workspace))
                        }
                        OutlinedButton(
                            onClick = { viewModel.pullProjectFromGitHub() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.settings_pull_latest))
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = stringResource(R.string.settings_create_push_new_github),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = newRepoName,
                        onValueChange = { newRepoName = it },
                        label = { Text(stringResource(R.string.settings_repository_name)) },
                        placeholder = { Text(state.activeProject?.slug ?: "my-forge-repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newRepoDesc,
                        onValueChange = { newRepoDesc = it },
                        label = { Text(stringResource(R.string.settings_description_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ForgeToggleRow(
                        title = stringResource(if (newRepoPrivate) R.string.settings_private_repository else R.string.settings_public_repository),
                        subtitle = stringResource(R.string.settings_visibility_of_the_new),
                        checked = newRepoPrivate,
                        onCheckedChange = { newRepoPrivate = it },
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            modifier = Modifier.weight(1f),
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
                            Text(stringResource(R.string.settings_create_push))
                        }
                    }

                    if (state.githubRepos.isNotEmpty()) {
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = stringResource(R.string.settings_your_repositories_tap_clone),
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
                                    Text(stringResource(R.string.settings_clone))
                                }
                            }
                        }
                    }
                }
            }
        }

        ForgeSectionLabel(stringResource(R.string.settings_runtime), horizontalPadding = 6.dp)
        // 4. Ubuntu 24.04 Root Chroot & Development Toolchains
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            val isProvisioning = state.setupSnapshot.status == com.nextcraft.forge.runtime.RuntimeSetupStatus.RUNNING
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_dev_stacks),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                DevStack.entries.forEach { stack ->
                    val selected = stack in state.selectedDevStacks
                    val installed = stack in state.installedDevStacks
                    ForgeToggleRow(
                        title = if (installed) "${stack.displayLabel()} ✓" else stack.displayLabel(),
                        subtitle = stack.displayDescription(),
                        checked = selected,
                        onCheckedChange = { if (!isProvisioning) viewModel.toggleDevStack(stack) },
                    )
                }
                if (state.setupSnapshot.status != com.nextcraft.forge.runtime.RuntimeSetupStatus.IDLE) {
                    Text(
                        text = state.setupSnapshot.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.accentText,
                        fontWeight = FontWeight.Medium,
                    )
                    state.setupSnapshot.errorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.dangerText,
                        )
                    }
                    LinearProgressIndicator(
                        progress = state.setupSnapshot.progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = viewModel::startRuntimeSetup,
                        enabled = !isProvisioning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_apply_provision_stacks))
                    }
                    if (isProvisioning) {
                        OutlinedButton(onClick = viewModel::pauseRuntimeSetup) {
                            Text(stringResource(R.string.launch_pause))
                        }
                    } else {
                        OutlinedButton(
                            onClick = viewModel::repairRootfs,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.settings_repair_chroot))
                        }
                    }
                }
            }
        }

        ForgeSectionLabel(stringResource(R.string.settings_about), horizontalPadding = 6.dp)
        // 6. Application Self-Updater
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_app_updates),
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.checkForAppUpdates() }) {
                        Text(stringResource(R.string.settings_check_for_app_updates))
                    }
                    state.updateInfo?.let { info ->
                        OutlinedButton(onClick = viewModel::downloadAndInstallAppUpdate) {
                            Text(stringResource(R.string.settings_download_install_update, info.versionName))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
