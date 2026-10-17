# Blok 1 — Fondasi Native Process, Root Chroot Ubuntu 24.04 & Model Domain Inti

Blok 1 adalah lapisan paling dasar (pondasi) dari **NextCraft Forge** yang menjembatani aplikasi Android dengan lingkungan Linux Ubuntu 24.04 ARM64 melalui **Root Chroot** (`su` + `chroot`) dan **JNI Native Process Spawner (`libforgespawn.so`)**.

### Fungsi Utama Blok 1:
1. **Model Domain Inti (`Models.kt`)**: Mendefinisikan seluruh struktur data aplikasi, termasuk jenis agen (`CLAUDE_CODE`, `ANTIGRAVITY`, `DEEPSEEK_HARNESS`), profil provider AI (`ProviderKind`, `ProviderProfile`, `ProviderProtocol`), pesan chat (`ChatMessage`), event streaming runtime (`RuntimeEvent`), serta status instalasi toolchain (`DevStack`).
2. **Native Process & PTY Spawner (`forge_spawn.c`, `CMakeLists.txt`, `NativeSpawnProcess.kt`)**:
   - Menjalankan proses Linux secara langsung lewat `fork()` + `execve()` di C dengan alokasi pseudo-terminal (`posix_openpt`, `ptsname_r`, `ioctl(TIOCSWINSZ)`), penutupan seluruh file descriptor bocor (`close_extra_fds` untuk FD >= 3), serta `prctl(PR_SET_PDEATHSIG, SIGTERM)` agar child process otomatis berhenti jika proses induk mati.
   - Memiliki fallback `ProcessBuilder` otomatis di `NativeSpawnProcess.kt` apabila `libforgespawn.so` belum tersedia, serta proteksi kunci sinkronisasi (`synchronized(ptyLock)`) terhadap race condition penutupan descriptor PTY.
3. **Supervisor Root Chroot & Hardening (`chroot-exec.sh`, `unmount-chroot.sh`, `RootfsRepair.kt`, `RuntimeCompatibility.kt`)**:
   - Mengelola bind-mount `/dev`, `/dev/pts`, `/proc`, `/sys`, `/tmp`, dan `/workspace/<slug>` ke dalam rootfs Ubuntu 24.04 dengan reference counting (`flock` + `active.pid`) agar mount point dibersihkan secara aman saat sesi terakhir selesai.
   - Menyuntikkan environment variable rahasia (API key / OAuth token) melalui file sementara bermode `0600` (`O_EXCL`) yang langsung dihapus (`rm -f`) sebelum `exec`, sehingga token tidak pernah bocor di `/proc/<pid>/cmdline`.
   - Memvalidasi kesiapan perangkat (akses `su`, arsitektur `arm64-v8a`, ruang penyimpanan bebas, serta perbaikan rootfs yang rusak).
4. **Instalasi & Ekstraksi Bundel Runtime (`RuntimeInstaller.kt`, `BoundedFileReads.kt`, dan skrip `scripts/runtime-bundles/*`)**:
   - Mengekstrak bundel `.tar.zst` dan `.tar.xz` (Ubuntu rootfs, Node.js, Claude Code, Antigravity CLI `agy`, DeepSeek Harness `dsh`, Python, C/C++, dan Android SDK/Gradle) dengan pemeriksaan anti-Zip-Slip & anti-symlink escape.
   - Membaca file log berukuran besar secara aman tanpa `OutOfMemoryError` (`BoundedFileReads.kt` & `InotifyLogTailer`) serta menjaga keutuhan karakter UTF-8 multi-byte di batas potongan chunk (`completeUtf8PrefixLength`).

