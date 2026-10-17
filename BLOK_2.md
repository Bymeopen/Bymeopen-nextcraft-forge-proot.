# Blok 2 — Workspace Checkpoints, Penyimpanan Terenkripsi & Local Protocol Gateways

Blok 2 menangani **keamanan penyimpanan kredensial**, **sistem snapshot/undo file proyek (Checkpoints)**, serta **gateway konversi protokol lokal (`127.0.0.1`)** agar agen yang berbicara dalam protokol Anthropic Messages API dapat terhubung ke provider OpenAI Chat Completions, OpenAI Responses, maupun OpenRouter.

### Fungsi Utama Blok 2:
1. **Snapshot, Diff & Undo/Accept File Proyek (`WorkspaceCheckpoints.kt`)**:
   - Menyimpan salinan fisik (physical copy dengan inode terpisah, bukan hardlink) dari workspace proyek sebelum agen bekerja, sehingga modifikasi in-place (`O_TRUNC`) oleh agen tidak merusak backup baseline.
   - Menghitung diff baris (`additions`, `deletions`, dan unified diff preview) dengan cache SHA-256 `(path, mtime, size)` dan deteksi file biner (`isProbablyText`), serta menyediakan fitur `undoLastChanges`, `acceptLastChanges`, `undoFileChange`, `acceptFileChange`, `revertFile`, dan `revertLatestTurn`.
   - Memblokir serangan path traversal (`../`) maupun symlinked ancestor directory lewat `safeWorkspaceFile()`.
