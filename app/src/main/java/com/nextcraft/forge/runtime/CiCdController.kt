package com.nextcraft.forge.runtime

import com.nextcraft.forge.ui.orRes
import com.nextcraft.forge.ui.UiText
import com.nextcraft.forge.R
import android.content.Context
import com.nextcraft.forge.data.ApiKeyVault
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.network.GitHubClient
import com.nextcraft.forge.network.GitHubWorkflow
import com.nextcraft.forge.network.GitHubWorkflowRun
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CiCdState(
    val repository: String? = null,
    val workflows: List<GitHubWorkflow> = emptyList(),
    val runs: List<GitHubWorkflowRun> = emptyList(),
    val branch: String = "main",
    val loading: Boolean = false,
    val message: UiText? = null,
)

/** Owner and repo of the `origin` remote in a workspace's .git/config, for github.com remotes only. */
internal fun githubRepositoryOf(workspace: File): Pair<String, String>? {
    val config = File(workspace, ".git/config").takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } ?: return null
    val origin = Regex("""\[remote "origin"][^\[]*?url\s*=\s*(\S+)""").find(config)?.groupValues?.get(1) ?: return null
    val match = Regex("""github\.com[:/]([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\.git)?/?$""").find(origin) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}

/** CI/CD pipeline: GitHub Actions runs and manual workflow dispatch for the active project's repository. */
class CiCdController(
    context: Context,
    private val activeProject: () -> Project?,
    private val workspaceFor: (String) -> File,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = GitHubClient(appContext)
    private val vault = ApiKeyVault(appContext)
    private val mutable = MutableStateFlow(CiCdState())
    val state: StateFlow<CiCdState> = mutable.asStateFlow()

    fun refresh() {
        val project = activeProject()
        if (project == null) {
            mutable.update { CiCdState(message = UiText.res(R.string.rt_open_project_first)) }
            return
        }
        mutable.update { it.copy(loading = true, message = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = vault.get("GITHUB_TOKEN").orEmpty()
                    check(token.isNotBlank()) { "Connect GitHub in Settings first." }
                    val (owner, repo) = githubRepositoryOf(workspaceFor(project.id))
                        ?: error("This project has no GitHub remote. Push it to GitHub or clone a GitHub repository.")
                    Triple("$owner/$repo", client.workflows(token, owner, repo), client.workflowRuns(token, owner, repo))
                }
            }
            mutable.update { current ->
                result.fold(
                    onSuccess = { (name, workflows, runs) -> current.copy(repository = name, workflows = workflows, runs = runs, loading = false) },
                    onFailure = { current.copy(loading = false, message = it.message.orRes(R.string.rt_cicd_load_failed)) },
                )
            }
        }
    }

    fun setBranch(branch: String) = mutable.update { it.copy(branch = branch.trim()) }

    fun dispatch(workflow: GitHubWorkflow) {
        val project = activeProject() ?: return
        val branch = mutable.value.branch.ifBlank { "main" }
        mutable.update { it.copy(loading = true, message = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = vault.get("GITHUB_TOKEN").orEmpty()
                    check(token.isNotBlank()) { "Connect GitHub in Settings first." }
                    val (owner, repo) = githubRepositoryOf(workspaceFor(project.id)) ?: error("No GitHub remote.")
                    client.dispatchWorkflow(token, owner, repo, workflow.id, branch)
                }
            }
            mutable.update {
                it.copy(
                    loading = false,
                    message = result.fold({ UiText.res(R.string.rt_cicd_started, workflow.name, branch) }, { e -> e.message.orRes(R.string.rt_cicd_start_failed) }),
                )
            }
        }
    }
}