## Daftar File dalam Blok Ini
- `app/src/main/java/com/nextcraft/forge/model/Models.kt` (391 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/BoundedFileReads.kt` (195 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/RuntimeCompatibility.kt` (47 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/RootfsRepair.kt` (191 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/NativeSpawnProcess.kt` (233 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/RuntimeInstaller.kt` (2456 baris)
- `app/src/main/cpp/forge_spawn.c` (297 baris)
- `app/src/main/cpp/CMakeLists.txt` (23 baris)
- `scripts/runtime-bundles/chroot-exec.sh` (166 baris)
- `scripts/runtime-bundles/unmount-chroot.sh` (38 baris)
- `scripts/runtime-bundles/build-core-on-rooted-android.sh` (65 baris)
- `scripts/runtime-bundles/build-claude-from-installed-android.sh` (45 baris)
- `scripts/runtime-bundles/build-agy-from-official.sh` (42 baris)
- `scripts/runtime-bundles/build-dsh-from-installed-android.sh` (48 baris)
- `scripts/runtime-bundles/create_deterministic_tar.py` (56 baris)

---

## 1. `app/src/main/java/com/nextcraft/forge/model/Models.kt` (391 baris)

```kotlin
package com.nextcraft.forge.model

import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

enum class ProviderProtocol {
    CLAUDE_LOGIN,
    ANTHROPIC,
    ANTHROPIC_GATEWAY,
    OPENROUTER,
    OPENAI_RESPONSES,
    OPENAI_CHAT,
}

enum class ProviderKind(
    val title: String,
    val subtitle: String,
    val protocol: ProviderProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val experimental: Boolean = false,
    val fixedBaseUrl: Boolean = false,
    val fixedProtocol: Boolean = false,
) {
    CLAUDE("Claude subscription", "Pro, Max, Team or Enterprise", ProviderProtocol.CLAUDE_LOGIN, "", "default"),
    ANTHROPIC("Anthropic API", "Usage billed through Console", ProviderProtocol.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-4-6"),
    LLM_ROUTER("OpenRouter", "Use your OpenRouter API key", ProviderProtocol.OPENROUTER, "https://openrouter.ai/api", "~anthropic/claude-sonnet-latest"),
    DEEPSEEK("DeepSeek", "Use your DeepSeek API key", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.deepseek.com/anthropic", "deepseek-v4-flash"),
    KIMI("Kimi", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.moonshot.ai/anthropic", "kimi-k2.6", true),
    OPENCODE_ZEN(
        "OpenCode Zen",
        "Models through the OpenCode Zen gateway",
        ProviderProtocol.OPENAI_RESPONSES,
        "https://opencode.ai/zen/v1",
        "deepseek-v4-flash",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    NVIDIA_NIM(
        "NVIDIA NIM",
        "OpenAI-compatible models hosted by NVIDIA",
        ProviderProtocol.OPENAI_CHAT,
        "https://integrate.api.nvidia.com/v1",
        "qwen/qwen2.5-coder-32b-instruct",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    CUSTOM("Custom API", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "", "", true),
}

/**
 * Coding agent engine installed in the Ubuntu 24.04 LTS Root Chroot runtime.
 * Each coding agent is installed independently on demand over the shared Core runtime.
 */
enum class AgentKind(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val downloadNote: String,
) {
    CLAUDE_CODE(
        "claude-code",
        "Claude Code",
        "Anthropic's coding agent · broad provider support",
        "71.8 MB",
    ),
    DEEPSEEK_HARNESS(
        "deepseek-harness",
        "DeepSeek Harness",
        "Official DeepSeek coding agent · API-key providers",
        "26.5 MB",
    ),
    ANTIGRAVITY(
        "antigravity",
        "Antigravity CLI",
        "Google's official coding agent · Google account",
        "39.9 MB",
    ),
    ;

    companion object {
        fun fromStored(value: String?): AgentKind = entries.firstOrNull {
            it.stableId == value || it.name == value
        } ?: CLAUDE_CODE
    }
}

/** Provider kinds usable with [AgentKind.DEEPSEEK_HARNESS]. Claude OAuth login has no dsh equivalent. */
val DEEPSEEK_HARNESS_PROVIDERS: Set<ProviderKind> = setOf(
    ProviderKind.DEEPSEEK,
    ProviderKind.ANTHROPIC,
    ProviderKind.LLM_ROUTER,
    ProviderKind.KIMI,
    ProviderKind.OPENCODE_ZEN,
    ProviderKind.NVIDIA_NIM,
    ProviderKind.CUSTOM,
)

val DSH_PROTOCOL_PROVIDERS: Set<ProviderKind> = setOf(
    ProviderKind.KIMI,
    ProviderKind.OPENCODE_ZEN,
    ProviderKind.NVIDIA_NIM,
    ProviderKind.CUSTOM,
)

fun defaultDshApiForProvider(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENCODE_ZEN -> "openai-responses"
    ProviderKind.NVIDIA_NIM -> "openai-completions"
    else -> "anthropic-messages"
}

/**
 * Best-effort protocol choice for a user-entered custom gateway URL.
 * The picker remains editable because a URL alone cannot prove a gateway's wire format.
 */
fun inferredDshApiForUrl(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
    return when {
        normalized.endsWith("/responses") -> "openai-responses"
        "/anthropic" in normalized || "api.anthropic.com" in normalized -> "anthropic-messages"
        normalized.endsWith("/v1") -> "openai-completions"
        else -> "anthropic-messages"
    }
}

/** Resolves the protocol DeepSeek Harness will actually use for this saved profile. */
fun providerProtocolForAgent(profile: ProviderProfile, agent: AgentKind): ProviderProtocol {
    if (agent != AgentKind.DEEPSEEK_HARNESS || profile.kind !in DSH_PROTOCOL_PROVIDERS) {
        return profile.kind.protocol
    }
    val api = if (profile.kind.fixedProtocol) defaultDshApiForProvider(profile.kind) else profile.dshApi
    return when (api) {
        "openai-completions" -> ProviderProtocol.OPENAI_CHAT
        "openai-responses" -> ProviderProtocol.OPENAI_RESPONSES
        else -> ProviderProtocol.ANTHROPIC_GATEWAY
    }
}

/** Provider choices shown for the selected coding agent. */
fun providersForAgent(agent: AgentKind): List<ProviderKind> = when (agent) {
    AgentKind.DEEPSEEK_HARNESS -> ProviderKind.entries.filter { it in DEEPSEEK_HARNESS_PROVIDERS }
    AgentKind.CLAUDE_CODE -> ProviderKind.entries.filterNot { it == ProviderKind.OPENCODE_ZEN }
    AgentKind.ANTIGRAVITY -> emptyList()
}

data class ProviderProfile(
    val kind: ProviderKind,
    val baseUrl: String = kind.defaultBaseUrl,
    val model: String = kind.defaultModel,
    val hasSecret: Boolean = false,
    /** dsh custom-route wire protocol for CUSTOM: anthropic-messages | openai-completions | openai-responses. */
    val dshApi: String = defaultDshApiForProvider(kind),
    /** Comma-separated OpenRouter provider slugs, tried in order. Blank keeps automatic routing. */
    val openRouterProviderOrder: String = "",
    /** Whether OpenRouter may use providers outside [openRouterProviderOrder]. */
    val openRouterAllowFallbacks: Boolean = true,
) {
    /** Effective base URL: fixed kinds always resolve to their constant; blank custom URLs fall back to defaultBaseUrl. */
    val resolvedBaseUrl: String
        get() = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl.trim().ifBlank { kind.defaultBaseUrl }

    val openRouterProviders: List<String>
        get() = openRouterProviderOrder.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
}

enum class ProjectKind { PROJECT, QUICK_PROJECT }

enum class ProjectType { GENERAL, ANDROID }

enum class AndroidTemplate(val label: String) {
    COMPOSE("Jetpack Compose"),
    XML("Kotlin + XML"),
}

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val language: String,
    val slug: String = projectSlug(name),
    val rootPath: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val kind: ProjectKind = ProjectKind.PROJECT,
    val type: ProjectType = ProjectType.GENERAL,
    val androidTemplate: AndroidTemplate? = null,
) {
    val formattedUpdatedAt: String
        get() {
            val diff = System.currentTimeMillis() - updatedAtMillis
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            return when {
                diff < 0 || seconds < 60 -> "Just now"
                minutes < 60 -> "${minutes}m ago"
                hours < 24 -> "${hours}h ago"
                days == 1L -> "Yesterday"
                days < 7 -> "${days}d ago"
                else -> {
                    val sdf = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
                    sdf.format(java.util.Date(updatedAtMillis))
                }
            }
        }
}

fun projectSlug(name: String): String {
    val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(48)
        .trimEnd('-')
    return ascii.ifBlank { "nextcraft" }
}

data class QuickChatIdentity(val displayName: String, val slug: String)

fun generateQuickChatIdentity(usedSlugs: Set<String>, random: Random = Random.Default): QuickChatIdentity {
    val adjectives = listOf("bright", "calm", "clever", "curious", "gentle", "nimble", "quiet", "swift", "wise", "bold")
    val pioneers = listOf("turing", "lovelace", "hopper", "tesla", "curie", "ramanujan", "bose", "kalam", "faraday", "darwin")
    repeat(20) {
        val base = "${adjectives.random(random)}-${pioneers.random(random)}"
        if (base !in usedSlugs) return QuickChatIdentity(base.toDisplayName(), base)
    }
    val base = "${adjectives.random(random)}-${pioneers.random(random)}"
    val slug = generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in usedSlugs }
    return QuickChatIdentity(slug.toDisplayName(), slug)
}

private fun String.toDisplayName(): String = split('-').joinToString(" ") { word ->
    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int,
    val sizeBytes: Long = 0,
    /** Null until the directory's direct children have been counted off the UI thread. */
    val childCount: Int? = null,
)

enum class RiskLevel { SAFE, REVIEW, HIGH }

/**
 * Optional development toolchains the user can pick during onboarding.
 * Node.js, npm, Git, and the selected coding agent are always installed over
 * the shared Ubuntu 24.04 LTS Core Chroot runtime.
 */
enum class DevStack(
    val label: String,
    val description: String,
    val installsSummary: String,
) {
    WEB(
        "Web (JavaScript / TypeScript)",
        "Websites and web apps with HTML, CSS, and JS frameworks.",
        "Node.js and npm (already included)",
    ),
    PYTHON(
        "Python",
        "Scripts, automation, data work, and Python backends.",
        "python3, pip, venv, and build tools",
    ),
    ANDROID(
        "Android (Java / Kotlin)",
        "Build Android app projects at native Chroot speed and install them directly on this phone.",
        "JDK 17, ARM64 Android SDK 36, Build Tools 35, Gradle 8.14.3, and an offline Maven cache",
    ),
    CPP(
        "C / C++",
        "Fast compiled programs, algorithms, and systems code.",
        "gcc, g++, make, cmake, gdb",
    ),
    PHP(
        "PHP",
        "Websites and apps with PHP — classic sites and Laravel projects.",
        "php-cli, common extensions, and Composer",
    ),
}

data class ToolRequest(
    val approvalId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val toolName: String,
    val explanation: String,
    val affectedPaths: List<String> = emptyList(),
    val commandPreview: String? = null,
    val risk: RiskLevel,
)

sealed interface RuntimeEvent {
    val sessionId: String

    data class SessionStarted(override val sessionId: String) : RuntimeEvent
    data class AssistantDelta(override val sessionId: String, val text: String) : RuntimeEvent
    data class ReasoningProgress(override val sessionId: String, val estimatedTokens: Int) : RuntimeEvent
    data class ReasoningSummary(
        override val sessionId: String,
        val summary: String,
        val blockId: Long,
        val startsNewBlock: Boolean = false,
        val isFinal: Boolean = false,
    ) : RuntimeEvent
    data class ToolStarted(
        override val sessionId: String,
        val toolName: String,
        val detail: String,
    ) : RuntimeEvent
    data class ToolProgress(
        override val sessionId: String,
        val toolName: String,
        val detail: String,
    ) : RuntimeEvent
    data class RuntimeLog(
        override val sessionId: String,
        val title: String,
        val detail: String,
    ) : RuntimeEvent
    data class ToolRequested(override val sessionId: String, val request: ToolRequest) : RuntimeEvent
    data class ToolApproved(override val sessionId: String, val approvalId: String) : RuntimeEvent
    data class ToolRejected(override val sessionId: String, val approvalId: String) : RuntimeEvent
    data class ToolCompleted(override val sessionId: String, val toolName: String, val summary: String) : RuntimeEvent
    data class FilesChanged(override val sessionId: String, val changes: List<ChangeItem>) : RuntimeEvent {
        val paths: List<String> get() = changes.map { it.path }
    }
    data class PreviewStarted(override val sessionId: String, val url: String) : RuntimeEvent
    data class SessionCompleted(override val sessionId: String) : RuntimeEvent
    data class SessionFailed(override val sessionId: String, val reason: String) : RuntimeEvent
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val createdAt: Instant = Instant.now(),
    val attachments: List<ChatAttachment> = emptyList(),
    val workItems: List<ActivityItem> = emptyList(),
    val workedMillis: Long = 0L,
)

data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class ProjectChat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

enum class DiffLineType { CONTEXT, ADDITION, DELETION, INFO }

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

data class ChangeItem(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val diffLines: List<DiffLine> = emptyList(),
    val binary: Boolean = false,
    val accepted: Boolean? = null,
)

data class ActivityItem(
    val title: String,
    val detail: String,
    val isComplete: Boolean = true,
    val isCommand: Boolean = false,
)
```

## 2. `app/src/main/java/com/nextcraft/forge/runtime/BoundedFileReads.kt` (195 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.os.FileObserver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Reads at most [maxBytes] from the start of a file without requiring newer Android APIs. */
internal fun File.readHeadBytes(maxBytes: Int): ByteArray {
    if (!isFile || maxBytes <= 0) return ByteArray(0)
    val expected = minOf(length(), maxBytes.toLong()).toInt()
    val bytes = ByteArray(expected)
    var offset = 0
    inputStream().use { input ->
        while (offset < expected) {
            val count = input.read(bytes, offset, expected - offset)
            if (count < 0) break
            offset += count
        }
    }
    return if (offset == expected) bytes else bytes.copyOf(offset)
}

/** Reads at most [maxBytes] from the start of a file as UTF-8 text without splitting trailing multi-byte characters. */
internal fun File.readBoundedText(maxBytes: Int): String {
    val bytes = readHeadBytes(maxBytes)
    if (bytes.isEmpty()) return ""
    val usable = if (length() > bytes.size) completeUtf8PrefixLength(bytes, bytes.size) else bytes.size
    val finalLen = if (usable > 0) usable else bytes.size
    return String(bytes, 0, finalLen, Charsets.UTF_8)
}

/** Reads at most [maxBytes] from the end of a file without allocating for the whole file or corrupting leading UTF-8 characters. */
internal fun File.readTailText(maxBytes: Int): String {
    if (!isFile || maxBytes <= 0) return ""
    return RandomAccessFile(this, "r").use { input ->
        val length = input.length()
        val byteCount = minOf(length, maxBytes.toLong()).toInt()
        if (byteCount <= 0) return@use ""
        val seekOffset = length - byteCount
        input.seek(seekOffset)
        val bytes = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val read = input.read(bytes, offset, byteCount - offset)
            if (read < 0) break
            offset += read
        }
        var start = 0
        if (seekOffset > 0L && offset > 0) {
            // Skip up to 3 leading UTF-8 continuation bytes (10xxxxxx) if the seek landed mid-codepoint.
            while (start < minOf(offset, 3) && (bytes[start].toInt() and 0xC0) == 0x80) {
                start++
            }
        }
        String(bytes, start, offset - start, Charsets.UTF_8)
    }
}

/**
 * Returns the byte length of [bytes] (within `0..length`) that ends on a complete UTF-8 character boundary.
 * Prevents 16 KB stream chunk reads from splitting a 2-, 3-, or 4-byte UTF-8 sequence into `\uFFFD`.
 */
internal fun completeUtf8PrefixLength(bytes: ByteArray, length: Int): Int {
    val limit = minOf(length, bytes.size)
    if (limit <= 0) return 0
    val last = bytes[limit - 1].toInt() and 0xFF
    if ((last and 0x80) == 0) return limit
    val minScan = maxOf(0, limit - 4)
    for (index in (limit - 1) downTo minScan) {
        val b = bytes[index].toInt() and 0xFF
        if ((b and 0xC0) != 0x80) {
            val expectedLen = when {
                (b and 0xE0) == 0xC0 -> 2
                (b and 0xF0) == 0xE0 -> 3
                (b and 0xF8) == 0xF0 -> 4
                else -> 1
            }
            val actualLen = limit - index
            return if (actualLen < expectedLen) index else limit
        }
    }
    return limit
}

/**
 * Low-latency (<2ms) persistent FileChannel + inotify (FileObserver) stream reader.
 * Replaces repeated RandomAccessFile open/close polling in agent runtime bridges.
 */
internal class InotifyLogTailer(
    private val file: File,
    private val maxLineBytes: Int = MAX_TAIL_LINE_BYTES,
) : AutoCloseable {
    private val wakeSignal = LinkedBlockingQueue<Unit>(1)
    private val raf: RandomAccessFile
    private val channel: java.nio.channels.FileChannel
    private val directBuf = ByteBuffer.allocateDirect(16 * 1024)
    private val lineOut = ByteArrayOutputStream(4096)
    private var lineOverflowed = false
    private val observer: FileObserver

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        raf = RandomAccessFile(file, "r")
        try {
            channel = raf.channel
            @Suppress("DEPRECATION")
            observer = object : FileObserver(
                (file.parentFile ?: file).absolutePath,
                MODIFY or CLOSE_WRITE or CREATE,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path == file.name) {
                        wakeSignal.offer(Unit)
                    }
                }
            }
            observer.startWatching()
        } catch (t: Throwable) {
            runCatching { raf.close() }
            throw t
        }
    }

    private fun decodeCurrentLine(): String {
        val bytes = lineOut.toByteArray()
        val overflow = lineOverflowed
        lineOut.reset()
        lineOverflowed = false
        val safeLen = if (overflow) {
            completeUtf8PrefixLength(bytes, bytes.size).let { if (it > 0) it else bytes.size }
        } else {
            bytes.size
        }
        val text = String(bytes, 0, safeLen, StandardCharsets.UTF_8)
        return if (overflow) "$text... [truncated]" else text
    }

    fun hasUnreadBytes(): Boolean = channel.position() < file.length()

    fun hasPendingPartialLine(): Boolean = lineOut.size() > 0 || lineOverflowed

    fun readLines(timeoutMs: Long = 40L, flushIncomplete: Boolean = false): List<String> {
        val result = ArrayList<String>(4)
        drainLines(timeoutMs = timeoutMs, flushIncomplete = flushIncomplete) { line ->
            result.add(line)
        }
        return result
    }

    /**
     * Waits up to [timeoutMs] for an inotify event on [file], then drains all newly appended complete lines.
     */
    fun drainLines(timeoutMs: Long = 40L, flushIncomplete: Boolean = false, onLine: (String) -> Unit) {
        if (timeoutMs > 0L && channel.position() >= file.length()) {
            wakeSignal.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }
        while (true) {
            directBuf.clear()
            val count = channel.read(directBuf)
            if (count <= 0) break
            directBuf.flip()
            for (i in 0 until count) {
                val b = directBuf.get()
                if (b == '\n'.code.toByte()) {
                    onLine(decodeCurrentLine())
                } else if (b != '\r'.code.toByte()) {
                    if (lineOut.size() < maxLineBytes) {
                        lineOut.write(b.toInt())
                    } else {
                        lineOverflowed = true
                    }
                }
            }
        }
        if (flushIncomplete && (lineOut.size() > 0 || lineOverflowed)) {
            onLine(decodeCurrentLine())
        }
    }

    override fun close() {
        runCatching { observer.stopWatching() }
        runCatching { channel.close() }
        runCatching { raf.close() }
    }

    companion object {
        private const val MAX_TAIL_LINE_BYTES = 512 * 1024
    }
}
```

## 3. `app/src/main/java/com/nextcraft/forge/runtime/RuntimeCompatibility.kt` (47 baris)

```kotlin
package com.nextcraft.forge.runtime

import java.util.concurrent.TimeUnit

internal fun supportsArm64Runtime(supportedAbis: Array<String>, osArchitecture: String?): Boolean {
    val kernelIsArm64 = osArchitecture.equals("aarch64", ignoreCase = true) ||
        osArchitecture.equals("arm64", ignoreCase = true)
    return kernelIsArm64 && supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
}

/**
 * Verifies that the device has working root access (`Magisk`, `KernelSU`, or `APatch`)
 * required by NextCraft Forge's Pure Root Chroot engine.
 */
internal fun isRootAccessAvailable(timeoutSeconds: Long = 8L): Boolean = runCatching {
    val proc = ProcessBuilder("su", "-c", "id -u")
        .redirectErrorStream(true)
        .start()
    val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        proc.destroyForcibly()
        return@runCatching false
    }
    val output = proc.inputStream.bufferedReader().use { it.readText().trim() }
    proc.exitValue() == 0 && output.lines().lastOrNull()?.trim() == "0"
}.getOrDefault(false)

/**
 * Disables Android 12–15+ Phantom Process Killer (`max_phantom_processes`) via `su`
 * so background Chroot daemon processes (`Gradle`, `Kotlin`, `Node`, `Claude Code`)
 * are not killed by `ActivityManager` under heavy compilation load.
 */
internal fun disablePhantomProcessKillerViaRoot() {
    runCatching {
        val cmd = """
            /system/bin/device_config set_sync_disabled_for_tests persistent 2>/dev/null || true
            /system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || true
            settings put global settings_enable_monitor_phantom_procs false 2>/dev/null || true
        """.trimIndent()
        val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        if (!proc.waitFor(5L, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        } else {
            runCatching { proc.inputStream.bufferedReader().use { it.readText() } }
        }
    }
}
```

## 4. `app/src/main/java/com/nextcraft/forge/runtime/RootfsRepair.kt` (191 baris)

```kotlin
package com.nextcraft.forge.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Failure-safe activation of a rebuilt Ubuntu 24.04 Chroot environment while
 * retaining the user's `/root` home directory and guaranteeing that all kernel
 * bind-mounts (`/dev`, `/proc`, `/sys`, `/system`, `/apex`, `/workspace`) are
 * unmounted before any directory rename or recursive delete occurs.
 */
internal object RootfsRepair {

    private fun shellSingleQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun runRootCommand(vararg command: String, timeoutSeconds: Long = 15L): Int =
        runCatching {
            val proc = ProcessBuilder(*command).redirectErrorStream(true).start()
            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                -1
            } else {
                runCatching { proc.inputStream.bufferedReader().use { it.readText() } }
                proc.exitValue()
            }
        }.getOrDefault(-1)

    private fun moveDirectory(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        // Never copyRecursively a rootfs: it follows symlinks and drops ownership, modes and
        // special files. A root `mv` preserves all of them (and is a plain rename on one fs).
        if (target.exists()) return false
        val exit = runRootCommand(
            "su", "-c",
            "mv -- ${shellSingleQuote(source.absolutePath)} ${shellSingleQuote(target.absolutePath)}",
            timeoutSeconds = 120L,
        )
        return exit == 0 && target.exists() && !source.exists()
    }

    /**
     * Unmounts every kernel mount inside [targetDir] in reverse mount-depth order
     * across both the master mount namespace (`su -mm`) and default root context (`su`),
     * and throws if `/proc/mounts` still reports any active mount under [targetDir].
     */
    fun ensureUnmounted(targetDir: File): Boolean {
        if (!targetDir.exists()) return true
        val canonicalPath = runCatching { targetDir.canonicalPath }.getOrDefault(targetDir.absolutePath).trimEnd('/')
        require(canonicalPath.length > 1 && canonicalPath.startsWith("/")) {
            "Refusing to operate on invalid rootfs path: '$canonicalPath'"
        }
        val quotedRootfs = shellSingleQuote(canonicalPath)
        val unmountScript = """
            ROOTFS_PATH=$quotedRootfs
            for pass in 1 2 3; do
                MOUNTS=${'$'}(awk -v prefix="${'$'}ROOTFS_PATH/" -v exact="${'$'}ROOTFS_PATH" 'index(${'$'}2, prefix) == 1 || ${'$'}2 == exact { print ${'$'}2 }' /proc/mounts | sort -r)
                [ -z "${'$'}MOUNTS" ] && break
                for mp in ${'$'}MOUNTS; do
                    if [ "${'$'}pass" -lt 3 ]; then
                        umount "${'$'}mp" 2>/dev/null || true
                    else
                        umount -lf "${'$'}mp" 2>/dev/null || umount -f "${'$'}mp" 2>/dev/null || true
                    fi
                done
            done
            if awk -v prefix="${'$'}ROOTFS_PATH/" -v exact="${'$'}ROOTFS_PATH" 'index(${'$'}2, prefix) == 1 || ${'$'}2 == exact { found=1 } END { exit !found }' /proc/mounts; then
                exit 1
            fi
            rm -rf "${'$'}{ROOTFS_PATH}/tmp/.nextcraft-mounts" 2>/dev/null || true
            exit 0
        """.trimIndent()

        val masterCode = runRootCommand("su", "-mm", "-c", unmountScript)
        val defaultCode = runRootCommand("su", "-c", unmountScript)
        check(masterCode == 0 && defaultCode == 0) {
            "Refusing to modify or delete Chroot directory '$canonicalPath' while kernel mounts are still active (master=$masterCode, default=$defaultCode)"
        }
        return true
    }

    fun safeDeleteRecursively(targetDir: File): Boolean {
        if (!targetDir.exists()) return true
        ensureUnmounted(targetDir)
        if (targetDir.deleteRecursively()) return true
        // Fallback to root rm -rf ONLY after ensureUnmounted verified 0 active kernel mounts
        val canonicalPath = targetDir.canonicalPath.trimEnd('/')
        require(canonicalPath.length > 1 && canonicalPath.startsWith("/")) {
            "Refusing to delete invalid path: '$canonicalPath'"
        }
        val quotedPath = shellSingleQuote(canonicalPath)
        val exit = runRootCommand(
            "su",
            "-c",
            "rm -rf --one-file-system -- $quotedPath 2>/dev/null || rm -rf -- $quotedPath",
        )
        return exit == 0 && !targetDir.exists()
    }

    fun reconcileInterruptedSwap(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        if (!backup.exists()) return

        ensureUnmounted(rootfs)
        ensureUnmounted(staging)
        ensureUnmounted(backup)

        if (!rootfs.exists()) {
            check(moveDirectory(backup, rootfs)) { "Could not restore the previous Linux environment" }
            safeDeleteRecursively(staging)
            return
        }

        if (validate(rootfs)) {
            restoreHome(backup, rootfs)
            check(validate(rootfs)) { "Repaired Linux environment failed validation" }
            check(safeDeleteRecursively(backup)) { "Could not remove the previous Linux environment" }
            safeDeleteRecursively(staging)
            return
        }

        rollback(rootfs, staging, backup)
    }

    fun activate(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        require(staging.isDirectory && validate(staging)) {
            "Rebuilt Linux environment failed validation"
        }
        check(!backup.exists()) { "A previous Linux environment repair is still pending" }

        ensureUnmounted(rootfs)
        ensureUnmounted(staging)

        val hadRootfs = rootfs.exists()
        if (hadRootfs) {
            check(moveDirectory(rootfs, backup)) { "Could not preserve the previous Linux environment" }
        }

        try {
            check(moveDirectory(staging, rootfs)) { "Could not activate the rebuilt Linux environment" }
            if (hadRootfs) restoreHome(backup, rootfs)
            check(validate(rootfs)) { "Rebuilt Linux environment failed validation after activation" }
            if (hadRootfs) {
                check(safeDeleteRecursively(backup)) { "Could not remove the previous Linux environment" }
            }
        } catch (error: Throwable) {
            if (hadRootfs && backup.exists()) {
                runCatching { rollback(rootfs, staging, backup) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun restoreHome(backup: File, rootfs: File) {
        val preservedHome = File(backup, "root")
        if (!preservedHome.exists()) return
        val freshHome = File(rootfs, "root")
        if (freshHome.exists()) {
            check(safeDeleteRecursively(freshHome)) { "Could not replace the rebuilt Linux home" }
        }
        check(moveDirectory(preservedHome, freshHome)) { "Could not restore the Linux home" }
    }

    private fun rollback(rootfs: File, staging: File, backup: File) {
        ensureUnmounted(rootfs)
        ensureUnmounted(staging)
        ensureUnmounted(backup)

        val activeHome = File(rootfs, "root")
        val backupHome = File(backup, "root")
        if (!backupHome.exists() && activeHome.exists()) {
            check(moveDirectory(activeHome, backupHome)) { "Could not preserve the Linux home during rollback" }
        }
        if (rootfs.exists()) {
            check(safeDeleteRecursively(rootfs)) { "Could not remove the failed Linux environment" }
        }
        check(moveDirectory(backup, rootfs)) { "Could not restore the previous Linux environment" }
        safeDeleteRecursively(staging)
    }
}
```

## 5. `app/src/main/java/com/nextcraft/forge/runtime/NativeSpawnProcess.kt` (233 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class NativeSpawnProcess private constructor(
    internal val pid: Int,
    internal val outputFile: File,
    private val stdin: OutputStream,
    private val ptyMasterFd: Int = -1,
    private val ptyFdLock: Any = Any(),
    private val isPtyFdOpen: () -> Boolean = { false },
    private val outputPump: Thread? = null,
    private val delegateProcess: Process? = null,
) : Process() {
    private val waitLock = Any()
    @Volatile private var result: Int? = null

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = FileInputStream(outputFile)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        result?.let { return it }
        val exitCode = synchronized(waitLock) {
            result?.let { return@synchronized it }
            val code = if (delegateProcess != null) {
                delegateProcess.waitFor()
            } else {
                NativeSpawn.waitFor(pid, false)
            }
            result = code
            code
        }
        runCatching { stdin.close() }
        outputPump?.join(1_000)
        return exitCode
    }

    override fun exitValue(): Int {
        result?.let { return it }
        synchronized(waitLock) {
            result?.let { return it }
            if (delegateProcess != null) {
                val code = delegateProcess.exitValue()
                result = code
                runCatching { stdin.close() }
                return code
            }
            val status = NativeSpawn.waitFor(pid, true)
            if (status == NativeSpawn.STILL_RUNNING) throw IllegalThreadStateException("Process is still running")
            result = status
            runCatching { stdin.close() }
            return status
        }
    }

    override fun destroy() {
        if (delegateProcess != null) {
            delegateProcess.destroy()
            killViaRootIfNeeded(pid, 15)
            return
        }
        // NativeSpawn.kill in forge_spawn.c automatically escalates via `su -c` if kill(-pid, 15) returns EPERM
        NativeSpawn.kill(pid, 15)
    }

    /** Send the same interrupt signal produced by Ctrl+C in a real terminal. */
    internal fun interrupt() {
        if (delegateProcess != null) {
            killViaRootIfNeeded(pid, 2)
            return
        }
        NativeSpawn.kill(pid, 2)
    }

    override fun destroyForcibly(): Process {
        if (delegateProcess != null) {
            delegateProcess.destroyForcibly()
            killViaRootIfNeeded(pid, 9)
            runCatching { stdin.close() }
            return this
        }
        NativeSpawn.kill(pid, 9)
        runCatching { stdin.close() }
        return this
    }

    private fun killViaRootIfNeeded(targetPid: Int, sig: Int) {
        // Guard against -1 (all processes), 0 (current process group), and 1 (init).
        if (targetPid <= 1) return
        runCatching {
            val proc = ProcessBuilder("su", "-c", "kill -s $sig -$targetPid $targetPid 2>/dev/null || true").start()
            if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }
    }

    /** Dynamically resize the pseudo-terminal window when soft-keyboard or orientation changes. */
    internal fun resizePty(rows: Int, columns: Int): Int {
        if (delegateProcess != null || ptyMasterFd < 0) return -1
        synchronized(ptyFdLock) {
            if (!isPtyFdOpen()) return -1
            return NativeSpawn.resizePty(ptyMasterFd, rows, columns)
        }
    }

    override fun isAlive(): Boolean =
        delegateProcess?.isAlive ?: runCatching { exitValue(); false }.getOrDefault(true)

    companion object {
        private fun extractProcessPid(process: Process): Int = runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        }.getOrDefault(-1)

        fun start(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: String,
            outputFile: File,
            pseudoTerminal: Boolean = false,
            ptyRows: Int = 40,
            ptyColumns: Int = 120,
        ): NativeSpawnProcess {
            outputFile.parentFile?.mkdirs()
            if (pseudoTerminal || !outputFile.exists()) {
                outputFile.delete()
                outputFile.createNewFile()
            }
            if (!NativeSpawn.isAvailable) {
                val pb = ProcessBuilder(argv)
                val cwdFile = File(cwd)
                if (cwdFile.isDirectory) pb.directory(cwdFile)
                if (environment.isNotEmpty()) pb.environment().putAll(environment)
                pb.redirectErrorStream(true)
                pb.redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                val fallback = pb.start()
                return NativeSpawnProcess(
                    pid = extractProcessPid(fallback),
                    outputFile = outputFile,
                    stdin = fallback.outputStream,
                    delegateProcess = fallback,
                )
            }
            val spawned = NativeSpawn.spawn(
                argv.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd,
                outputFile.absolutePath,
                pseudoTerminal,
                ptyRows,
                ptyColumns,
            )
            check(spawned != null && spawned.size == 3 && spawned[0] > 0) { "Native Chroot runtime launch failed" }
            val input = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(spawned[1]))
            val masterFd = spawned[2]
            val ptyLock = Any()
            var ptyOpen = masterFd >= 0
            val pump = masterFd.takeIf { it >= 0 }?.let { outputFd ->
                val pfd = ParcelFileDescriptor.adoptFd(outputFd)
                Thread({
                    try {
                        FileInputStream(pfd.fileDescriptor).use { source ->
                            FileOutputStream(outputFile, true).use { destination ->
                                val buffer = ByteArray(8 * 1024)
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count <= 0) break
                                    destination.write(buffer, 0, count)
                                    destination.flush()
                                }
                            }
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (e: OutOfMemoryError) {
                        throw e
                    } catch (_: java.io.IOException) {
                        // Normal EOF when child closes PTY slave FD
                    } finally {
                        synchronized(ptyLock) {
                            ptyOpen = false
                            runCatching { pfd.close() }
                        }
                    }
                }, "nextcraft-pty-output").apply {
                    isDaemon = true
                    start()
                }
            }
            return NativeSpawnProcess(
                pid = spawned[0],
                outputFile = outputFile,
                stdin = input,
                ptyMasterFd = masterFd,
                ptyFdLock = ptyLock,
                isPtyFdOpen = { ptyOpen },
                outputPump = pump,
            )
        }
    }
}

private object NativeSpawn {
    const val STILL_RUNNING = -2

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("forgespawn")
        true
    }.getOrDefault(false)

    external fun spawn(
        argv: Array<String>,
        environment: Array<String>,
        cwd: String,
        outputFile: String,
        pseudoTerminal: Boolean,
        ptyRows: Int,
        ptyColumns: Int,
    ): IntArray?

    external fun waitFor(pid: Int, noHang: Boolean): Int
    external fun kill(pid: Int, signal: Int): Int
    external fun resizePty(masterFd: Int, rows: Int, columns: Int): Int
}
```

## 6. `app/src/main/java/com/nextcraft/forge/runtime/RuntimeInstaller.kt` (2456 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

data class InstalledRuntime(
    val chrootExec: File,
    val rootfs: File,
) {
    /** Compatibility alias for callers referencing the runtime launcher executable. */
    val proot: File get() = chrootExec
    val binDir: File get() = chrootExec.parentFile ?: rootfs
}

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
)

data class AgentUpdateInfo(
    val installedVersion: String,
    val latestVersion: String,
)

internal fun isSupportedDshVersion(version: String): Boolean =
    version == RuntimeInstaller.DSH_VERSION

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

private data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
)

class RuntimeInstaller(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val runtimeBinDir = File(runtimeDir, "bin")
    private val chrootExecFile = File(runtimeBinDir, "chroot-exec.sh")
    private val unmountChrootFile = File(runtimeBinDir, "unmount-chroot.sh")
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val coreReadyMarker = File(rootfs, ".pocket-runtime-ready")
    private val nextcraftReadyMarker = File(rootfs, ".nextcraft-runtime-ready")
    private val claudeMarker = File(rootfs, ".pocket-claude-version")
    private val bundledClaudeMarker = File(rootfs, ".pocket-bundled-claude-version")
    private val rootfsMarker = File(rootfs, ".pocket-rootfs-version")
    private val languageToolsMarker = File(rootfs, ".pocket-language-tools-version")
    private val coreToolsMarker = File(rootfs, ".pocket-core-tools-version")
    private val systemUpgradeMarker = File(rootfs, ".pocket-system-upgrade-version")
    private val devStacksFile = File(rootfs, ".pocket-dev-stacks.json")
    private val dshMarker = File(rootfs, ".pocket-dsh-version")
    private val agyMarker = File(rootfs, ".pocket-agy-version")
    private val githubCliMarker = File(rootfs, ".pocket-github-cli-version")
    private val dshAndroidCompatibilityMarker = File(rootfs, ".pocket-dsh-android-compat-version")
    private val macosMetadataRepairMarker = File(rootfs, ".pocket-macos-metadata-repair")

    fun isInstalled(): Boolean {
        val chrootScriptsReady = runCatching { ensureChrootScripts() }.isSuccess
        val rootfsLayoutReady = ensureRootfsCompatibilityLinks()
        val legacyLanguageTools = languageToolsMarker.readTextOrNull() == LANGUAGE_TOOLS_VERSION
        val coreToolsReady = File(rootfs, "usr/bin/git").exists() && isSupportedCoreToolsVersion()
        val ready = chrootScriptsReady &&
            chrootExecFile.canExecute() &&
            rootfsLayoutReady &&
            File(rootfs, "usr/bin/bash").exists() &&
            isSupportedRootfsVersion(rootfsMarker.readTextOrNull()) &&
            File(rootfs, "usr/local/bin/node").exists() &&
            (legacyLanguageTools || coreToolsReady) &&
            (coreReadyMarker.exists() || nextcraftReadyMarker.exists())
        if (ready) repairLegacyMacosMetadata()
        return ready
    }

    private fun repairLegacyMacosMetadata() {
        if (macosMetadataRepairMarker.isFile) return
        if (!rootfs.isDirectory) return
        stripMacosMetadataArtifacts(rootfs)
        macosMetadataRepairMarker.parentFile?.mkdirs()
        macosMetadataRepairMarker.writeText("1")
    }

    /** Returns the already verified Root Chroot runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { "NextCraft Forge Root Chroot runtime installation is incomplete." }
        return InstalledRuntime(
            chrootExec = chrootExecFile,
            rootfs = rootfs,
        )
    }

    /**
     * Ensures `chroot-exec.sh` and `unmount-chroot.sh` are installed in `filesDir/runtime/bin`
     * with executable permissions (`0755`).
     */
    @Synchronized
    fun ensureChrootScripts(): File {
        runtimeBinDir.mkdirs()
        if (!chrootExecFile.isFile || chrootExecFile.readTextOrNull() != CHROOT_EXEC_SCRIPT.trim()) {
            chrootExecFile.writeText(CHROOT_EXEC_SCRIPT)
        }
        Os.chmod(chrootExecFile.absolutePath, 0b111101101)

        if (!unmountChrootFile.isFile || unmountChrootFile.readTextOrNull() != UNMOUNT_CHROOT_SCRIPT.trim()) {
            unmountChrootFile.writeText(UNMOUNT_CHROOT_SCRIPT)
        }
        Os.chmod(unmountChrootFile.absolutePath, 0b111101101)
        return chrootExecFile
    }

    /** Unmounts all kernel mounts under [targetRootfs] before deleting or swapping rootfs directories. */
    fun unmountChrootMounts(targetRootfs: File = rootfs): Boolean {
        ensureChrootScripts()
        return RootfsRepair.ensureUnmounted(targetRootfs)
    }

    /** Removes only scaffolding written automatically by earlier alpha builds. */
    fun cleanupLegacyWorkspaceScaffolding() {
        val workspaces = File(context.filesDir, "workspaces")
        workspaces.listFiles { file -> file.isDirectory }.orEmpty().forEach { workspace ->
            File(workspace, "README.md").deleteIfExact(LEGACY_README)
            File(workspace, "index.html").deleteIfExact(LEGACY_INDEX)

            listOf(
                File(workspace, ".claude/settings.json"),
                File(workspace, ".claude.json"),
            ).forEach { settings ->
                if (settings.isFile && settings.readTextOrNull()?.contains("/opt/pocket/permission-hook.sh") == true) {
                    settings.delete()
                }
            }
            File(workspace, ".claude").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun File.deleteIfExact(expected: String) {
        if (isFile && runCatching { readText() }.getOrNull() == expected) delete()
    }

    suspend fun ensureInstalled(
        selectedStacks: Set<DevStack> = emptySet(),
        agent: AgentKind = AgentKind.CLAUDE_CODE,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): InstalledRuntime {
        require(
            supportsArm64Runtime(
                android.os.Build.SUPPORTED_ABIS,
                System.getProperty("os.arch"),
            ),
        ) { "NextCraft Forge requires an ARM64 (arm64-v8a / aarch64) Android device." }
        require(isRootAccessAvailable()) {
            "Root access (su -mm) is required by NextCraft Forge's Ubuntu 24.04 LTS Chroot engine. Grant root permission in Magisk, KernelSU, or APatch and try again."
        }
        val chrootExec = ensureChrootScripts()
        require(chrootExec.canExecute()) { "The Root Chroot supervisor script is unavailable" }

        val staging = File(runtimeDir, "ubuntu.installing")
        val repairBackup = File(runtimeDir, "ubuntu.repair-backup")
        RootfsRepair.reconcileInterruptedSwap(
            rootfs = rootfs,
            staging = staging,
            backup = repairBackup,
            validate = ::isValidCoreRootfs,
        )
        val layoutBroken = rootfs.isDirectory && !ensureRootfsCompatibilityLinks()
        if (layoutBroken ||
            !File(rootfs, "usr/bin/bash").exists() ||
            !isSupportedRootfsVersion(rootfsMarker.readTextOrNull())
        ) {
            onProgress(RuntimeInstallProgress("Preparing Ubuntu Root Chroot runtime…", 0.03f))
            val archive = obtainRuntimeBundle(
                CORE_BUNDLE,
                preferEmbedded = isAssetAvailable("runtime/${CORE_BUNDLE.fileName}"),
                from = 0.03f,
                to = 0.25f,
                onProgress = onProgress,
            )
            onProgress(RuntimeInstallProgress("Unpacking Core Linux rootfs…", 0.28f))
            check(RootfsRepair.ensureUnmounted(staging)) { "Staging rootfs still has active mounts" }
            staging.deleteRecursively()
            staging.mkdirs()
            extractZstdTar(archive, staging)
            stripMacosMetadataArtifacts(staging)
            ensureAndroidSocketGroupConfiguration(staging)
            RootfsRepair.activate(
                rootfs = rootfs,
                staging = staging,
                backup = repairBackup,
                validate = ::isValidCoreRootfs,
            )
            writeResolver()
            if (archive.parentFile == downloads) archive.delete()
        }

        check(ensureRootfsCompatibilityLinks()) { "Core runtime has an invalid Linux filesystem layout" }
        ensureAndroidSocketGroupConfiguration(rootfs)

        migrateLegacyClaudeMarker()
        ensureSettingsAndHooks()

        val coreNeeded = !File(rootfs, "usr/bin/git").exists() || !isSupportedCoreToolsVersion()
        if (coreNeeded) {
            installNodeIfNeeded(chrootExec, 0.58f, 0.66f, onProgress)
        }
        if (systemUpgradeMarker.readTextOrNull() != SYSTEM_UPGRADE_VERSION) {
            runSystemMaintenance(chrootExec, onProgress)
            systemUpgradeMarker.writeText(SYSTEM_UPGRADE_VERSION)
        }
        if (coreNeeded) {
            aptInstall(
                chrootExec,
                listOf("git", "ca-certificates"),
                "Installing Git and base SSL certificates…",
                0.70f,
                onProgress,
            )
            writeResolver()
            verifyGuest(chrootExec, "git --version", "Failed to verify Git inside Root Chroot")
            coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        }
        if (!coreReadyMarker.exists()) coreReadyMarker.writeText(CORE_TOOLS_VERSION)
        if (!nextcraftReadyMarker.exists()) nextcraftReadyMarker.writeText(CORE_TOOLS_VERSION)

        val missingStacks = selectedStacks.filterNot(::isStackInstalled)
        missingStacks.forEachIndexed { index, stack ->
            val slice = 0.26f / maxOf(1, missingStacks.size)
            val from = 0.72f + index * slice
            applyStack(chrootExec, stack, from, from + slice, onProgress)
        }

        when (agent) {
            AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(chrootExec, 0.985f, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(chrootExec, 0.985f, onProgress)
            AgentKind.ANTIGRAVITY -> ensureAgyInstalled(chrootExec, 0.985f, onProgress)
        }
        onProgress(RuntimeInstallProgress("NextCraft Forge Root Chroot setup complete", 1f))
        return InstalledRuntime(chrootExec, rootfs)
    }

    /**
     * Installs one coding agent on demand. Safe to call again: an already-installed
     * agent returns immediately without network access.
     */
    suspend fun ensureAgentInstalled(
        agent: AgentKind,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(runtime.chrootExec, 0.05f, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(runtime.chrootExec, 0.05f, onProgress)
            AgentKind.ANTIGRAVITY -> ensureAgyInstalled(runtime.chrootExec, 0.05f, onProgress)
        }
        onProgress(RuntimeInstallProgress("${agent.title} is ready", 1f))
    }

    fun isAgentInstalled(agent: AgentKind): Boolean {
        return when (agent) {
            AgentKind.CLAUDE_CODE -> {
                migrateLegacyClaudeMarker()
                isInstalled() && File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/")).canExecute() &&
                    !claudeMarker.readTextOrNull().isNullOrBlank()
            }
            AgentKind.DEEPSEEK_HARNESS -> isInstalled() &&
                File(rootfs, "usr/local/lib/dsh/node_modules/.bin/dsh").isFile &&
                isSupportedDshVersion(dshMarker.readTextOrNull().orEmpty())
            AgentKind.ANTIGRAVITY -> isInstalled() &&
                File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() &&
                !agyMarker.readTextOrNull().isNullOrBlank()
        }
    }

    val dshVersion: String get() = dshMarker.readTextOrNull().orEmpty()

    val claudeVersion: String
        get() {
            migrateLegacyClaudeMarker()
            return claudeMarker.readTextOrNull().orEmpty()
        }

    val agyVersion: String get() = agyMarker.readTextOrNull().orEmpty()

    val githubCliVersion: String get() = githubCliMarker.readTextOrNull().orEmpty()

    fun isGitHubCliInstalled(): Boolean = isInstalled() &&
        File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/")).canExecute() &&
        githubCliMarker.readTextOrNull() == GITHUB_CLI_VERSION

    /** Installs GitHub's official ARM64 CLI on demand; it is not bundled in the APK. */
    suspend fun ensureGitHubCliInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isGitHubCliInstalled()) return
        writeResolver()
        downloads.mkdirs()
        val downloaded = File(downloads, "gh-$GITHUB_CLI_VERSION-linux-arm64.tar.gz")
        onProgress(RuntimeInstallProgress("Downloading GitHub CLI…", 0.05f))
        val githubDownloadLabel = "Downloading GitHub CLI $GITHUB_CLI_VERSION…"
        downloadVerified(GITHUB_CLI_RELEASE_URL, downloaded, GITHUB_CLI_RELEASE_SHA256) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    message = githubDownloadLabel,
                    fraction = 0.05f + ratio * 0.75f,
                    downloadedBytes = bytes,
                    totalBytes = total.takeIf { it > 0L },
                    event = RuntimeInstallEvent.DOWNLOAD,
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing GitHub CLI $GITHUB_CLI_VERSION…", 0.85f, indeterminate = true))
        val destination = File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        try {
            @Suppress("DEPRECATION")
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
                var entry = archive.nextTarEntry
                while (entry != null) {
                    if (entry.isFile && entry.name.removePrefix("./").endsWith("/bin/gh")) {
                        val staged = File(destination.parentFile, ".gh-$GITHUB_CLI_VERSION.installing")
                        try {
                            FileOutputStream(staged).use { archive.copyTo(it) }
                            Os.chmod(staged.absolutePath, 0b111101101)
                            Os.rename(staged.absolutePath, destination.absolutePath)
                        } catch (e: Throwable) {
                            staged.delete()
                            throw e
                        }
                        found = true
                        break
                    }
                    entry = archive.nextTarEntry
                }
            }
            check(found) { "Official GitHub CLI archive did not contain the expected binary" }
        } finally {
            downloaded.delete()
        }
        verifyGuest(
            chrootExec = installedRuntime().chrootExec,
            command = "$GITHUB_CLI_GUEST_PATH --version",
            failureMessage = "Failed to verify GitHub CLI",
        )
        githubCliMarker.writeText(GITHUB_CLI_VERSION)
        check(isGitHubCliInstalled()) { "GitHub CLI installation is incomplete" }
        onProgress(RuntimeInstallProgress("GitHub CLI is ready", 1f))
    }

    /** Provisions Git credential store and GitHub CLI hosts.yml inside the guest rootfs with 0600 permissions. */
    fun configureGitHubAccess(token: String, login: String) {
        val cleanToken = token.trim().replace("\r", "").replace("\n", "")
        if (cleanToken.isEmpty()) return
        val cleanLogin = login.trim().replace("\r", "").replace("\n", "").ifEmpty { "x-access-token" }
        val rootHome = File(rootfs, "root").apply { mkdirs() }
        val gitCredentials = File(rootHome, ".git-credentials")
        gitCredentials.writeText("https://$cleanLogin:$cleanToken@github.com\n")
        runCatching { Os.chmod(gitCredentials.absolutePath, 384) } // 0600

        val gitConfig = File(rootHome, ".gitconfig")
        gitConfig.writeText(
            buildString {
                appendLine("[user]")
                appendLine("    name = $cleanLogin")
                appendLine("    email = $cleanLogin@users.noreply.github.com")
                appendLine("[init]")
                appendLine("    defaultBranch = main")
                appendLine("[credential]")
                appendLine("    helper = store")
                appendLine("[safe]")
                appendLine("    directory = *")
            },
        )
        runCatching { Os.chmod(gitConfig.absolutePath, 384) } // 0600

        val ghConfigDir = File(rootHome, ".config/gh").apply { mkdirs() }
        val ghHosts = File(ghConfigDir, "hosts.yml")
        ghHosts.writeText(
            buildString {
                appendLine("github.com:")
                appendLine("    oauth_token: $cleanToken")
                appendLine("    user: $cleanLogin")
                appendLine("    git_protocol: https")
            },
        )
        runCatching { Os.chmod(ghHosts.absolutePath, 384) } // 0600
    }

    /** Removes stored Git and GitHub CLI credentials from the guest rootfs. */
    fun clearGitHubAccess() {
        val rootHome = File(rootfs, "root")
        File(rootHome, ".git-credentials").delete()
        File(rootHome, ".config/gh/hosts.yml").delete()
    }

    /** Versions recorded after each real agent binary has been installed and verified. */
    fun installedAgentVersions(): Map<AgentKind, String> = buildMap {
        migrateLegacyClaudeMarker()
        claudeMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(AgentKind.CLAUDE_CODE) && it.matches(CLAUDE_VERSION_PATTERN) }
            ?.let { put(AgentKind.CLAUDE_CODE, it) }

        dshMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(AgentKind.DEEPSEEK_HARNESS) }
            ?.let { put(AgentKind.DEEPSEEK_HARNESS, it) }

        agyMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() }
            ?.let { put(AgentKind.ANTIGRAVITY, it) }
    }

    /** Checks each installed agent against its own authoritative release source. */
    suspend fun checkAgentUpdates(): Map<AgentKind, AgentUpdateInfo> {
        val installed = installedAgentVersions()
        return buildMap {
            installed[AgentKind.CLAUDE_CODE]?.let { current ->
                runCatching {
                    JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
                }.getOrNull()?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                    put(AgentKind.CLAUDE_CODE, AgentUpdateInfo(current, latest))
                }
            }
            installed[AgentKind.DEEPSEEK_HARNESS]?.let { current ->
                DSH_VERSION.takeIf { isVersionNewer(it, current) }?.let { compatible ->
                    put(AgentKind.DEEPSEEK_HARNESS, AgentUpdateInfo(current, compatible))
                }
            }
            installed[AgentKind.ANTIGRAVITY]?.let { current ->
                runCatching { fetchAgyManifest().getString("version") }.getOrNull()
                    ?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                        put(AgentKind.ANTIGRAVITY, AgentUpdateInfo(current, latest))
                    }
            }
        }
    }

    suspend fun updateAgent(
        agent: AgentKind,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            AgentKind.CLAUDE_CODE -> updateClaude(runtime, expectedVersion, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> updateDsh(runtime, expectedVersion, onProgress)
            AgentKind.ANTIGRAVITY -> updateAgy(runtime, expectedVersion, onProgress)
        }
        onProgress(
            RuntimeInstallProgress(
                "${agent.title} $expectedVersion is ready",
                1f,
                event = RuntimeInstallEvent.COMPLETED,
            ),
        )
    }

    private suspend fun updateClaude(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val latest = JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
        check(latest == expectedVersion) { "A newer Claude Code release is available. Refresh updates and try again." }
        val base = "https://downloads.claude.ai/claude-code-releases/$latest"
        val manifest = JSONObject(fetchText("$base/manifest.json"))
        val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
        val downloaded = File(downloads, "claude-$latest")
        val claudeDownloadLabel = "Downloading Claude Code $latest…"
        downloadVerified("$base/linux-arm64/claude", downloaded, checksum) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(claudeDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        claude.parentFile?.mkdirs()
        val staged = File(claude.parentFile, ".claude-$latest.installing")
        try {
            downloaded.copyTo(staged, overwrite = true)
            Os.chmod(staged.absolutePath, 0b111101101)
            Os.rename(staged.absolutePath, claude.absolutePath)
        } catch (e: Throwable) {
            staged.delete()
            throw e
        } finally {
            downloaded.delete()
        }
        verifyGuest(runtime.chrootExec, "$CLAUDE_GUEST_PATH --version", "Failed to verify updated Claude Code")
        claudeMarker.writeText(latest)
    }

    private suspend fun updateAgy(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val manifest = fetchAgyManifest()
        val latest = manifest.getString("version")
        check(latest == expectedVersion) { "A newer Antigravity CLI release is available. Refresh updates and try again." }
        val downloaded = File(downloads, "antigravity-$latest-linux-arm64.tar.gz")
        val agyDownloadLabel = "Downloading Antigravity CLI $latest…"
        downloadVerified(manifest.getString("url"), downloaded, manifest.getString("sha512"), algorithm = "SHA-512") { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(agyDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val destination = File(rootfs, AGY_GUEST_PATH.removePrefix("/"))
        var found = false
        try {
            @Suppress("DEPRECATION")
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
                var entry = archive.nextTarEntry
                while (entry != null) {
                    if (entry.isFile && entry.name.removePrefix("./") == "antigravity") {
                        val staged = File(destination.parentFile, ".agy-$latest.installing")
                        try {
                            FileOutputStream(staged).use { archive.copyTo(it) }
                            Os.chmod(staged.absolutePath, 0b111101101)
                            Os.rename(staged.absolutePath, destination.absolutePath)
                        } catch (e: Throwable) {
                            staged.delete()
                            throw e
                        }
                        found = true
                        break
                    }
                    entry = archive.nextTarEntry
                }
            }
            check(found) { "Antigravity update archive is incomplete" }
        } finally {
            downloaded.delete()
        }
        verifyGuest(runtime.chrootExec, "$AGY_GUEST_PATH --version", "Failed to verify updated Antigravity CLI")
        agyMarker.writeText(latest)
    }

    private suspend fun updateDsh(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(isSupportedDshVersion(expectedVersion)) {
            "DeepSeek Harness $expectedVersion is not compatible with this NextCraft Forge build"
        }
        val quotedVersion = expectedVersion.replace(Regex("[^0-9A-Za-z.+-]"), "")
        check(quotedVersion == expectedVersion) { "Invalid DeepSeek Harness version" }
        runGuestCommand(
            chrootExec = runtime.chrootExec,
            command = "set -e; next=/usr/local/lib/dsh.updating; " +
                "rm -rf \"${'$'}next\"; mkdir -p \"${'$'}next\"; " +
                "cd \"${'$'}next\"; npm init -y >/dev/null; " +
                "npm install --omit=dev --no-audit --no-fund @deepseek-ai/dsh@$quotedVersion",
            displayCommand = "npm install @deepseek-ai/dsh@$quotedVersion",
            fraction = 0.55f,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Failed to update DeepSeek Harness",
        )
        val staged = File(rootfs, "usr/local/lib/dsh.updating")
        applyDshAndroidCompatibility(staged, expectedVersion)
        runGuestCommand(
            chrootExec = runtime.chrootExec,
            command = "set -e; old=/usr/local/lib/dsh.previous; rm -rf \"${'$'}old\"; " +
                "mv /usr/local/lib/dsh \"${'$'}old\"; " +
                "if mv /usr/local/lib/dsh.updating /usr/local/lib/dsh; then :; " +
                "else mv \"${'$'}old\" /usr/local/lib/dsh; exit 1; fi",
            displayCommand = "Activate DeepSeek Harness $quotedVersion",
            fraction = 0.9f,
            timeoutMs = 60_000L,
            onProgress = onProgress,
            failureMessage = "Failed to activate DeepSeek Harness update",
        )
        val verified = runCatching {
            verifyGuest(runtime.chrootExec, "/usr/local/bin/dsh --profile headless --help", "Failed to verify updated DeepSeek Harness")
        }
        if (verified.isFailure) {
            runCatching {
                runGuestCommand(
                    chrootExec = runtime.chrootExec,
                    command = "set -e; rm -rf /usr/local/lib/dsh; mv /usr/local/lib/dsh.previous /usr/local/lib/dsh",
                    displayCommand = "Restore previous DeepSeek Harness",
                    fraction = 0.95f,
                    timeoutMs = 60_000L,
                    onProgress = onProgress,
                    failureMessage = "Failed to restore previous DeepSeek Harness",
                )
            }
            verified.getOrThrow()
        }
        File(rootfs, "usr/local/lib/dsh.previous").deleteRecursively()
        dshMarker.writeText(expectedVersion)
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun fetchAgyManifest(): JSONObject = JSONObject(
        fetchText("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"),
    )

    private fun isVersionNewer(candidate: String, current: String): Boolean {
        fun numericParts(value: String) = value.substringBefore('-').substringBefore('+')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
        val left = numericParts(candidate)
        val right = numericParts(current)
        for (index in 0 until maxOf(left.size, right.size)) {
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        val candidatePre = '-' in candidate.substringBefore('+')
        val currentPre = '-' in current.substringBefore('+')
        return !candidatePre && currentPre
    }

    private fun migrateLegacyClaudeMarker() {
        if (claudeMarker.isFile) return
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        if (!claude.isFile) return
        val legacyVersion = sequenceOf(
            coreReadyMarker.readTextOrNull(),
            bundledClaudeMarker.readTextOrNull(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.matches(CLAUDE_VERSION_PATTERN) } ?: return
        claudeMarker.writeText(legacyVersion)
    }

    private fun isSupportedCoreToolsVersion(): Boolean = coreToolsMarker.readTextOrNull() in setOf(
        CORE_TOOLS_VERSION,
        LEGACY_CORE_TOOLS_VERSION,
        NEXTCRAFT_CORE_TOOLS_VERSION,
    )

    private fun isSupportedRootfsVersion(version: String?): Boolean = version in setOf(
        ROOTFS_VERSION,
        LEGACY_ROOTFS_VERSION,
    )

    private suspend fun ensureClaudeInstalled(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        migrateLegacyClaudeMarker()
        if (isAgentInstalled(AgentKind.CLAUDE_CODE)) return
        installRuntimeOverlay(
            bundle = CLAUDE_BUNDLE,
            message = "Installing Claude Code $CLAUDE_BUNDLED_VERSION…",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        verifyGuest(chrootExec, "$CLAUDE_GUEST_PATH --version", "Failed to verify Claude Code")
        require(claudeMarker.readTextOrNull() == CLAUDE_BUNDLED_VERSION) {
            "The Claude Code runtime bundle is incomplete"
        }
    }

    private suspend fun ensureAgyInstalled(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(AgentKind.ANTIGRAVITY)) return
        installRuntimeOverlay(
            bundle = AGY_BUNDLE,
            message = "Installing Antigravity CLI $AGY_VERSION…",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
            forceEmbedded = isAssetAvailable("runtime/${AGY_BUNDLE.fileName}"),
        )
        verifyGuest(chrootExec, "$AGY_GUEST_PATH --version", "Failed to verify Antigravity CLI")
        agyMarker.writeText(AGY_VERSION)
        require(isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            "Antigravity CLI installation is incomplete"
        }
    }

    private suspend fun ensureDshInstalled(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
            ensureDshAndroidCompatibility()
            return
        }
        val dshDir = File(rootfs, "usr/local/lib/dsh")
        val dshBackup = File(rootfs, "usr/local/lib/dsh.bak")
        val dshMarkerBackup = File(dshMarker.parentFile, "${dshMarker.name}.bak")
        val compatMarkerBackup = File(dshAndroidCompatibilityMarker.parentFile, "${dshAndroidCompatibilityMarker.name}.bak")
        File(rootfs, "usr/local/lib/dsh.updating").deleteRecursively()
        File(rootfs, "usr/local/lib/dsh.previous").deleteRecursively()
        dshBackup.deleteRecursively()
        dshMarkerBackup.delete()
        compatMarkerBackup.delete()
        if (dshDir.exists()) dshDir.renameTo(dshBackup)
        if (dshMarker.exists()) dshMarker.renameTo(dshMarkerBackup)
        if (dshAndroidCompatibilityMarker.exists()) dshAndroidCompatibilityMarker.renameTo(compatMarkerBackup)
        try {
            installRuntimeOverlay(
                bundle = DSH_BUNDLE,
                message = "Installing DeepSeek Harness $DSH_VERSION…",
                from = fraction,
                to = 0.995f,
                onProgress = onProgress,
            )
            ensureDshAndroidCompatibility()
            verifyGuest(chrootExec, "/usr/local/bin/dsh --profile headless --help", "Failed to verify DeepSeek Harness")
            require(isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
                "The DeepSeek Harness runtime bundle is incomplete"
            }
            dshBackup.deleteRecursively()
            dshMarkerBackup.delete()
            compatMarkerBackup.delete()
        } catch (e: Throwable) {
            dshDir.deleteRecursively()
            if (dshBackup.exists()) dshBackup.renameTo(dshDir)
            if (dshMarkerBackup.exists()) dshMarkerBackup.renameTo(dshMarker)
            if (compatMarkerBackup.exists()) compatMarkerBackup.renameTo(dshAndroidCompatibilityMarker)
            throw e
        }
    }

    /**
     * Ensures DSH uses COPYFILE_EXCL (`copyFile(tmp, finalPath, 1)`) for atomic no-clobber publication.
     */
    fun ensureDshAndroidCompatibility() {
        if (!isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) return
        applyDshAndroidCompatibility(File(rootfs, "usr/local/lib/dsh"), dshMarker.readTextOrNull().orEmpty())
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun applyDshAndroidCompatibility(dshRoot: File, version: String) {
        check(isSupportedDshVersion(version)) {
            "DeepSeek Harness $version is not compatible with this NextCraft Forge build"
        }
        val persistence = File(
            dshRoot,
            "node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        )
        val localFs = File(
            dshRoot,
            "node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
        )
        patchDshHardLinkPublication(
            file = persistence,
            importBefore = "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            importAfter = "import { copyFile, link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            callBefore = "await link(tmp, finalPath);",
            callAfter = "await copyFile(tmp, finalPath, 1);",
        )
        patchDshHardLinkPublication(
            file = localFs,
            importBefore = "import { chmod, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            importAfter = "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            callBefore = "await linkFile(tempPath, absolutePath);",
            callAfter = "await copyFile(tempPath, absolutePath, 1);",
        )
    }

    private fun patchDshHardLinkPublication(
        file: File,
        importBefore: String,
        importAfter: String,
        callBefore: String,
        callAfter: String,
    ) {
        check(file.isFile) { "DeepSeek Harness compatibility file is missing: ${file.name}" }
        var source = file.readText()
        if (callAfter in source && importAfter in source) return
        check(callBefore in source && importBefore in source) {
            "Installed DeepSeek Harness source is not compatible with this NextCraft Forge build"
        }
        source = source.replace(importBefore, importAfter).replace(callBefore, callAfter)
        val staging = File(file.parentFile, "${file.name}.tmp")
        staging.writeText(source)
        if (!staging.renameTo(file)) {
            staging.copyTo(file, overwrite = true)
            staging.delete()
        }
    }

    /**
     * Installs one optional development stack inside Ubuntu Root Chroot.
     */
    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        if (isStackInstalled(stack) && stack != DevStack.ANDROID) return
        applyStack(runtime.chrootExec, stack, 0.05f, 0.95f, onProgress)
        onProgress(RuntimeInstallProgress("${stack.label} stack is ready", 1f))
    }

    /**
     * Removes an optional development stack without touching projects or the core
     * Node.js/Git runtime.
     */
    suspend fun removeStack(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(stack != DevStack.WEB) { "Web (Node.js / npm) is part of the Core runtime and cannot be removed." }
        val runtime = installedRuntime()
        if (!isStackInstalled(stack)) return

        onProgress(RuntimeInstallProgress("Removing ${stack.label}…", 0.1f, indeterminate = true))
        when (stack) {
            DevStack.WEB -> Unit
            DevStack.PYTHON -> removePythonStack()
            DevStack.ANDROID -> removeAndroidStack()
            DevStack.CPP -> aptRemove(
                runtime.chrootExec,
                listOf("build-essential", "gcc", "g++", "make", "cmake", "gdb"),
                0.45f,
                onProgress,
            )
            DevStack.PHP -> {
                aptRemove(
                    runtime.chrootExec,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip"),
                    0.45f,
                    onProgress,
                )
                removePath(File(rootfs, "usr/local/bin/composer"))
                removePath(File(rootfs, "root/.cache/composer"))
                removePath(File(rootfs, "root/.composer"))
            }
        }

        writeDevStackState(readDevStackState().apply { put(stack.name, false) })
        onProgress(RuntimeInstallProgress("Removed ${stack.label}", 1f))
    }

    private fun removePythonStack() {
        listOf(
            ".pocket-python-tools-version",
            "usr/bin/python3",
            "usr/bin/python3.8",
            "usr/bin/pip",
            "usr/bin/pip3",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1.0",
            "usr/lib/python3",
            "usr/lib/python3.8",
            "usr/local/lib/python3.8",
            "usr/share/python3",
            "usr/share/python-wheels",
            "root/.cache/pip",
        ).forEach { removePath(File(rootfs, it)) }
    }

    private fun removeAndroidStack() {
        listOf(
            "root/android-sdk",
            "root/maven",
            "root/.pocket-android-tools-version",
            "root/.gradle/caches",
            "root/.gradle/daemon",
            "root/.gradle/native",
            "root/.gradle/notifications",
            "root/.gradle/wrapper/dists",
            "root/.gradle/init.d/pocketdev-android.gradle",
            "opt/gradle",
            "opt/jdk-17.0.20.1+1",
            "usr/local/bin/jar",
            "usr/local/bin/jarsigner",
            "usr/local/bin/java",
            "usr/local/bin/javac",
            "usr/local/bin/javadoc",
            "usr/local/bin/keytool",
        ).forEach { removePath(File(rootfs, it)) }
        removeAndroidGradleProperty()
    }

    private fun removeAndroidGradleProperty() {
        val properties = File(rootfs, "root/.gradle/gradle.properties")
        if (!properties.isFile) return
        val remaining = properties.readLines().filterNot(::isManagedAndroidGradleProperty)
        if (remaining.isEmpty()) {
            properties.delete()
        } else {
            properties.writeText(remaining.joinToString("\n").trimEnd() + "\n")
        }
    }

    private fun removePath(file: File) {
        if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            check(file.deleteRecursively()) { "Could not remove ${file.name}" }
        } else if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            check(file.delete()) { "Could not remove ${file.name}" }
        }
    }

    private suspend fun applyStack(
        chrootExec: File,
        stack: DevStack,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        var verified = true
        when (stack) {
            DevStack.WEB -> {
                onProgress(RuntimeInstallProgress("Checking Node.js and npm…", from))
                verifyGuest(chrootExec, "node --version && npm --version", "Failed to verify Node.js")
            }
            DevStack.PYTHON -> {
                installRuntimeOverlay(PYTHON_BUNDLE, "Installing Python toolchain…", from, to, onProgress)
                runCatching {
                    verifyGuest(chrootExec, "python3 --version && pip3 --version", "Failed to verify Python")
                }.onFailure { error ->
                    verified = false
                    onProgress(
                        RuntimeInstallProgress(
                            "Python verification failed: ${error.message?.take(120).orEmpty()}",
                            to,
                        ),
                    )
                }
            }
            DevStack.ANDROID -> {
                installAndroidToolchain(chrootExec, from, to, onProgress)
            }
            DevStack.CPP -> {
                aptInstall(
                    chrootExec,
                    listOf("build-essential", "cmake", "gdb"),
                    "Installing C/C++ build toolchain…",
                    from,
                    onProgress,
                )
                verifyGuest(
                    chrootExec,
                    "gcc --version && g++ --version && make --version && cmake --version",
                    "Failed to verify C/C++ toolchain",
                )
            }
            DevStack.PHP -> {
                aptInstall(
                    chrootExec,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip"),
                    "Installing PHP and extensions…",
                    from,
                    onProgress,
                )
                installComposer(chrootExec, from, onProgress)
                verifyGuest(chrootExec, "php --version && composer --version", "Failed to verify PHP and Composer")
            }
        }
        if (!verified) return
        writeDevStackState(readDevStackState().apply { put(stack.name, true) })
        onProgress(RuntimeInstallProgress("Installed ${stack.label}", to))
    }

    private suspend fun installComposer(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val composer = File(rootfs, "usr/local/bin/composer")
        if (composer.isFile) return
        onProgress(RuntimeInstallProgress("Downloading Composer…", fraction))
        downloads.mkdirs()
        val staged = File(downloads, "composer.phar")
        val checksum = fetchText("https://getcomposer.org/download/latest-stable/composer.phar.sha256sum")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: error("Composer checksum was not found")
        downloadVerified(
            "https://getcomposer.org/download/latest-stable/composer.phar",
            staged,
            checksum,
        ) { _, _ -> }
        composer.parentFile?.mkdirs()
        val staging = File(composer.parentFile, ".composer.installing")
        try {
            staged.inputStream().use { input -> FileOutputStream(staging).use { input.copyTo(it) } }
            Os.chmod(staging.absolutePath, 0b111101101)
            Os.rename(staging.absolutePath, composer.absolutePath)
        } catch (e: Throwable) {
            staging.delete()
            throw e
        } finally {
            staged.delete()
        }
        onProgress(RuntimeInstallProgress("Installing Composer…", fraction))
    }

    private suspend fun installAndroidToolchain(
        chrootExec: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val marker = File(rootfs, "root/.pocket-android-tools-version")
        val androidHome = File(rootfs, "root/android-sdk")
        val gradleHome = File(rootfs, "opt/gradle")
        val localMaven = File(rootfs, "root/maven/localMvnRepository")
        if (marker.readTextOrNull() != ANDROID_TOOLS_VERSION ||
            !File(androidHome, "platforms/android-36/android.jar").isFile ||
            !File(androidHome, "build-tools/35.0.0/aapt2").isFile ||
            !File(gradleHome, "gradle-8.14.3/bin/gradle").isFile ||
            !localMaven.isDirectory
        ) {
            installRuntimeOverlay(
                ANDROID_BUNDLE,
                "Installing Android SDK 36, Build Tools 35, Gradle 8.14.3, and Maven cache…",
                from,
                to,
                onProgress,
            )
            makeAndroidToolsExecutable(androidHome, gradleHome)
            marker.parentFile?.mkdirs()
            marker.writeText(ANDROID_TOOLS_VERSION)
        }
        writeAndroidGradleConfiguration(rootfs)

        verifyGuest(
            chrootExec,
            "java -version 2>&1 | grep -E '\"17\\.|version 17' && " +
                "gradle --version && aapt2 version && test -f \"${'$'}ANDROID_HOME/platforms/android-36/android.jar\"",
            "Failed to verify Android SDK, Gradle, and Java 17",
        )
    }

    private suspend fun installRuntimeOverlay(
        bundle: RuntimeBundle,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        forceEmbedded: Boolean = false,
    ) {
        val preferEmbedded = forceEmbedded || isAssetAvailable("runtime/${bundle.fileName}")
        val archive = obtainRuntimeBundle(bundle, preferEmbedded = preferEmbedded, from = from, to = to * 0.8f + from * 0.2f, onProgress = onProgress)
        onProgress(RuntimeInstallProgress(message, to * 0.8f + from * 0.2f, indeterminate = true))
        try {
            extractZstdTar(archive, rootfs)
            stripMacosMetadataArtifacts(rootfs)
        } finally {
            if (archive.parentFile == downloads) archive.delete()
        }
        onProgress(RuntimeInstallProgress("Installed ${bundle.label} runtime bundle", to))
    }

    private fun stripMacosMetadataArtifacts(root: File) {
        if (!root.isDirectory) return
        val queue = ArrayDeque<File>()
        queue.add(root)
        var removed = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith("._")) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                    removed++
                } else if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    queue.add(child)
                }
            }
        }
        if (removed > 0) {
            Log.i("RuntimeInstaller", "Stripped $removed macOS metadata artifacts")
        }
    }

    private fun isAssetAvailable(assetPath: String): Boolean =
        runCatching {
            context.assets.open(assetPath).close()
            true
        }.getOrDefault(false)

    private suspend fun obtainRuntimeBundle(
        bundle: RuntimeBundle,
        preferEmbedded: Boolean,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): File {
        downloads.mkdirs()
        val destination = File(downloads, bundle.fileName)
        if (preferEmbedded && isAssetAvailable("runtime/${bundle.fileName}")) {
            val loadingLabel = "Loading ${bundle.label} bundle…"
            onProgress(RuntimeInstallProgress(loadingLabel, from, 0, bundle.compressedBytes))
            val temporary = File(downloads, "${bundle.fileName}.part")
            context.assets.open("runtime/${bundle.fileName}").use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val ratio = (copied.toFloat() / bundle.compressedBytes).coerceIn(0f, 1f)
                        onProgress(RuntimeInstallProgress(loadingLabel, from + ratio * (to - from), copied, bundle.compressedBytes))
                    }
                }
            }
            require(digest(temporary, "SHA-256").equals(bundle.sha256, ignoreCase = true)) {
                "${bundle.label} bundle checksum mismatch"
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Could not stage the ${bundle.label} bundle" }
            return destination
        }

        val url = "$DEFAULT_RUNTIME_RELEASE_BASE_URL/${bundle.fileName}"
        val downloadingLabel = "Downloading ${bundle.label} bundle…"
        downloadVerified(url, destination, bundle.sha256) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(downloadingLabel, from + ratio * (to - from), downloaded, total.takeIf { it > 0 }))
        }
        return destination
    }

    private fun makeAndroidToolsExecutable(androidHome: File, gradleHome: File) {
        val buildTools = File(androidHome, "build-tools/35.0.0")
        listOf("aapt", "aapt2", "aidl", "apksigner", "d8", "dexdump", "split-select", "zipalign")
            .map { File(buildTools, it) }
            .plus(File(gradleHome, "gradle-8.14.3/bin/gradle"))
            .filter(File::isFile)
            .forEach { Os.chmod(it.absolutePath, 0b111101101) }
    }

    private fun writeAndroidGradleInitScript(runtimeRootfs: File) {
        val script = File(runtimeRootfs, "root/.gradle/init.d/pocketdev-android.gradle")
        script.parentFile?.mkdirs()
        script.writeText(
            """
            def pocketMaven = uri('/root/maven/localMvnRepository')
            beforeSettings { settings ->
                settings.pluginManagement.repositories {
                    maven { url = pocketMaven }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            settingsEvaluated { settings ->
                settings.dependencyResolutionManagement.repositories {
                    maven { url = pocketMaven }
                }
            }
            gradle.beforeProject { project ->
                project.extensions.extraProperties.set(
                    'android.aapt2FromMavenOverride',
                    '/root/android-sdk/build-tools/35.0.0/aapt2'
                )
                project.buildscript.repositories {
                    maven { url = pocketMaven }
                }
            }
            """.trimIndent() + "\n",
        )
    }

    @Synchronized
    private fun writeAndroidGradleConfiguration(runtimeRootfs: File) {
        val aapt2 = File(runtimeRootfs, ANDROID_AAPT2_HOST_PATH)
        if (!aapt2.isFile) return

        writeAndroidGradleInitScript(runtimeRootfs)
        val gradleDir = File(runtimeRootfs, "root/.gradle").apply { mkdirs() }
        val properties = File(gradleDir, "gradle.properties")
        val existingLines = properties.readTextOrNull()?.lineSequence()?.toList().orEmpty()
        val managedLines = listOf(
            "$ANDROID_AAPT2_PROPERTY=$ANDROID_AAPT2_GUEST_PATH",
            "$GRADLE_JVMARGS_PROPERTY=-Xmx512m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8",
            "$GRADLE_WORKERS_PROPERTY=2",
            "$GRADLE_PARALLEL_PROPERTY=false",
            "$KOTLIN_EXECUTION_PROPERTY=in-process",
        )
        val updatedLines = existingLines.filterNot(::isManagedAndroidGradleProperty) + managedLines
        if (existingLines == updatedLines) return

        val temporary = File(gradleDir, "gradle.properties.nextcraft.tmp")
        try {
            temporary.writeText(updatedLines.joinToString("\n").trimEnd() + "\n")
            Os.rename(temporary.absolutePath, properties.absolutePath)
        } catch (e: Throwable) {
            temporary.delete()
            throw e
        }
    }

    private fun isManagedAndroidGradleProperty(line: String): Boolean =
        MANAGED_ANDROID_GRADLE_PROPERTIES.any { key ->
            Regex("^\\s*${Regex.escape(key)}\\s*[:=].*$").matches(line)
        }

    fun migrateLegacyToolMarkers() {
        if (!File(rootfs, "usr/bin/bash").isFile) return
        if (languageToolsMarker.readTextOrNull() != LANGUAGE_TOOLS_VERSION) return
        if (coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION) coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        val state = readDevStackState()
        DevStack.entries.forEach { stack -> if (!state.containsKey(stack.name)) state[stack.name] = true }
        writeDevStackState(state)
    }

    fun installedStacks(): Set<DevStack> = readDevStackState()
        .filterValues { it }
        .keys
        .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
        .filter(::isStackInstalled)
        .toSet()

    fun isStackInstalled(stack: DevStack): Boolean {
        if (readDevStackState()[stack.name] != true) return false
        if (stack != DevStack.ANDROID) return true
        return File(rootfs, "root/.pocket-android-tools-version").readTextOrNull() == ANDROID_TOOLS_VERSION &&
            File(rootfs, "root/android-sdk/platforms/android-36/android.jar").isFile &&
            File(rootfs, "root/android-sdk/build-tools/35.0.0/aapt2").isFile &&
            File(rootfs, "opt/gradle/gradle-8.14.3/bin/gradle").isFile &&
            File(rootfs, "root/maven/localMvnRepository").let { it.isDirectory && !it.list().isNullOrEmpty() } &&
            File(rootfs, "root/.gradle/init.d/pocketdev-android.gradle").isFile
    }

    private fun readDevStackState(): MutableMap<String, Boolean> {
        if (!devStacksFile.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(devStacksFile.readText())
            mutableMapOf<String, Boolean>().apply {
                DevStack.entries.forEach { stack ->
                    if (obj.has(stack.name)) put(stack.name, obj.optBoolean(stack.name))
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    private fun writeDevStackState(state: Map<String, Boolean>) {
        devStacksFile.parentFile?.mkdirs()
        val obj = JSONObject()
        state.forEach { (name, value) -> obj.put(name, value) }
        devStacksFile.writeText(obj.toString())
    }

    private suspend fun installNodeIfNeeded(
        chrootExec: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (File(rootfs, "usr/local/bin/node").exists()) return
        val nodeDownloadLabel = "Downloading Node.js $NODE_VERSION…"
        onProgress(RuntimeInstallProgress(nodeDownloadLabel, from))
        downloads.mkdirs()
        val nodeFileName = "node-$NODE_VERSION-linux-arm64.tar.gz"
        val nodeBaseUrl = "https://nodejs.org/dist/$NODE_VERSION"
        val checksum = fetchText("$nodeBaseUrl/SHASUMS256.txt")
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.endsWith("  $nodeFileName") }
            ?.substringBefore(' ')
            ?: error("Node.js checksum was not found")
        val nodeArchive = File(downloads, nodeFileName)
        downloadVerified("$nodeBaseUrl/$nodeFileName", nodeArchive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    nodeDownloadLabel,
                    from + ratio * (to - from),
                    downloaded,
                    total.takeIf { it > 0 },
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing Node.js $NODE_VERSION…", to))
        val nodeStaging = File(runtimeDir, "node.installing")
        try {
            nodeStaging.deleteRecursively()
            nodeStaging.mkdirs()
            extractNodeArchive(nodeArchive, nodeStaging)
            val nodeHome = File(rootfs, "usr/local/lib/nodejs")
            nodeHome.deleteRecursively()
            nodeHome.parentFile?.mkdirs()
            check(nodeStaging.renameTo(nodeHome)) { "Could not activate Node.js" }
            val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
            listOf("node", "npm", "npx", "corepack").forEach { command ->
                val link = File(localBin, command)
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
                Os.symlink("../lib/nodejs/bin/$command", link.absolutePath)
            }
        } catch (e: Throwable) {
            nodeStaging.deleteRecursively()
            throw e
        } finally {
            nodeArchive.delete()
        }
    }

    private suspend fun aptInstall(
        chrootExec: File,
        packages: List<String>,
        message: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(RuntimeInstallProgress(message, fraction))
        aptInstallInternal(chrootExec, packages, fraction, onProgress)
    }

    private suspend fun runSystemMaintenance(
        chrootExec: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = "Updating Ubuntu packages inside Root Chroot…",
                fraction = 0.69f,
                indeterminate = true,
            ),
        )
        writeResolver()
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 upgrade -y"
        runGuestCommand(
            chrootExec = chrootExec,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get update && apt-get upgrade -y",
            fraction = 0.69f,
            timeoutMs = 35 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Ubuntu system maintenance failed inside Root Chroot",
        )
    }

    private suspend fun aptInstallInternal(
        chrootExec: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        writeResolver()
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            chrootExec = chrootExec,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get install -y $packageNames",
            fraction = fraction,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not install packages: $packageNames",
        )
    }

    private suspend fun aptRemove(
        chrootExec: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o DPkg::Lock::Timeout=120 purge -y $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            chrootExec = chrootExec,
            command = command,
            displayCommand = "apt-get purge -y $packageNames",
            fraction = fraction,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not remove packages: $packageNames",
        )
    }

    private suspend fun runGuestCommand(
        chrootExec: File,
        command: String,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "root@forge:~# $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val running = process(
            chrootExec = chrootExec,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            guestWorkspacePath = "/root",
        )
        runCatching { running.outputStream.close() }
        val native = running as? NativeSpawnProcess
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                var offset = 0L
                var pending = ""
                while (running.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                    coroutineContext.ensureActive()
                    val file = native?.outputFile
                    if (file != null && file.length() > offset) {
                        RandomAccessFile(file, "r").use { input ->
                            val length = input.length()
                            if (length > offset) {
                                input.seek(offset)
                                val available = (length - offset).coerceAtMost(256 * 1024).toInt()
                                val bytes = ByteArray(available)
                                val read = input.read(bytes)
                                if (read > 0) {
                                    val usable = completeUtf8PrefixLength(bytes, read).let {
                                        if (it > 0 || !running.isAlive) (if (it > 0) it else read) else 0
                                    }
                                    if (usable > 0) {
                                        offset += usable
                                        pending += String(bytes, 0, usable, Charsets.UTF_8).replace('\r', '\n')
                                    }
                                }
                            }
                        }
                        val parts = pending.split('\n')
                        pending = parts.last()
                        for (raw in parts.dropLast(1)) {
                            val line = sanitizeTerminalLine(raw)
                            if (line.isNotBlank()) {
                                collected.appendLine(line)
                                if (collected.length > MAX_COLLECTED_OUTPUT) {
                                    collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                                }
                                onProgress(
                                    RuntimeInstallProgress(
                                        message = line,
                                        fraction = fraction,
                                        terminalLine = line,
                                        indeterminate = true,
                                        event = RuntimeInstallEvent.OUTPUT,
                                    ),
                                )
                            }
                        }
                    } else {
                        delay(80)
                    }
                }
                sanitizeTerminalLine(pending).takeIf(String::isNotBlank)?.let { line ->
                    collected.appendLine(line)
                    onProgress(RuntimeInstallProgress(line, fraction, terminalLine = line, indeterminate = true, event = RuntimeInstallEvent.OUTPUT))
                }
            }
        } finally {
            if (running.isAlive) {
                running.destroy()
                repeat(10) {
                    if (!running.isAlive) return@repeat
                    runCatching { Thread.sleep(50) }
                }
                if (running.isAlive) {
                    running.destroyForcibly()
                }
            }
            native?.outputFile?.delete()
        }
        val exit = running.waitFor()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) "Command completed" else "Command failed (exit $exit)",
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { actionableProcessError(collected.toString(), failureMessage) }
    }

    private fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    private suspend fun verifyGuest(chrootExec: File, command: String, failureMessage: String) {
        val verify = process(
            chrootExec = chrootExec,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            guestWorkspacePath = "/root",
        )
        runCatching { verify.outputStream.close() }
        try {
            withTimeout(60_000L) {
                while (verify.isAlive) delay(50)
            }
            val exit = verify.waitFor()
            val output = (verify as? NativeSpawnProcess)?.outputFile
                ?.let(::readProcessOutputSafely)
                .orEmpty()
                .trim()
            check(exit == 0) { actionableProcessError(output, failureMessage) }
        } finally {
            if (verify.isAlive) {
                verify.destroy()
                verify.destroyForcibly()
            }
            runCatching { verify.waitFor() }
            (verify as? NativeSpawnProcess)?.outputFile?.delete()
        }
    }

    /**
     * Restores merged-/usr compatibility links (`bin -> usr/bin`, `lib -> usr/lib`, `sbin -> usr/sbin`)
     * inside the Ubuntu rootfs without replacing real directories.
     */
    private fun ensureRootfsCompatibilityLinks(targetRootfs: File = rootfs): Boolean {
        if (!targetRootfs.isDirectory) return false
        val links = mapOf(
            "bin" to "usr/bin",
            "lib" to "usr/lib",
            "sbin" to "usr/sbin",
        )
        return runCatching {
            links.forEach { (name, destination) ->
                val link = File(targetRootfs, name)
                val path = link.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    if (java.nio.file.Files.readSymbolicLink(path).toString() != destination) {
                        java.nio.file.Files.delete(path)
                        Os.symlink(destination, link.absolutePath)
                    }
                } else if (link.exists()) {
                    check(link.isDirectory) { "Linux /$name is not a directory or symbolic link" }
                } else {
                    Os.symlink(destination, link.absolutePath)
                }
            }
            File(targetRootfs, "usr/bin/env").canExecute() &&
                File(targetRootfs, "usr/bin/bash").canExecute() &&
                File(targetRootfs, "lib/ld-linux-aarch64.so.1").exists()
        }.onFailure {
            Log.e("RuntimeInstaller", "Could not repair Linux compatibility links", it)
        }.getOrDefault(false)
    }

    /**
     * Ensures Android paranoid-network socket groups (`aid_inet` 3003, `aid_net_raw` 3004)
     * and APT sandbox root configuration are present inside the chroot rootfs.
     */
    private fun ensureAndroidSocketGroupConfiguration(targetRootfs: File = rootfs) {
        runCatching {
            val groupFile = File(targetRootfs, "etc/group")
            if (groupFile.isFile) {
                var groupText = groupFile.readText()
                var changed = false
                if (!groupText.lineSequence().any { it.startsWith("aid_inet:") }) {
                    if (!groupText.endsWith("\n")) groupText += "\n"
                    groupText += "aid_inet:x:3003:root,_apt\n"
                    changed = true
                }
                if (!groupText.lineSequence().any { it.startsWith("aid_net_raw:") }) {
                    if (!groupText.endsWith("\n")) groupText += "\n"
                    groupText += "aid_net_raw:x:3004:root,_apt\n"
                    changed = true
                }
                if (changed) groupFile.writeText(groupText)
            }
            val aptConf = File(targetRootfs, "etc/apt/apt.conf.d/99android-chroot")
            aptConf.parentFile?.mkdirs()
            val expectedAptConf = "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n"
            if (aptConf.readTextOrNull() != expectedAptConf.trim()) {
                aptConf.writeText(expectedAptConf)
            }
        }
    }

    private fun isValidCoreRootfs(targetRootfs: File): Boolean =
        ensureRootfsCompatibilityLinks(targetRootfs) &&
            File(targetRootfs, "usr/bin/bash").isFile &&
            isSupportedRootfsVersion(File(targetRootfs, ".pocket-rootfs-version").readTextOrNull())

    private fun actionableProcessError(output: String, fallback: String): String {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val chrootError = lines.firstOrNull {
            it.startsWith("chroot:") || it.startsWith("mount:") || it.contains("Permission denied", ignoreCase = true)
        }
        return chrootError ?: output.trim().takeLast(1_000).ifBlank { fallback }
    }

    private fun readProcessOutputSafely(file: File): String = runCatching {
        file.readTailText(MAX_PROCESS_OUTPUT_BYTES)
    }.getOrElse { error ->
        Log.w("RuntimeInstaller", "Could not decode process output as UTF-8: ${error.message}")
        ""
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        onProgress(RuntimeInstallProgress("Checking Root Chroot runtime files…", 0.15f))
        ensureChrootScripts()
        ensureAndroidSocketGroupConfiguration(rootfs)
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress("Preparing runtime IPC bridge…", 0.42f))
        disablePhantomProcessKillerViaRoot()
        check(File(rootfs, "usr/local/bin/node").canExecute()) { "Core runtime is missing Node.js" }
        check(File(rootfs, "usr/bin/git").canExecute()) { "Core runtime is missing Git" }
        onProgress(RuntimeInstallProgress("Root Chroot runtime ready", 1f))
        return installed
    }

    /**
     * Spawns a command inside the Ubuntu 24.04 Root Chroot via `su -mm` and `chroot-exec.sh`.
     * No PRoot binary or ptrace translation is used.
     */
    fun process(
        chrootExec: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
        outputFile: File = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        pseudoTerminal: Boolean = false,
        ptyRows: Int = 40,
        ptyColumns: Int = 120,
    ): Process {
        check(ensureRootfsCompatibilityLinks(rootfs)) { "Core runtime has an invalid Linux filesystem layout" }
        require(
            guestWorkspacePath == "/root" ||
                guestWorkspacePath == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(guestWorkspacePath),
        ) { "Invalid project workspace path" }
        ensureChrootScripts()
        writeResolver()
        workspace.mkdirs()
        File(rootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        writeAndroidGradleConfiguration(rootfs)
        if (guestWorkspacePath != "/root") {
            ensureWorkspaceTrust(guestWorkspacePath)
        }
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        val appUid = AndroidProcess.myUid().toString()

        val guestEnv = buildMap {
            put("HOME", "/root")
            put("USER", "root")
            put("LOGNAME", "root")
            val androidReady = File(rootfs, "root/.pocket-android-tools-version").readTextOrNull() == ANDROID_TOOLS_VERSION
            val basePath = "/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            if (androidReady) {
                put("ANDROID_HOME", "/root/android-sdk")
                put("ANDROID_SDK_ROOT", "/root/android-sdk")
                put("GRADLE_HOME", "/opt/gradle/gradle-8.14.3")
                put("GRADLE_USER_HOME", "/root/.gradle")
                put("ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride", "/root/android-sdk/build-tools/35.0.0/aapt2")
                put("PATH", "/opt/gradle/gradle-8.14.3/bin:/root/android-sdk/build-tools/35.0.0:/root/android-sdk/cmdline-tools/latest/bin:$basePath")
            } else {
                put("PATH", basePath)
            }
            put("LANG", "C.UTF-8")
            put("TERM", "xterm-256color")
            put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            putAll(environment)
        }

        // Write environment variables (including sensitive API keys/OAuth tokens) to a
        // one-time mode-0600 file inside filesDir/runtime/env and immediately unlink it
        // inside the root shell before `exec`, so secrets NEVER appear in `ps aux` or
        // `/proc/<pid>/cmdline` while surviving Magisk/KernelSU/APatch su environ sanitization.
        val runtimeEnvDir = File(runtimeDir, "env").apply {
            mkdirs()
            runCatching { Os.chmod(absolutePath, 0b111000000) } // 0700
        }
        // A launch that never reached `rm -f` (su denied, process killed) leaves API keys on
        // disk. Purge anything old enough that it cannot belong to a launch in progress.
        runtimeEnvDir.listFiles { f -> f.name.startsWith(".env-") }
            ?.filter { System.currentTimeMillis() - it.lastModified() > 10 * 60 * 1_000L }
            ?.forEach { it.delete() }
        val envFile = File(runtimeEnvDir, ".env-${java.util.UUID.randomUUID()}.sh")
        val envScript = buildString {
            for ((key, value) in guestEnv) {
                if (VALID_ENV_NAME.matches(key)) {
                    append("export ").append(key).append('=').append(shellSingleQuote(value)).append('\n')
                }
            }
        }
        val envBytes = envScript.toByteArray(Charsets.UTF_8)
        runCatching {
            val fd = Os.open(
                envFile.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_EXCL,
                0b110000000,
            )
            FileOutputStream(fd).use { it.write(envBytes) }
        }.onFailure {
            envFile.writeText(envScript)
            runCatching { Os.chmod(envFile.absolutePath, 0b110000000) } // 0600
        }

        val quotedEnvFile = shellSingleQuote(envFile.absolutePath)
        val chrootArgs = listOf(
            chrootExec.absolutePath,
            rootfs.absolutePath,
            workspace.absolutePath,
            guestWorkspacePath,
            bridge.absolutePath,
            appUid,
        ) + guestCommand
        val suCommand = ". $quotedEnvFile; rm -f $quotedEnvFile; exec " +
            chrootArgs.joinToString(" ") { shellSingleQuote(it) }

        return try {
            NativeSpawnProcess.start(
                argv = listOf("su", "-mm", "-c", suCommand),
                environment = emptyMap(),
                cwd = context.filesDir.absolutePath,
                outputFile = outputFile,
                pseudoTerminal = pseudoTerminal,
                ptyRows = ptyRows,
                ptyColumns = ptyColumns,
            )
        } catch (error: Throwable) {
            envFile.delete()
            throw error
        }
    }

    private fun shellSingleQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    fun ensureSettingsAndHooks() {
        val hook = File(rootfs, "opt/pocket/permission-hook.sh")
        hook.parentFile?.mkdirs()
        // Hands every permission request to the app through the shared bridge directory and
        // waits for the user's/guardrail decision. The app classifies risk, auto-answers
        // SAFE/REVIEW and prompts for HIGH. No answer within the timeout means deny.
        hook.writeText(
            """#!/bin/sh
BRIDGE="${'$'}{NEXTCRAFT_BRIDGE_DIR:-/pocketdev-bridge}"
ID="${'$'}(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "${'$'}${'$'}-${'$'}(date +%s)")"
DECISION=deny
if [ -d "${'$'}BRIDGE" ] && cat > "${'$'}BRIDGE/${'$'}ID.request.tmp" && mv "${'$'}BRIDGE/${'$'}ID.request.tmp" "${'$'}BRIDGE/${'$'}ID.request"; then
    i=0
    while [ "${'$'}i" -lt 600 ]; do
        if [ -f "${'$'}BRIDGE/${'$'}ID.response" ]; then
            [ "${'$'}(cat "${'$'}BRIDGE/${'$'}ID.response")" = "allow" ] && DECISION=allow
            break
        fi
        sleep 0.1
        i=${'$'}((i + 1))
    done
    rm -f "${'$'}BRIDGE/${'$'}ID.request" "${'$'}BRIDGE/${'$'}ID.request.tmp" "${'$'}BRIDGE/${'$'}ID.response"
else
    cat > /dev/null
fi
printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"%s"}}}\n' "${'$'}DECISION"
""",
        )
        Os.chmod(hook.absolutePath, 0b111101101)

        val settingsContent = JSONObject()
            .put("disableAllHooks", false)
            .put(
                "permissions",
                JSONObject()
                    .put("allow", claudeWorkspaceToolRules())
                    .put("defaultMode", "acceptEdits"),
            )
            .put(
                "hooks",
                JSONObject().put(
                    "PermissionRequest",
                    JSONArray().put(
                        JSONObject()
                            .put("matcher", "Bash|Edit|Write|NotebookEdit")
                            .put(
                                "hooks",
                                JSONArray().put(
                                    JSONObject().put("type", "command").put("command", "/opt/pocket/permission-hook.sh"),
                                ),
                            ),
                    ),
                ),
            )
            .toString()

        val settingsPaths = listOf(
            File(rootfs, "root/.claude/pocket-settings.json"),
            File(rootfs, "root/.claude/settings.json"),
            File(rootfs, "etc/claude/settings.json"),
        )
        for (target in settingsPaths) {
            target.parentFile?.mkdirs()
            target.writeText(settingsContent)
        }
        ensureWorkspaceTrust("/workspace")
    }

    private fun ensureWorkspaceTrust(workspacePath: String) {
        val stateFile = File(rootfs, "root/.claude.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
            .forEach { state.remove(it) }
        val projects = state.optJSONObject("projects") ?: JSONObject()
        val workspace = projects.optJSONObject(workspacePath) ?: JSONObject()
        workspace.put("hasTrustDialogAccepted", true)
        projects.put(workspacePath, workspace)
        state.put("projects", projects)
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(state.toString())
    }

    // Only auto-allow read-only tools. Bash/Edit/Write/NotebookEdit go through the
    // PermissionRequest hook so the app can classify and gate each command.
    private fun claudeWorkspaceToolRules() = JSONArray().apply {
        put("Read")
        put("Glob")
        put("Grep")
    }

    private fun writeResolver() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager?.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val servers = dns.mapNotNull { it.hostAddress }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        val resolvFile = File(rootfs, "etc/resolv.conf")
        resolvFile.parentFile?.mkdirs()
        if (java.nio.file.Files.isSymbolicLink(resolvFile.toPath())) {
            resolvFile.delete()
        }
        resolvFile.writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun openZstdInputStream(archive: File): java.io.InputStream {
        val rawStream = BufferedInputStream(archive.inputStream())
        return try {
            ZstdCompressorInputStream(rawStream)
        } catch (_: Throwable) {
            runCatching { rawStream.close() }
            val process = runCatching {
                ProcessBuilder("zstd", "-d", "-c", archive.absolutePath).start()
            }.getOrElse {
                val escapedPath = archive.absolutePath.replace("'", "'\"'\"'")
                val pathExport = "export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:${'$'}PATH; "
                ProcessBuilder("su", "-c", "${pathExport}zstd -d -c '$escapedPath'").start()
            }
            object : java.io.FilterInputStream(BufferedInputStream(process.inputStream)) {
                private var reachedEof = false

                override fun read(): Int = super.read().also { if (it < 0) reachedEof = true }

                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it < 0) reachedEof = true }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { process.errorStream.close() }
                        runCatching { process.outputStream.close() }
                        val finished = runCatching {
                            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                        }.getOrDefault(false)
                        if (!finished) {
                            process.destroyForcibly()
                        } else if (reachedEof && process.exitValue() != 0) {
                            throw java.io.IOException("zstd decompression failed with exit code ${process.exitValue()}")
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractZstdTar(archive: File, destination: File) {
        val rootPath = destination.canonicalFile.toPath()
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(openZstdInputStream(archive)).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val currentEntry = entry
                val cleanName = currentEntry.name.removePrefix("./").trimEnd('/')
                if (cleanName.isNotBlank()) {
                    val target = safeChild(destination, cleanName)
                    when {
                        currentEntry.isDirectory -> {
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            target.mkdirs()
                        }
                        currentEntry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            val resolvedLink = target.toPath().parent.resolve(currentEntry.linkName).normalize()
                            require(resolvedLink.startsWith(rootPath)) { "Archive symlink escapes runtime" }
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(currentEntry.linkName, target.absolutePath)
                        }
                        currentEntry.isLink -> {
                            target.parentFile?.mkdirs()
                            val linkTarget = safeChild(destination, currentEntry.linkName.removePrefix("./").trimEnd('/'))
                            if (linkTarget.exists()) {
                                linkOrCopyFile(linkTarget, target)
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        currentEntry.isFile -> {
                            target.parentFile?.mkdirs()
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, currentEntry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile && !java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                "Archive hard-link target is missing"
            }
            target.parentFile?.mkdirs()
            linkOrCopyFile(linkTarget, target)
            runCatching { Os.chmod(target.absolutePath, Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractNodeArchive(archive: File, destination: File) {
        val rootPath = destination.canonicalFile.toPath()
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val currentEntry = entry
                val relative = currentEntry.name.removePrefix("./").substringAfter('/', "").trimEnd('/')
                if (relative.isNotBlank()) {
                    val target = safeChild(destination, relative)
                    when {
                        currentEntry.isDirectory -> {
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            target.mkdirs()
                        }
                        currentEntry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            val resolvedLink = target.toPath().parent.resolve(currentEntry.linkName).normalize()
                            require(resolvedLink.startsWith(rootPath)) { "Archive symlink escapes runtime" }
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(currentEntry.linkName, target.absolutePath)
                        }
                        currentEntry.isLink -> {
                            val relativeLink = currentEntry.linkName.removePrefix("./").substringAfter('/', "").trimEnd('/')
                            val linkTarget = safeChild(destination, relativeLink)
                            target.parentFile?.mkdirs()
                            if (linkTarget.exists()) {
                                linkOrCopyFile(linkTarget, target)
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        currentEntry.isFile -> {
                            target.parentFile?.mkdirs()
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, currentEntry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile && !java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                "Node.js archive hard-link target is missing"
            }
            target.parentFile?.mkdirs()
            linkOrCopyFile(linkTarget, target)
            runCatching { Os.chmod(target.absolutePath, Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun linkOrCopyFile(source: File, destination: File) {
        if (destination.exists() || java.nio.file.Files.isSymbolicLink(destination.toPath())) destination.delete()
        val linked = runCatching {
            Os.link(source.absolutePath, destination.absolutePath)
            true
        }.getOrDefault(false)
        if (!linked) {
            source.inputStream().use { input -> FileOutputStream(destination).use { input.copyTo(it) } }
        }
    }

    private fun safeChild(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/').trimEnd('/')
        val segments = normalized.split('/')
        require(
            normalized.isNotBlank() &&
                !normalized.contains('\u0000') &&
                !normalized.startsWith('/') &&
                segments.isNotEmpty() &&
                segments.none { it.isEmpty() || it == "." || it == ".." },
        ) {
            "Unsafe archive path"
        }
        val file = File(root, normalized)
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.toPath()
        var cursor: File? = file.parentFile
        while (cursor != null && cursor != root && cursor.canonicalFile != rootCanonical) {
            val cursorPath = cursor.toPath()
            if (java.nio.file.Files.isSymbolicLink(cursorPath)) {
                val linkTarget = java.nio.file.Files.readSymbolicLink(cursorPath)
                val resolved = cursorPath.parent.resolve(linkTarget).normalize()
                require(resolved.startsWith(rootPath)) { "Archive path escapes runtime via symlink" }
            }
            require(cursor.canonicalFile.toPath().startsWith(rootPath)) { "Archive path escapes runtime" }
            cursor = cursor.parentFile
        }
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Archive path escapes runtime" }
        if (!java.nio.file.Files.isSymbolicLink(file.toPath())) {
            val canonicalFile = file.canonicalFile.toPath()
            require(canonicalFile.startsWith(rootPath) && canonicalFile != rootPath) {
                "Archive path escapes runtime"
            }
        }
        return file
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedChecksum: String,
        algorithm: String = "SHA-256",
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && digest(destination, algorithm).equals(expectedChecksum, ignoreCase = true)) {
            onBytes(destination.length(), destination.length())
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
            val code = connection.responseCode
            check(code in 200..299) { "Download failed with HTTP $code" }
            val resumed = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!resumed) {
                temporary.delete()
                existing = 0L
            }
            val total = connection.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
            connection.inputStream.use { input ->
                FileOutputStream(temporary, resumed).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = existing
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onBytes(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val actual = digest(temporary, algorithm)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            check(code in 200..299) { "Request failed with HTTP $code" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        fun rootfsDir(context: Context): File = File(context.filesDir, "runtime/ubuntu")
        fun bridgeRootDir(context: Context): File = File(context.filesDir, "runtime-bridge")

        const val AGY_GUEST_PATH = "/root/.local/bin/agy"
        const val GITHUB_CLI_GUEST_PATH = "/root/.local/bin/gh"
        private const val DEFAULT_RUNTIME_RELEASE_BASE_URL = "https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09"
        private const val AGY_VERSION = "1.1.27"
        private const val GITHUB_CLI_VERSION = "2.100.0"
        private const val GITHUB_CLI_RELEASE_URL = "https://github.com/cli/cli/releases/download/v2.100.0/gh_2.100.0_linux_arm64.tar.gz"
        private const val GITHUB_CLI_RELEASE_SHA256 = "ea4e7a581a32ccad6cc7923cb1576ac5859ba4b9a16ab22eb8f8a96e78e2e961"

        init {
            check(
                GITHUB_CLI_RELEASE_SHA256.length == 64 &&
                    GITHUB_CLI_RELEASE_SHA256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' },
            ) {
                "Invalid GITHUB_CLI_RELEASE_SHA256 length"
            }
        }
        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val ROOTFS_VERSION = "ubuntu-24.04.5-arm64"
        private const val LEGACY_ROOTFS_VERSION = "ubuntu-20.04.5-arm64"
        private const val NODE_VERSION = "v24.19.0"
        private const val LANGUAGE_TOOLS_VERSION = "node-v24.19.0-python3-v1"
        private const val CORE_TOOLS_VERSION = "core-bundle-2026.09.5"
        private const val LEGACY_CORE_TOOLS_VERSION = "core-bundle-2026.09.4"
        private const val NEXTCRAFT_CORE_TOOLS_VERSION = "nextcraft-core-2026.10.1"
        private const val SYSTEM_UPGRADE_VERSION = "ubuntu-maintenance-v1"
        private const val ANDROID_TOOLS_VERSION = "sdk36-build-tools35-gradle8.14.3-maven-2026.09"
        private const val ANDROID_AAPT2_PROPERTY = "android.aapt2FromMavenOverride"
        private const val GRADLE_JVMARGS_PROPERTY = "org.gradle.jvmargs"
        private const val GRADLE_WORKERS_PROPERTY = "org.gradle.workers.max"
        private const val GRADLE_PARALLEL_PROPERTY = "org.gradle.parallel"
        private const val KOTLIN_EXECUTION_PROPERTY = "kotlin.compiler.execution.strategy"
        private val MANAGED_ANDROID_GRADLE_PROPERTIES = setOf(
            ANDROID_AAPT2_PROPERTY,
            GRADLE_JVMARGS_PROPERTY,
            GRADLE_WORKERS_PROPERTY,
            GRADLE_PARALLEL_PROPERTY,
            KOTLIN_EXECUTION_PROPERTY,
        )
        private const val ANDROID_AAPT2_GUEST_PATH = "/root/android-sdk/build-tools/35.0.0/aapt2"
        private const val ANDROID_AAPT2_HOST_PATH = "root/android-sdk/build-tools/35.0.0/aapt2"
        private val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        const val DSH_VERSION = "0.1.2-rc.1"
        private const val DSH_ANDROID_COMPATIBILITY_VERSION = "copyfile-excl-v1"
        private val CORE_BUNDLE = RuntimeBundle(
            label = "Core",
            fileName = "pocketdev-core-arm64-2026.09.5.tar.zst",
            sha256 = "df0cf7251c74f82d424231e3804114a4ca66b16130eea9abab11e220dc7ac012",
            compressedBytes = 72_185_773L,
        )
        private const val CLAUDE_BUNDLED_VERSION = "2.1.263"
        private const val CLAUDE_GUEST_PATH = "/usr/local/bin/claude"
        private val CLAUDE_BUNDLE = RuntimeBundle(
            label = "Claude Code",
            fileName = "pocketdev-claude-arm64-2026.09.1.tar.zst",
            sha256 = "0f68e15630e8c0fc941afe3f61ab5a3eb4407b334018de6dbdabfa5eca627724",
            compressedBytes = 75_289_800L,
        )
        private val PYTHON_BUNDLE = RuntimeBundle(
            label = "Python",
            fileName = "pocketdev-python-arm64-2026.09.2.tar.zst",
            sha256 = "6b3f56f7743fec142bc045db3ea561ee83fe89af87c2799165ef60351164ef85",
            compressedBytes = 55_419_626L,
        )
        private val ANDROID_BUNDLE = RuntimeBundle(
            label = "Android",
            fileName = "pocketdev-android-arm64-2026.09.1.tar.zst",
            sha256 = "01bea058ebcb17416d1eb08c0211b3782da3228eb3c8348eb3719f2d61dd3ec6",
            compressedBytes = 569_652_007L,
        )
        private val DSH_BUNDLE = RuntimeBundle(
            label = "DeepSeek Harness",
            fileName = "pocketdev-dsh-arm64-2026.09.1.tar.zst",
            sha256 = "88e6a23ba74e1cd74a2c923b7e0d6bd78ba4b7e5f8a9b649f12bf4ffe158cce5",
            compressedBytes = 27_752_194L,
        )
        private val AGY_BUNDLE = RuntimeBundle(
            label = "Antigravity CLI",
            fileName = "pocketdev-agy-arm64-2026.09.1.tar.zst",
            sha256 = "a659ab9188956fc4721ca86fb21b5118e0e489f47a5e02ae6b4f2fb423659d78",
            compressedBytes = 41_870_025L,
        )
        private const val MAX_TERMINAL_LINE = 500
        private const val MAX_COLLECTED_OUTPUT = 24_000
        private const val MAX_PROCESS_OUTPUT_BYTES = 256 * 1024
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
        private val VALID_ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        private val CHROOT_EXEC_SCRIPT = """
            #!/system/bin/sh
            set -e

            if [ "${'$'}#" -lt 6 ]; then
                echo "Usage: chroot-exec.sh <rootfs> <host_workspace> <guest_workspace> <host_bridge> <app_uid> <cmd...>" >&2
                exit 2
            fi

            ROOTFS="${'$'}1"
            HOST_WORKSPACE="${'$'}2"
            GUEST_WORKSPACE="${'$'}3"
            HOST_BRIDGE="${'$'}4"
            APP_UID="${'$'}5"
            shift 5

            if [ -z "${'$'}ROOTFS" ] || [ -z "${'$'}HOST_WORKSPACE" ] || [ -z "${'$'}GUEST_WORKSPACE" ] || [ -z "${'$'}HOST_BRIDGE" ] || [ -z "${'$'}APP_UID" ]; then
                echo "Usage: chroot-exec.sh <rootfs> <host_workspace> <guest_workspace> <host_bridge> <app_uid> <cmd...>" >&2
                exit 2
            fi

            case "${'$'}GUEST_WORKSPACE" in
                *..*|/workspace/.|/workspace/*/*)
                    echo "Invalid guest workspace path: ${'$'}GUEST_WORKSPACE" >&2
                    exit 65
                    ;;
                /root|/workspace|/workspace/[a-zA-Z0-9._-]*) ;;
                *)
                    echo "Invalid guest workspace path: ${'$'}GUEST_WORKSPACE" >&2
                    exit 65
                    ;;
            esac

            case "${'$'}APP_UID" in
                ''|*[!0-9]*)
                    echo "Invalid app UID: ${'$'}APP_UID" >&2
                    exit 65
                    ;;
            esac

            is_mounted() {
                awk -v target="${'$'}1" '${'$'}2 == target { found=1; exit } END { exit !found }' /proc/mounts
            }

            mount_once() {
                local src="${'$'}1"
                local dst="${'$'}2"
                local fstype="${'$'}3"
                local opts="${'$'}4"
                mkdir -p "${'$'}dst"
                if ! is_mounted "${'$'}dst"; then
                    if [ "${'$'}fstype" = "bind" ]; then
                        mount -o bind "${'$'}src" "${'$'}dst"
                        if [ "${'$'}opts" = "ro" ]; then
                            mount -o remount,bind,ro "${'$'}dst" 2>/dev/null || true
                        fi
                    else
                        mount -t "${'$'}fstype" -o "${'$'}opts" "${'$'}src" "${'$'}dst"
                    fi
                fi
            }

            mount_dynamic_bind() {
                local src="${'$'}1"
                local dst="${'$'}2"
                local state_dir="${'$'}ROOTFS/tmp/.nextcraft-mounts"
                local state_key
                state_key=${'$'}(printf '%s' "${'$'}dst" | tr '/' '_')
                local state_file="${'$'}state_dir/${'$'}state_key"
                mkdir -p "${'$'}src" "${'$'}dst" "${'$'}state_dir"
                if is_mounted "${'$'}dst"; then
                    local prev_src=""
                    [ -f "${'$'}state_file" ] && prev_src=${'$'}(cat "${'$'}state_file" 2>/dev/null || true)
                    if [ "${'$'}prev_src" != "${'$'}src" ]; then
                        umount "${'$'}dst" 2>/dev/null || umount -l "${'$'}dst" 2>/dev/null || true
                    fi
                fi
                if ! is_mounted "${'$'}dst"; then
                    mount -o bind "${'$'}src" "${'$'}dst"
                    printf '%s' "${'$'}src" > "${'$'}state_file"
                fi
            }

            mount -o remount,suid,exec /data 2>/dev/null || true

            mount_once "/dev" "${'$'}ROOTFS/dev" "bind" "rw"
            mount_once "/dev/pts" "${'$'}ROOTFS/dev/pts" "bind" "rw"
            mount_once "proc" "${'$'}ROOTFS/proc" "proc" "rw,nosuid,nodev,noexec"
            mount_once "sysfs" "${'$'}ROOTFS/sys" "sysfs" "rw,nosuid,nodev,noexec"
            mount_once "tmpfs" "${'$'}ROOTFS/tmp" "tmpfs" "rw,nosuid,nodev,mode=1777"
            mount_once "tmpfs" "${'$'}ROOTFS/dev/shm" "tmpfs" "rw,nosuid,nodev,mode=1777,size=256M"

            for bionic_dir in /system /apex /vendor /product; do
                if [ -d "${'$'}bionic_dir" ]; then
                    mount_once "${'$'}bionic_dir" "${'$'}ROOTFS${'$'}bionic_dir" "bind" "ro"
                fi
            done

            mkdir -p "${'$'}HOST_WORKSPACE" "${'$'}HOST_BRIDGE"
            if [ "${'$'}HOST_WORKSPACE" != "${'$'}ROOTFS${'$'}GUEST_WORKSPACE" ]; then
                mount_dynamic_bind "${'$'}HOST_WORKSPACE" "${'$'}ROOTFS${'$'}GUEST_WORKSPACE"
            fi
            mount_dynamic_bind "${'$'}HOST_BRIDGE" "${'$'}ROOTFS/nextcraft-bridge"
            mount_dynamic_bind "${'$'}HOST_BRIDGE" "${'$'}ROOTFS/pocketdev-bridge"
            mount_dynamic_bind "${'$'}HOST_BRIDGE" "${'$'}ROOTFS/pocket-bridge"

            if [ ! -s "${'$'}ROOTFS/etc/resolv.conf" ]; then
                mkdir -p "${'$'}ROOTFS/etc"
                printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "${'$'}ROOTFS/etc/resolv.conf"
            fi

            fix_workspace_ownership() {
                case "${'$'}HOST_WORKSPACE" in
                    "${'$'}ROOTFS"|"${'$'}ROOTFS"/*)
                        ;;
                    *)
                        chown -R "${'$'}APP_UID:${'$'}APP_UID" "${'$'}HOST_WORKSPACE" 2>/dev/null || true
                        chmod -R u+rwX,g+rwX "${'$'}HOST_WORKSPACE" 2>/dev/null || true
                        ;;
                esac
                chown -R "${'$'}APP_UID:${'$'}APP_UID" "${'$'}HOST_BRIDGE" 2>/dev/null || true
                chmod -R u+rwX,g+rwX "${'$'}HOST_BRIDGE" 2>/dev/null || true
            }
            trap fix_workspace_ownership EXIT INT TERM

            unset ANDROID_ROOT ANDROID_DATA ANDROID_ART_ROOT ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT
            unset ANDROID_ASSETS ANDROID_BOOTLOGO ANDROID_RUNTIME_ROOT ANDROID_STORAGE
            unset ASEC_MOUNTPOINT BOOTCLASSPATH DEX2OATBOOTCLASSPATH EXTERNAL_STORAGE SYSTEMSERVERCLASSPATH
            unset LD_PRELOAD LD_LIBRARY_PATH

            export HOME="/root"
            export USER="root"
            export LOGNAME="root"
            export TERM="${'$'}{TERM:-xterm-256color}"
            export LANG="${'$'}{LANG:-C.UTF-8}"
            export TMPDIR="/tmp"
            export CLAUDE_CONFIG_DIR="/root/.claude"
            export SSL_CERT_FILE="/etc/ssl/certs/ca-certificates.crt"
            export CURL_CA_BUNDLE="/etc/ssl/certs/ca-certificates.crt"
            export NODE_EXTRA_CA_CERTS="/etc/ssl/certs/ca-certificates.crt"
            export REQUESTS_CA_BUNDLE="/etc/ssl/certs/ca-certificates.crt"
            export GIT_SSL_CAINFO="/etc/ssl/certs/ca-certificates.crt"
            export NODE_OPTIONS="${'$'}{NODE_OPTIONS:---dns-result-order=ipv4first}"
            export COREPACK_ENABLE_DOWNLOAD_PROMPT="0"
            export GLIBC_TUNABLES="glibc.pthread.rseq=0"
            export NEXTCRAFT_BRIDGE_DIR="/pocketdev-bridge"
            export POCKETDEV_BRIDGE_DIR="/pocketdev-bridge"
            export PATH="${'$'}{PATH:-/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
            case ":${'$'}PATH:" in
                *:/root/.local/bin:*) ;;
                *) export PATH="/opt/nextcraft/bin:/root/.local/bin:${'$'}PATH" ;;
            esac

            CHROOT_BIN=""
            for candidate in /system/bin/chroot /system/xbin/chroot /sbin/chroot /debug_ramdisk/chroot "${'$'}ROOTFS/usr/sbin/chroot"; do
                if [ -x "${'$'}candidate" ]; then
                    CHROOT_BIN="${'$'}candidate"
                    break
                fi
            done
            if [ -z "${'$'}CHROOT_BIN" ]; then
                if command -v busybox >/dev/null 2>&1; then
                    CHROOT_BIN="busybox chroot"
                else
                    echo "chroot binary not found on host or guest rootfs" >&2
                    exit 127
                fi
            fi

            ${'$'}CHROOT_BIN "${'$'}ROOTFS" /usr/bin/env \
                GUEST_WORKSPACE="${'$'}GUEST_WORKSPACE" \
                /bin/sh -c 'cd "${'$'}GUEST_WORKSPACE" && exec "${'$'}@"' -- "${'$'}@"
        """.trimIndent() + "\n"

        private val UNMOUNT_CHROOT_SCRIPT = """
            #!/system/bin/sh
            ROOTFS="${'$'}1"
            if [ -z "${'$'}ROOTFS" ]; then
                echo "Usage: unmount-chroot.sh <rootfs_path>" >&2
                exit 2
            fi
            ROOTFS_REAL=${'$'}(realpath "${'$'}ROOTFS" 2>/dev/null || printf '%s' "${'$'}ROOTFS")
            ROOTFS_CLEAN="${'$'}{ROOTFS_REAL%/}"
            for pass in 1 2 3; do
                MOUNTS=${'$'}(awk -v prefix="${'$'}ROOTFS_CLEAN/" -v exact="${'$'}ROOTFS_CLEAN" 'index(${'$'}2, prefix) == 1 || ${'$'}2 == exact { print ${'$'}2 }' /proc/mounts | sort -r)
                if [ -z "${'$'}MOUNTS" ]; then
                    break
                fi
                for mnt in ${'$'}MOUNTS; do
                    if [ "${'$'}pass" -lt 3 ]; then
                        umount "${'$'}mnt" 2>/dev/null || true
                    else
                        umount -l "${'$'}mnt" 2>/dev/null || umount -f "${'$'}mnt" 2>/dev/null || true
                    fi
                done
            done
            if awk -v prefix="${'$'}ROOTFS_CLEAN/" -v exact="${'$'}ROOTFS_CLEAN" 'index(${'$'}2, prefix) == 1 || ${'$'}2 == exact { found=1; exit } END { exit !found }' /proc/mounts; then
                exit 1
            fi
            rm -rf "${'$'}ROOTFS_CLEAN/tmp/.nextcraft-mounts" 2>/dev/null || true
            exit 0
        """.trimIndent() + "\n"
    }
}
```

## 7. `app/src/main/cpp/forge_spawn.c` (297 baris)

```c
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void close_pair(int pair[2]) {
    if (pair[0] >= 0) close(pair[0]);
    if (pair[1] >= 0) close(pair[1]);
}

/**
 * Closes all inherited file descriptors >= 3 in the forked child before execve/execlp
 * so JVM sockets (LocalFormatGateway, OpenRouterRoutingGateway), Keystore/DataStore FDs,
 * and sibling PTY master FDs never leak into child processes.
 */
static void close_extra_fds(void) {
    DIR *dir = opendir("/proc/self/fd");
    if (dir != NULL) {
        int dir_fd = dirfd(dir);
        struct dirent *entry;
        while ((entry = readdir(dir)) != NULL) {
            int fd = atoi(entry->d_name);
            if (fd >= 3 && fd != dir_fd) {
                close(fd);
            }
        }
        closedir(dir);
        return;
    }
    long max_fd = sysconf(_SC_OPEN_MAX);
    if (max_fd < 0 || max_fd > 65536) max_fd = 1024;
    for (int fd = 3; fd < (int)max_fd; fd++) {
        close(fd);
    }
}

/**
 * Kills and reaps a newly-forked child on early error paths so it never becomes a zombie (<defunct>).
 */
static void kill_and_reap_child(pid_t pid) {
    if (pid <= 0) return;
    kill(pid, SIGKILL);
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) break;
    }
}

static void free_spawn_allocations(char **argv, jsize argc, char **envp, jsize envc,
                                   char *cwd, char *output_path) {
    if (argv) {
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
    }
    if (envp) {
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(envp);
    }
    free(cwd);
    free(output_path);
}

/**
 * Mengirim sinyal ke grup proses (-pid) yang telah ter-elevasi ke UID 0 (root) via `su`.
 * Dipanggil ketika kill(-pid, signal) dari UID aplikasi (u0_aXXX) ditolak kernel dengan EPERM.
 */
static int kill_root_process_group_via_su(int pid, int sig) {
    char cmd[128];
    snprintf(cmd, sizeof(cmd),
             "kill -%d -%d 2>/dev/null || kill -%d %d 2>/dev/null",
             sig, pid, sig, pid);

    pid_t helper = fork();
    if (helper < 0) return -1;
    if (helper == 0) {
        prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
        int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
        if (null_fd >= 0) {
            dup2(null_fd, STDIN_FILENO);
            dup2(null_fd, STDOUT_FILENO);
            dup2(null_fd, STDERR_FILENO);
            if (null_fd > STDERR_FILENO) close(null_fd);
        }
        close_extra_fds();
        execlp("su", "su", "-c", cmd, (char *)NULL);
        _exit(127);
    }
    int status = 0;
    while (waitpid(helper, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    return (WIFEXITED(status) && WEXITSTATUS(status) == 0) ? 0 : -1;
}

JNIEXPORT jintArray JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_spawn(JNIEnv *env, jobject self, jobjectArray java_argv,
                                                   jobjectArray java_env, jstring java_cwd,
                                                   jstring java_output, jboolean use_pty,
                                                   jint pty_rows, jint pty_columns) {
    (void)self;
    if (!java_argv || !java_env || !java_cwd || !java_output) return NULL;
    jsize argc = (*env)->GetArrayLength(env, java_argv);
    jsize envc = (*env)->GetArrayLength(env, java_env);
    if (argc <= 0) return NULL;
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!argv || !envp) {
        free(argv);
        free(envp);
        return NULL;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        const char *utf = value ? (*env)->GetStringUTFChars(env, value, NULL) : NULL;
        argv[i] = strdup(utf ? utf : "");
        if (utf) (*env)->ReleaseStringUTFChars(env, value, utf);
        if (value) (*env)->DeleteLocalRef(env, value);
        if (!argv[i]) {
            free_spawn_allocations(argv, argc, envp, envc, NULL, NULL);
            return NULL;
        }
    }
    for (jsize i = 0; i < envc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_env, i);
        const char *utf = value ? (*env)->GetStringUTFChars(env, value, NULL) : NULL;
        envp[i] = strdup(utf ? utf : "");
        if (utf) (*env)->ReleaseStringUTFChars(env, value, utf);
        if (value) (*env)->DeleteLocalRef(env, value);
        if (!envp[i]) {
            free_spawn_allocations(argv, argc, envp, envc, NULL, NULL);
            return NULL;
        }
    }
    const char *cwd_utf = (*env)->GetStringUTFChars(env, java_cwd, NULL);
    char *cwd = strdup(cwd_utf ? cwd_utf : "/");
    if (cwd_utf) (*env)->ReleaseStringUTFChars(env, java_cwd, cwd_utf);

    const char *output_utf = (*env)->GetStringUTFChars(env, java_output, NULL);
    char *output_path = strdup(output_utf ? output_utf : "/dev/null");
    if (output_utf) (*env)->ReleaseStringUTFChars(env, java_output, output_utf);

    if (!cwd || !output_path) {
        free_spawn_allocations(argv, argc, envp, envc, cwd, output_path);
        return NULL;
    }

    int in_pipe[2] = {-1, -1};
    int master_fd = -1;
    char slave_name[128];
    slave_name[0] = '\0';
    if (use_pty) {
        master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
        if (master_fd < 0 || grantpt(master_fd) != 0 || unlockpt(master_fd) != 0 ||
            ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
            if (master_fd >= 0) close(master_fd);
            free_spawn_allocations(argv, argc, envp, envc, cwd, output_path);
            return NULL;
        }
    } else if (pipe2(in_pipe, O_CLOEXEC) != 0) {
        free_spawn_allocations(argv, argc, envp, envc, cwd, output_path);
        return NULL;
    }

    pid_t parent_pid_before_fork = getpid();
    pid_t pid = fork();
    if (pid == 0) {
        // Ensure child receives SIGTERM if parent JVM process dies unexpectedly
        prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0);
        if (getppid() != parent_pid_before_fork) {
            _exit(126);
        }
        if (use_pty) {
            if (setsid() < 0) _exit(126);
            int slave_fd = open(slave_name, O_RDWR | O_CLOEXEC);
            if (slave_fd < 0 || ioctl(slave_fd, TIOCSCTTY, 0) != 0) _exit(126);
            struct winsize size = {
                .ws_row = (unsigned short)(pty_rows > 0 ? pty_rows : 40),
                .ws_col = (unsigned short)(pty_columns > 0 ? pty_columns : 120),
            };
            ioctl(slave_fd, TIOCSWINSZ, &size);
            struct termios terminal;
            if (tcgetattr(slave_fd, &terminal) == 0) {
                terminal.c_lflag &= (tcflag_t)~(ECHO | ECHONL);
                tcsetattr(slave_fd, TCSANOW, &terminal);
            }
            dup2(slave_fd, STDIN_FILENO);
            dup2(slave_fd, STDOUT_FILENO);
            dup2(slave_fd, STDERR_FILENO);
            if (slave_fd > STDERR_FILENO) close(slave_fd);
            close(master_fd);
        } else {
            // Setiap peluncuran Chroot mendapat process group sendiri (PGID == pid)
            // agar pembatalan menghentikan seluruh pohon proses di bawahnya.
            setpgid(0, 0);
            close(in_pipe[1]);
            int output_fd = open(output_path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
            if (output_fd < 0) _exit(126);
            dup2(in_pipe[0], STDIN_FILENO);
            dup2(output_fd, STDOUT_FILENO);
            dup2(output_fd, STDERR_FILENO);
            close(in_pipe[0]);
            close(output_fd);
        }
        close_extra_fds();
        if (chdir(cwd) != 0 && chdir("/") != 0) {
            _exit(126);
        }
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        execve(argv[0], argv, envp);
        dprintf(STDERR_FILENO, "NextCraft native exec failed: %s\n", strerror(errno));
        _exit(127);
    }

    if (pid > 0 && !use_pty) setpgid(pid, pid);
    free_spawn_allocations(argv, argc, envp, envc, cwd, output_path);

    if (pid < 0) {
        if (use_pty) close(master_fd); else close_pair(in_pipe);
        return NULL;
    }
    if (!use_pty) close(in_pipe[0]);
    int input_fd = use_pty ? fcntl(master_fd, F_DUPFD_CLOEXEC, 0) : in_pipe[1];
    if (input_fd < 0) {
        if (use_pty) close(master_fd);
        kill_and_reap_child(pid);
        return NULL;
    }
    jint values[3] = {pid, input_fd, use_pty ? master_fd : -1};
    jintArray result = (*env)->NewIntArray(env, 3);
    if (!result) {
        close(input_fd);
        if (use_pty) close(master_fd);
        kill_and_reap_child(pid);
        return NULL;
    }
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_waitFor(JNIEnv *env, jobject self, jint pid, jboolean no_hang) {
    (void)env;
    (void)self;
    int status = 0;
    pid_t value;
    do {
        value = waitpid((pid_t)pid, &status, no_hang ? WNOHANG : 0);
    } while (value < 0 && errno == EINTR && !no_hang);

    if (value == 0) return -2;
    if (value < 0) return -128 - errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_kill(JNIEnv *env, jobject self, jint pid, jint signal) {
    (void)env;
    (void)self;
    if (pid <= 0) return -1;
    // Target seluruh process group (-pid) terlebih dahulu.
    int result = kill((pid_t)(-pid), signal);
    if (result != 0 && errno == ESRCH) {
        result = kill((pid_t)pid, signal);
    }
    // Jika proses berjalan sebagai UID 0 (root) di dalam Chroot, kill() dari UID app (u0_aXXX)
    // akan gagal dengan errno == EPERM. Eskalasi otomatis sinyal tersebut via su -c!
    if (result != 0 && errno == EPERM) {
        result = kill_root_process_group_via_su((int)pid, (int)signal);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_resizePty(JNIEnv *env, jobject self,
                                                       jint master_fd, jint rows, jint columns) {
    (void)env;
    (void)self;
    if (master_fd < 0) return -EBADF;
    struct winsize size = {
        .ws_row = (unsigned short)(rows > 0 ? rows : 40),
        .ws_col = (unsigned short)(columns > 0 ? columns : 120),
    };
    return ioctl(master_fd, TIOCSWINSZ, &size) == 0 ? 0 : -errno;
}
```

## 8. `app/src/main/cpp/CMakeLists.txt` (23 baris)

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(nextcraft_forge_native C)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "NextCraft Forge currently supports only arm64-v8a")
endif()

