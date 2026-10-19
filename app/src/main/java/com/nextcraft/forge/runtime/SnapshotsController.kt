package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.Project
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

data class SnapshotsState(
    val projectName: String = "",
    val snapshots: List<WorkspaceSnapshot> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

/** Checkpoint manager: manual project snapshots with restore (see [SnapshotManager]). */
class SnapshotsController(
    context: Context,
    private val activeProject: () -> Project?,
    private val workspaceFor: (String) -> File,
    /** True while an agent turn, build or terminal command may be writing to the project. */
    private val projectBusy: () -> Boolean,
) {
    private val manager = SnapshotManager(context.applicationContext.filesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow(SnapshotsState())
    val state: StateFlow<SnapshotsState> = mutable.asStateFlow()

    fun refresh() {
        val project = activeProject() ?: run {
            mutable.update { SnapshotsState(message = "Open a project first.") }
            return
        }
        scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { manager.list(project.id) }.getOrDefault(emptyList()) }
            mutable.update { it.copy(projectName = project.name, snapshots = list) }
        }
    }

    fun create(label: String) = task("Snapshot saved.") { project ->
        manager.create(project.id, workspaceFor(project.id), label)
    }

    fun restore(snapshot: WorkspaceSnapshot) {
        if (projectBusy()) {
            mutable.update { it.copy(message = "Wait for the running agent, build or terminal command first.") }
            return
        }
        task("Restored \"${snapshot.label}\". The state before the restore was saved as a snapshot.") { project ->
            manager.restore(project.id, workspaceFor(project.id), snapshot)
        }
    }

    fun delete(snapshot: WorkspaceSnapshot) = task("Snapshot deleted.") { manager.delete(snapshot) }

    private fun task(success: String, block: (Project) -> Unit) {
        val project = activeProject() ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block(project) } }
            val list = withContext(Dispatchers.IO) { runCatching { manager.list(project.id) }.getOrDefault(emptyList()) }
            mutable.update {
                it.copy(
                    busy = false,
                    snapshots = list,
                    projectName = project.name,
                    message = result.fold({ success }, { e -> e.message ?: "Operation failed." }),
                )
            }
        }
    }
}
