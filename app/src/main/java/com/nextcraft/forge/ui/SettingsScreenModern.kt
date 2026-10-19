@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.BuildConfig
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.providersForAgent
import com.nextcraft.forge.runtime.AntigravityAuthStatus
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import kotlinx.coroutines.launch

/**
 * Settings, grouped the way the reference layout does: General (appearance, language, safety),
 * AI agent (agent tabs, access mode, endpoint, model & keys), GitHub, Developer tools, Linux
 * runtime, Danger zone and About. Colours and type stay Linear; only the layout follows the reference.
 */
@Composable
fun SettingsScreenModern(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme

    var githubTokenInput by remember { mutableStateOf("") }
    var newRepoName by rememberSaveable { mutableStateOf("") }
    var newRepoDesc by rememberSaveable { mutableStateOf("") }
    var newRepoPrivate by rememberSaveable { mutableStateOf(true) }

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
            title = stringResource(R.string.settings_settings),
            subtitle = stringResource(R.string.settings_preferences_configuration),
            horizontalPadding = 6.dp,
        )

        // ───────────────────────── General ─────────────────────────
        ForgeSectionLabel(stringResource(R.string.settings_general), horizontalPadding = 6.dp)
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            val activeThemeMode = ForgeThemeMode.fromStored(state.themeMode)
            var appearanceOpen by rememberSaveable { mutableStateOf(false) }
            SettingsRow(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.settings_appearance),
                subtitle = stringResource(activeThemeMode.title),
                onClick = { appearanceOpen = !appearanceOpen },
                trailing = { Chevron(appearanceOpen) },
            )
            AnimatedVisibility(visible = appearanceOpen) {
                // 2 × 2 tiles: icon-less by design, the badge carries the name; selection by accent tint + ring.
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ForgeThemeMode.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { mode ->
                                val selected = mode == activeThemeMode
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) scheme.primary.copy(alpha = 0.12f) else tokens.cardElevatedColor,
                                    border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, scheme.primary.copy(alpha = 0.6f)) else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 76.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(role = Role.RadioButton) { viewModel.setThemeMode(mode.id) },
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = stringResource(mode.title),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (selected) scheme.primary else scheme.onSurface,
                                        )
                                        Text(
                                            text = stringResource(mode.subtitle),
                                            fontSize = 12.sp,
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            ThinDivider()

            val languages = AppLanguage.entries
            val activeLanguage = languages.firstOrNull { it.code.equals(state.languageCode, ignoreCase = true) } ?: languages.first()
            var languageOpen by rememberSaveable { mutableStateOf(false) }
            SettingsRow(
                icon = Icons.Default.List,
                title = stringResource(R.string.settings_language),
                subtitle = activeLanguage.nativeName,
                onClick = { languageOpen = !languageOpen },
                trailing = { Chevron(languageOpen) },
            )
            AnimatedVisibility(visible = languageOpen) {
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    languages.forEach { lang ->
                        RadioRow(
                            title = lang.nativeName,
                            subtitle = lang.displayName,
                            selected = lang == activeLanguage,
                            onClick = { viewModel.setLanguageCode(lang.code) },
                        )
                    }
                }
            }
            ThinDivider()

            ForgeToggleRow(
                title = stringResource(R.string.settings_high_risk_command_approvals),
                subtitle = stringResource(R.string.settings_prompt_before_executing_destructive),
                checked = state.interactiveHighRiskApprovals,
                onCheckedChange = viewModel::setInteractiveHighRiskApprovals,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            ForgeToggleRow(
                title = stringResource(R.string.settings_auto_approve_all_tools),
                subtitle = stringResource(R.string.settings_auto_approve_all_tools_subtitle),
                checked = state.autoApproveAllTools,
                onCheckedChange = viewModel::setAutoApproveAllTools,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        // ───────────────────────── GitHub ─────────────────────────
        ForgeSectionLabel(stringResource(R.string.settings_github), horizontalPadding = 6.dp)
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_repository_sync), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                state.githubStatusMessage?.asString()?.let { status ->
                    Text(text = status, fontSize = 13.sp, color = scheme.onSurfaceVariant)
                }
                state.githubDeviceCode?.let { dc ->
                    Surface(shape = RoundedCornerShape(14.dp), color = tokens.cardElevatedColor, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.settings_one_time_device_code, dc.userCode),
                                fontSize = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            ForgePrimaryButton(
                                text = stringResource(R.string.settings_copy_code_open_github),
                                onClick = {
                                    clipboard.setText(AnnotatedString(dc.userCode))
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri))) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(onClick = { clipboard.setText(AnnotatedString(dc.userCode)) }) {
                                Text(stringResource(R.string.settings_copy_code))
                            }
                        }
                    }
                }
                if (state.githubLogin.isBlank()) {
                    ForgePrimaryButton(
                        text = stringResource(R.string.settings_sign_in_with_github),
                        onClick = { viewModel.startGitHubDeviceSignIn() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        shape = RoundedCornerShape(14.dp),
                        value = githubTokenInput,
                        onValueChange = { githubTokenInput = it },
                        label = { Text(stringResource(R.string.settings_or_paste_github_personal)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = {
                            if (githubTokenInput.isNotBlank()) {
                                viewModel.connectGitHubWithToken(githubTokenInput)
                                githubTokenInput = ""
                            }
                        },
                        enabled = githubTokenInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.settings_connect)) }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ForgeStatusIcon(ForgeStatus.DONE, iconSize = 18.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.settings_connected_as, state.githubLogin),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::disconnectGitHub) { Text(stringResource(R.string.settings_disconnect)) }
                    }
                    ForgeToggleRow(
                        title = stringResource(R.string.settings_auto_commit_push),
                        subtitle = stringResource(R.string.settings_commit_and_push_the),
                        checked = state.autoSyncGitHub,
                        onCheckedChange = viewModel::setAutoSyncGitHub,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForgePrimaryButton(
                            text = stringResource(R.string.settings_push_workspace),
                            onClick = { viewModel.syncProjectWithGitHub() },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            shape = RoundedCornerShape(100),
                            onClick = { viewModel.pullProjectFromGitHub() },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text(stringResource(R.string.settings_pull_latest)) }
                    }

                    var createOpen by rememberSaveable { mutableStateOf(false) }
                    ThinDivider()
                    SettingsRow(
                        icon = Icons.Default.Add,
                        title = stringResource(R.string.settings_create_push_new_github),
                        onClick = { createOpen = !createOpen },
                        trailing = { Chevron(createOpen) },
                        horizontalPadding = 0.dp,
                    )
                    AnimatedVisibility(visible = createOpen) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                shape = RoundedCornerShape(14.dp),
                                value = newRepoName,
                                onValueChange = { newRepoName = it },
                                label = { Text(stringResource(R.string.settings_repository_name)) },
                                placeholder = { Text(state.activeProject?.slug ?: "my-forge-repo") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                shape = RoundedCornerShape(14.dp),
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
                            ForgePrimaryButton(
                                text = stringResource(R.string.settings_create_push),
                                onClick = {
                                    val targetName = newRepoName.ifBlank { state.activeProject?.slug.orEmpty() }
                                    if (targetName.isNotBlank()) {
                                        viewModel.createGitHubRepository(name = targetName, description = newRepoDesc, isPrivate = newRepoPrivate, pushActiveProject = true)
                                        newRepoName = ""
                                        newRepoDesc = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (state.githubRepos.isNotEmpty()) {
                        ThinDivider()
                        Text(
                            text = stringResource(R.string.settings_your_repositories_tap_clone),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurfaceVariant,
                        )
                        state.githubRepos.take(10).forEach { repo ->
                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(repo.fullName, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    if (repo.description.isNotBlank()) {
                                        Text(repo.description, fontSize = 12.5.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                TextButton(onClick = { viewModel.cloneGitHubRepository(repo) }) { Text(stringResource(R.string.settings_clone)) }
                            }
                        }
                    }
                }
            }
        }

        // ───────────────────────── Developer tools ─────────────────────────
        ForgeSectionLabel(stringResource(R.string.settings_runtime), horizontalPadding = 6.dp)
        var toolsOpen by rememberSaveable { mutableStateOf(true) }
        val optionalInstalled = state.installedDevStacks.count { it != DevStack.WEB }
        DisclosureCard(
            icon = Icons.Default.Build,
            title = stringResource(R.string.settings_developer_tools),
            subtitle = stringResource(R.string.settings_core_plus_optional, optionalInstalled),
            expanded = toolsOpen,
            onToggle = { toolsOpen = !toolsOpen },
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DevStack.entries.forEach { stack ->
                    val installed = stack in state.installedDevStacks
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stack.displayLabel(), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(stack.displayDescription(), fontSize = 13.sp, color = scheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        when {
                            stack == DevStack.WEB -> Text(stringResource(R.string.settings_included), fontSize = 14.sp, color = scheme.onSurfaceVariant)
                            installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                                ForgeStatusIcon(ForgeStatus.DONE, iconSize = 16.dp)
                                TextButton(onClick = { viewModel.removeStack(stack) }, enabled = !runtimeBusy) {
                                    Text(stringResource(R.string.settings_remove), color = tokens.dangerText)
                                }
                            }
                            else -> OutlinedButton(
                                shape = RoundedCornerShape(100),
                                onClick = { viewModel.installStack(stack) },
                                enabled = !runtimeBusy,
                                modifier = Modifier.heightIn(min = 40.dp),
                            ) { Text(stringResource(R.string.settings_add)) }
                        }
                    }
                }
                SetupProgress(state)
                if (isProvisioning) {
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = viewModel::pauseRuntimeSetup,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.launch_pause)) }
                }
            }
        }

        // ───────────────────────── Linux runtime ─────────────────────────
        var runtimeOpen by rememberSaveable { mutableStateOf(false) }
        DisclosureCard(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.settings_linux_runtime),
            subtitle = stringResource(R.string.onb_subtitle),
            expanded = runtimeOpen,
            onToggle = { runtimeOpen = !runtimeOpen },
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyValueRow(stringResource(R.string.settings_architecture), if (state.arm64Supported) "ARM64 (aarch64)" else "—")
                KeyValueRow(stringResource(R.string.settings_environment), "Ubuntu 24.04 · Root chroot")
                KeyValueRow(stringResource(R.string.settings_active_agent), state.agentKind.displayTitle())
                if (state.installedAgents.isNotEmpty()) {
                    ThinDivider()
                    Text(stringResource(R.string.settings_installed_agents), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
                    state.installedAgents.forEach { kind ->
                        KeyValueRow(kind.displayTitle(), state.agentUpdates[kind]?.latestVersion?.let { stringResource(R.string.settings_agent_update, it) } ?: stringResource(R.string.settings_agent_installed))
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = viewModel::clearTerminal,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_clear_terminal_history))
                }
            }
        }

        // ───────────────────────── Danger zone ─────────────────────────
        ForgeSectionLabel(stringResource(R.string.settings_danger_zone), horizontalPadding = 6.dp)
        ForgeThemedCard(
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.dangerText.copy(alpha = 0.45f)),
        ) {
            SettingsRow(
                icon = Icons.Default.Warning,
                iconTint = tokens.dangerText,
                title = stringResource(R.string.settings_repair_rootfs),
                subtitle = stringResource(R.string.settings_repair_rootfs_sub),
                onClick = { if (!isProvisioning) viewModel.repairRootfs() },
                trailing = { Text("›", color = scheme.onSurfaceVariant) },
            )
        }

        // ───────────────────────── About ─────────────────────────
        ForgeSectionLabel(stringResource(R.string.settings_about), horizontalPadding = 6.dp)
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            SettingsRow(
                icon = Icons.Default.Info,
                title = stringResource(R.string.settings_version),
                trailing = { Text("v${BuildConfig.VERSION_NAME}", color = scheme.onSurfaceVariant, fontSize = 14.sp) },
            )
            ThinDivider()
            SettingsRow(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.settings_check_for_app_updates),
                subtitle = state.updateStatusMessage ?: stringResource(R.string.settings_check_updates_sub),
                onClick = { viewModel.checkForAppUpdates() },
                trailing = { Text("›", color = scheme.onSurfaceVariant) },
            )
            state.updateProgress?.let { fraction ->
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            state.updateInfo?.let { info ->
                Box(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    ForgePrimaryButton(
                        text = stringResource(R.string.settings_download_install_update, info.versionName),
                        onClick = viewModel::downloadAndInstallAppUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.shell_nextcraft_forge), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.projects_tagline), fontSize = 13.sp, color = scheme.onSurfaceVariant)
            }
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }
    }
}