# Pure Root Chroot Edition: Hanya membangun libforgespawn.so
# (talloc, android-shmem, proot, prootloader, dan executable_carrier dihapus total)
add_library(forgespawn SHARED forge_spawn.c)

target_compile_options(forgespawn PRIVATE
    -Wall
    -Wextra
    -O2
    -fPIC
)

# Keamanan stack non-executable + kompatibilitas 16 KB page size (Android 15/16)
target_link_options(forgespawn PRIVATE
    "-Wl,-z,noexecstack"
    "-Wl,-z,max-page-size=16384"
)
```

## 9. `scripts/runtime-bundles/chroot-exec.sh` (166 baris)

```bash
#!/system/bin/sh
# scripts/runtime-bundles/chroot-exec.sh
# NextCraft Forge (com.nextcraft.forge) — Pure Root Chroot Supervisor Script
# Dipanggil via:
#   su -mm -c "/data/data/com.nextcraft.forge/files/runtime/bin/chroot-exec.sh <rootfs> <workspace_host> <guest_workspace> <bridge_host> <app_uid> <cmd...>"
set -e

if [ "$#" -lt 6 ]; then
    echo "Usage: $0 <rootfs> <workspace_host> <guest_workspace> <bridge_host> <app_uid> <command...>" >&2
    exit 64
