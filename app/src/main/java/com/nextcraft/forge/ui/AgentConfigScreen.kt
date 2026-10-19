@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.providersForAgent
import com.nextcraft.forge.runtime.RuntimeSetupStatus

/**
 * The Agent tab: which coding agent is active and how it reaches its model. Agent pill tabs, an
 * install card when the agent is missing, then Access mode / Endpoint / Model & access (or the
 * Google account card for Antigravity) and Runtime updates. Conversations live under Projects.
 */
@Composable
fun AgentConfigScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val agyAuth by viewModel.antigravityAuth.state.collectAsState()
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme

    var baseUrlInput by rememberSaveable(state.providerProfile.kind) { mutableStateOf(state.providerProfile.baseUrl) }
    var modelInput by rememberSaveable(state.providerProfile.kind, state.providerProfile.model) { mutableStateOf(state.providerProfile.model) }
    var openRouterOrderInput by rememberSaveable(state.providerProfile.kind) { mutableStateOf(state.providerProfile.openRouterProviderOrder) }
    var keyLabelInput by rememberSaveable { mutableStateOf("") }
    // Secrets use plain remember: rememberSaveable would write them into the saved-instance Bundle.
    var keySecretInput by remember { mutableStateOf("") }
    var agyModelInput by rememberSaveable(state.antigravityModel) { mutableStateOf(state.antigravityModel) }
    var agyEffortInput by rememberSaveable(state.antigravityEffort) { mutableStateOf(state.antigravityEffort) }

    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    val isProvisioning = state.setupSnapshot.status == RuntimeSetupStatus.RUNNING
    val runtimeBusy = !state.runtimeInstalled || isProvisioning

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ForgeLargeTitle(
            title = stringResource(R.string.agent_config_title),
            subtitle = listOf(state.agentKind.displayTitle(), state.providerProfile.model.ifBlank { "default" }).joinToString(" · "),
            horizontalPadding = 6.dp,
        )

        val agents = AgentKind.entries
        ForgePillTabs(
            items = agents.map { it.displayTitle() },
            selectedIndex = agents.indexOf(state.agentKind).coerceAtLeast(0),
            onSelect = { viewModel.selectAgent(agents[it]) },
        )
        val agent = state.agentKind
        val agentInstalled = agent in state.installedAgents

        if (!agentInstalled) {
            // Not installed: one card, one call to action (the reference's "X is not installed" state).
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ForgeStatusIcon(ForgeStatus.TODO, iconSize = 18.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_agent_not_installed, agent.displayTitle()),
                            fontSize = ForgeType.cardTitle,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_agent_install_hint, agent.downloadNote),
                        fontSize = 14.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    ForgePrimaryButton(
                        text = stringResource(R.string.settings_agent_install_named, agent.displayTitle()),
                        onClick = { viewModel.installAgent(agent) },
                        enabled = !isProvisioning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SetupProgress(state)
                }
            }
        } else if (agent == AgentKind.ANTIGRAVITY) {
            AntigravityAccountCard(
                state = state,
                viewModel = viewModel,
                agyAuth = agyAuth,
                agyModelInput = agyModelInput,
                onAgyModelChange = { agyModelInput = it },
                agyEffortInput = agyEffortInput,
                onAgyEffortChange = { agyEffortInput = it },
            )
        } else {
            val availableProviders = providersForAgent(agent)
            val provider = state.providerProfile.kind

            // Access mode: radio list of the providers this agent can talk to.
            var accessOpen by rememberSaveable(agent) { mutableStateOf(false) }
            DisclosureCard(
                icon = Icons.Default.Share,
                title = stringResource(R.string.settings_access_mode),
                subtitle = provider.displayTitle(),
                expanded = accessOpen,
                onToggle = { accessOpen = !accessOpen },
            ) {
                availableProviders.forEach { kind ->
                    RadioRow(
                        title = kind.displayTitle(),
                        subtitle = kind.displaySubtitle(),
                        selected = kind == provider,
                        onClick = { viewModel.updateProviderKind(kind) },
                    )
                }
            }

            // Claude subscription: sign in through Claude Code's own login (account or SSO) instead of pasting a token.
            if (agent == AgentKind.CLAUDE_CODE && provider == ProviderKind.CLAUDE) {
                ClaudeAccountCard(viewModel)
            }

            // Endpoint & protocol: base URL, model id, OpenRouter order.
            var endpointOpen by rememberSaveable(agent) { mutableStateOf(false) }
            DisclosureCard(
                icon = Icons.Default.Info,
                title = stringResource(R.string.settings_endpoint_protocol),
                subtitle = if (provider.fixedBaseUrl || provider == ProviderKind.CLAUDE) state.providerProfile.model.ifBlank { "default" } else state.providerProfile.baseUrl,
                expanded = endpointOpen,
                onToggle = { endpointOpen = !endpointOpen },
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!provider.fixedBaseUrl && provider != ProviderKind.CLAUDE) {
                        OutlinedTextField(
                            shape = RoundedCornerShape(14.dp),
                            value = baseUrlInput,
                            onValueChange = { baseUrlInput = it },
                            label = { Text(stringResource(R.string.settings_api_base_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        shape = RoundedCornerShape(14.dp),
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text(stringResource(R.string.settings_model_identifier)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (provider == ProviderKind.LLM_ROUTER) {
                        OutlinedTextField(
                            shape = RoundedCornerShape(14.dp),
                            value = openRouterOrderInput,
                            onValueChange = { openRouterOrderInput = it },
                            label = { Text(stringResource(R.string.settings_openrouter_provider_order_comma)) },
                            placeholder = { Text("anthropic,google-vertex,deepseek") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ForgePrimaryButton(
                        text = stringResource(R.string.settings_save_config),
                        onClick = { viewModel.updateProviderConfig(baseUrl = baseUrlInput, model = modelInput, openRouterOrder = openRouterOrderInput) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Model & access: discover / validate, discovered models as a radio list, keys.
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.settings_model_access),
                            fontSize = ForgeType.cardTitle,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::discoverProviderModels, enabled = !state.apiBusy) {
                            Text(stringResource(R.string.settings_discover_models))
                        }
                    }
                    if (state.apiBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    state.apiStatusMessage?.asString()?.let { msg ->
                        Text(text = msg, fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    }
                    // Model choice for every provider: discovered models when available, otherwise sensible presets.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_current_model), fontSize = 13.sp, color = scheme.onSurfaceVariant)
                            Text(
                                text = state.providerProfile.model.ifBlank { "default" },
                                fontSize = ForgeType.cardTitle,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        OutlinedButton(shape = RoundedCornerShape(100), onClick = { showModelPicker = true }) {
                            Text(stringResource(R.string.settings_choose_model))
                        }
                    }
                    if (showModelPicker) {
                        ModelPickerDialog(
                            title = provider.displayTitle(),
                            options = providerModelOptions(provider, state.discoveredModels, state.providerProfile.model),
                            selectedId = state.providerProfile.model,
                            loading = state.apiBusy,
                            onRefresh = if (provider == ProviderKind.CLAUDE) null else viewModel::discoverProviderModels,
                            onSelect = { id ->
                                modelInput = id
                                viewModel.updateProviderConfig(baseUrl = baseUrlInput, model = id, openRouterOrder = openRouterOrderInput)
                                showModelPicker = false
                            },
                            onDismiss = { showModelPicker = false },
                        )
                    }
                    if (state.discoveredModels.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_models_found, state.discoveredModels.size),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurfaceVariant,
                        )
                        state.discoveredModels.take(30).forEach { m ->
                            RadioRow(
                                title = m.displayName,
                                subtitle = m.id,
                                mono = true,
                                selected = m.id == state.providerProfile.model,
                                onClick = {
                                    modelInput = m.id
                                    viewModel.updateProviderConfig(baseUrl = baseUrlInput, model = m.id, openRouterOrder = openRouterOrderInput)
                                },
                                horizontalPadding = 0.dp,
                            )
                        }
                    }
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = viewModel::validateProviderConnection,
                        enabled = !state.apiBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.settings_validate)) }

                    ThinDivider()
                    Text(
                        text = stringResource(R.string.settings_api_keys_for_auto, provider.displayTitle()),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.savedApiKeys.forEach { keyInfo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) { viewModel.selectActiveApiKey(keyInfo.id) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (keyInfo.isActive) {
                                ForgeStatusIcon(ForgeStatus.DONE, iconSize = 18.dp)
                            } else {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (keyInfo.isActive) stringResource(R.string.settings_key_active, keyInfo.name) else keyInfo.name,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (keyInfo.isActive) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            IconButton(onClick = { viewModel.removeApiKey(keyInfo.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_delete_key), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    OutlinedTextField(
                        shape = RoundedCornerShape(14.dp),
                        value = keyLabelInput,
                        onValueChange = { keyLabelInput = it },
                        label = { Text(stringResource(R.string.settings_label)) },
                        placeholder = { Text("Primary") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        shape = RoundedCornerShape(14.dp),
                        value = keySecretInput,
                        onValueChange = { keySecretInput = it },
                        label = { Text(stringResource(R.string.settings_api_key_token)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ForgePrimaryButton(
                        text = stringResource(R.string.settings_add_key_to_android),
                        onClick = {
                            if (keySecretInput.isNotBlank()) {
                                viewModel.addApiKey(keyLabelInput, keySecretInput)
                                keyLabelInput = ""
                                keySecretInput = ""
                            }
                        },
                        enabled = keySecretInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Runtime updates: installed agents, update / remove, check.
        if (state.installedAgents.isNotEmpty()) {
            var updatesOpen by rememberSaveable { mutableStateOf(false) }
            DisclosureCard(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.settings_runtime_updates),
                subtitle = stringResource(R.string.settings_installed_agents_current),
                expanded = updatesOpen,
                onToggle = { updatesOpen = !updatesOpen },
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.installedAgents.forEach { kind ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
                            ForgeStatusIcon(ForgeStatus.DONE, iconSize = 16.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(kind.displayTitle(), fontSize = 15.sp, modifier = Modifier.weight(1f))
                            state.agentUpdates[kind]?.let { info ->
                                TextButton(onClick = { viewModel.updateAgent(kind) }, enabled = !runtimeBusy) {
                                    Text(stringResource(R.string.settings_agent_update, info.latestVersion))
                                }
                            }
                            TextButton(onClick = { viewModel.removeAgent(kind) }, enabled = !runtimeBusy) {
                                Text(stringResource(R.string.settings_agent_remove), color = tokens.dangerText)
                            }
                        }
                    }
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = viewModel::checkAgentUpdates,
                        enabled = !state.checkingAgentUpdates,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.settings_agent_check_updates)) }
                    SetupProgress(state)
                }
            }
        }

    }
}
