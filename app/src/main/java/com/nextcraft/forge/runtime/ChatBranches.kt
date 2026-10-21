package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.Project
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a chat works when it has its own branch: a clone of the project, named after the chat. */
data class ChatBranch(
    /** Pseudo project id the agent bridge uses, so checkpoints and mounts are separate from the project's own. */
    val pseudoProjectId: String,
    val slug: String,
    val cloneDir: File,
)

data class BranchCommit(val hash: String, val subject: String)

/** What a chat branch holds compared with the project (main) it was cut from. */
data class BranchStatus(
    val commits: Int = 0,
    val uncommitted: Int = 0,
    val filesChanged: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    /** Commits on the project that the branch does not have yet. */
    val behind: Int = 0,
    /** Files with unresolved merge conflicts inside the branch. */
    val conflicts: List<String> = emptyList(),
    val recent: List<BranchCommit> = emptyList(),
)

sealed interface BranchMergeResult {
    data object Done : BranchMergeResult
    data class Conflict(val files: List<String>) : BranchMergeResult
    data class Failed(val message: String) : BranchMergeResult
}

/**
 * One clone of the project per chat ("branch"), so chats that work at the same time cannot overwrite each other's
 * files. A git worktree would be the obvious tool, but it records absolute paths into the main repository and each chroot
 * run only mounts one project folder; a local clone (objects are hard-linked, so it costs almost no space) is
 * self-contained. The clones live inside the project at `.forge/chats/<id>` so that one chroot run sees both and can
 * merge them: commit after every turn, update from main, merge into main, discard.
 */
class ChatBranches(context: Context) {
    private val appContext = context.applicationContext
    private val runner = ChrootRunner(appContext)
    private val checkpoints = WorkspaceCheckpoints(appContext.filesDir, appContext)

    /** Paths of the branch of [chatId]; registers the clone directory for the pseudo project. */
    fun branchFor(project: Project, chatId: String): ChatBranch {
        val id = branchId(chatId)
        val main = checkpoints.ensureWorkspace(project.id)
        val dir = File(main, ".forge/chats/$id")
        val pseudo = pseudoProjectId(project.id, id)
        WorkspaceCheckpoints.registerWorkspaceOverride(pseudo, dir)
        return ChatBranch(pseudo, branchSlug(project.slug, id), dir)
    }

    /** Creates the project repository if needed, snapshots uncommitted work, and clones it for the chat. */
    suspend fun ensure(project: Project, chatId: String): ChatBranch = withContext(Dispatchers.IO) {
        val branch = branchFor(project, chatId)
        val id = branchId(chatId)
        val result = git(project, chatId, ENSURE_SCRIPT)
        check(result.ok) { "Could not prepare the chat branch: ${result.output.trim().takeLast(300)}" }
        branch.also { check(File(it.cloneDir, ".git").exists()) { "The chat branch clone is missing ($id)." } }
    }

    /** Commits what the agent changed in the branch (one commit per turn). */
    suspend fun commitTurn(project: Project, chatId: String, message: String) = withContext(Dispatchers.IO) {
        branchFor(project, chatId)
        git(project, chatId, COMMIT_SCRIPT, "MSG" to commitMessage(message))
    }

    suspend fun status(project: Project, chatId: String): BranchStatus? = withContext(Dispatchers.IO) {
        val branch = branchFor(project, chatId)
        if (!File(branch.cloneDir, ".git").exists()) return@withContext null
        val result = git(project, chatId, STATUS_SCRIPT)
        if (result.ok) parseStatus(result.output) else null
    }

    /** `git diff --stat` and the patch of the branch against the commit it was cut from (long patches are cut). */
    suspend fun diff(project: Project, chatId: String): String = withContext(Dispatchers.IO) {
        branchFor(project, chatId)
        val result = git(project, chatId, DIFF_SCRIPT)
        result.output.take(MAX_DIFF_CHARS).ifBlank { "" }
    }

    suspend fun mergeIntoMain(project: Project, chatId: String, title: String): BranchMergeResult = withContext(Dispatchers.IO) {
        branchFor(project, chatId)
        val result = git(project, chatId, MERGE_SCRIPT, "TITLE" to commitMessage("Merge chat: $title"))
        parseMergeResult(result.output, result.ok)
    }