fi

ROOTFS="$1"
WORKSPACE_HOST="$2"
GUEST_WORKSPACE="$3"
BRIDGE_HOST="$4"
APP_UID="$5"
shift 5

# Validasi keamanan path guest workspace (wajib /root, /workspace, atau /workspace/<slug> tanpa '..' atau nested '/')
case "$GUEST_WORKSPACE" in
    *..*|/workspace/.|/workspace/*/*)
        echo "Invalid guest workspace path: $GUEST_WORKSPACE" >&2
        exit 65
        ;;
    /root|/workspace|/workspace/[a-zA-Z0-9._-]*) ;;
    *)
        echo "Invalid guest workspace path: $GUEST_WORKSPACE" >&2
        exit 65
        ;;
esac

case "$APP_UID" in
    ''|*[!0-9]*)
        echo "Invalid app UID: $APP_UID" >&2
        exit 65
        ;;
esac

is_mounted() {
    awk -v target="$1" '$2 == target { found=1; exit } END { exit !found }' /proc/mounts
}

# Exit Trap: Kembalikan kepemilikan file workspace & bridge ke UID aplikasi Android (u0_aXXX)
# Mencegah bug EACCES pada WorkspaceCheckpoints.kt setelah proses Chroot (UID 0) selesai,
# sekaligus mencegah chown rekursif jika WORKSPACE_HOST berada di dalam ROOTFS (misal /root)!
cleanup_ownership() {
    case "$WORKSPACE_HOST" in
        "$ROOTFS"|"$ROOTFS"/*)
            ;;
        *)
            chown -R "$APP_UID:$APP_UID" "$WORKSPACE_HOST" 2>/dev/null || true
            chmod -R u+rwX,g+rwX "$WORKSPACE_HOST" 2>/dev/null || true
            ;;
    esac
    chown -R "$APP_UID:$APP_UID" "$BRIDGE_HOST" 2>/dev/null || true
    chmod -R u+rwX,g+rwX "$BRIDGE_HOST" 2>/dev/null || true
}
trap cleanup_ownership EXIT INT TERM

# Fungsi Idempotent Mount untuk direktori sistem statis
mount_once() {
    local fstype="$1"
    local src="$2"
    local dst="$3"
    local opts="$4"
    mkdir -p "$dst"
    if ! is_mounted "$dst"; then
        if [ "$fstype" = "bind" ]; then
            mount --bind "$src" "$dst"
            if [ "$opts" = "ro" ]; then
                mount -o remount,ro,bind "$dst" 2>/dev/null || true
            fi
        else
            mount -t "$fstype" -o "$opts" "$src" "$dst"
        fi
    fi
}

# Fungsi Dynamic Bind Mount: mendeteksi pergantian proyek (misal pindah dari proj-A ke proj-B)
# Menggunakan state file per-destination agar sesi paralel (mis. probe hello vs workspace) tidak saling menimpa state
mount_dynamic_bind() {
    local src="$1"
    local dst="$2"
    local state_dir="$ROOTFS/tmp/.nextcraft-mounts"
    local state_key
    state_key=$(printf '%s' "$dst" | tr '/' '_')
    local state_file="$state_dir/$state_key"
    mkdir -p "$src" "$dst" "$state_dir"
    local current_src=""
    if [ -f "$state_file" ]; then
        current_src=$(cat "$state_file" 2>/dev/null || true)
    fi
    if is_mounted "$dst"; then
        if [ "$current_src" != "$src" ]; then
            umount "$dst" 2>/dev/null || umount -lf "$dst" 2>/dev/null || true
        fi
    fi
    if ! is_mounted "$dst"; then
        mount --bind "$src" "$dst"
        printf '%s' "$src" > "$state_file"
    fi
}

# 1. Pastikan partisi /data mengizinkan exec & suid di dalam mount namespace ini
mount -o remount,exec,suid /data 2>/dev/null || true

# 2. Mount virtual filesystems kernel Linux
# Bind-mount /dev dan /dev/pts dari host agar slave PTY yang dibuka oleh forge_spawn.c
# di sisi host memiliki inode yang 100% identik di dalam Chroot (/dev/pts/<N>).
mount_once "bind"  "/dev"     "$ROOTFS/dev"     "rw"
mount_once "bind"  "/dev/pts" "$ROOTFS/dev/pts" "rw"
mount_once "proc"  "proc"     "$ROOTFS/proc"    "rw,nosuid,nodev,noexec,relatime"
mount_once "sysfs" "sysfs"    "$ROOTFS/sys"     "rw,nosuid,nodev,noexec,relatime"
mount_once "tmpfs" "tmpfs"    "$ROOTFS/tmp"     "rw,nosuid,nodev,mode=1777"
mount_once "tmpfs" "tmpfs"    "$ROOTFS/dev/shm" "rw,nosuid,nodev,mode=1777"

# 3. Read-Only Bind Mount partisi Bionic Android (Agar binary Bionic aapt2 jalan di dalam Ubuntu 24.04)
for sysdir in /system /apex /vendor /product; do
    if [ -d "$sysdir" ]; then
        mount_once "bind" "$sysdir" "$ROOTFS$sysdir" "ro"
    fi
done

# 4. Dynamic Bind Mount Workspace Proyek & Folder IPC Bridge
# Mendukung /nextcraft-bridge, /pocketdev-bridge, dan /pocket-bridge untuk kompatibilitas penuh dengan hook agen
if [ "$WORKSPACE_HOST" != "$ROOTFS$GUEST_WORKSPACE" ]; then
    mount_dynamic_bind "$WORKSPACE_HOST" "$ROOTFS$GUEST_WORKSPACE"
fi
mount_dynamic_bind "$BRIDGE_HOST" "$ROOTFS/nextcraft-bridge"
mount_dynamic_bind "$BRIDGE_HOST" "$ROOTFS/pocketdev-bridge"
mount_dynamic_bind "$BRIDGE_HOST" "$ROOTFS/pocket-bridge"

# 5. Pastikan DNS resolver tersedia
if [ ! -s "$ROOTFS/etc/resolv.conf" ]; then
    printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "$ROOTFS/etc/resolv.conf"
fi

# 6. Bersihkan variabel environment spesifik Android Bionic tanpa menghapus variabel AI/Gateway
# (ANTHROPIC_BASE_URL, ANTHROPIC_API_KEY, OPENAI_API_KEY, CLAUDE_*, DEEPSEEK_*, SSH_CONNECTION tetap utuh!)
unset LD_LIBRARY_PATH LD_PRELOAD
unset ANDROID_ASSETS ANDROID_BOOTLOGO ANDROID_DATA ANDROID_I18N_ROOT
unset ANDROID_ROOT ANDROID_RUNTIME_ROOT ANDROID_STORAGE ANDROID_TZDATA_ROOT
unset ASEC_MOUNTPOINT BOOTCLASSPATH DEX2OATBOOTCLASSPATH EXTERNAL_STORAGE SYSTEMSERVERCLASSPATH

export HOME="/root"
export USER="root"
export LOGNAME="root"
export LANG="${LANG:-C.UTF-8}"
export TERM="${TERM:-xterm-256color}"
export GLIBC_TUNABLES="glibc.pthread.rseq=0"

BASE_PATH="/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
if [ -d "$ROOTFS/root/android-sdk" ]; then
    export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
    export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/root/android-sdk}"
    export GRADLE_HOME="${GRADLE_HOME:-/opt/gradle/gradle-8.14.3}"
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
    export ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride="/root/android-sdk/build-tools/35.0.0/aapt2"
    export PATH="/opt/gradle/gradle-8.14.3/bin:/root/android-sdk/build-tools/35.0.0:/root/android-sdk/cmdline-tools/latest/bin:$BASE_PATH"
else
    export PATH="$BASE_PATH"
fi

# 7. Masuk ke Chroot Ubuntu 24.04 LTS ARM64, pindah ke direktori $GUEST_WORKSPACE, dan eksekusi perintah
chroot "$ROOTFS" /bin/sh -c 'cd "$1" && shift && exec "$@"' -- "$GUEST_WORKSPACE" "$@"
```

## 10. `scripts/runtime-bundles/unmount-chroot.sh` (38 baris)

```bash
#!/system/bin/sh
# scripts/runtime-bundles/unmount-chroot.sh
# NextCraft Forge (com.nextcraft.forge) — Safe Chroot Unmount Guard
# WAJIB dijalankan via `su -mm` dan `su` sebelum RootfsRepair menghapus atau me-rename direktori rootfs!
set -e

if [ -z "$1" ]; then
    echo "Usage: $0 <rootfs_canonical_path>" >&2
    exit 64
fi

ROOTFS_REAL=$(realpath "$1" 2>/dev/null || printf '%s' "$1")
ROOTFS_PATH="${ROOTFS_REAL%/}"

# Lepaskan seluruh mount di bawah $ROOTFS_PATH dari urutan terdalam ke terluar (sort -r)
for pass in 1 2 3; do
    MOUNTS=$(awk -v prefix="${ROOTFS_PATH}/" -v exact="${ROOTFS_PATH}" 'index($2, prefix) == 1 || $2 == exact { print $2 }' /proc/mounts | sort -r)
    if [ -z "$MOUNTS" ]; then
        break
    fi
    for mp in $MOUNTS; do
        if [ "$pass" -lt 3 ]; then
            umount "$mp" 2>/dev/null || true
        else
            umount -lf "$mp" 2>/dev/null || umount -f "$mp" 2>/dev/null || true
        fi
    done
done

# Verifikasi keras: tolak (exit 1) jika masih ada satu pun mount kernel yang tersisa di bawah $ROOTFS_PATH
if awk -v prefix="${ROOTFS_PATH}/" -v exact="${ROOTFS_PATH}" 'index($2, prefix) == 1 || $2 == exact { found=1; exit } END { exit !found }' /proc/mounts; then
    echo "CRITICAL: Active kernel mounts still remain under $ROOTFS_PATH:" >&2
    awk -v prefix="${ROOTFS_PATH}/" -v exact="${ROOTFS_PATH}" 'index($2, prefix) == 1 || $2 == exact { print $2 }' /proc/mounts >&2
    exit 1
fi

rm -rf "$ROOTFS_PATH/tmp/.nextcraft-mounts" 2>/dev/null || true
exit 0
```

## 11. `scripts/runtime-bundles/build-core-on-rooted-android.sh` (65 baris)

```bash
#!/usr/bin/env bash
set -euo pipefail

# Build the portable NextCraft Forge Core bundle (Ubuntu 24.04.5 LTS noble ARM64)
# on a rooted ARM64 Android phone. The phone is only the build host: the archive
# is made from the official Ubuntu 24.04.5 ARM64 rootfs.
ADB_SERIAL="${ADB_SERIAL:-}"
REMOTE="${NEXTCRAFT_REMOTE_DIR:-/data/adb/nextcraft-bundle}"
VERSION="${NEXTCRAFT_CORE_VERSION:-2026.10.1}"
DNS_SERVER="${NEXTCRAFT_DNS:-1.1.1.1}"
ROOTFS_FILE="ubuntu-base-24.04.5-base-arm64.tar.gz"
ROOTFS_SHA256="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/${ROOTFS_FILE}"

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi
}

[[ "$(uname -m)" == "arm64" || "$(uname -m)" == "aarch64" ]] || {
  echo "Run this from an ARM64 workstation controlling the rooted phone." >&2
  exit 1
}
adb_cmd wait-for-device
adb_cmd shell "su -c 'id -u'" | grep -qx 0 || { echo "ADB root is unavailable" >&2; exit 1; }

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
curl -fL --retry 3 -o "$temp_dir/$ROOTFS_FILE" "$ROOTFS_URL"
printf '%s  %s\n' "$ROOTFS_SHA256" "$temp_dir/$ROOTFS_FILE" | shasum -a 256 -c -
adb_cmd push "$temp_dir/$ROOTFS_FILE" /data/local/tmp/"$ROOTFS_FILE"

adb_cmd shell "su -c 'rm -rf $REMOTE; mkdir -p $REMOTE/rootfs $REMOTE/output; toybox tar -xzf /data/local/tmp/$ROOTFS_FILE -C $REMOTE/rootfs; rm -f /data/local/tmp/$ROOTFS_FILE'"
adb_cmd shell "su -c 'mount --bind /dev $REMOTE/rootfs/dev; mount --bind /dev/pts $REMOTE/rootfs/dev/pts; mount -t proc proc $REMOTE/rootfs/proc; mount -t sysfs sysfs $REMOTE/rootfs/sys'"

guest() {
  local command="$1"
  adb_cmd shell "su -c 'chroot $REMOTE/rootfs /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 GLIBC_TUNABLES=glibc.pthread.rseq=0 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -lc \"$command\"'"
}

# 1. Konfigurasi Android Paranoid Network (AID_INET = 3003) & APT Non-Sandbox untuk Chroot Android
guest "grep -q '^aid_inet:' /etc/group || printf 'aid_inet:x:3003:root,_apt\naid_net_raw:x:3004:root,_apt\n' >> /etc/group"
guest "printf 'APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n' > /etc/apt/apt.conf.d/99android-chroot"

# 2. Update sistem Ubuntu 24.04.5 LTS & instal kakas dasar
guest "printf 'nameserver $DNS_SERVER\\n' > /etc/resolv.conf; apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade"
guest "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git ca-certificates curl wget unzip zip xz-utils zstd jq ripgrep"

# 3. Instal Node.js v24.19.0 ARM64 dengan verifikasi SHA256 resmi
guest "set -e; NODE_VERSION=v24.19.0; NODE_FILE=node-\${NODE_VERSION}-linux-arm64.tar.gz; cd /tmp; curl -fsSLO https://nodejs.org/dist/\${NODE_VERSION}/\${NODE_FILE}; curl -fsSL https://nodejs.org/dist/\${NODE_VERSION}/SHASUMS256.txt | grep \"  \${NODE_FILE}\" | sha256sum -c -; mkdir -p /usr/local/lib/nodejs; tar -xzf \${NODE_FILE} -C /usr/local/lib/nodejs; ln -sfn /usr/local/lib/nodejs/node-\${NODE_VERSION} /usr/local/lib/nodejs/current; ln -sfn /usr/local/lib/nodejs/current/bin/node /usr/local/bin/node; ln -sfn /usr/local/lib/nodejs/current/bin/npm /usr/local/bin/npm; ln -sfn /usr/local/lib/nodejs/current/bin/npx /usr/local/bin/npx; rm -f /tmp/\${NODE_FILE}"
guest "apt-get clean && rm -rf /var/lib/apt/lists/* /var/cache/apt/* /tmp/* /var/tmp/*"

# 4. Siapkan direktori mount (/workspace/nextcraft, /nextcraft-bridge) & version markers
guest "mkdir -p /workspace /workspace/nextcraft /nextcraft-bridge /pocket-bridge /opt/nextcraft /opt/pocketdev /root/.gradle/init.d /system /apex /vendor /product"
guest "printf 'ubuntu-24.04.5-arm64\\n' > /.nextcraft-rootfs-version && cp /.nextcraft-rootfs-version /.pocket-rootfs-version"
guest "printf 'core-bundle-$VERSION\\n' > /.nextcraft-core-tools-version && cp /.nextcraft-core-tools-version /.pocket-core-tools-version"
guest "printf 'core-bundle-$VERSION\\n' > /.nextcraft-runtime-ready && cp /.nextcraft-runtime-ready /.pocket-runtime-ready"
guest "printf 'ubuntu-maintenance-v1\\n' > /.nextcraft-system-upgrade-version && cp /.nextcraft-system-upgrade-version /.pocket-system-upgrade-version"
guest "printf '{\"WEB\":true,\"PYTHON\":false,\"CPP\":false,\"PHP\":false,\"ANDROID\":false}\\n' > /.nextcraft-dev-stacks.json && cp /.nextcraft-dev-stacks.json /.pocket-dev-stacks.json"
guest "rm -rf /root/.cache /root/.npm /root/.composer /root/.gradle/caches /root/.ssh; find /var/log -type f -delete; rm -f /etc/ssh/ssh_host_* /etc/machine-id /var/lib/dbus/machine-id /root/.bash_history"

# 5. Unmount virtual filesystems secara bersih sebelum membuat arsip deterministik .tar.zst
adb_cmd shell "su -c 'umount -lf $REMOTE/rootfs/sys; umount -lf $REMOTE/rootfs/proc; umount -lf $REMOTE/rootfs/dev/pts; umount -lf $REMOTE/rootfs/dev; mkdir -p $REMOTE/rootfs/output; mount --bind $REMOTE/output $REMOTE/rootfs/output'"
guest "export ZSTD_CLEVEL=19 ZSTD_NBTHREADS=0; tar --exclude='./output' --exclude='._*' --exclude='.DS_Store' --sort=name --mtime=2026-09-05T00:00:00Z --owner=0 --group=0 --numeric-owner --use-compress-program=/usr/bin/zstd -cf /output/nextcraft-core-arm64-$VERSION.tar.zst -C / ."
adb_cmd shell "su -c 'umount -lf $REMOTE/rootfs/output; sha256sum $REMOTE/output/nextcraft-core-arm64-$VERSION.tar.zst; ls -lh $REMOTE/output/nextcraft-core-arm64-$VERSION.tar.zst'"
echo "Core bundle created on the rooted ARM64 device at $REMOTE/output/nextcraft-core-arm64-$VERSION.tar.zst"
```

## 12. `scripts/runtime-bundles/build-claude-from-installed-android.sh` (45 baris)

```bash
#!/usr/bin/env bash
set -euo pipefail

# Export the checksum-verified official Claude Code ARM64 binary from an
# existing NextCraft Forge / Mobile-Harness installation on a rooted device.
# Only the executable and version marker are included; authentication, settings,
# conversations, projects, and device data are never copied.
ADB_SERIAL="${ADB_SERIAL:-}"
PACKAGE="${NEXTCRAFT_PACKAGE:-com.nextcraft.forge}"
VERSION="${NEXTCRAFT_CLAUDE_VERSION:-2.1.263}"
SOURCE_SHA256="${NEXTCRAFT_CLAUDE_SHA256:-7d25d7c8ae6c6e009cc7dae4e817f674179fd31fb7761bcd56fee4c2902b4c03}"
BUNDLE_VERSION="${NEXTCRAFT_CLAUDE_BUNDLE_VERSION:-2026.09.1}"
ARCHIVE="nextcraft-claude-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
APP_ROOTFS="/data/data/${PACKAGE}/files/runtime/ubuntu"

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi
}

command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }
adb_cmd wait-for-device
adb_cmd shell "su -c 'test -x ${APP_ROOTFS}/usr/local/bin/claude'"

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
mkdir -p "$temp_dir/payload/usr/local/bin" "$ROOT_DIR/dist/runtime-bundles"

adb_cmd exec-out "su -c 'cat ${APP_ROOTFS}/usr/local/bin/claude'" \
  > "$temp_dir/payload/usr/local/bin/claude"
actual_sha256="$(shasum -a 256 "$temp_dir/payload/usr/local/bin/claude" | cut -d' ' -f1)"
[[ "$actual_sha256" == "$SOURCE_SHA256" ]] || {
  echo "Claude Code source checksum mismatch" >&2
  exit 1
}
chmod 0755 "$temp_dir/payload/usr/local/bin/claude"
printf '%s\n' "$VERSION" > "$temp_dir/payload/.nextcraft-claude-version"
printf '%s\n' "$VERSION" > "$temp_dir/payload/.pocket-claude-version"

python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
shasum -a 256 "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
wc -c "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE" "$temp_dir/payload.tar"
echo "Claude Code bundle created: dist/runtime-bundles/$ARCHIVE"
```

## 13. `scripts/runtime-bundles/build-agy-from-official.sh` (42 baris)

```bash
#!/usr/bin/env bash
set -euo pipefail

# Build a portable NextCraft Forge overlay from Google's checksum-pinned official
# Antigravity CLI release. The archive contains only the executable and version
# marker: no OAuth credentials, settings, conversations, projects, or device data.
VERSION="${NEXTCRAFT_AGY_VERSION:-1.1.27}"
BUNDLE_VERSION="${NEXTCRAFT_AGY_BUNDLE_VERSION:-2026.09.1}"
SOURCE_URL="${NEXTCRAFT_AGY_URL:-https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.27-5211191891591168/linux-arm/cli_linux_arm64.tar.gz}"
SOURCE_SHA512="${NEXTCRAFT_AGY_SHA512:-ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9}"
if [ ${#SOURCE_SHA512} -ne 128 ]; then
  echo "ERROR: SOURCE_SHA512 must be exactly 128 hex characters" >&2
  exit 1
fi
ARCHIVE="nextcraft-agy-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
mkdir -p "$temp_dir/payload/root/.local/bin" "$ROOT_DIR/dist/runtime-bundles"

curl -fL --retry 3 -o "$temp_dir/agy.tar.gz" "$SOURCE_URL"
actual_sha512="$(shasum -a 512 "$temp_dir/agy.tar.gz" | cut -d' ' -f1)"
[[ "$actual_sha512" == "$SOURCE_SHA512" ]] || {
  echo "Antigravity source checksum mismatch" >&2
  exit 1
}

tar -xzf "$temp_dir/agy.tar.gz" -C "$temp_dir" antigravity
install -m 0755 "$temp_dir/antigravity" "$temp_dir/payload/root/.local/bin/agy"
printf '%s\n' "$VERSION" > "$temp_dir/payload/.nextcraft-agy-version"
printf '%s\n' "$VERSION" > "$temp_dir/payload/.pocket-agy-version"

python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
shasum -a 256 "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
wc -c "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE" "$temp_dir/payload.tar"
echo "Antigravity bundle created: dist/runtime-bundles/$ARCHIVE"
```

## 14. `scripts/runtime-bundles/build-dsh-from-installed-android.sh` (48 baris)

```bash
#!/usr/bin/env bash
set -euo pipefail

# Package the pinned DeepSeek Harness installation from NextCraft Forge's
# Ubuntu 24.04 Root Chroot runtime. Only the npm payload, launcher symlink, and
# version marker are exported; provider settings, API keys, sessions, chats, and
# workspaces are never included.
ADB_SERIAL="${ADB_SERIAL:-}"
PACKAGE="${NEXTCRAFT_PACKAGE:-com.nextcraft.forge}"
VERSION="${NEXTCRAFT_DSH_BUNDLE_VERSION:-2026.09.1}"
DSH_VERSION="${NEXTCRAFT_DSH_VERSION:-0.1.2-rc.1}"
ARCHIVE="nextcraft-dsh-arm64-${VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
APP_DATA="/data/data/${PACKAGE}"
APP_ROOTFS="${APP_DATA}/files/runtime/ubuntu"

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi
}

command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }
adb_cmd wait-for-device
adb_cmd shell "su -c 'test -f ${APP_ROOTFS}/usr/local/lib/dsh/node_modules/.bin/dsh'"
actual_version="$(adb_cmd exec-out "su -c 'cat ${APP_ROOTFS}/.nextcraft-dsh-version 2>/dev/null || cat ${APP_ROOTFS}/.pocket-dsh-version'" | tr -d '\r\n')"
[[ "$actual_version" == "$DSH_VERSION" ]] || {
  echo "Expected DeepSeek Harness $DSH_VERSION, found $actual_version" >&2
  exit 1
}

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"; adb_cmd shell "su -c \"rm -f /data/local/tmp/nextcraft-dsh-export.tar\"" >/dev/null 2>&1 || true' EXIT
mkdir -p "$temp_dir/payload" "$ROOT_DIR/dist/runtime-bundles"

adb_cmd shell "su -c 'tar -cf /data/local/tmp/nextcraft-dsh-export.tar -C ${APP_ROOTFS} usr/local/lib/dsh usr/local/bin/dsh'"
adb_cmd exec-out "su -c 'cat /data/local/tmp/nextcraft-dsh-export.tar'" > "$temp_dir/source.tar"
tar -xf "$temp_dir/source.tar" -C "$temp_dir/payload"
printf '%s\n' "$DSH_VERSION" > "$temp_dir/payload/.nextcraft-dsh-version"
printf '%s\n' "$DSH_VERSION" > "$temp_dir/payload/.pocket-dsh-version"

[[ -L "$temp_dir/payload/usr/local/bin/dsh" ]]
[[ -f "$temp_dir/payload/usr/local/lib/dsh/node_modules/.bin/dsh" ]]
python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"

shasum -a 256 "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
wc -c "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE" "$temp_dir/payload.tar"
echo "DeepSeek Harness bundle created: dist/runtime-bundles/$ARCHIVE"
```

## 15. `scripts/runtime-bundles/create_deterministic_tar.py` (56 baris)

```python
#!/usr/bin/env python3
"""Create a deterministic root-owned tar overlay from a prepared directory."""

import os
import sys
import tarfile
from pathlib import Path


def normalized(info: tarfile.TarInfo) -> tarfile.TarInfo:
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 1_757_116_800  # 2026-09-06T00:00:00Z
    if info.isdir():
        info.mode = 0o755
    elif info.isfile():
        info.mode = 0o755 if (info.mode & 0o111) else 0o644
    info.pax_headers = {}
    return info


def main() -> None:
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <source_dir> <destination.tar>", file=sys.stderr)
        sys.exit(2)

    source = Path(sys.argv[1]).resolve()
    destination = Path(sys.argv[2]).resolve()
    entries: list[Path] = []
    for current, directories, files in os.walk(source, followlinks=False):
        directories[:] = sorted(name for name in directories if not name.startswith("._"))
        files = sorted(name for name in files if not name.startswith("._") and name != ".DS_Store")
        current_path = Path(current)
        if current_path != source:
            entries.append(current_path)
        for d in directories:
            dir_path = current_path / d
            if dir_path.is_symlink():
                entries.append(dir_path)
        entries.extend(current_path / name for name in files)
    entries.sort(key=lambda path: path.relative_to(source).as_posix())

    with tarfile.open(destination, "w", format=tarfile.GNU_FORMAT, dereference=False) as archive:
        for path in entries:
            archive.add(
                path,
                arcname=path.relative_to(source).as_posix(),
                recursive=False,
                filter=normalized,
            )


if __name__ == "__main__":
    main()
```