2. **Kontrak Runtime Bridge & Konfigurasi Peluncuran (`RuntimeBridge.kt`)**:
   - Mendefinisikan interface `RuntimeBridge` yang seragam bagi seluruh agen coding serta `RuntimeLaunchConfigBuilder` yang merakit environment variable (`ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, `CLAUDE_CODE_OAUTH_TOKEN`, model tier) dengan normalisasi URL otomatis.
3. **Gateway Translasi Protokol Lokal (`LocalFormatGateway.kt` & `OpenRouterRoutingGateway.kt`)**:
   - Menjalankan server HTTP loopback di `127.0.0.1:<port-acak>` dengan autentikasi token sekali-pakai berbasis `MessageDigest.isEqual` (constant-time) terhadap `activeApiKey` dan pembatas ukuran header/body.
   - `LocalFormatGateway` menerjemahkan request Anthropic `/v1/messages` (termasuk tool calls, streaming SSE, dan thinking blocks) ke format OpenAI `/v1/chat/completions` atau `/v1/responses` secara real-time, lengkap dengan rotasi API key otomatis (`synchronized(rotationLock)`) saat terkena HTTP 401/403/429.
   - `OpenRouterRoutingGateway` menyuntikkan objek konfigurasi routing `provider: { order: [...], allow_fallbacks: ... }` khusus OpenRouter pada setiap request.
4. **Brankas API Key Terenkripsi & Preferensi Aplikasi (`ApiKeyVault.kt` & `AppPreferences.kt`)**:
   - `ApiKeyVault` menyimpan daftar API key di Android Keystore AES-256-GCM (`setUserAuthenticationRequired` bila perangkat memiliki kunci layar) dan mendukung multi-key rotation (`rotateNext`) per provider.
   - `AppPreferences` menyimpan proyek (dengan validasi kanonik `rootPath`), riwayat percakapan, profil provider, dan pengaturan agen secara atomik (`AtomicFile` / temp-rename) dengan validasi ID penyimpanan (`safeStorageId`).

## Daftar File dalam Blok Ini
- `app/src/main/java/com/nextcraft/forge/runtime/WorkspaceCheckpoints.kt` (536 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/RuntimeBridge.kt` (221 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/LocalFormatGateway.kt` (752 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/OpenRouterRoutingGateway.kt` (232 baris)
- `app/src/main/java/com/nextcraft/forge/data/ApiKeyVault.kt` (269 baris)
- `app/src/main/java/com/nextcraft/forge/data/AppPreferences.kt` (521 baris)

---

## 1. `app/src/main/java/com/nextcraft/forge/runtime/WorkspaceCheckpoints.kt` (536 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.DiffLine
import com.nextcraft.forge.model.DiffLineType
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Workspace checkpoint / snapshot / diff store shared by agent bridges.
 *
 * Uses isolated physical file copies (never hardlinks) for baseline snapshots so
 * in-place file writes (`O_TRUNC`) by coding agents never mutate the checkpoint backup.
 */
class WorkspaceCheckpoints(
    private val filesDir: File,
    @Suppress("UNUSED_PARAMETER") private val context: Context? = null,
) {
    private data class FileKey(val path: String, val mtime: Long, val size: Long)

    private val projectRoots = ConcurrentHashMap<String, String>()
    private val projectLocks = ConcurrentHashMap<String, Any>()
    private val sha256Cache = ConcurrentHashMap<FileKey, String>()

    private fun lockFor(projectId: String): Any = projectLocks.computeIfAbsent(projectId) { Any() }

    private fun safeProjectId(projectId: String): String {
        val clean = projectId.trim()
        require(
            clean.isNotEmpty() &&
                clean != "." &&
                !clean.contains('\u0000') &&
                !clean.contains('/') &&
                !clean.contains('\\') &&
                !clean.contains(".."),
        ) {
            "Unsafe project identifier"
        }
        return clean
    }

    fun ensureWorkspace(projectId: String): File {
        val safeId = safeProjectId(projectId)
        val base = File(filesDir, "workspaces/$safeId").apply { mkdirs() }.canonicalFile
        val rootPath = projectRoots[safeId].orEmpty()
        if (rootPath.isBlank()) return base
        val selected = File(base, rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        val safeId = safeProjectId(projectId)
        val normalized = rootPath.replace('\\', '/').trim().trim('/')
        require(
            normalized.isBlank() || (
                !normalized.contains('\u0000') &&
                    !normalized.startsWith('/') &&
                    normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }
                ),
        ) {
            "Unsafe project root"
        }
        synchronized(lockFor(safeId)) {
            val previous = projectRoots.put(safeId, normalized).orEmpty()
            if (previous != normalized) checkpointDir(safeId).deleteRecursively()
        }
    }

    fun checkpointDir(projectId: String) = File(filesDir, "checkpoints/${safeProjectId(projectId)}/latest")

    fun createCheckpoint(projectId: String, workspace: File) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            val checkpoint = checkpointDir(safeId)
            val backup = File(checkpoint, "project")
            // Keep the original baseline only while unreviewed pending files exist.
            if (backup.isDirectory && readChangedPathsUnlocked(safeId).isNotEmpty()) return
            File(checkpoint, "changes.json").delete()
            File(checkpoint, ".changes.json.tmp").delete()
            backup.mkdirs()
            val workspacePath = workspace.canonicalFile.toPath()
            val activeRelatives = HashSet<String>()
            workspace.walkTopDown()
                .onEnter { directory ->
                    val relative = if (directory == workspace) "" else directory.relativeTo(workspace).invariantSeparatorsPath
                    directory == workspace || (
                        !isInternalRuntimePath(relative) &&
                            !Files.isSymbolicLink(directory.toPath()) &&
                            runCatching { directory.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                        )
                }
                .filter {
                    it.isFile &&
                        !isInternalRuntimePath(it.relativeTo(workspace).invariantSeparatorsPath) &&
                        !Files.isSymbolicLink(it.toPath()) &&
                        runCatching { it.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                }
                .forEach { source ->
                    val relative = source.relativeTo(workspace).invariantSeparatorsPath
                    activeRelatives.add(relative)
                    val destination = safeWorkspaceFile(backup, relative)
                    // Fast path: identical size + mtime means the baseline is current. Only hash
                    // when metadata differs, so large workspaces are not re-digested every prompt.
                    val upToDate = destination.isFile &&
                        !Files.isSymbolicLink(destination.toPath()) &&
                        destination.length() == source.length() &&
                        (
                            destination.lastModified() == source.lastModified() ||
                                digest(destination) == digest(source)
                            )
                    if (!upToDate) {
                        destination.parentFile?.mkdirs()
                        // Always copy bytes into a distinct inode so in-place O_TRUNC edits in
                        // workspace never overwrite the checkpoint baseline!
                        source.copyTo(destination, overwrite = true)
                        runCatching { destination.setLastModified(source.lastModified()) }
                    }
                }
            val backupPath = backup.canonicalFile.toPath()
            val staleFiles = backup.walkTopDown()
                .filter {
                    it.isFile &&
                        runCatching { it.canonicalFile.toPath().startsWith(backupPath) }.getOrDefault(false) &&
                        it.relativeTo(backup).invariantSeparatorsPath !in activeRelatives
                }
                .toList()
            staleFiles.forEach { stale ->
                deleteTargetAndPruneEmptyParents(backup, stale)
            }
        }
    }

    fun saveChangedPaths(projectId: String, paths: List<String>) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            val dir = checkpointDir(safeId).apply { mkdirs() }
            val manifest = File(dir, "changes.json")
            val merged = (readChangedPathsUnlocked(safeId) + paths)
                .filterNot(::isInternalRuntimePath)
                .distinct()
                .sorted()
            writeManifestAtomically(manifest, JSONArray(merged).toString())
        }
    }

    fun readChangedPaths(projectId: String): List<String> {
        val safeId = safeProjectId(projectId)
        return synchronized(lockFor(safeId)) {
            readChangedPathsUnlocked(safeId)
        }
    }

    private fun readChangedPathsUnlocked(safeId: String): List<String> {
        val manifest = File(checkpointDir(safeId), "changes.json")
        if (!manifest.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
    }

    fun removeChangedPath(projectId: String, path: String) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            val remaining = readChangedPathsUnlocked(safeId).filterNot { it == path }
            if (remaining.isEmpty()) {
                checkpointDir(safeId).deleteRecursively()
            } else {
                val manifest = File(checkpointDir(safeId), "changes.json")
                writeManifestAtomically(manifest, JSONArray(remaining).toString())
            }
        }
    }

    fun revertFile(projectId: String, workspace: File, path: String): Boolean {
        val safeId = safeProjectId(projectId)
        if (isInternalRuntimePath(path)) return false
        return synchronized(lockFor(safeId)) {
            val pending = readChangedPathsUnlocked(safeId)
            if (path !in pending) return@synchronized false
            val backup = File(checkpointDir(safeId), "project")
            if (!backup.isDirectory) return@synchronized false
            val target = safeWorkspaceFile(workspace, path)
            val original = safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                deleteTargetAndPruneEmptyParents(workspace, target)
            }
            val remaining = pending.filterNot { it == path }
            if (remaining.isEmpty()) {
                checkpointDir(safeId).deleteRecursively()
            } else {
                writeManifestAtomically(File(checkpointDir(safeId), "changes.json"), JSONArray(remaining).toString())
            }
            true
        }
    }

    fun revertLatestTurn(projectId: String, workspace: File): List<String> {
        val safeId = safeProjectId(projectId)
        return synchronized(lockFor(safeId)) {
            val checkpoint = checkpointDir(safeId)
            val backup = File(checkpoint, "project")
            val paths = readChangedPathsUnlocked(safeId).filterNot(::isInternalRuntimePath)
            if (!backup.isDirectory || paths.isEmpty()) return@synchronized emptyList()
            paths.forEach { path ->
                val target = safeWorkspaceFile(workspace, path)
                val original = safeWorkspaceFile(backup, path)
                if (original.isFile) {
                    target.parentFile?.mkdirs()
                    original.copyTo(target, overwrite = true)
                } else if (target.isFile) {
                    deleteTargetAndPruneEmptyParents(workspace, target)
                }
            }
            checkpoint.deleteRecursively()
            paths
        }
    }

    fun acceptFile(projectId: String, workspace: File, path: String): Boolean {
        val safeId = safeProjectId(projectId)
        if (isInternalRuntimePath(path)) return false
        return synchronized(lockFor(safeId)) {
            val pending = readChangedPathsUnlocked(safeId)
            if (path !in pending) return@synchronized false
            val checkpoint = checkpointDir(safeId)
            val backup = File(checkpoint, "project")
            val current = safeWorkspaceFile(workspace, path)
            val baseline = safeWorkspaceFile(backup, path)
            if (current.isFile) {
                baseline.parentFile?.mkdirs()
                current.copyTo(baseline, overwrite = true)
            } else if (baseline.isFile) {
                deleteTargetAndPruneEmptyParents(backup, baseline)
            }
            val remaining = pending.filterNot { it == path }
            if (remaining.isEmpty()) {
                checkpoint.deleteRecursively()
            } else {
                writeManifestAtomically(File(checkpoint, "changes.json"), JSONArray(remaining).toString())
            }
            true
        }
    }

    fun acceptAll(projectId: String) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            checkpointDir(safeId).deleteRecursively()
        }
    }

    private fun deleteTargetAndPruneEmptyParents(root: File, target: File) {
        val rootCanonical = runCatching { root.canonicalFile }.getOrDefault(root)
        var parent = target.parentFile
        target.delete()
        while (parent != null) {
            val currentParent = parent
            val parentCanonical = runCatching { currentParent.canonicalFile }.getOrDefault(currentParent)
            if (currentParent == root || parentCanonical == rootCanonical || !parentCanonical.toPath().startsWith(rootCanonical.toPath())) {
                break
            }
            val children = currentParent.list()
            if (children != null && children.isEmpty() && currentParent.delete()) {
                parent = currentParent.parentFile
            } else {
                break
            }
        }
    }

    private fun writeManifestAtomically(manifest: File, content: String) {
        manifest.parentFile?.mkdirs()
        val tmp = File(manifest.parentFile, ".${manifest.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(manifest)) {
            android.util.Log.w("Checkpoints", "Atomic rename failed for ${manifest.name}; falling back to copy")
            tmp.copyTo(manifest, overwrite = true)
            tmp.delete()
        }
    }

    fun buildChangeDetails(projectId: String, workspace: File, paths: List<String>): List<ChangeItem> {
        val backup = File(checkpointDir(projectId), "project")
        return paths.map { path ->
            val beforeFile = safeWorkspaceFile(backup, path).takeIf(File::isFile)
            val afterFile = safeWorkspaceFile(workspace, path).takeIf(File::isFile)
            if (listOfNotNull(beforeFile, afterFile).any { it.length() > MAX_DIFF_FILE_BYTES }) {
                return@map ChangeItem(
                    path = path,
                    additions = 0,
                    deletions = 0,
                    diffLines = listOf(
                        DiffLine(
                            DiffLineType.INFO,
                            "File is too large to preview safely. Undo and Keep still work.",
                        ),
                    ),
                )
            }
            val before = beforeFile?.readBytes() ?: ByteArray(0)
            val after = afterFile?.readBytes() ?: ByteArray(0)
            val binary = !before.isProbablyText() || !after.isProbablyText()
            val (additions, deletions) = lineChanges(before, after)
            ChangeItem(
                path = path,
                additions = additions,
                deletions = deletions,
                diffLines = buildDiffLines(before, after),
                binary = binary,
            )
        }
    }

    fun buildDiffLines(beforeBytes: ByteArray, afterBytes: ByteArray): List<DiffLine> {
        if (!beforeBytes.isProbablyText() || !afterBytes.isProbablyText()) {
            return listOf(DiffLine(DiffLineType.INFO, "Binary file changed"))
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_RENDERED_DIFF_LINES || after.size > MAX_RENDERED_DIFF_LINES) {
            return listOf(
                DiffLine(
                    DiffLineType.INFO,
                    "Diff is too large to display (${before.size} → ${after.size} lines). Undo and Keep still work.",
                ),
            )
        }

        val lcs = Array(before.size + 1) { IntArray(after.size + 1) }
        for (oldIndex in before.lastIndex downTo 0) {
            for (newIndex in after.lastIndex downTo 0) {
                lcs[oldIndex][newIndex] = if (before[oldIndex] == after[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < before.size || newIndex < after.size) {
            when {
                oldIndex < before.size && newIndex < after.size && before[oldIndex] == after[newIndex] -> {
                    result += DiffLine(DiffLineType.CONTEXT, before[oldIndex], oldIndex + 1, newIndex + 1)
                    oldIndex++
                    newIndex++
                }
                newIndex < after.size && (oldIndex == before.size || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex]) -> {
                    result += DiffLine(DiffLineType.ADDITION, after[newIndex], null, newIndex + 1)
                    newIndex++
                }
                oldIndex < before.size -> {
                    result += DiffLine(DiffLineType.DELETION, before[oldIndex], oldIndex + 1, null)
                    oldIndex++
                }
            }
        }
        return collapseUnchangedLines(result)
    }

    private fun collapseUnchangedLines(lines: List<DiffLine>): List<DiffLine> {
        val changedIndexes = lines.indices.filter { lines[it].type != DiffLineType.CONTEXT }
        if (changedIndexes.isEmpty()) return lines
        val visible = BooleanArray(lines.size)
        changedIndexes.forEach { changed ->
            for (index in maxOf(0, changed - DIFF_CONTEXT_LINES)..minOf(lines.lastIndex, changed + DIFF_CONTEXT_LINES)) {
                visible[index] = true
            }
        }
        val result = mutableListOf<DiffLine>()
        var index = 0
        while (index < lines.size) {
            if (visible[index]) {
                result += lines[index++]
            } else {
                val start = index
                while (index < lines.size && !visible[index]) index++
                result += DiffLine(DiffLineType.INFO, "… ${index - start} unchanged lines …")
            }
        }
        return result
    }

    private fun lineChanges(beforeBytes: ByteArray, afterBytes: ByteArray): Pair<Int, Int> {
        if (!beforeBytes.isProbablyText() || !afterBytes.isProbablyText()) {
            return (if (afterBytes.isNotEmpty()) 1 else 0) to (if (beforeBytes.isNotEmpty()) 1 else 0)
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_DIFF_LINES || after.size > MAX_DIFF_LINES) {
            return maxOf(0, after.size - before.size) to maxOf(0, before.size - after.size)
        }
        var previous = IntArray(after.size + 1)
        before.forEach { oldLine ->
            val current = IntArray(after.size + 1)
            after.forEachIndexed { index, newLine ->
                current[index + 1] = if (oldLine == newLine) {
                    previous[index] + 1
                } else {
                    maxOf(previous[index + 1], current[index])
                }
            }
            previous = current
        }
        val common = previous[after.size]
        return (after.size - common) to (before.size - common)
    }

    private fun ByteArray.isProbablyText(): Boolean =
        size <= MAX_DIFF_FILE_BYTES && none { it == 0.toByte() }

    private fun textLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty() || !bytes.isProbablyText()) return emptyList()
        val lines = bytes.toString(Charsets.UTF_8).split('\n')
        return if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

    fun safeWorkspaceFile(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/')
        val segments = normalized.split('/')
        require(
            normalized.isNotBlank() &&
                !normalized.contains('\u0000') &&
                !normalized.startsWith('/') &&
                segments.isNotEmpty() &&
                segments.none { it.isEmpty() || it == "." || it == ".." },
        ) {
            "Unsafe workspace path"
        }
        val file = File(root, normalized)
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.toPath()
        var cursor: File? = file
        while (cursor != null && cursor != root && cursor.canonicalFile != rootCanonical) {
            require(!Files.isSymbolicLink(cursor.toPath())) {
                "Symbolic links cannot be restored or diffed directly"
            }
            cursor = cursor.parentFile
        }
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Workspace path escapes project" }
        val canonicalFile = file.canonicalFile.toPath()
        require(canonicalFile.startsWith(rootPath) && canonicalFile != rootPath) {
            "Workspace path escapes project"
        }
        return file
    }

    fun snapshot(root: File): Map<String, String> {
        val rootPath = root.canonicalFile.toPath()
        return root.walkTopDown()
            .onEnter { directory ->
                val relative = if (directory == root) "" else directory.relativeTo(root).invariantSeparatorsPath
                directory == root || (
                    !isInternalRuntimePath(relative) &&
                        !Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .filter {
                it.isFile &&
                    !isInternalRuntimePath(it.relativeTo(root).invariantSeparatorsPath) &&
                    !Files.isSymbolicLink(it.toPath()) &&
                    runCatching { it.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .associate { it.relativeTo(root).invariantSeparatorsPath to digest(it) }
    }

    fun changedFiles(root: File, before: Map<String, String>): List<String> {
        val after = snapshot(root)
        return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
    }

    fun isInternalRuntimePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == ".claude.json" || IGNORED_DIRECTORY_NAMES.any { directory ->
            normalized == directory || normalized.startsWith("$directory/") || normalized.contains("/$directory/")
        }
    }

    private fun digest(file: File): String {
        val mtime = file.lastModified()
        val size = file.length()
        val key = if (mtime > 0L) FileKey(file.absolutePath, mtime, size) else null
        if (key != null) {
            sha256Cache[key]?.let { return it }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (key != null && file.lastModified() == mtime && file.length() == size) {
            if (sha256Cache.size > 4_096) sha256Cache.clear()
            sha256Cache[key] = hex
        }
        return hex
    }

    companion object {
        private const val MAX_DIFF_LINES = 2_000
        private const val MAX_RENDERED_DIFF_LINES = 600
        private const val MAX_DIFF_FILE_BYTES = 1_000_000L
        private const val DIFF_CONTEXT_LINES = 3
        private val IGNORED_DIRECTORY_NAMES = setOf(
            ".claude",
            ".git",
            ".gradle",
            ".idea",
            ".next",
            ".dart_tool",
            ".venv",
            "build",
            "dist",
            "node_modules",
            "target",
            "venv",
        )
    }
}
```

## 2. `app/src/main/java/com/nextcraft/forge/runtime/RuntimeBridge.kt` (221 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import kotlinx.coroutines.flow.Flow

data class RuntimeLaunchConfig(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

/**
 * Shared cancellation hook so the foreground notification or UI can stop an active
 * coding agent session in ClaudeRuntimeBridge, AntigravityRuntimeBridge, or DshRuntimeBridge.
 */
object RuntimeTaskController {
    @Volatile
    var stopAction: (() -> Unit)? = null

    @Synchronized
    fun stopCurrentTask() {
        stopAction?.invoke()
    }

    @Synchronized
    fun requestStop() {
        stopAction?.invoke()
    }
}

/**
 * Contract and helper dispatcher for RuntimeExecutionService so bridges in Block 3
 * can post foreground notifications without circular compile-time dependencies.
 */
object RuntimeExecutionServiceContract {
    const val SERVICE_CLASS_NAME = "com.nextcraft.forge.runtime.RuntimeExecutionService"
    const val ACTION_START = "com.nextcraft.forge.runtime.action.START"
    const val ACTION_PROGRESS = "com.nextcraft.forge.runtime.action.PROGRESS"
    const val ACTION_COMPLETE = "com.nextcraft.forge.runtime.action.COMPLETE"
    const val ACTION_FAILED = "com.nextcraft.forge.runtime.action.FAILED"
    const val ACTION_CANCELLED = "com.nextcraft.forge.runtime.action.CANCELLED"
    const val EXTRA_PROJECT_NAME = "com.nextcraft.forge.runtime.extra.PROJECT_NAME"
    const val EXTRA_DETAIL = "com.nextcraft.forge.runtime.extra.DETAIL"

    private fun resolveServiceClass(): Class<*>? =
        runCatching { Class.forName(SERVICE_CLASS_NAME) }.getOrNull()

    fun startForeground(context: Context, projectName: String) {
        val cls = resolveServiceClass() ?: return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, cls)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PROJECT_NAME, projectName),
            )
        }
    }

    fun pushProgress(context: Context, projectName: String?, detail: String) {
        val cls = resolveServiceClass() ?: return
        runCatching {
            context.startService(
                Intent(context, cls)
                    .setAction(ACTION_PROGRESS)
                    .putExtra(EXTRA_PROJECT_NAME, projectName)
                    .putExtra(EXTRA_DETAIL, detail),
            )
        }
    }

    fun finishForeground(context: Context, completed: Boolean, projectName: String, detail: String, tag: String = "RuntimeBridge") {
        val cls = resolveServiceClass() ?: return
        runCatching {
            context.startService(
                Intent(context, cls)
                    .setAction(if (completed) ACTION_COMPLETE else ACTION_FAILED)
                    .putExtra(EXTRA_PROJECT_NAME, projectName)
                    .putExtra(EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w(tag, "Could not post task result notification", error)
            runCatching { context.stopService(Intent(context, cls)) }
        }
    }

    fun cancelForeground(context: Context) {
        val cls = resolveServiceClass() ?: return
        runCatching {
            context.startService(Intent(context, cls).setAction(ACTION_CANCELLED))
        }.onFailure {
            runCatching { context.stopService(Intent(context, cls)) }
        }
    }
}

interface RuntimeBridge {
    val events: Flow<RuntimeEvent>
    suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String
    suspend fun respondToApproval(request: ToolRequest, approved: Boolean)
    suspend fun stopSession(sessionId: String)
    suspend fun stopActiveSession()
    suspend fun undoLastChanges(projectId: String): Boolean
    suspend fun acceptLastChanges(projectId: String)
    suspend fun loadPendingChanges(projectId: String): List<ChangeItem>
    suspend fun undoFileChange(projectId: String, path: String): Boolean
    suspend fun acceptFileChange(projectId: String, path: String): Boolean
}

/** Keeps recent context while preventing an old chat from becoming an unbounded prompt allocation. */
internal fun List<ChatMessage>.recentWithinCharacterBudget(maxCharacters: Int): List<ChatMessage> {
    if (maxCharacters <= 0 || isEmpty()) return emptyList()
    var remaining = maxCharacters
    val selected = ArrayDeque<ChatMessage>()
    for (message in asReversed()) {
        if (remaining <= 0) break
        val text = if (message.text.length <= remaining) message.text else message.text.takeLast(remaining)
        selected.addFirst(message.copy(text = text))
        remaining -= text.length
        if (text.length < message.text.length) break
    }
    return selected.toList()
}

/**
 * Self-hosted Custom API servers (LM Studio, vLLM, Ollama, Local Gateway…) often need no key,
 * but the agent CLIs refuse to start without one. Send a harmless placeholder that such servers ignore.
 */
internal fun localServerPlaceholderKey(profile: ProviderProfile): String =
    if (profile.kind == ProviderKind.CUSTOM) "no-key-required" else ""

object RuntimeLaunchConfigBuilder {
    fun build(
        profile: ProviderProfile,
        authToken: String? = null,
        localGatewayUrl: String? = null,
    ): RuntimeLaunchConfig {
        val environment = linkedMapOf(
            "DISABLE_AUTOUPDATER" to "1",
            // Skip telemetry, error reporting and other background network calls inside Chroot.
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
            "DISABLE_TELEMETRY" to "1",
            "DISABLE_ERROR_REPORTING" to "1",
        )
        when (profile.kind.protocol) {
            ProviderProtocol.CLAUDE_LOGIN -> {
                require(!authToken.isNullOrBlank()) { "Enter a Claude subscription token first" }
                environment["CLAUDE_CODE_OAUTH_TOKEN"] = authToken
                // Claude Code gives API-key variables precedence over OAuth. Explicitly
                // clear them so a previous API provider can never shadow this token.
                environment["ANTHROPIC_API_KEY"] = ""
                environment["ANTHROPIC_AUTH_TOKEN"] = ""
            }
            ProviderProtocol.ANTHROPIC -> {
                environment["ANTHROPIC_BASE_URL"] = profile.resolvedBaseUrl.trim().trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.ANTHROPIC_GATEWAY -> {
                // Claude Code appends /v1/messages itself; accept base URLs entered with or
                // without /v1 so the agent hits the same endpoint as Test connection.
                environment["ANTHROPIC_BASE_URL"] = profile.resolvedBaseUrl.trim().trimEnd('/').removeSuffix("/v1")
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.OPENROUTER -> {
                environment["ANTHROPIC_BASE_URL"] = (localGatewayUrl ?: profile.resolvedBaseUrl).trim().trimEnd('/').removeSuffix("/v1")
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.OPENAI_RESPONSES,
            ProviderProtocol.OPENAI_CHAT,
            -> {
                require(!localGatewayUrl.isNullOrBlank()) { "A local format gateway is required for this provider" }
                environment["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = "claude-sonnet-4-6"
            }
        }
        val runtimeModel = environment["ANTHROPIC_MODEL"] ?: profile.model
        if (profile.kind.protocol != ProviderProtocol.CLAUDE_LOGIN) {
            environment["ANTHROPIC_DEFAULT_OPUS_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_SONNET_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = runtimeModel
            environment["ANTHROPIC_SMALL_MODEL"] = runtimeModel
            environment["ANTHROPIC_FAST_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_SUBAGENT_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"] = "1"
            environment["CLAUDE_CODE_DISABLE_TOKEN_COUNTING"] = "1"
            environment["DISABLE_TELEMETRY"] = "1"
            if (!authToken.isNullOrBlank()) {
                environment["ANTHROPIC_AUTH_TOKEN"] = authToken
                if (profile.kind == ProviderKind.LLM_ROUTER) {
                    environment["ANTHROPIC_API_KEY"] = ""
                    environment["OPENROUTER_API_KEY"] = authToken
                } else {
                    environment["ANTHROPIC_API_KEY"] = authToken
                }
            }
        }
        return RuntimeLaunchConfig(
            executable = "/usr/local/bin/claude",
            arguments = listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"),
            environment = environment,
        )
    }
}
```

## 3. `app/src/main/java/com/nextcraft/forge/runtime/LocalFormatGateway.kt` (752 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.util.Log
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.ProviderProfile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Small loopback-only Anthropic-to-OpenAI compatibility bridge for Claude Code. */
internal class LocalFormatGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
    private val onRotateApiKey: (() -> String?)? = null,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val rotationLock = Any()
    @Volatile private var activeApiKey: String = apiKey
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"
    private val isResponsesProtocol: Boolean
        get() = profile.kind.protocol == ProviderProtocol.OPENAI_RESPONSES ||
            profile.resolvedBaseUrl.trim().trimEnd('/').endsWith("/responses")

    fun start(): LocalFormatGateway = apply {
        Thread({ acceptLoop() }, "forge-format-gateway").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                runCatching {
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.tcpNoDelay = true
                }
                Thread({ socket.use(::handle) }, "forge-format-request").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        if (!isAuthorized(headers)) {
            writeJson(output, 401, errorJson("authentication_error", "Missing or invalid credentials"))
            return
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_REQUEST_BYTES) {
            writeJson(output, 413, errorJson("invalid_request_error", "Request body is too large"))
            return
        }
        val bodyBytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bodyBytes, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        if (offset < length) {
            writeJson(output, 400, errorJson("invalid_request_error", "Truncated request body"))
            return
        }
        val bodyText = bodyBytes.decodeToString(0, offset)
        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        if (path.endsWith("/count_tokens")) {
            val approximate = bodyText.length / 4 + 1
            writeJson(output, 200, JSONObject().put("input_tokens", approximate).toString())
            return
        }
        if (!path.endsWith("/messages")) {
            writeJson(output, 404, errorJson("not_found", "Unsupported gateway endpoint"))
            return
        }
        runCatching {
            val anthropic = JSONObject(bodyText)
            val requestedModel = anthropic.optString("model", profile.model)
            val wantsStream = anthropic.optBoolean("stream", false)
            if (wantsStream && !isResponsesProtocol) {
                streamFromProvider(anthropic, requestedModel, output)
                return@runCatching
            }
            val upstream = callProvider(toOpenAi(anthropic))
            if (upstream.first !in 200..299) {
                Log.w("FormatGateway", "Provider returned HTTP ${upstream.first}: ${providerError(upstream.second)}")
                writeJson(output, upstream.first, errorJson("api_error", providerError(upstream.second)))
            } else {
                val translated = fromOpenAi(JSONObject(upstream.second), requestedModel)
                if (wantsStream) writeStream(output, translated) else writeJson(output, 200, translated.toString())
            }
        }.onFailure { error ->
            writeJson(output, 502, errorJson("api_error", error.message ?: "Provider request failed"))
        }
    }

    private fun toOpenAi(source: JSONObject): JSONObject {
        if (isResponsesProtocol) return toOpenAiResponses(source)
        val target = JSONObject()
            .put("model", normalizeModel(profile.model))
            .put("stream", false)
            .put("max_tokens", source.optInt("max_tokens", 4096))
        if (source.has("temperature")) target.put("temperature", source.get("temperature"))
        val messages = JSONArray()
        source.opt("system")?.let { system ->
            val text = when (system) {
                is JSONArray -> contentText(system)
                else -> system.toString()
            }
            if (text.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", text))
        }
        val sourceMessages = source.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until sourceMessages.length()) {
            val message = sourceMessages.getJSONObject(index)
            val role = message.optString("role")
            val content = message.opt("content")
            if (content !is JSONArray) {
                messages.put(JSONObject().put("role", role).put("content", content ?: ""))
                continue
            }
            val text = contentText(content)
            val toolCalls = JSONArray()
            val toolResults = mutableListOf<JSONObject>()
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                when (part.optString("type")) {
                    "tool_use" -> toolCalls.put(
                        JSONObject().put("id", part.optString("id"))
                            .put("type", "function")
                            .put("function", JSONObject().put("name", part.optString("name")).put("arguments", part.optJSONObject("input")?.toString() ?: "{}")),
                    )
                    "tool_result" -> toolResults += JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", part.optString("tool_use_id"))
                        .put("content", valueText(part.opt("content")))
                }
            }
            if (role == "user") {
                toolResults.forEach(messages::put)
            }
            if (text.isNotBlank() || toolCalls.length() > 0) {
                val converted = JSONObject().put("role", role).put("content", text.ifBlank { JSONObject.NULL })
                if (toolCalls.length() > 0) converted.put("tool_calls", toolCalls)
                messages.put(converted)
            }
            if (role != "user") {
                toolResults.forEach(messages::put)
            }
        }
        target.put("messages", messages)
        source.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                converted.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject()
                            .put("name", tool.optString("name"))
                            .put("description", tool.optString("description"))
                            .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object")),
                    ),
                )
            }
            target.put("tools", converted).put("tool_choice", "auto")
        }
        return target
    }

    private fun toOpenAiResponses(source: JSONObject): JSONObject {
        val target = JSONObject()
            .put("model", normalizeModel(profile.model))
            .put("stream", false)
            .put("max_output_tokens", source.optInt("max_tokens", 4096))
        if (source.has("temperature")) target.put("temperature", source.get("temperature"))
        source.opt("system")?.let { system ->
            val text = when (system) {
                is JSONArray -> contentText(system)
                else -> system.toString()
            }
            if (text.isNotBlank()) target.put("instructions", text)
        }
        val input = JSONArray()
        val sourceMessages = source.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until sourceMessages.length()) {
            val message = sourceMessages.getJSONObject(index)
            val role = message.optString("role")
            val content = message.opt("content")
            if (content !is JSONArray) {
                input.put(JSONObject().put("role", role).put("content", content?.toString().orEmpty()))
                continue
            }
            val text = contentText(content)
            val toolCalls = mutableListOf<JSONObject>()
            val toolResults = mutableListOf<JSONObject>()
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                when (part.optString("type")) {
                    "tool_use" -> toolCalls += JSONObject()
                        .put("type", "function_call")
                        .put("call_id", part.optString("id"))
                        .put("name", part.optString("name"))
                        .put("arguments", part.optJSONObject("input")?.toString() ?: "{}")
                    "tool_result" -> toolResults += JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", part.optString("tool_use_id"))
                        .put("output", valueText(part.opt("content")))
                }
            }
            if (role == "user") {
                toolResults.forEach(input::put)
            }
            if (text.isNotBlank()) {
                input.put(JSONObject().put("role", role).put("content", text))
            }
            toolCalls.forEach(input::put)
            if (role != "user") {
                toolResults.forEach(input::put)
            }
        }
        target.put("input", input)
        source.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                converted.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", tool.optString("name"))
                        .put("description", tool.optString("description"))
                        .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object")),
                )
            }
            target.put("tools", converted).put("tool_choice", "auto")
        }
        return target
    }

    private fun fromOpenAi(source: JSONObject, model: String): JSONObject {
        if (source.has("output") && !source.has("choices")) {
            return fromOpenAiResponses(source, model)
        }
        val message = source.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
        val content = JSONArray()
        val text = message.optString("content")
        if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
        val calls = message.optJSONArray("tool_calls") ?: JSONArray()
        for (index in 0 until calls.length()) {
            val call = calls.getJSONObject(index)
            val function = call.optJSONObject("function") ?: JSONObject()
            val arguments = runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())
            content.put(
                JSONObject().put("type", "tool_use")
                    .put("id", call.optString("id").ifBlank { "tool_${UUID.randomUUID()}" })
                    .put("name", function.optString("name"))
                    .put("input", arguments),
            )
        }
        val usage = source.optJSONObject("usage") ?: JSONObject()
        return JSONObject().put("id", source.optString("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message").put("role", "assistant").put("model", model)
            .put("content", content).put("stop_reason", if (calls.length() > 0) "tool_use" else "end_turn")
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", usage.optInt("prompt_tokens")).put("output_tokens", usage.optInt("completion_tokens")))
    }

    private fun fromOpenAiResponses(source: JSONObject, model: String): JSONObject {
        val output = source.optJSONArray("output") ?: JSONArray()
        val content = JSONArray()
        var hasToolUse = false
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "message" -> {
                    val parts = item.optJSONArray("content")
                    val text = if (parts != null) contentText(parts) else item.optString("content")
                    if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
                }
                "function_call" -> {
                    hasToolUse = true
                    val arguments = runCatching { JSONObject(item.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                    val callId = item.optString("call_id").ifBlank { item.optString("id") }.ifBlank { "tool_${UUID.randomUUID()}" }
                    content.put(
                        JSONObject().put("type", "tool_use")
                            .put("id", callId)
                            .put("name", item.optString("name"))
                            .put("input", arguments),
                    )
                }
            }
        }
        if (content.length() == 0) {
            val fallbackText = source.optString("output_text")
            if (fallbackText.isNotBlank()) {
                content.put(JSONObject().put("type", "text").put("text", fallbackText))
            }
        }
        val usage = source.optJSONObject("usage") ?: JSONObject()
        val inputTokens = usage.optInt("input_tokens", usage.optInt("prompt_tokens"))
        val outputTokens = usage.optInt("output_tokens", usage.optInt("completion_tokens"))
        return JSONObject().put("id", source.optString("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message").put("role", "assistant").put("model", model)
            .put("content", content).put("stop_reason", if (hasToolUse) "tool_use" else "end_turn")
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens))
    }

    private fun callProvider(body: JSONObject, allowRetry: Boolean = true): Pair<Int, String> {
        val rawBase = profile.resolvedBaseUrl.trim().trimEnd('/')
        val endpoint = if (isResponsesProtocol) {
            "${rawBase.removeSuffix("/responses").removeSuffix("/chat/completions")}/responses"
        } else {
            "${rawBase.removeSuffix("/chat/completions")}/chat/completions"
        }
        if (!isPlainHttpAllowedEndpoint(endpoint)) {
            return 400 to errorJson("invalid_request_error", "Plain http:// is only allowed for localhost")
        }
        val currentKey = activeApiKey
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (currentKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $currentKey")
            }
            if (endpoint.startsWith("https://opencode.ai/zen/")) {
                connection.setRequestProperty("User-Agent", "opencode/1.18.20")
                connection.setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (allowRetry && code in setOf(402, 429) && onRotateApiKey != null) {
                val shouldRetry = synchronized(rotationLock) {
                    if (activeApiKey == currentKey) {
                        val rotated = onRotateApiKey.invoke()?.takeIf { it.isNotBlank() && it != currentKey }
                        if (rotated != null) {
                            activeApiKey = rotated
                            true
                        } else {
                            false
                        }
                    } else {
                        true
                    }
                }
                if (shouldRetry) {
                    connection.disconnect()
                    return callProvider(body, allowRetry = false)
                }
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                buildString {
                    val buffer = CharArray(8 * 1024)
                    while (length < MAX_UPSTREAM_RESPONSE_CHARS) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_UPSTREAM_RESPONSE_CHARS - length))
                        if (count <= 0) break
                        append(buffer, 0, count)
                    }
                }
            }.orEmpty()
            code to responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun streamFromProvider(
        anthropic: JSONObject,
        requestedModel: String,
        output: BufferedOutputStream,
        allowRetry: Boolean = true,
    ) {
        val rawBase = profile.resolvedBaseUrl.trim().trimEnd('/')
        val endpoint = "${rawBase.removeSuffix("/chat/completions")}/chat/completions"
        if (!isPlainHttpAllowedEndpoint(endpoint)) {
            writeJson(output, 400, errorJson("invalid_request_error", "Plain http:// is only allowed for localhost"))
            return
        }
        val body = toOpenAi(anthropic).put("stream", true)
        val currentKey = activeApiKey
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream, application/json")
            if (currentKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $currentKey")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (allowRetry && code in setOf(402, 429) && onRotateApiKey != null) {
                val shouldRetry = synchronized(rotationLock) {
                    if (activeApiKey == currentKey) {
                        val rotated = onRotateApiKey.invoke()?.takeIf { it.isNotBlank() && it != currentKey }
                        if (rotated != null) {
                            activeApiKey = rotated
                            true
                        } else {
                            false
                        }
                    } else {
                        true
                    }
                }
                if (shouldRetry) {
                    connection.disconnect()
                    streamFromProvider(anthropic, requestedModel, output, allowRetry = false)
                    return
                }
            }
            if (code !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(4096) }.orEmpty()
                Log.w("FormatGateway", "Provider returned HTTP $code: ${providerError(errorText)}")
                writeJson(output, code, errorJson("api_error", providerError(errorText)))
                return
            }
            val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            val stream = connection.inputStream ?: run {
                writeJson(output, 502, errorJson("api_error", "Empty response from provider"))
                return
            }
            if (contentType.contains("text/event-stream")) {
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    streamOpenAiSseLines(lines, output, requestedModel)
                }
            } else {
                val responseText = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    buildString {
                        val buffer = CharArray(8 * 1024)
                        while (length < MAX_UPSTREAM_RESPONSE_CHARS) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_UPSTREAM_RESPONSE_CHARS - length))
                            if (count <= 0) break
                            append(buffer, 0, count)
                        }
                    }
                }
                val trimmed = responseText.trimStart()
                if (trimmed.startsWith("data:") || trimmed.startsWith(":")) {
                    streamOpenAiSseLines(responseText.lineSequence(), output, requestedModel)
                } else {
                    val translated = fromOpenAi(JSONObject(responseText), requestedModel)
                    writeStream(output, translated)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun streamOpenAiSseLines(
        lines: Sequence<String>,
        output: BufferedOutputStream,
        requestedModel: String,
    ) {
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray())
        fun emitEvent(name: String, data: JSONObject) {
            output.write("event: $name\ndata: $data\n\n".toByteArray())
            output.flush()
        }
        val msgId = "msg_${UUID.randomUUID()}"
        val startMessage = JSONObject()
            .put("id", msgId)
            .put("type", "message")
            .put("role", "assistant")
            .put("model", requestedModel)
            .put("content", JSONArray())
            .put("stop_reason", JSONObject.NULL)
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0))
        emitEvent("message_start", JSONObject().put("type", "message_start").put("message", startMessage))

        var nextBlockIndex = 0
        var openBlockType: String? = null
        var openBlockIndex = -1
        val activeToolSlots = HashMap<Int, Int>()
        var sawToolCalls = false
        var inputTokens = 0
        var outputTokens = 0

        fun closeOpenBlock() {
            if (openBlockIndex >= 0) {
                emitEvent("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", openBlockIndex))
                openBlockType = null
                openBlockIndex = -1
            }
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") break
            val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            chunk.optJSONObject("usage")?.let { usage ->
                inputTokens = usage.optInt("prompt_tokens", usage.optInt("input_tokens", inputTokens))
                outputTokens = usage.optInt("completion_tokens", usage.optInt("output_tokens", outputTokens))
            }
            val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: continue

            val reasoningDelta = delta.optString("reasoning_content").ifEmpty { delta.optString("reasoning") }
            if (reasoningDelta.isNotEmpty()) {
                if (openBlockType != "thinking") {
                    closeOpenBlock()
                    val idx = nextBlockIndex++
                    openBlockType = "thinking"
                    openBlockIndex = idx
                    emitEvent(
                        "content_block_start",
                        JSONObject()
                            .put("type", "content_block_start")
                            .put("index", idx)
                            .put("content_block", JSONObject().put("type", "thinking").put("thinking", "")),
                    )
                }
                emitEvent(
                    "content_block_delta",
                    JSONObject()
                        .put("type", "content_block_delta")
                        .put("index", openBlockIndex)
                        .put("delta", JSONObject().put("type", "thinking_delta").put("thinking", reasoningDelta)),
                )
            }

            val textDelta = if (delta.isNull("content")) "" else delta.optString("content")
            if (textDelta.isNotEmpty()) {
                if (openBlockType != "text") {
                    closeOpenBlock()
                    val idx = nextBlockIndex++
                    openBlockType = "text"
                    openBlockIndex = idx
                    emitEvent(
                        "content_block_start",
                        JSONObject()
                            .put("type", "content_block_start")
                            .put("index", idx)
                            .put("content_block", JSONObject().put("type", "text").put("text", "")),
                    )
                }
                emitEvent(
                    "content_block_delta",
                    JSONObject()
                        .put("type", "content_block_delta")
                        .put("index", openBlockIndex)
                        .put("delta", JSONObject().put("type", "text_delta").put("text", textDelta)),
                )
            }

            val toolCalls = delta.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                for (tcIdx in 0 until toolCalls.length()) {
                    val tc = toolCalls.optJSONObject(tcIdx) ?: continue
                    sawToolCalls = true
                    val slot = tc.optInt("index", tcIdx)
                    val fn = tc.optJSONObject("function")
                    val id = tc.optString("id")
                    val name = fn?.optString("name").orEmpty()
                    val argsDelta = fn?.optString("arguments").orEmpty()
                    if (slot !in activeToolSlots) {
                        closeOpenBlock()
                        val idx = nextBlockIndex++
                        activeToolSlots[slot] = idx
                        openBlockType = "tool_use"
                        openBlockIndex = idx
                        emitEvent(
                            "content_block_start",
                            JSONObject()
                                .put("type", "content_block_start")
                                .put("index", idx)
                                .put(
                                    "content_block",
                                    JSONObject()
                                        .put("type", "tool_use")
                                        .put("id", id.ifBlank { "tool_${UUID.randomUUID()}" })
                                        .put("name", name)
                                        .put("input", JSONObject()),
                                ),
                        )
                    }
                    if (argsDelta.isNotEmpty()) {
                        val targetIdx = activeToolSlots[slot] ?: openBlockIndex
                        emitEvent(
                            "content_block_delta",
                            JSONObject()
                                .put("type", "content_block_delta")
                                .put("index", targetIdx)
                                .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", argsDelta)),
                        )
                    }
                }
            }
        }

        closeOpenBlock()
        emitEvent(
            "message_delta",
            JSONObject()
                .put("type", "message_delta")
                .put(
                    "delta",
                    JSONObject()
                        .put("stop_reason", if (sawToolCalls) "tool_use" else "end_turn")
                        .put("stop_sequence", JSONObject.NULL),
                )
                .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens)),
        )
        emitEvent("message_stop", JSONObject().put("type", "message_stop"))
    }

    private fun isPlainHttpAllowedEndpoint(url: String): Boolean =
        com.nextcraft.forge.network.LocalNetwork.isPlainHttpAllowed(url)

    private fun writeStream(output: BufferedOutputStream, message: JSONObject) {
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray())
        fun event(name: String, data: JSONObject) {
            output.write("event: $name\ndata: $data\n\n".toByteArray())
            output.flush()
        }
        val content = message.getJSONArray("content")
        event(
            "message_start",
            JSONObject().put("type", "message_start").put(
                "message",
                JSONObject(message.toString()).put("content", JSONArray()).put("stop_reason", JSONObject.NULL),
            ),
        )
        for (index in 0 until content.length()) {
            val block = content.getJSONObject(index)
            val type = block.getString("type")
            val start = if (type == "text") {
                JSONObject().put("type", "text").put("text", "")
            } else {
                JSONObject().put("type", "tool_use").put("id", block.getString("id")).put("name", block.getString("name")).put("input", JSONObject())
            }
            event("content_block_start", JSONObject().put("type", "content_block_start").put("index", index).put("content_block", start))
            val delta = if (type == "text") {
                JSONObject().put("type", "text_delta").put("text", block.getString("text"))
            } else {
                JSONObject().put("type", "input_json_delta").put("partial_json", block.getJSONObject("input").toString())
            }
            event("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", index).put("delta", delta))
            event("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", index))
        }
        event(
            "message_delta",
            JSONObject().put("type", "message_delta").put(
                "delta",
                JSONObject().put("stop_reason", message.getString("stop_reason")).put("stop_sequence", JSONObject.NULL),
            ).put("usage", message.getJSONObject("usage")),
        )
        event("message_stop", JSONObject().put("type", "message_stop"))
        output.flush()
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = if (code in 200..299) "OK" else "Error"
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    /**
     * Only forward requests that present the active provider key.
     */
    private fun isAuthorized(headers: Map<String, String>): Boolean {
        val presented = headers["x-api-key"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            ?: return false
        val presentedBytes = presented.toByteArray()
        return MessageDigest.isEqual(presentedBytes, apiKey.toByteArray())
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (out.size() <= MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (out.size() == 0) null else out.toByteArray().decodeToString()
            if (value == '\n'.code) return out.toByteArray().decodeToString().trimEnd('\r')
            out.write(value)
        }
        return null
    }

    private fun contentText(array: JSONArray): String = buildString {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("type") in setOf("text", "output_text")) append(item.optString("text"))
        }
    }

    private fun valueText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> contentText(value).ifBlank { value.toString() }
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }

    private fun providerError(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().ifBlank { body.take(500) }
    }.getOrDefault(body.take(500))

    private fun normalizeModel(model: String): String = model
        .removePrefix("models/")
        .removePrefix("anthropic/")
        .substringBefore('[')
        .trim()

    private fun errorJson(type: String, message: String) =
        JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", message)).toString()

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 8 * 1024 * 1024
        const val MAX_UPSTREAM_RESPONSE_CHARS = 4 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