    /** Brings the project's newer commits into the branch; conflicts are left in the branch for the agent to resolve. */
    suspend fun updateFromMain(project: Project, chatId: String): BranchMergeResult = withContext(Dispatchers.IO) {
        branchFor(project, chatId)
        val result = git(project, chatId, UPDATE_SCRIPT)
        parseMergeResult(result.output, result.ok)
    }

    suspend fun discard(project: Project, chatId: String) = withContext(Dispatchers.IO) {
        val branch = branchFor(project, chatId)
        git(project, chatId, DISCARD_SCRIPT)
        WorkspaceCheckpoints.clearWorkspaceOverride(branch.pseudoProjectId)
    }

    private suspend fun git(project: Project, chatId: String, script: String, vararg extra: Pair<String, String>): ChrootResult {
        val id = branchId(chatId)
        val main = checkpoints.ensureWorkspace(project.id)
        val env = mapOf("FORGE_ID" to id, "FORGE_SLUG" to project.slug) + extra.toMap()
        return runner.run(
            workspaceDir = main,
            slug = project.slug,
            command = script,
            environment = env,
            timeoutMillis = GIT_TIMEOUT_MS,
        )
    }

    companion object {
        private const val GIT_TIMEOUT_MS = 3 * 60_000L
        const val MAX_DIFF_CHARS = 120_000

        /** Eight letters or digits of the chat id: short, safe in paths, refs and branch names. */
        fun branchId(chatId: String): String =
            chatId.filter { it.isLetterOrDigit() }.lowercase().takeLast(8).ifEmpty { "main" }

        fun pseudoProjectId(projectId: String, id: String): String = "$projectId-c$id"

        /** Chroot workspace names are at most 64 characters of [a-z0-9-]. */
        fun branchSlug(projectSlug: String, id: String): String = "${projectSlug.take(48).trimEnd('-')}-c$id"

        fun commitMessage(text: String): String =
            text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().replace(Regex("[\\u0000-\\u001f]"), " ").take(100)
                .ifEmpty { "Update" }

        /** Reads the `key=value` and `C <hash> <subject>` lines STATUS_SCRIPT prints. */
        fun parseStatus(output: String): BranchStatus {
            val values = HashMap<String, String>()
            val commits = ArrayList<BranchCommit>()
            output.lineSequence().forEach { line ->
                when {
                    line.startsWith("C ") -> line.removePrefix("C ").split(' ', limit = 2).let {
                        if (it.size == 2) commits += BranchCommit(it[0], it[1])
                    }
                    '=' in line -> values[line.substringBefore('=')] = line.substringAfter('=').trim()
                }
            }
            val stat = values["stat"].orEmpty().split(' ').mapNotNull { it.toIntOrNull() }
            return BranchStatus(
                commits = values["ahead"]?.toIntOrNull() ?: 0,
                uncommitted = values["uncommitted"]?.toIntOrNull() ?: 0,
                additions = stat.getOrElse(0) { 0 },
                deletions = stat.getOrElse(1) { 0 },
                filesChanged = stat.getOrElse(2) { 0 },
                behind = values["behind"]?.toIntOrNull() ?: 0,
                conflicts = values["conflicts"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
                recent = commits,
            )
        }

        fun parseMergeResult(output: String, ok: Boolean): BranchMergeResult {
            val lines = output.lineSequence().map(String::trim).toList()
            return when {
                lines.any { it == "RESULT=merged" || it == "RESULT=updated" } -> BranchMergeResult.Done
                lines.any { it == "RESULT=conflict" } -> BranchMergeResult.Conflict(
                    lines.firstOrNull { it.startsWith("files=") }.orEmpty().removePrefix("files=")
                        .split(',').map(String::trim).filter(String::isNotEmpty),
                )
                else -> BranchMergeResult.Failed(output.trim().takeLast(300).ifEmpty { if (ok) "Unexpected result" else "git failed" })
            }
        }

        // Shell scripts: § stands for $ so that Kotlin does not read them as templates.
        private fun script(body: String): String = ("""
            set -e
            M="/workspace/§FORGE_SLUG"
            C="§M/.forge/chats/§FORGE_ID"
            G() { git -c user.name="NextCraft Forge" -c user.email="forge@localhost" -c safe.directory='*' -c core.fileMode=false "§@"; }
        """.trimIndent() + "\n" + body.trimIndent()).replace('§', '$')

        /** Makes sure the project is a git repository with everything committed, then clones it for the chat. */
        private val ENSURE_SCRIPT = script("""
            cd "§M"
            [ -d .git ] || G init -q -b main
            mkdir -p .git/info
            grep -qxF '/.forge/' .git/info/exclude 2>/dev/null || echo '/.forge/' >> .git/info/exclude
            G add -A
            if ! G rev-parse -q --verify HEAD >/dev/null; then
              G commit -q --allow-empty -m "Forge: initial commit"
            elif ! G diff --cached --quiet; then
              G commit -q -m "Forge: snapshot before chat branch"
            fi
            if [ ! -d "§C/.git" ]; then
              mkdir -p "§(dirname "§C")"
              G clone -q --local "§M" "§C"
              cd "§C"
              G checkout -q -b "chat/§FORGE_ID"
              G tag -f forge-base HEAD >/dev/null
            fi
        """)

        private val COMMIT_SCRIPT = script("""
            cd "§C"
            G add -A
            if ! G diff --cached --quiet; then G commit -q -m "§MSG"; fi
        """)

        private val STATUS_SCRIPT = script("""
            cd "§C"
            echo "ahead=§(G rev-list --count forge-base..HEAD)"
            echo "uncommitted=§(G status --porcelain | wc -l)"
            echo "conflicts=§(G diff --name-only --diff-filter=U | tr '\n' ',')"
            G add -N . 2>/dev/null || true
            echo "stat=§(G diff --numstat forge-base | awk '{a+=§1;d+=§2;f++} END{print a+0,d+0,f+0}')"
            echo "behind=§(G -C "§M" rev-list --count "§(G rev-parse forge-base)"..HEAD)"
            G log --format='C %h %s' -n 8 forge-base..HEAD
        """)

        private val DIFF_SCRIPT = script("""
            cd "§C"
            G add -N . 2>/dev/null || true
            G diff --stat forge-base || true
            echo
            G diff forge-base || true
        """)

        /** Merges the committed branch into the project; a conflict aborts the merge and leaves the project untouched. */
        private val MERGE_SCRIPT = script("""
            cd "§M"
            G add -A
            if ! G diff --cached --quiet; then G commit -q -m "Forge: snapshot before merging a chat"; fi
            G fetch -q "§C" "+HEAD:refs/forge/chat-§FORGE_ID"
            if G merge --no-ff -m "§TITLE" "refs/forge/chat-§FORGE_ID" >/dev/null 2>&1; then
              echo RESULT=merged
              cd "§C"
              G fetch -q "§M" HEAD
              G merge -q --ff-only FETCH_HEAD >/dev/null 2>&1 || true
              G tag -f forge-base HEAD >/dev/null
            else
              echo RESULT=conflict
              echo "files=§(G diff --name-only --diff-filter=U | tr '\n' ',')"
              G merge --abort
            fi
        """)

        /** Merges the project into the branch; on conflict the merge stays open in the branch for the agent. */
        private val UPDATE_SCRIPT = script("""
            cd "§M"
            G add -A
            if ! G diff --cached --quiet; then G commit -q -m "Forge: snapshot before updating a chat"; fi
            cd "§C"
            G add -A
            if ! G diff --cached --quiet; then G commit -q -m "Forge: save work before update"; fi
            G fetch -q "§M" HEAD
            G tag -f forge-base FETCH_HEAD >/dev/null
            if G merge --no-edit FETCH_HEAD >/dev/null 2>&1; then
              echo RESULT=updated
            else
              echo RESULT=conflict
              echo "files=§(G diff --name-only --diff-filter=U | tr '\n' ',')"
            fi
        """)

        private val DISCARD_SCRIPT = script("""
            rm -rf "§C"
            cd "§M"
            G update-ref -d "refs/forge/chat-§FORGE_ID" >/dev/null 2>&1 || true
        """)
    }
}