/** Google account card for Antigravity, plus its model and effort. */
@Composable
internal fun AntigravityAccountCard(
    state: ForgeUiState,
    viewModel: MainViewModel,
    agyAuth: com.nextcraft.forge.runtime.AntigravityAuthState,
    agyModelInput: String,
    onAgyModelChange: (String) -> Unit,
    agyEffortInput: String,
    onAgyEffortChange: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var manualCode by rememberSaveable { mutableStateOf(false) }
    var agyVerificationCode by rememberSaveable { mutableStateOf("") }
    val hasAgyCredential by produceState(initialValue = false, agyAuth.status) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { viewModel.antigravityAuth.hasOfficialCredential() }
    }
    val signedIn = agyAuth.status == AntigravityAuthStatus.SIGNED_IN || hasAgyCredential
    val inFlight = agyAuth.status == AntigravityAuthStatus.STARTING || agyAuth.status == AntigravityAuthStatus.COMPLETING

    ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_google_account), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = scheme.primary.copy(alpha = 0.16f), modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            when {
                                signedIn -> R.string.settings_google_signed_in
                                inFlight -> R.string.settings_google_waiting
                                else -> R.string.settings_google_not_signed_in
                            },
                        ),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = agyAuth.message ?: stringResource(R.string.settings_google_required),
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (inFlight || state.apiBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (signedIn) {
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = { scope.launch { runCatching { viewModel.antigravityAuth.logout() } } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) { Text(stringResource(R.string.settings_sign_out_google_oauth)) }
            } else {
                ForgePrimaryButton(
                    text = stringResource(if (inFlight || agyAuth.authorizationUrl != null) R.string.settings_restart_google_sign_in else R.string.settings_sign_in_with_google),
                    onClick = { scope.launch { viewModel.antigravityAuth.beginLogin() } },
                    enabled = !inFlight,
                    modifier = Modifier.fillMaxWidth(),
                )
                agyAuth.authorizationUrl?.let { url ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            shape = RoundedCornerShape(100),
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text(stringResource(R.string.agent_open_google_sign_in)) }
                        OutlinedButton(
                            shape = RoundedCornerShape(100),
                            onClick = { clipboard.setText(AnnotatedString(url)) },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text(stringResource(R.string.agent_copy_link)) }
                    }
                }
                if (!manualCode) {
                    TextButton(onClick = { manualCode = true }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.settings_enter_code_manually))
                    }
                } else {
                    OutlinedTextField(
                        shape = RoundedCornerShape(14.dp),
                        value = agyVerificationCode,
                        onValueChange = { agyVerificationCode = it },
                        label = { Text(stringResource(R.string.settings_one_time_code)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ForgePrimaryButton(
                            text = stringResource(R.string.settings_complete_sign_in),
                            onClick = {
                                if (agyVerificationCode.isNotBlank()) {
                                    runCatching { viewModel.antigravityAuth.submitCode(agyVerificationCode.trim()) }
                                    agyVerificationCode = ""
                                }
                            },
                            enabled = agyVerificationCode.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            manualCode = false
                            agyVerificationCode = ""
                            viewModel.antigravityAuth.cancel()
                        }) { Text(stringResource(R.string.agent_cancel)) }
                    }
                }
            }
            state.apiStatusMessage?.let { msg ->
                Text(text = msg.asString(), fontSize = 13.sp, color = scheme.onSurfaceVariant)
            }
            Text(
                text = stringResource(R.string.settings_antigravity_footnote),
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    var modelOpen by rememberSaveable { mutableStateOf(false) }
    DisclosureCard(
        icon = Icons.Default.Info,
        title = stringResource(R.string.settings_model_access),
        subtitle = "${state.antigravityModel} · ${state.antigravityEffort.uppercase()}",
        expanded = modelOpen,
        onToggle = { modelOpen = !modelOpen },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                shape = RoundedCornerShape(14.dp),
                value = agyModelInput,
                onValueChange = onAgyModelChange,
                label = { Text(stringResource(R.string.settings_antigravity_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val efforts = listOf("low", "medium", "high")
            ForgePillTabs(
                items = efforts.map { stringResource(R.string.settings_effort, it.uppercase()) },
                selectedIndex = efforts.indexOf(agyEffortInput).coerceAtLeast(0),
                onSelect = { onAgyEffortChange(efforts[it]) },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForgePrimaryButton(
                    text = stringResource(R.string.settings_save_antigravity_model),
                    onClick = { viewModel.setAntigravityConfig(agyModelInput, agyEffortInput) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = viewModel::validateAntigravityConnection,
                    enabled = !state.apiBusy,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) { Text(stringResource(R.string.settings_validate)) }
            }
        }
    }
}

/* ───────────────────────── Building blocks ───────────────────────── */

/** 56dp list row: leading icon in a tinted square, title/subtitle, custom trailing. */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 14.dp,
    trailing: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(10.dp), color = tokens.cardElevatedColor, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        trailing()
    }
}

/** Card with a SettingsRow header that reveals its body. */
@Composable
internal fun DisclosureCard(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
        SettingsRow(icon = icon, title = title, subtitle = subtitle, onClick = onToggle, trailing = { Chevron(expanded) })
        AnimatedVisibility(visible = expanded) {
            Column {
                ThinDivider()
                content()
            }
        }
    }
}

@Composable
internal fun Chevron(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = stringResource(if (expanded) R.string.projects_collapse else R.string.projects_expand),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ThinDivider() {
    Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}

/** Radio row: title + subtitle left, Material radio right; whole row is the target. */
@Composable
internal fun RadioRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    mono: Boolean = false,
    horizontalPadding: androidx.compose.ui.unit.Dp = 14.dp,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    fontFamily = if (mono) FontFamily.Monospace else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
internal fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Progress and message of the runtime setup service while it runs, errors in the danger colour. */
@Composable
internal fun SetupProgress(state: ForgeUiState) {
    val status = state.setupSnapshot.status
    // Step, percent, MB transferred and the latest terminal lines while an install runs or after it failed.
    if (status == RuntimeSetupStatus.RUNNING || status == RuntimeSetupStatus.ERROR) {
        RuntimeProgressPanel(state.setupSnapshot)
    }
}