```

## 4. `app/src/main/java/com/nextcraft/forge/runtime/OpenRouterRoutingGateway.kt` (232 baris)

```kotlin
package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.ProviderProfile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Adds NextCraft Forge's saved OpenRouter routing policy to Anthropic Messages requests. */
internal fun applyOpenRouterRouting(source: JSONObject, profile: ProviderProfile): JSONObject {
    val providers = profile.openRouterProviders
    if (providers.isEmpty()) return source
    val routing = JSONObject()
        .put("order", JSONArray(providers))
        .put("allow_fallbacks", profile.openRouterAllowFallbacks)
    return source.put("provider", routing)
}

/** Loopback proxy used because coding-agent CLIs do not expose OpenRouter's provider body field. */
internal class OpenRouterRoutingGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
    private val onRotateApiKey: (() -> String?)? = null,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val rotationLock = Any()
    @Volatile private var activeApiKey: String = apiKey
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"

    fun start(): OpenRouterRoutingGateway = apply {
        Thread({ acceptLoop() }, "forge-openrouter-routing").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                runCatching {
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.tcpNoDelay = true
                }
                Thread({ socket.use(::handle) }, "forge-openrouter-request").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase(Locale.US)] = line.substring(split + 1).trim()
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        if (!isAuthorized(headers)) {
            writeJson(output, 401, errorJson("Missing or invalid loopback gateway credentials"))
            return
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_REQUEST_BYTES) {
            writeJson(output, 413, errorJson("Request is too large"))
            return
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        if (offset < length) {
            writeJson(output, 400, errorJson("Truncated request body"))
            return
        }
        if (path.endsWith("/count_tokens")) {
            writeJson(output, 200, JSONObject().put("input_tokens", body.decodeToString(0, offset).length / 4 + 1).toString())
            return
        }
        val isChatCompletions = path == "/chat/completions" || path == "/v1/chat/completions"
        val isMessages = path == "/messages" || path == "/v1/messages"
        if (method != "POST" || (!isMessages && !isChatCompletions)) {
            writeJson(output, 404, errorJson("Unsupported OpenRouter endpoint"))
            return
        }

        runCatching {
            val routedBody = applyOpenRouterRouting(JSONObject(body.decodeToString(0, offset)), profile).toString().toByteArray()
            val normalizedBase = profile.resolvedBaseUrl.trim().trimEnd('/')
                .removeSuffix("/chat/completions")
                .removeSuffix("/messages")
                .removeSuffix("/v1")
            val endpoint = if (isChatCompletions) "$normalizedBase/v1/chat/completions" else "$normalizedBase/v1/messages"
            if (!isPlainHttpAllowedEndpoint(endpoint)) {
                writeJson(output, 400, errorJson("Plain http:// is only allowed for localhost"))
                return
            }
            if (!forwardUpstream(endpoint, headers, routedBody, output, allowRetry = true)) {
                writeJson(output, 502, errorJson("OpenRouter upstream request failed"))
            }
        }.onFailure { error ->
            writeJson(output, 502, errorJson(error.message ?: "OpenRouter request failed"))
        }
    }

    private fun forwardUpstream(
        endpoint: String,
        headers: Map<String, String>,
        routedBody: ByteArray,
        output: BufferedOutputStream,
        allowRetry: Boolean,
    ): Boolean {
        val currentKey = activeApiKey
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", headers["accept"] ?: "application/json")
            connection.setRequestProperty("Authorization", "Bearer $currentKey")
            connection.setRequestProperty("anthropic-version", headers["anthropic-version"] ?: "2023-06-01")
            headers["anthropic-beta"]?.let { connection.setRequestProperty("anthropic-beta", it) }
            connection.outputStream.use { it.write(routedBody) }

            val code = connection.responseCode
            if (allowRetry && code in setOf(402, 429) && onRotateApiKey != null) {
                val shouldRetry = synchronized(rotationLock) {
                    if (activeApiKey == currentKey) {
                        val rotated = onRotateApiKey.invoke()?.takeIf { it.isNotBlank() && it != currentKey }
                        if (rotated != null) {
                            activeApiKey = rotated
                            true
                        } else {
                            false
                        }
                    } else {
                        true
                    }
                }
                if (shouldRetry) {
                    connection.disconnect()
                    return forwardUpstream(endpoint, headers, routedBody, output, allowRetry = false)
                }
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val reason = if (code in 200..299) "OK" else "Error"
            val contentType = connection.contentType ?: "application/json"
            output.write(
                "HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            output.flush()
            stream?.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    output.flush()
                }
            }
            output.flush()
            return true
        } finally {
            connection.disconnect()
        }
    }

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        val presented = headers["x-api-key"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            ?: return false
        val presentedBytes = presented.toByteArray()
        return MessageDigest.isEqual(presentedBytes, apiKey.toByteArray())
    }

    private fun isPlainHttpAllowedEndpoint(url: String): Boolean =
        com.nextcraft.forge.network.LocalNetwork.isPlainHttpAllowed(url)

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = if (code in 200..299) "OK" else "Error"
        output.write(
            "HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (out.size() <= MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (out.size() == 0) null else out.toByteArray().decodeToString()
            if (value == '\n'.code) return out.toByteArray().decodeToString().trimEnd('\r')
            out.write(value)
        }
        return null
    }

    private fun errorJson(message: String): String = JSONObject()
        .put("type", "error")
        .put("error", JSONObject().put("type", "api_error").put("message", message))
        .toString()

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
```

## 5. `app/src/main/java/com/nextcraft/forge/data/ApiKeyVault.kt` (269 baris)

```kotlin
package com.nextcraft.forge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

class ApiKeyVault(context: Context) {
    private val preferences = context.getSharedPreferences("nextcraft_secrets", Context.MODE_PRIVATE)
    private val alias = "nextcraft-provider-key"

    init {
        migrateAuthBoundKeyIfNeeded()
    }

    /**
     * Earlier builds created the master key with setUserAuthenticationRequired(true) and a 300 s
     * validity window, which made every decrypt fail (and self-destruct) a few minutes after each
     * unlock. When such a key is still present, re-encrypt every secret under a fresh unbound key.
     * Decryption of the old key only works shortly after an unlock, so this retries on each launch
     * until it succeeds and never deletes anything on failure.
     */
    @Synchronized
    private fun migrateAuthBoundKeyIfNeeded() {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(alias)) return
            val oldKey = keyStore.getKey(alias, null) as? SecretKey ?: return
            val info = SecretKeyFactory.getInstance(oldKey.algorithm, "AndroidKeyStore")
                .getKeySpec(oldKey, KeyInfo::class.java) as KeyInfo
            if (!info.isUserAuthenticationRequired) return

            // Decrypt everything first; abort (without touching storage) if any entry fails.
            val secretIds = preferences.all.keys
                .filter { it.endsWith(".value") }
                .map { it.removeSuffix(".value") }
            val plaintexts = secretIds.associateWith { storageId ->
                val iv = Base64.decode(preferences.getString("$storageId.iv", null) ?: return, Base64.NO_WRAP)
                val encrypted = Base64.decode(preferences.getString("$storageId.value", null) ?: return, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, oldKey, GCMParameterSpec(128, iv))
                cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            }
            resetKeyStoreKey()
            plaintexts.forEach { (storageId, secret) -> putEncrypted(storageId, secret) }
            android.util.Log.i("ApiKeyVault", "Migrated ${plaintexts.size} secrets to an unbound keystore key")
        }.onFailure { error ->
            android.util.Log.w("ApiKeyVault", "Keystore migration deferred: ${error::class.simpleName}")
        }
    }

    @Synchronized
    fun put(providerId: String, secret: String) {
        if (secret.isBlank()) return
        val entries = ensurePool(providerId)
        val active = entries.firstOrNull { it.id == activeId(providerId) } ?: entries.firstOrNull()
        if (active == null) {
            add(providerId, "Primary", secret)
        } else {
            putEncrypted(secretKey(providerId, active.id), secret)
        }
    }

    @Synchronized
    fun add(providerId: String, name: String, secret: String): ApiKeyInfo {
        require(secret.isNotBlank()) { "API key cannot be empty" }
        val entries = ensurePool(providerId).toMutableList()
        val entry = ApiKeyInfo(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "API key ${entries.size + 1}" }.take(60),
            isActive = entries.isEmpty(),
        )
        putEncrypted(secretKey(providerId, entry.id), secret)
        entries += entry.copy(isActive = false)
        savePool(providerId, entries)
        if (entries.size == 1) setActiveId(providerId, entry.id)
        return entry.copy(isActive = entries.size == 1)
    }

    @Synchronized
    fun list(providerId: String): List<ApiKeyInfo> {
        val entries = ensurePool(providerId)
        val active = activeId(providerId) ?: entries.firstOrNull()?.id
        return entries.map { it.copy(isActive = it.id == active) }
    }

    @Synchronized
    fun credentials(providerId: String): List<ApiKeyCredential> = list(providerId).mapNotNull { info ->
        getEncrypted(secretKey(providerId, info.id))?.let { secret ->
            ApiKeyCredential(info.id, info.name, secret, info.isActive)
        }
    }.sortedByDescending(ApiKeyCredential::isActive)

    @Synchronized
    fun activate(providerId: String, keyId: String): Boolean {
        if (ensurePool(providerId).none { it.id == keyId }) return false
        setActiveId(providerId, keyId)
        return true
    }

    /**
     * Rotates the active key to the next available key in the provider's pool
     * (used for automatic failover on HTTP 429 rate-limit or 402 quota errors).
     */
    @Synchronized
    fun rotateNext(providerId: String): String? {
        val entries = list(providerId)
        if (entries.size <= 1) return get(providerId)
        val currentIdx = entries.indexOfFirst { it.isActive }.coerceAtLeast(0)
        val nextEntry = entries[(currentIdx + 1) % entries.size]
        setActiveId(providerId, nextEntry.id)
        return getEncrypted(secretKey(providerId, nextEntry.id))
    }

    @Synchronized
    fun remove(providerId: String, keyId: String) {
        val remaining = ensurePool(providerId).filterNot { it.id == keyId }
        removeEncrypted(secretKey(providerId, keyId))
        savePool(providerId, remaining)
        if (activeId(providerId) == keyId) {
            preferences.edit().remove(activeKey(providerId)).apply()
            remaining.firstOrNull()?.let { setActiveId(providerId, it.id) }
        }
    }

    private fun putEncrypted(storageId: String, secret: String) {
        val plaintext = cleanSecret(secret).toByteArray(Charsets.UTF_8)
        val (iv, encrypted) = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.iv to cipher.doFinal(plaintext)
        }.getOrElse { error ->
            // Never reset the master key here: that would make every other stored secret
            // (all provider keys + GITHUB_TOKEN) unreadable. Surface the failure instead.
            android.util.Log.w("ApiKeyVault", "Encrypt failed: ${error::class.simpleName}")
            throw IllegalStateException("Could not encrypt the API key. Unlock the device and try again.", error)
        }
        preferences.edit()
            .putString("$storageId.iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("$storageId.value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun contains(providerId: String): Boolean = get(providerId) != null

    @Synchronized
    fun remove(providerId: String) {
        ensurePool(providerId).forEach { removeEncrypted(secretKey(providerId, it.id)) }
        preferences.edit()
            .remove("$providerId.iv")
            .remove("$providerId.value")
            .remove(poolKey(providerId))
            .remove(activeKey(providerId))
            .apply()
    }

    @Synchronized
    fun get(providerId: String): String? {
        val active = list(providerId).firstOrNull { it.isActive } ?: return null
        return getEncrypted(secretKey(providerId, active.id))
    }

    private fun getEncrypted(storageId: String): String? {
        val ivStr = preferences.getString("$storageId.iv", null) ?: return null
        val valStr = preferences.getString("$storageId.value", null) ?: return null
        return runCatching {
            val iv = Base64.decode(ivStr, Base64.NO_WRAP)
            val encrypted = Base64.decode(valStr, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cleanSecret(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
        }.onFailure { error ->
            // Only drop ciphertext that is provably corrupt. Transient keystore failures
            // (device locked, keystore daemon busy) must not destroy the user's secrets.
            android.util.Log.w("ApiKeyVault", "Decrypt failed: ${error::class.simpleName}")
            if (error is javax.crypto.AEADBadTagException || error is IllegalArgumentException) {
                removeEncrypted(storageId)
            }
        }.getOrNull()
    }

    /**
     * API keys and OAuth tokens never contain whitespace. Terminals often wrap long tokens such as
     * `claude setup-token` output, so pasted secrets can include line breaks or a "Bearer " prefix.
     */
    private fun cleanSecret(secret: String): String =
        secret.trim().removePrefix("Bearer ").removePrefix("bearer ").filterNot(Char::isWhitespace)

    private fun removeEncrypted(storageId: String) {
        preferences.edit().remove("$storageId.iv").remove("$storageId.value").apply()
    }

    private fun ensurePool(providerId: String): List<ApiKeyInfo> {
        readPool(providerId).takeIf(List<ApiKeyInfo>::isNotEmpty)?.let { return it }
        if (!preferences.contains("$providerId.value")) return emptyList()
        val legacySecret = getEncrypted(providerId) ?: return emptyList()
        val legacy = ApiKeyInfo("legacy", "Primary", true)
        putEncrypted(secretKey(providerId, legacy.id), legacySecret)
        removeEncrypted(providerId)
        savePool(providerId, listOf(legacy))
        setActiveId(providerId, legacy.id)
        return listOf(legacy)
    }

    private fun readPool(providerId: String): List<ApiKeyInfo> = runCatching {
        val rawPool = preferences.getString(poolKey(providerId), "[]").orEmpty().ifBlank { "[]" }
        val array = JSONArray(rawPool)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                val id = item.optString("id")
                if (id.isBlank()) null else ApiKeyInfo(id, item.optString("name").ifBlank { "API key" })
            }
        }
    }.getOrDefault(emptyList())

    private fun savePool(providerId: String, entries: List<ApiKeyInfo>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        preferences.edit().putString(poolKey(providerId), array.toString()).apply()
    }

    private fun activeId(providerId: String): String? = preferences.getString(activeKey(providerId), null)
    private fun setActiveId(providerId: String, id: String) = preferences.edit().putString(activeKey(providerId), id).apply()
    private fun poolKey(providerId: String) = "$providerId.pool"
    private fun activeKey(providerId: String) = "$providerId.active"
    private fun secretKey(providerId: String, id: String) = "$providerId.pool.$id"

    private fun resetKeyStoreKey() {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            // An existing key that cannot be loaded right now is a transient keystore error.
            // Do not delete it: that would orphan every ciphertext written with it.
            return keyStore.getKey(alias, null) as? SecretKey
                ?: throw IllegalStateException("AndroidKeyStore key '$alias' is unavailable")
        }
        // No user-authentication binding: the app never shows a BiometricPrompt/Keyguard, so an
        // auth-bound key would throw UserNotAuthenticatedException minutes after each unlock.
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}

data class ApiKeyInfo(val id: String, val name: String, val isActive: Boolean = false)

data class ApiKeyCredential(val id: String, val name: String, val secret: String, val isActive: Boolean)
```

## 6. `app/src/main/java/com/nextcraft/forge/data/AppPreferences.kt` (521 baris)

```kotlin
package com.nextcraft.forge.data

import android.content.Context
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.ChatAttachment
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.defaultDshApiForProvider
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.model.providersForAgent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

class AppPreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences("nextcraft_preferences", Context.MODE_PRIVATE)

    var onboardingComplete: Boolean
        get() = preferences.getBoolean("onboarding_complete", false)
        set(value) { preferences.edit().putBoolean("onboarding_complete", value).apply() }

    var runtimeSetupComplete: Boolean
        get() = preferences.getBoolean("runtime_setup_complete", false)
        set(value) { preferences.edit().putBoolean("runtime_setup_complete", value).apply() }

    var backgroundSetupComplete: Boolean
        get() = preferences.getBoolean("background_setup_complete", false)
        set(value) { preferences.edit().putBoolean("background_setup_complete", value).apply() }

    var initialLanguageSelected: Boolean
        get() = preferences.getBoolean("initial_language_selected", onboardingComplete || runtimeSetupComplete)
        set(value) { preferences.edit().putBoolean("initial_language_selected", value).apply() }

    /** Coding agent engine the user picked during setup. Absent = pre-agent-choice install → Claude. */
    var agentKind: String
        get() = preferences.getString("agent_kind", AgentKind.CLAUDE_CODE.stableId) ?: AgentKind.CLAUDE_CODE.stableId
        set(value) { preferences.edit().putString("agent_kind", value).apply() }

    /** Agent chosen for the initial runtime installation; used as the leading UI tab. */
    var primaryAgentKind: String
        get() = preferences.getString("primary_agent_kind", "") ?: ""
        set(value) { preferences.edit().putString("primary_agent_kind", value).apply() }

    var antigravityModel: String
        get() = preferences.getString("agent_antigravity_model", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_model", value).apply() }

    var antigravityEffort: String
        get() = preferences.getString("agent_antigravity_effort", "high") ?: "high"
        set(value) { preferences.edit().putString("agent_antigravity_effort", value).apply() }

    var antigravitySignedIn: Boolean
        get() = preferences.getBoolean("agent_antigravity_signed_in", false)
        set(value) { preferences.edit().putBoolean("agent_antigravity_signed_in", value).apply() }

    var antigravityAccountEmail: String
        get() = preferences.getString("agent_antigravity_account_email", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_account_email", value).apply() }

    var githubLogin: String
        get() = preferences.getString("github_login", "") ?: ""
        set(value) { preferences.edit().putString("github_login", value).apply() }

    /** Auto-commit and push the workspace to GitHub after every agent turn. Off by default. */
    var autoSyncGitHub: Boolean
        get() = preferences.getBoolean("auto_sync_github", false)
        set(value) { preferences.edit().putBoolean("auto_sync_github", value).apply() }

    /** Whether high-risk tool executions prompt for interactive approval instead of immediate rejection. */
    var interactiveHighRiskApprovals: Boolean
        get() = preferences.getBoolean("interactive_high_risk_approvals", true)
        set(value) { preferences.edit().putBoolean("interactive_high_risk_approvals", value).apply() }

    fun saveAgentConversation(agent: AgentKind, projectId: String, chatId: String, conversationId: String?) {
        val key = agentConversationKey(agent, projectId, chatId)
        preferences.edit().apply {
            if (conversationId.isNullOrBlank()) remove(key) else putString(key, conversationId)
        }.commit()
    }

    fun loadAgentConversation(agent: AgentKind, projectId: String, chatId: String): String? =
        preferences.getString(agentConversationKey(agent, projectId, chatId), null)

    fun clearAgentConversations(agent: AgentKind) {
        val stable = agent.stableId
        val keys = preferences.all.keys.filter { key ->
            key.startsWith("agent_conversation_${stable}_") ||
                key.startsWith("agent_conversation_v2_${stable}_")
        }
        if (keys.isEmpty()) return
        preferences.edit().apply { keys.forEach(::remove) }.apply()
    }

    private fun agentConversationKey(agent: AgentKind, projectId: String, chatId: String): String {
        val version = if (agent == AgentKind.ANTIGRAVITY) "v2_" else ""
        return "agent_conversation_${version}${agent.stableId}_${projectId}_$chatId"
    }

    /** Pinned dsh version recorded when DeepSeek Harness was installed. */
    var dshVersion: String
        get() = preferences.getString("dsh_version", "") ?: ""
        set(value) { preferences.edit().putString("dsh_version", value).apply() }

    var themeMode: String
        get() = preferences.getString("theme_mode", "dark") ?: "dark"
        set(value) { preferences.edit().putString("theme_mode", value).apply() }

    var languageCode: String
        get() = preferences.getString("language_code", "system") ?: "system"
        set(value) { preferences.edit().putString("language_code", value).apply() }

    var legacySeededCredentialRemoved: Boolean
        get() = preferences.getBoolean("legacy_seeded_credential_removed", false)
        set(value) { preferences.edit().putBoolean("legacy_seeded_credential_removed", value).apply() }

    var testProviderDefaultsVersion: Int
        get() = preferences.getInt("test_provider_defaults_version", 0)
        set(value) { preferences.edit().putInt("test_provider_defaults_version", value).apply() }

    var lastAppUpdateCheckMillis: Long
        get() = preferences.getLong("last_app_update_check_millis", 0L)
        set(value) { preferences.edit().putLong("last_app_update_check_millis", value).apply() }

    var debugUpdateManifestUrl: String
        get() = preferences.getString("debug_update_manifest_url", "") ?: ""
        set(value) { preferences.edit().putString("debug_update_manifest_url", value).apply() }

    /** Development stacks the user picked during onboarding (names of DevStack). */
    var selectedDevStacks: Set<String>
        get() {
            val raw = preferences.getString("selected_dev_stacks", null) ?: return emptySet()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
            }.getOrDefault(emptySet())
        }
        set(value) {
            val arr = JSONArray()
            value.sorted().forEach(arr::put)
            preferences.edit().putString("selected_dev_stacks", arr.toString()).apply()
        }

    fun saveProvider(profile: ProviderProfile, agent: AgentKind? = null) {
        val editor = preferences.edit()
            .putString("provider_kind", profile.kind.name)
            .putString("provider_base_url", profile.baseUrl)
            .putString("provider_model", profile.model)
            .putString("provider_dsh_api", profile.dshApi)
            .putString("provider_openrouter_order", profile.openRouterProviderOrder)
            .putBoolean("provider_openrouter_allow_fallbacks", profile.openRouterAllowFallbacks)
        if (agent != null) {
            val prefix = providerPrefix(agent)
            editor
                .putString("${prefix}kind", profile.kind.name)
                .putString("${prefix}base_url", profile.baseUrl)
                .putString("${prefix}model", profile.model)
                .putString("${prefix}dsh_api", profile.dshApi)
                .putString("${prefix}openrouter_order", profile.openRouterProviderOrder)
                .putBoolean("${prefix}openrouter_allow_fallbacks", profile.openRouterAllowFallbacks)
        }
        editor.apply()
    }

    fun loadProvider(vault: ApiKeyVault, agent: AgentKind? = null): ProviderProfile {
        val prefix = agent?.let(::providerPrefix)
        val hasAgentProfile = prefix != null && preferences.contains("${prefix}kind")
        val sourcePrefix = if (hasAgentProfile) prefix.orEmpty() else "provider_"
        val storedKind = runCatching {
            ProviderKind.valueOf(preferences.getString("${sourcePrefix}kind", null).orEmpty())
        }.getOrNull()
        val storedBaseUrl = storedKind?.let {
            preferences.getString("${sourcePrefix}base_url", it.defaultBaseUrl) ?: it.defaultBaseUrl
        }.orEmpty()
        val storedModel = storedKind?.let {
            preferences.getString("${sourcePrefix}model", it.defaultModel) ?: it.defaultModel
        }.orEmpty()
        val legacyClaudeDefaultInDeepSeek = agent == AgentKind.DEEPSEEK_HARNESS &&
            storedKind == ProviderKind.ANTHROPIC &&
            !vault.contains(ProviderKind.ANTHROPIC.name) &&
            storedBaseUrl == ProviderKind.ANTHROPIC.defaultBaseUrl &&
            storedModel == ProviderKind.ANTHROPIC.defaultModel
        val kind = when {
            agent == null -> storedKind ?: ProviderKind.ANTHROPIC
            agent == AgentKind.DEEPSEEK_HARNESS && (!hasAgentProfile || legacyClaudeDefaultInDeepSeek) -> ProviderKind.DEEPSEEK
            storedKind != null && storedKind in providersForAgent(agent) -> storedKind
            agent == AgentKind.DEEPSEEK_HARNESS -> ProviderKind.DEEPSEEK
            else -> ProviderKind.ANTHROPIC
        }
        val useStoredValues = storedKind == kind
        val savedModel = if (useStoredValues) {
            preferences.getString("${sourcePrefix}model", kind.defaultModel) ?: kind.defaultModel
        } else {
            kind.defaultModel
        }
        val model = if (
            kind == ProviderKind.DEEPSEEK &&
            savedModel in setOf("deepseek-chat", "deepseek-reasoner")
        ) {
            kind.defaultModel.also {
                preferences.edit().putString("${sourcePrefix}model", it).apply()
            }
        } else {
            savedModel
        }
        return ProviderProfile(
            kind = kind,
            baseUrl = if (useStoredValues) {
                preferences.getString("${sourcePrefix}base_url", kind.defaultBaseUrl) ?: kind.defaultBaseUrl
            } else {
                kind.defaultBaseUrl
            },
            model = model,
            hasSecret = vault.contains(kind.name),
            dshApi = if (useStoredValues) {
                preferences.getString("${sourcePrefix}dsh_api", defaultDshApiForProvider(kind))
                    ?: defaultDshApiForProvider(kind)
            } else {
                defaultDshApiForProvider(kind)
            },
            openRouterProviderOrder = if (useStoredValues) {
                preferences.getString("${sourcePrefix}openrouter_order", "").orEmpty()
            } else {
                ""
            },
            openRouterAllowFallbacks = if (useStoredValues) {
                preferences.getBoolean("${sourcePrefix}openrouter_allow_fallbacks", true)
            } else {
                true
            },
        )
    }

    private fun providerPrefix(agent: AgentKind): String = "provider_${agent.stableId.replace('-', '_')}_"

    fun saveProjects(projects: List<Project>) {
        val arr = JSONArray()
        projects.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
                put("language", p.language)
                put("slug", p.slug)
                put("rootPath", p.rootPath)
                put("updatedAtMillis", p.updatedAtMillis)
                put("kind", p.kind.name)
                put("type", p.type.name)
                p.androidTemplate?.let { put("androidTemplate", it.name) }
            })
        }
        preferences.edit().putString("projects_json", arr.toString()).apply()
    }

    fun loadProjects(): List<Project> {
        val raw = preferences.getString("projects_json", null) ?: return emptyList()
        var needsSave = false
        val list = runCatching {
            val arr = JSONArray(raw)
            val usedSlugs = mutableSetOf<String>()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val storedKind = obj.optString("kind", ProjectKind.PROJECT.name)
                if (!obj.has("kind") || storedKind == "QUICK_CHAT") needsSave = true
                val requestedSlug = obj.optString("slug").ifBlank { projectSlug(name) }
                var slug = requestedSlug
                if (!usedSlugs.add(slug)) {
                    slug = "$requestedSlug-${id.take(6)}"
                    var suffix = 2
                    while (!usedSlugs.add(slug)) slug = "$requestedSlug-${suffix++}"
                }
                if (slug != obj.optString("slug")) needsSave = true
                var millis = obj.optLong("updatedAtMillis", 0L)
                if (millis <= 0L) {
                    val workspaceDir = File(context.filesDir, "workspaces/$id")
                    millis = if (workspaceDir.exists() && workspaceDir.lastModified() > 0L) {
                        workspaceDir.lastModified()
                    } else {
                        System.currentTimeMillis() - 3600_000L
                    }
                    needsSave = true
                }
                val workspaceBase = File(context.filesDir, "workspaces").canonicalFile
                val rawRoot = obj.optString("rootPath", "")
                val validRoot = rawRoot.takeIf { root ->
                    if (root.isBlank()) {
                        true
                    } else if (root.startsWith('/') || root.contains("..") || root.contains('\u0000')) {
                        false
                    } else {
                        runCatching {
                            val candidate = File(workspaceBase, root).canonicalFile
                            candidate.toPath().startsWith(workspaceBase.toPath())
                        }.getOrDefault(false)
                    }
                } ?: ""
                Project(
                    id = id,
                    name = name,
                    description = obj.optString("description", ""),
                    language = obj.optString("language", ""),
                    slug = slug,
                    rootPath = validRoot,
                    updatedAtMillis = millis,
                    kind = when (storedKind) {
                        "QUICK_CHAT" -> ProjectKind.QUICK_PROJECT
                        else -> runCatching { ProjectKind.valueOf(storedKind) }
                            .getOrDefault(ProjectKind.PROJECT)
                    },
                    type = runCatching {
                        ProjectType.valueOf(obj.optString("type", ProjectType.GENERAL.name))
                    }.getOrDefault(ProjectType.GENERAL),
                    androidTemplate = obj.optString("androidTemplate").takeIf(String::isNotBlank)?.let {
                        runCatching { AndroidTemplate.valueOf(it) }.getOrNull()
                    },
                )
            }
        }.getOrDefault(emptyList())

        if (needsSave && list.isNotEmpty()) {
            saveProjects(list)
        }
        return list
    }

    private val chatsDir = File(context.filesDir, "chats").also { it.mkdirs() }

    private fun safeStorageId(id: String): String {
        val trimmed = id.trim()
        require(
            trimmed.isNotEmpty() &&
                trimmed != "." &&
                trimmed != ".." &&
                !trimmed.contains('/') &&
                !trimmed.contains('\\') &&
                !trimmed.contains("..") &&
                !trimmed.contains('\u0000'),
        ) { "Unsafe storage identifier" }
        return trimmed
    }

    @Synchronized
    fun saveProjectChats(projectId: String, chats: List<ProjectChat>) {
        val safeProject = safeStorageId(projectId)
        val projectDir = File(chatsDir, safeProject).also { it.mkdirs() }
        val arr = JSONArray()
        chats.forEach { chat ->
            arr.put(JSONObject().apply {
                put("id", safeStorageId(chat.id))
                put("title", chat.title)
                put("createdAtMillis", chat.createdAtMillis)
                put("updatedAtMillis", chat.updatedAtMillis)
            })
        }
        val destination = File(projectDir, "index.json")
        val temporary = File(projectDir, ".index.json.tmp")
        temporary.writeText(arr.toString())
        if (!temporary.renameTo(destination)) {
            android.util.Log.w("AppPreferences", "Atomic rename failed for ${destination.name}; falling back to copy")
            temporary.inputStream().use { src -> destination.outputStream().use { dst -> src.copyTo(dst) } }
            temporary.delete()
        }
    }

    @Synchronized
    fun loadProjectChats(projectId: String): List<ProjectChat> {
        val safeProject = safeStorageId(projectId)
        val projectDir = File(chatsDir, safeProject).also { it.mkdirs() }
        val index = File(projectDir, "index.json")
        if (index.exists()) {
            return runCatching {
                val arr = JSONArray(index.readText())
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ProjectChat(
                        id = safeStorageId(obj.getString("id")),
                        title = obj.optString("title", "Chat"),
                        createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                        updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis()),
                    )
                }.sortedByDescending { it.updatedAtMillis }
            }.getOrDefault(emptyList())
        }

        val legacy = File(chatsDir, "$safeProject.json")
        val legacyMessages = loadLegacyMessages(legacy)
        val now = System.currentTimeMillis()
        val chat = ProjectChat(
            id = "main",
            title = legacyMessages.firstOrNull { it.fromUser }?.text?.toChatTitle() ?: "Main chat",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        saveProjectChats(safeProject, listOf(chat))
        if (legacyMessages.isNotEmpty()) saveMessages(safeProject, chat.id, legacyMessages)
        return listOf(chat)
    }

    @Synchronized
    fun saveMessages(projectId: String, chatId: String, messages: List<ChatMessage>) {
        val safeProject = safeStorageId(projectId)
        val safeChat = safeStorageId(chatId)
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("fromUser", m.fromUser)
                put("text", m.text)
                put("createdAt", m.createdAt.toString())
                put("attachments", JSONArray().apply {
                    m.attachments.forEach { attachment ->
                        put(JSONObject().apply {
                            put("id", attachment.id)
                            put("displayName", attachment.displayName)
                            put("relativePath", attachment.relativePath)
                            put("mimeType", attachment.mimeType)
                            put("sizeBytes", attachment.sizeBytes)
                        })
                    }
                })
                put("workedMillis", m.workedMillis)
                put("workItems", JSONArray().apply {
                    m.workItems.forEach { item ->
                        put(JSONObject().apply {
                            put("title", item.title)
                            put("detail", item.detail)
                            put("isComplete", item.isComplete)
                            put("isCommand", item.isCommand)
                        })
                    }
                })
            })
        }
        val projectDir = File(chatsDir, safeProject).also { it.mkdirs() }
        val destination = File(projectDir, "$safeChat.json")
        val temporary = File(projectDir, ".$safeChat.json.tmp")
        temporary.writeText(arr.toString())
        if (!temporary.renameTo(destination)) {
            android.util.Log.w("AppPreferences", "Atomic rename failed for ${destination.name}; falling back to copy")
            temporary.inputStream().use { src -> destination.outputStream().use { dst -> src.copyTo(dst) } }
            temporary.delete()
        }
    }

    @Synchronized
    fun loadMessages(projectId: String, chatId: String): List<ChatMessage> {
        val safeProject = safeStorageId(projectId)
        val safeChat = safeStorageId(chatId)
        val file = File(File(chatsDir, safeProject), "$safeChat.json")
        return loadLegacyMessages(file)
    }

    @Synchronized
    fun deleteProjectChats(projectId: String) {
        val safeProject = safeStorageId(projectId)
        File(chatsDir, safeProject).deleteRecursively()
        File(chatsDir, "$safeProject.json").delete()
    }

    private fun loadLegacyMessages(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ChatMessage(
                    id = obj.getString("id"),
                    fromUser = obj.getBoolean("fromUser"),
                    text = obj.getString("text"),
                    createdAt = runCatching { Instant.parse(obj.getString("createdAt")) }
                        .getOrDefault(Instant.now()),
                    attachments = obj.optJSONArray("attachments")?.let { attachments ->
                        (0 until attachments.length()).mapNotNull { index ->
                            runCatching {
                                attachments.getJSONObject(index).let { attachment ->
                                    ChatAttachment(
                                        id = attachment.optString("id").ifBlank { UUID.randomUUID().toString() },
                                        displayName = attachment.getString("displayName"),
                                        relativePath = attachment.getString("relativePath"),
                                        mimeType = attachment.optString("mimeType", "application/octet-stream"),
                                        sizeBytes = attachment.optLong("sizeBytes", 0L),
                                    )
                                }
                            }.getOrNull()
                        }
                    }.orEmpty(),
                    workedMillis = obj.optLong("workedMillis", 0L),
                    workItems = obj.optJSONArray("workItems")?.let { workItems ->
                        (0 until workItems.length()).mapNotNull { index ->
                            runCatching {
                                workItems.getJSONObject(index).let { item ->
                                    ActivityItem(
                                        title = item.optString("title"),
                                        detail = item.optString("detail"),
                                        isComplete = item.optBoolean("isComplete", true),
                                        isCommand = item.optBoolean("isCommand", false),
                                    )
                                }
                            }.getOrNull()
                        }
                    }.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun String.toChatTitle(): String {
        val clean = replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= 42) clean else clean.take(39).trimEnd() + "…"
    }
}
```
