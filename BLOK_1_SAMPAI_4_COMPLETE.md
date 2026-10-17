# DOKUMENTASI LENGKAP NEXTCRAFT FORGE — BLOK 1 SAMPAI BLOK 4 (TERINTEGRASI & TERSINKRONISASI)

> Dokumen ini menggabungkan secara utuh seluruh kode sumber inti dan arsitektur dari:
> - `BLOK_1.md` (Core Models, Native PTY Spawn C/JNI, Rootfs Repair, Chroot Scripts, RuntimeInstaller)
> - `BLOK_2.md` (WorkspaceCheckpoints, RuntimeBridge, LocalFormatGateway, OpenRouterRoutingGateway, ApiKeyVault, AppPreferences)
> - `BLOK_3.md` (AgentDriver, ClaudeRuntimeBridge, AntigravityRuntimeBridge, AntigravityAuthController, DshRuntimeBridge)
> - `BLOK_4.md` (Android Build & Install Pipeline, Foreground Services, Network Gateways, GitHub OAuth, AppUpdater)


---

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

---

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
            return 400 to errorJson("invalid_request_error", "Plain http:// is only allowed for local network addresses")
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
            writeJson(output, 400, errorJson("invalid_request_error", "Plain http:// is only allowed for local network addresses"))
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
        return MessageDigest.isEqual(presentedBytes, activeApiKey.toByteArray())
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
                writeJson(output, 400, errorJson("Plain http:// is only allowed for local network addresses"))
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
        return MessageDigest.isEqual(presentedBytes, activeApiKey.toByteArray())
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

---

# Blok 3 — Mesin Penggerak Multi-Agen (Claude Code, Antigravity CLI & DeepSeek Harness)

Blok 3 berisi implementasi **3 mesin agen coding otonom** yang berjalan di dalam Root Chroot Ubuntu 24.04 beserta pengontrol autentikasi OAuth interaktifnya.

### Fungsi Utama Blok 3:
1. **Abstraksi Penggerak Agen (`AgentDriver.kt`)**:
   - Menyediakan antarmuka terpadu (`AgentDriver`, `ClaudeCodeDriver`, `AntigravityDriver`, `DeepSeekHarnessDriver`, `SwitchableAgentDriver`) sehingga UI atau ViewModel dapat berpindah agen secara dinamis tanpa mengubah alur pemanggilan sesi.
2. **Bridge Claude Code (`ClaudeRuntimeBridge.kt`)**:
   - Menjalankan `@anthropic-ai/claude-code` dengan `--output-format stream-json --include-partial-messages --verbose`.
   - Mem-parsing stream event JSON (`content_block_start`, `content_block_delta`, `thinking_delta`, `text_delta`, `tool_use`, `tool_result`, `result`), menyensor API key dari log, menegakkan klasifikasi risiko (`classifyRisk`) pada permintaan izin tool melalui direktori jembatan `runtime-bridge`, serta memancarkan progres ke notifikasi foreground.
3. **Bridge Antigravity CLI (`AntigravityRuntimeBridge.kt`)**:
   - Menjalankan binary resmi Google Antigravity (`agy`) dengan `--input-format stream-json --output-format stream-json --dangerously-skip-permissions`.
   - Mengelola kelanjutan percakapan (`--conversation <id>` atau `--new-project`), mem-parsing `step_update` & `result`, memantau progres build Gradle yang berjalan lama, dan menerjemahkan error jaringan/kuota menjadi pesan yang mudah dipahami.
4. **Kontroler Login OAuth Antigravity (`AntigravityAuthController.kt`)**:
   - Mengemudikan alur login interaktif `agy` melalui PTY (`SSH_CONNECTION` mode) secara otomatis: menjawab jabat tangan terminal DEC/Kitty (`[?u`), memilih menu Google OAuth, mengekstrak URL otorisasi Google resmi untuk dibuka di browser pengguna, mengirimkan kode verifikasi secara sinkron (`@Synchronized`), dan melewati layar setup awal hingga token tersimpan resmi oleh `agy`.
5. **Bridge DeepSeek Harness (`DshRuntimeBridge.kt`)**:
   - Menjalankan `dsh --profile sdk` menggunakan protokol dua arah **JSON-RPC 2.0** (`initialize` -> `session/prompt` -> `shutdown`).
   - Menghasilkan `settings.yaml` dinamis sesuai rute provider (`DshRouteMapper`), mem-parsing stream reasoning dan eksekusi tool (`DshSdkProtocolParser`), serta menangani deduplikasi pesan akhir.

## Daftar File dalam Blok Ini
- `app/src/main/java/com/nextcraft/forge/runtime/AgentDriver.kt` (86 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/ClaudeRuntimeBridge.kt` (872 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/AntigravityRuntimeBridge.kt` (671 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/AntigravityAuthController.kt` (377 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/DshRuntimeBridge.kt` (969 baris)

---

## 1. `app/src/main/java/com/nextcraft/forge/runtime/AgentDriver.kt` (86 baris)

```kotlin
package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.AgentKind

/** Features exposed by an agent without teaching shared UI about a concrete CLI. */
enum class AgentCapability {
    API_KEY,
    ACCOUNT_LOGIN,
    PROVIDER_PICKER,
    MODEL_PICKER,
    REASONING_EFFORT,
    RESUME,
    INTERACTIVE_APPROVALS,
}

interface AgentDriver {
    val kind: AgentKind
    val runtime: RuntimeBridge
    val capabilities: Set<AgentCapability>
    fun isInstalled(installer: RuntimeInstaller): Boolean = installer.isAgentInstalled(kind)
    suspend fun install(installer: RuntimeInstaller, onProgress: suspend (RuntimeInstallProgress) -> Unit) =
        installer.ensureAgentInstalled(kind, onProgress)
}

private class BuiltInAgentDriver(
    override val kind: AgentKind,
    override val runtime: RuntimeBridge,
    override val capabilities: Set<AgentCapability>,
) : AgentDriver

/** Single selection point for agent runtimes. Adding an agent does not change existing bridges. */
class AgentRegistry(drivers: List<AgentDriver>) {
    private val byId = drivers.associateBy { it.kind.stableId }

    init {
        require(byId.size == drivers.size) { "Duplicate agent id" }
        require(AgentKind.entries.all { it.stableId in byId }) { "Every built-in agent must be registered" }
    }

    fun require(kind: AgentKind): AgentDriver =
        byId[kind.stableId] ?: error("Agent '${kind.stableId}' is not registered")

    companion object {
        fun builtIns(
            claude: RuntimeBridge,
            deepSeek: RuntimeBridge,
            antigravity: RuntimeBridge,
        ) = AgentRegistry(
            listOf(
                BuiltInAgentDriver(
                    AgentKind.CLAUDE_CODE,
                    claude,
                    setOf(
                        AgentCapability.API_KEY,
                        AgentCapability.ACCOUNT_LOGIN,
                        AgentCapability.PROVIDER_PICKER,
                        AgentCapability.MODEL_PICKER,
                        AgentCapability.RESUME,
                        AgentCapability.INTERACTIVE_APPROVALS,
                    ),
                ),
                BuiltInAgentDriver(
                    AgentKind.DEEPSEEK_HARNESS,
                    deepSeek,
                    setOf(
                        AgentCapability.API_KEY,
                        AgentCapability.PROVIDER_PICKER,
                        AgentCapability.MODEL_PICKER,
                        AgentCapability.RESUME,
                        AgentCapability.INTERACTIVE_APPROVALS,
                    ),
                ),
                BuiltInAgentDriver(
                    AgentKind.ANTIGRAVITY,
                    antigravity,
                    setOf(
                        AgentCapability.ACCOUNT_LOGIN,
                        AgentCapability.MODEL_PICKER,
                        AgentCapability.REASONING_EFFORT,
                        AgentCapability.RESUME,
                    ),
                ),
            ),
        )
    }
}
```

## 2. `app/src/main/java/com/nextcraft/forge/runtime/ClaudeRuntimeBridge.kt` (872 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import android.util.Log
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.RiskLevel
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal object ProviderRuntimeErrorDetector {
    fun detect(line: String): String? {
        val json = runCatching { JSONObject(line) }.getOrNull()
        // Structured stream-json lines carry assistant text and tool output. Scanning those
        // would abort the session whenever a file or answer merely mentions "quota" or
        // "rate limit". Only error-bearing fields are inspected; raw (non-JSON) lines are
        // runtime stderr and are scanned whole.
        val combined = if (json == null) {
            line.lowercase()
        } else buildString {
            append(json.optString("error"))
            if (json.optString("type") == "error") {
                append(' ')
                append(json.optString("message"))
            }
            if (json.optBoolean("is_error")) {
                append(' ')
                append(json.optString("result"))
            }
        }.lowercase()
        return when {
            "user not found" in combined -> "User not found. Check the API key and provider account."
            "authentication_failed" in combined ||
                "authentication failed" in combined ||
                "invalid api key" in combined ||
                "http 401" in combined ||
                "http 403" in combined ||
                "http 429" in combined ||
                "expired" in combined ||
                "quota" in combined ||
                "rate limit" in combined ||
                (json?.optString("subtype") == "api_retry" && json.optInt("error_status") in listOf(401, 403, 429)) ->
                "The provider rejected the saved API key."
            else -> null
        }
    }
}

class ClaudeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    private val rotateSecretFor: (ProviderProfile) -> String? = { profile ->
        com.nextcraft.forge.data.ApiKeyVault(context).rotateNext(profile.kind.name)
    },
    private val interactiveHighRiskApprovals: () -> Boolean = {
        com.nextcraft.forge.data.AppPreferences(context).interactiveHighRiskApprovals
    },
) : RuntimeBridge {
    private val installer by lazy { RuntimeInstaller(context) }
    private val checkpoints by lazy { WorkspaceCheckpoints(context.filesDir, context) }
    private val eventBus = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RuntimeEvent> = eventBus
    private val pending = ConcurrentHashMap<String, PendingPermission>()
    private val toolNames = ConcurrentHashMap<String, String>()
    private val seenToolCalls = ConcurrentHashMap.newKeySet<String>()
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    private val streamedText = StringBuilder()
    private val streamedThinking = StringBuilder()
    private var lastReasoningTokens = 0
    private var lastReasoningUpdateAt = 0L
    private var lastThinkingUpdateAt = 0L
    private var currentThinkingBlockId = 0L

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        toolNames.clear()
        seenToolCalls.clear()
        lastReasoningTokens = 0
        lastReasoningUpdateAt = 0L
        lastThinkingUpdateAt = 0L
        currentThinkingBlockId = 0L
        streamedText.clear()
        streamedThinking.clear()
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting Claude Code…")
        val secret = secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (secret.isBlank()) {
            val message = if (provider.kind == ProviderKind.CLAUDE) {
                "No Claude subscription token is saved. Add one from Agent → AI provider."
            } else {
                "No API key is saved for ${provider.kind.title}."
            }
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, message))
            return@withContext sessionId
        }

        var formatGateway: LocalFormatGateway? = null
        var openRouterGateway: OpenRouterRoutingGateway? = null
        var spawnedOutputFile: File? = null
        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread({
                        runCatching {
                            running.destroy()
                            Thread.sleep(500)
                            if (running.isAlive) running.destroyForcibly()
                        }
                    }, "forge-claude-stop").apply { isDaemon = true }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            installer.ensureSettingsAndHooks()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            formatGateway = if (provider.kind.protocol in setOf(
                    ProviderProtocol.OPENAI_CHAT,
                    ProviderProtocol.OPENAI_RESPONSES,
                )
            ) LocalFormatGateway(provider, secret) { rotateSecretFor(provider) }.start() else null
            openRouterGateway = if (
                provider.kind == ProviderKind.LLM_ROUTER && provider.openRouterProviders.isNotEmpty()
            ) OpenRouterRoutingGateway(provider, secret) { rotateSecretFor(provider) }.start() else null
            val launch = RuntimeLaunchConfigBuilder.build(
                provider,
                authToken = secret,
                localGatewayUrl = formatGateway?.url ?: openRouterGateway?.url,
            )
            Log.d("ClaudeBridge", "Provider: ${provider.kind}, Model: ${provider.model}")
            Log.d("ClaudeBridge", "Launch environment keys: ${launch.environment.keys}")

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)

            val command = buildList {
                add(launch.executable)
                // Do not use --bare here: Claude Code bare mode ignores CLAUDE_CODE_OAUTH_TOKEN,
                // which is how setup-token authenticates Claude subscriptions.
                add("-p")
                add(contextPrompt)
                add("--output-format")
                add("stream-json")
                add("--include-partial-messages")
                add("--verbose")
                add("--model")
                add(launch.environment["ANTHROPIC_MODEL"] ?: provider.model)
                add("--max-turns")
                add("25")
            }
            Log.d("ClaudeBridge", "Launching Claude Code (${command.size} args, prompt=${contextPrompt.length} chars)")
            val process = installer.process(
                installed.chrootExec,
                installed.rootfs,
                workspace,
                launch.environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
            )
            activeProcess = process
            runCatching { process.outputStream.close() }
            if (userStopRequested) process.destroy()
            coroutineScope {
                val permissionWatcher = launch { watchPermissionRequests(sessionId) }
                var lastDiagnostic = ""
                val nativeProcess = process as? NativeSpawnProcess
                    ?: error("Unsupported Android runtime process")
                spawnedOutputFile = nativeProcess.outputFile
                suspend fun processClaudeLine(rawLine: String) {
                    val line = rawLine.trimEnd('\r')
                    if (line.isBlank()) return
                    ProviderRuntimeErrorDetector.detect(line)?.let { reason ->
                        process.destroyForcibly()
                        throw ProviderSessionException(reason)
                    }
                    if (!consumeClaudeEvent(sessionId, line)) {
                        lastDiagnostic = sanitizeForDisplay(line.takeLast(500))
                        terminalStatus(line)?.let { (title, detail) ->
                            eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, title, detail))
                        }
                    }
                }
                InotifyLogTailer(nativeProcess.outputFile).use { tailer ->
                    while (process.isAlive || tailer.hasUnreadBytes()) {
                        for (line in tailer.readLines(timeoutMs = 50L)) {
                            processClaudeLine(line)
                        }
                    }
                    for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                        processClaudeLine(line)
                    }
                }
                val exit = process.waitFor()
                Log.d("ClaudeBridge", "Process exited with code $exit")
                permissionWatcher.cancelAndJoin()
                pending.values.filter { it.request.sessionId == sessionId }.forEach { permission ->
                    permission.response.writeText("deny")
                    pending.remove(permission.request.approvalId)
                }
                val changed = checkpoints.changedFiles(workspace, before)
                if (changed.isNotEmpty()) {
                    Log.d("ClaudeBridge", "Changed files (${changed.size})")
                    checkpoints.saveChangedPaths(projectId, changed)
                    val details = loadPendingChanges(projectId)
                    eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                } else if (checkpoints.readChangedPaths(projectId).isEmpty()) {
                    acceptLastChanges(projectId)
                }
                if (exit == 0) {
                    emitCompletedOnce(sessionId)
                    finishForegroundRuntime(
                        completed = true,
                        projectName = projectSlug,
                        detail = "Claude Code finished in $projectSlug",
                    )
                } else {
                    if (userStopRequested) throw ProviderSessionException("Stopped by user")
                    error(lastDiagnostic.ifBlank { "Claude Code stopped with exit code $exit" })
                }
            }
        }.onFailure { error ->
            Log.e("ClaudeBridge", "Session failed: ${error::class.simpleName}")
            val message = friendlyError(error)
            emitFailureOnce(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = projectSlug,
                    detail = message,
                )
            }
        }
        runCatching { formatGateway?.close() }
        runCatching { openRouterGateway?.close() }
        runCatching { spawnedOutputFile?.delete() }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    /**
     * Runs a one-turn "reply ok" prompt with the saved credentials to prove Claude Code can
     * authenticate inside Root Chroot.
     */
    suspend fun hello(provider: ProviderProfile, timeoutMillis: Long = 90_000L): String = withContext(Dispatchers.IO) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val secret = secretFor(provider).orEmpty()
        check(secret.isNotBlank()) { "No Claude subscription token is saved." }
        val installed = installer.installedRuntime()
        val probeDir = File(context.cacheDir, "claude-hello").apply { mkdirs() }
        val outputFile = File(context.cacheDir, "claude-hello-${System.nanoTime()}.log")
        val launch = RuntimeLaunchConfigBuilder.build(provider, authToken = secret)
        val command = listOf(
            launch.executable,
            "-p", "Reply with exactly: ok",
            "--output-format", "json",
            "--max-turns", "1",
            "--model", launch.environment["ANTHROPIC_MODEL"] ?: provider.model,
        )
        val process = installer.process(
            installed.chrootExec,
            installed.rootfs,
            probeDir,
            launch.environment,
            command,
            guestWorkspacePath = "/workspace/claude-hello",
            emulateHardLinks = false,
            outputFile = outputFile,
        )
        try {
            runCatching { process.outputStream.close() }
            withTimeout(timeoutMillis) {
                while (process.isAlive) delay(200)
            }
            val output = outputFile.takeIf(File::exists)?.readText().orEmpty()
            val result = output.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("{") }
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .lastOrNull { it.optString("type") == "result" }
            when {
                result == null -> error(output.trim().takeLast(300).ifBlank { "Claude Code did not answer." })
                result.optBoolean("is_error") -> error(result.optString("result").ifBlank { result.optString("subtype") })
                else -> result.optString("result").trim().ifBlank { "ok" }
            }
        } catch (e: TimeoutCancellationException) {
            error("Claude Code did not answer in time. Check your connection and try again.")
        } finally {
            runCatching { process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { outputFile.delete() }
            runCatching { probeDir.deleteRecursively() }
            Log.d("ClaudeBridge", "Connection test took ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = withContext(Dispatchers.IO) {
        val permission = pending.remove(request.approvalId) ?: return@withContext
        permission.response.writeText(if (approved) "allow" else "deny")
        eventBus.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertLatestTurn(projectId, workspace).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.acceptAll(projectId)
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertFile(projectId, workspace, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.acceptFile(projectId, workspace, path)
    }

    private suspend fun watchPermissionRequests(sessionId: String) {
        val bridge = File(context.filesDir, "runtime-bridge")
        val handledApprovals = HashSet<String>()
        while (kotlin.coroutines.coroutineContext.isActive) {
            expirePendingPermissions(sessionId)
            bridge.listFiles { file -> file.name.endsWith(".request") }.orEmpty().forEach { file ->
                val approvalId = file.name.removeSuffix(".request")
                if (!handledApprovals.add(approvalId)) {
                    runCatching { file.delete() }
                    return@forEach
                }
                processPermissionRequestFile(sessionId, file)
            }
            delay(50)
        }
    }

    internal suspend fun expirePendingPermissions(
        sessionId: String,
        nowElapsedRealtime: Long = android.os.SystemClock.elapsedRealtime(),
        timeoutMs: Long = HIGH_RISK_APPROVAL_TIMEOUT_MS,
    ) {
        pending.values
            .filter { it.request.sessionId == sessionId && nowElapsedRealtime - it.requestedAtElapsedRealtime >= timeoutMs }
            .forEach { expired ->
                if (pending.remove(expired.request.approvalId, expired)) {
                    runCatching { expired.response.writeText("deny") }
                    eventBus.emit(RuntimeEvent.ToolRejected(sessionId, expired.request.approvalId))
                }
            }
    }

    internal suspend fun processPermissionRequestFile(sessionId: String, file: File) {
        val approvalId = file.name.removeSuffix(".request")
        runCatching {
            val json = JSONObject(file.readText())
            val toolName = json.optString("tool_name", "Tool")
            val input = json.optJSONObject("tool_input") ?: JSONObject()
            val command = input.optString("command").ifBlank { null }
            val paths = listOf("file_path", "path", "notebook_path")
                .mapNotNull { key -> input.optString(key).takeIf(String::isNotBlank) }
            val explanation = input.optString("description")
                .ifBlank { command.orEmpty() }
                .ifBlank { "$toolName running in project" }

            val risk = classifyRisk(toolName, command)
            val response = File(file.parentFile, "$approvalId.response")
            if (risk == RiskLevel.HIGH) {
                if (interactiveHighRiskApprovals()) {
                    val toolRequest = ToolRequest(
                        approvalId = approvalId,
                        sessionId = sessionId,
                        toolName = toolName,
                        explanation = sanitizeForDisplay(explanation),
                        affectedPaths = paths,
                        commandPreview = command,
                        risk = risk,
                    )
                    pending[approvalId] = PendingPermission(toolRequest, response)
                    runCatching { file.delete() }
                    Log.i("ClaudeBridge", "Requesting interactive user approval $approvalId for high-risk $toolName ($paths)")
                    eventBus.emit(RuntimeEvent.ToolRequested(sessionId, toolRequest))
                } else {
                    Log.w("ClaudeBridge", "Denied high-risk permission request $approvalId for $toolName ($paths)")
                    response.writeText("deny")
                    runCatching { file.delete() }
                    eventBus.emit(
                        RuntimeEvent.RuntimeLog(
                            sessionId,
                            "Permission denied",
                            sanitizeForDisplay("Blocked high-risk $toolName request: $explanation"),
                        ),
                    )
                }
            } else {
                Log.d("ClaudeBridge", "Approving permission request $approvalId for $toolName ($paths, risk=$risk)")
                response.writeText("allow")
                runCatching { file.delete() }
                eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, explanation))
            }
        }.onFailure {
            File(file.parentFile, "$approvalId.response").writeText("deny")
            runCatching { file.delete() }
        }
    }

    private suspend fun consumeClaudeEvent(sessionId: String, line: String): Boolean {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
        consumeClaudeJsonEvent(sessionId, json)
        return true
    }

    private suspend fun consumeClaudeJsonEvent(sessionId: String, json: JSONObject) {
        when (json.optString("type")) {
            "stream_event" -> json.optJSONObject("event")?.let { consumeClaudeJsonEvent(sessionId, it) }
            "system" -> when (json.optString("subtype")) {
                "init" -> Unit
                "thinking_tokens" -> emitReasoningProgress(sessionId, json.optInt("estimated_tokens"))
                "permission_denied" -> eventBus.emit(
                    RuntimeEvent.RuntimeLog(
                        sessionId,
                        "Permission denied",
                        sanitizeForDisplay(json.optString("decision_reason").ifBlank { json.optString("message") }),
                    ),
                )
            }
            "content_block_start" -> {
                val block = json.optJSONObject("content_block")
                when (block?.optString("type")) {
                    "thinking" -> {
                        currentThinkingBlockId += 1
                        streamedThinking.clear()
                        emitReasoningSummary(sessionId, "", startsNewBlock = true)
                        block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            streamedThinking.append(it)
                            emitReasoningSummary(sessionId, it, force = true)
                        }
                    }
                    "text" -> streamedText.clear()
                }
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta")
                when (delta?.optString("type")) {
                    "thinking_delta" -> delta.optString("thinking").takeIf(String::isNotEmpty)?.let {
                        streamedThinking.append(it)
                        emitReasoningSummary(sessionId, streamedThinking.toString())
                    }
                    "text_delta", "" -> delta.optString("text").takeIf(String::isNotEmpty)?.let {
                        streamedText.append(it)
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                    }
                }
            }
            "content_block_stop" -> {
                if (streamedThinking.isNotBlank()) {
                    emitReasoningSummary(sessionId, streamedThinking.toString(), force = true, isFinal = true)
                }
            }
            "assistant" -> {
                val message = json.optJSONObject("message") ?: return
                val content = message.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    when (block.optString("type")) {
                        "text" -> if (streamedText.isEmpty()) {
                            block.optString("text").takeIf(String::isNotBlank)?.let {
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                            }
                        }
                        "thinking" -> block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            if (currentThinkingBlockId == 0L || streamedThinking.toString() != it) {
                                currentThinkingBlockId += 1
                                streamedThinking.clear()
                                streamedThinking.append(it)
                                emitReasoningSummary(
                                    sessionId,
                                    it,
                                    force = true,
                                    startsNewBlock = true,
                                    isFinal = true,
                                )
                            }
                        }
                        "tool_use" -> emitToolStarted(sessionId, block)
                    }
                }
                streamedText.clear()
                if (message.optString("stop_reason") == "end_turn") {
                    emitCompletedOnce(sessionId)
                    terminateActiveProcessGracefully()
                }
            }
            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "tool_result") {
                        val toolId = block.optString("tool_use_id")
                        val toolName = toolNames.remove(toolId) ?: "Tool"
                        val contentArray = block.optJSONArray("content")
                        val extractedContent = if (contentArray != null) {
                            buildString {
                                for (itemIndex in 0 until contentArray.length()) {
                                    val item = contentArray.optJSONObject(itemIndex)
                                    val text = item?.optString("text")?.takeIf(String::isNotBlank)
                                        ?: contentArray.optString(itemIndex).takeIf(String::isNotBlank)
                                    if (text != null) {
                                        if (isNotEmpty()) append('\n')
                                        append(text)
                                    }
                                }
                            }
                        } else {
                            block.optString("content")
                        }
                        val result = extractedContent
                            .ifBlank { if (block.optBoolean("is_error")) "Tool failed" else "Completed successfully" }
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, sanitizeForDisplay(result)))
                    }
                }
            }
            "result" -> {
                if (json.optBoolean("is_error")) {
                    val message = json.optString("result").ifBlank { "Claude Code reported an error" }
                    throw IllegalStateException(message)
                }
                emitCompletedOnce(sessionId)
                terminateActiveProcessGracefully()
            }
        }
    }

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        force: Boolean = false,
        startsNewBlock: Boolean = false,
        isFinal: Boolean = false,
    ) {
        val summary = sanitizeForDisplay(text).trim().take(2_000)
        if (summary.isBlank() && !startsNewBlock) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (startsNewBlock || isFinal || force || now - lastThinkingUpdateAt >= 150) {
            lastThinkingUpdateAt = now
            eventBus.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = summary,
                    blockId = currentThinkingBlockId,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                ),
            )
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitReasoningProgress(sessionId: String, tokens: Int) {
        if (tokens <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (tokens - lastReasoningTokens >= 25 || now - lastReasoningUpdateAt >= 500) {
            lastReasoningTokens = tokens
            lastReasoningUpdateAt = now
            eventBus.emit(RuntimeEvent.ReasoningProgress(sessionId, tokens))
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitToolStarted(sessionId: String, block: JSONObject) {
        val id = block.optString("id")
        if (id.isNotBlank() && !seenToolCalls.add(id)) return
        val name = block.optString("name", "Tool")
        if (id.isNotBlank()) toolNames[id] = name
        val input = block.optJSONObject("input") ?: JSONObject()
        val detail = when (name) {
            "Bash" -> input.optString("command").ifBlank { input.optString("description") }
            "Write", "Edit", "Read", "NotebookEdit" -> input.optString("file_path").ifBlank { input.optString("notebook_path") }
            "Glob" -> input.optString("pattern")
            "Grep" -> input.optString("pattern").let { pattern ->
                input.optString("path").takeIf(String::isNotBlank)?.let { "$pattern in $it" } ?: pattern
            }
            else -> input.optString("description").ifBlank { "Running $name" }
        }
        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, name, sanitizeForDisplay(detail.ifBlank { "Running $name" })))
        val compactDetail = detail.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { name }
        pushForegroundProgress("Running $name: $compactDetail")
    }

    private fun terminalStatus(line: String): Pair<String, String>? = null

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "Project",
                detail = "Claude Code finished",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "Project",
                    detail = reason,
                )
            }
        }
    }

    private fun sanitizeForDisplay(value: String): String {
        return value
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("(?i)(authorization|api[_-]?key)\\s*[:=]\\s*[^\\s,}]+"), "${'$'}1: ••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600)
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
    ): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                    !msg.text.startsWith("Failed to") &&
                    !msg.text.startsWith("Error:") &&
                    !msg.text.contains("API Error")
            }
            .dropLast(1)
            .recentWithinCharacterBudget(MAX_CONVERSATION_HISTORY_CHARACTERS)

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (installer.isStackInstalled(DevStack.ANDROID)) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
            sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
            sb.appendLine("NextCraft Forge globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this NextCraft Forge runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in NextCraft Forge Settings before building.")
        }
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    private fun classifyRisk(tool: String, command: String?): RiskLevel =
        classifyToolRisk(tool, command)

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is ProviderSessionException -> message
            message.contains("user not found", true) -> "User not found. Check the API key and provider account."
            message.contains("checksum", true) -> "Runtime verification failed. Nothing unverified was executed."
            message.contains("HTTP 401", true) || message.contains("authentication", true) -> "The provider rejected the saved API key."
            message.isBlank() -> "The real Claude Code runtime could not start."
            else -> message.take(500)
        }
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        RuntimeExecutionServiceContract.pushProgress(context, activeProjectSlug, text)
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun terminateActiveProcessGracefully() {
        val running = activeProcess ?: return
        Thread {
            runCatching {
                running.destroy()
                Thread.sleep(3_000)
                if (running.isAlive) running.destroyForcibly()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startForegroundRuntime(projectName: String) {
        RuntimeExecutionServiceContract.startForeground(context, projectName)
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.finishForeground(context, completed, projectName, detail, "ClaudeBridge")
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.cancelForeground(context)
    }

    private data class PendingPermission(
        val request: ToolRequest,
        val response: File,
        val requestedAtElapsedRealtime: Long = android.os.SystemClock.elapsedRealtime(),
    )
    private class ProviderSessionException(message: String) : IllegalStateException(message)

    companion object {
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val HIGH_RISK_APPROVAL_TIMEOUT_MS = 40_000L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
    }
}

internal fun classifyToolRisk(tool: String, command: String?): RiskLevel {
    val preview = "${tool.lowercase()} ${command.orEmpty().lowercase()}"
    val highRiskPatterns = listOf(
        "rm -rf",
        "rm -fr",
        "rm -r ",
        "git push",
        "git reset",
        "sudo",
        "curl ",
        "wget ",
        "chmod 777",
        "dd if=",
        "mkfs",
        "chown -r /",
        "/dev/block",
        "umount",
        "| sh",
        "| bash",
    )
    return when {
        highRiskPatterns.any(preview::contains) -> RiskLevel.HIGH
        tool in listOf("Write", "Edit", "NotebookEdit", "Bash") -> RiskLevel.REVIEW
        else -> RiskLevel.SAFE
    }
}
```

## 3. `app/src/main/java/com/nextcraft/forge/runtime/AntigravityRuntimeBridge.kt` (671 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class AntigravityAuthStatus { SIGNED_OUT, STARTING, AWAITING_CODE, COMPLETING, SIGNED_IN, ERROR }

data class AntigravityAuthState(
    val status: AntigravityAuthStatus = AntigravityAuthStatus.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: String? = null,
    val accountEmail: String? = null,
)

internal sealed interface AntigravityParsedEvent {
    data class Initialized(val conversationId: String) : AntigravityParsedEvent
    data class Text(val value: String) : AntigravityParsedEvent
    data class ToolStarted(val name: String, val detail: String) : AntigravityParsedEvent
    data class ToolCompleted(val name: String, val detail: String) : AntigravityParsedEvent
    data class Result(
        val conversationId: String?,
        val status: String,
        val response: String?,
        val error: String?,
    ) : AntigravityParsedEvent
}

internal object AntigravityEventParser {
    fun parse(line: String): AntigravityParsedEvent? {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (root.optString("event")) {
            "init" -> root.optString("conversation_id")
                .takeIf(String::isNotBlank)
                ?.let(AntigravityParsedEvent::Initialized)
            "step_update" -> {
                val step = root.optJSONObject("step_update") ?: return null
                val delta = step.optString("text_delta")
                if (delta.isNotEmpty()) return AntigravityParsedEvent.Text(delta)
                val type = step.optString("step_type")
                if (type.contains("tool", true) || type.contains("command", true)) {
                    val rawName = step.optString("tool_name").ifBlank {
                        step.optJSONObject("tool_info")?.optString("name").orEmpty()
                    }.ifBlank { type.ifBlank { "Tool" } }
                    val name = antigravityToolDisplayName(rawName)
                    val detail = antigravityToolDetail(step, rawName).ifBlank { name }
                    if (step.optString("state") in setOf("DONE", "ERROR")) {
                        val output = step.optJSONObject("tool_info")
                            ?.optString("output")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::antigravityToolOutput)
                        val error = step.optJSONObject("tool_info")
                            ?.optJSONObject("error")
                            ?.optString("message")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::redactToolDetail)
                        AntigravityParsedEvent.ToolCompleted(name, output ?: error ?: detail)
                    } else {
                        AntigravityParsedEvent.ToolStarted(name, detail)
                    }
                } else null
            }
            "result" -> {
                val result = root.optJSONObject("result") ?: root
                AntigravityParsedEvent.Result(
                    result.optString("conversation_id").takeIf(String::isNotBlank),
                    result.optString("status", "ERROR"),
                    result.optString("response").takeIf(String::isNotBlank),
                    result.optString("error").takeIf(String::isNotBlank),
                )
            }
            else -> null // Forward compatible with new agy event types.
        }
    }
}

private fun antigravityToolDisplayName(name: String): String = when (name.lowercase()) {
    "run_command" -> "Bash"
    "write_to_file", "replace_file_content", "multi_replace_file_content" -> "Write"
    "view_file" -> "Read"
    "list_dir" -> "List files"
    "grep_search", "search_files" -> "Search"
    "schedule" -> "Wait"
    "manage_task" -> "Manage task"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun antigravityToolDetail(step: JSONObject, rawName: String): String {
    step.optString("description").takeIf(String::isNotBlank)?.let { return redactToolDetail(it) }
    val info = step.optJSONObject("tool_info")
    val parameters = info?.optJSONObject("parameters")
    val preferredKeys = when (rawName.lowercase()) {
        "run_command" -> listOf("CommandLine", "command", "cmd")
        "write_to_file", "replace_file_content", "multi_replace_file_content" ->
            listOf("TargetFile", "AbsolutePath", "path", "file")
        "view_file" -> listOf("AbsolutePath", "TargetFile", "path", "file")
        "list_dir" -> listOf("DirectoryPath", "AbsolutePath", "path")
        "grep_search", "search_files" -> listOf("Query", "Pattern", "query", "pattern")
        "schedule" -> listOf("Prompt", "DurationSeconds")
        "manage_task" -> listOf("Action", "TaskId")
        else -> emptyList()
    }
    preferredKeys.forEach { key ->
        parameters?.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let {
            val prefix = when {
                rawName.equals("schedule", true) && key == "DurationSeconds" -> "Wait "
                rawName.equals("manage_task", true) -> "$key: "
                else -> ""
            }
            val suffix = if (rawName.equals("schedule", true) && key == "DurationSeconds") "s" else ""
            return redactToolDetail(prefix + it + suffix)
        }
    }
    return parameters?.toString()?.takeUnless { it == "{}" }?.let(::redactToolDetail)
        ?: step.optJSONObject("tool")?.toString()?.let(::redactToolDetail)
        .orEmpty()
}

private fun redactToolDetail(value: String): String = value
    .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
    .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(500)

private fun antigravityToolOutput(value: String): String {
    val redacted = value
        .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
        .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    return if (redacted.length <= 6_000) redacted else "…\n" + redacted.takeLast(6_000)
}

/** Official Antigravity CLI bridge running inside NextCraft Forge Root Chroot. OAuth and credentials remain owned by agy. */
private const val HELLO_TIMEOUT_MILLIS = 90_000L

/**
 * Stream-json's result event is terminal, but agy may keep its input loop alive
 * after emitting it. Stop the entire spawned process group so task completion is
 * driven by the protocol rather than an implementation-specific process exit.
 */
private suspend fun stopAfterTerminalResult(process: Process) {
    if (!process.isAlive) return
    process.destroy()
    repeat(20) {
        if (!process.isAlive) return
        delay(50)
    }
    if (process.isAlive) process.destroyForcibly()
    repeat(20) {
        if (!process.isAlive) return
        delay(50)
    }
}

class AntigravityRuntimeBridge(
    private val context: Context,
    private val model: () -> String,
    private val effort: () -> String,
    private val conversationId: (String) -> String?,
    private val saveConversationId: (String, String) -> Unit,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RuntimeEvent> = eventBus
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false
    @Volatile private var foregroundResultPosted = false
    @Volatile private var activeProjectSlug = ""
    @Volatile private var taskStartedAtElapsedRealtime = 0L
    @Volatile private var lastForegroundProgressAt = 0L

    fun configureProjectRoot(projectId: String, rootPath: String) = checkpoints.configureProjectRoot(projectId, rootPath)

    /**
     * Lightweight connectivity probe: sends a tiny hello to agy and returns its
     * reply text. No foreground service, no checkpoints, no saved conversation.
     */
    suspend fun hello(timeoutMillis: Long = HELLO_TIMEOUT_MILLIS): String = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            throw IllegalStateException("Antigravity CLI is not installed.")
        }
        val startedAtMillis = System.currentTimeMillis()
        try {
            withTimeout(timeoutMillis) {
                val installed = installer.installedRuntime()
                val probeDir = File(context.cacheDir, "agy-hello").apply { mkdirs() }
                val command = buildList {
                    add(RuntimeInstaller.AGY_GUEST_PATH)
                    addAll(listOf("--input-format", "stream-json"))
                    addAll(listOf("--output-format", "stream-json"))
                    addAll(listOf("--print-timeout", "2m"))
                    add("--dangerously-skip-permissions")
                    addAntigravitySelection(model(), effort())
                    add("--new-project")
                }
                val outputFile = File(context.cacheDir, "agy-hello-${System.nanoTime()}.log")
                val process = installer.process(
                    installed.chrootExec,
                    installed.rootfs,
                    probeDir,
                    emptyMap(),
                    command,
                    guestWorkspacePath = "/workspace/hello",
                    emulateHardLinks = false,
                    outputFile = outputFile,
                )
                try {
                    val request = JSONObject()
                        .put("event", "user")
                        .put("message", JSONObject().put("content", "Reply with exactly: ok"))
                        .toString() + "\n"
                    process.outputStream.write(request.toByteArray())
                    process.outputStream.flush()
                    process.outputStream.close()
                    var reply: String? = null
                    var lastDiagnostic = ""
                    fun handleLine(rawLine: String): Boolean {
                        val line = rawLine.trimEnd('\r')
                        if (line.isBlank()) return false
                        lastDiagnostic = line.takeLast(500)
                        when (val event = AntigravityEventParser.parse(line)) {
                            is AntigravityParsedEvent.Text -> if (reply == null && event.value.isNotBlank()) reply = event.value
                            is AntigravityParsedEvent.Result -> {
                                if (event.status.equals("SUCCESS", ignoreCase = true)) {
                                    if (reply == null && !event.response.isNullOrBlank()) reply = event.response
                                    return true
                                }
                                throw AntigravitySessionException(friendlyError(event.error ?: event.status))
                            }
                            else -> Unit
                        }
                        return false
                    }
                    var done = false
                    InotifyLogTailer(outputFile).use { tailer ->
                        while (!done && (process.isAlive || tailer.hasUnreadBytes())) {
                            for (line in tailer.readLines(timeoutMs = 50L)) {
                                if (handleLine(line)) {
                                    done = true
                                    break
                                }
                            }
                        }
                        if (!done) {
                            for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                                if (handleLine(line)) {
                                    done = true
                                    break
                                }
                            }
                        }
                    }
                    check(done) { friendlyError(lastDiagnostic.ifBlank { "Antigravity exited without answering" }) }
                    stopAfterTerminalResult(process)
                    reply?.trim().takeUnless { it.isNullOrEmpty() } ?: "ok"
                } finally {
                    runCatching { process.destroy() }
                    runCatching {
                        if (process.isAlive) process.destroyForcibly()
                    }
                    runCatching { outputFile.delete() }
                    runCatching { probeDir.deleteRecursively() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            val diagnostic = recentAntigravityDiagnostic(startedAtMillis)
            val message = if (diagnostic.isBlank()) {
                "Antigravity did not answer. Try again."
            } else {
                antigravityFriendlyError(e.message.orEmpty(), diagnostic)
            }
            throw AntigravitySessionException(message)
        } catch (e: Throwable) {
            throw AntigravitySessionException(
                antigravityFriendlyError(e.message.orEmpty(), recentAntigravityDiagnostic(startedAtMillis)),
            )
        }
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        val sessionStartedAtMillis = System.currentTimeMillis()
        activeSessionId = sessionId
        userStopRequested = false
        foregroundResultPosted = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        finished.remove(sessionId)
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            emitFailure(sessionId, "Antigravity CLI is not installed. Open Settings → Coding agent to install it.")
            return@withContext sessionId
        }

        var spawnedOutputFile: File? = null
        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread {
                        runCatching {
                            running.destroy()
                            Thread.sleep(500)
                            if (running.isAlive) running.destroyForcibly()
                        }
                    }.apply { isDaemon = true }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            pushForegroundProgress("Starting Antigravity…")
            val installed = installer.installedRuntime()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val command = antigravityCommand(model(), effort(), conversationId(projectId))
            val process = installer.process(
                installed.chrootExec,
                installed.rootfs,
                workspace,
                emptyMap(),
                command,
                guestWorkspacePath = "/workspace/$projectSlug",
                emulateHardLinks = false,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()
            val request = JSONObject()
                .put("event", "user")
                .put("message", JSONObject().put("content", antigravityWorkspacePrompt(projectSlug, prompt)))
                .toString() + "\n"
            process.outputStream.write(request.toByteArray())
            process.outputStream.flush()
            process.outputStream.close()

            val native = process as? NativeSpawnProcess ?: error("Unsupported Antigravity process")
            spawnedOutputFile = native.outputFile
            var lastDiagnostic = ""
            var resultSeen = false
            var assistantTextSeen = false
            var activeToolName: String? = null
            var activeToolDetail = ""
            var activeToolStartedAtMillis = 0L
            var lastToolProgressAtMillis = 0L
            suspend fun handleLine(rawLine: String) {
                val line = rawLine.trimEnd('\r')
                if (line.isBlank()) return
                lastDiagnostic = line.takeLast(1_000)
                when (val event = AntigravityEventParser.parse(line)) {
                    is AntigravityParsedEvent.Initialized -> saveConversationId(projectId, event.conversationId)
                    is AntigravityParsedEvent.Text -> {
                        assistantTextSeen = true
                        pushForegroundProgress("Antigravity is working…")
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.value))
                    }
                    is AntigravityParsedEvent.ToolStarted -> {
                        activeToolName = event.name
                        activeToolDetail = event.detail
                        activeToolStartedAtMillis = System.currentTimeMillis()
                        lastToolProgressAtMillis = activeToolStartedAtMillis
                        pushForegroundProgress("Running ${event.name}: ${event.detail}")
                        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail))
                    }
                    is AntigravityParsedEvent.ToolCompleted -> {
                        if (activeToolName == event.name) activeToolName = null
                        pushForegroundProgress("Antigravity is working…")
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.name, event.detail))
                    }
                    is AntigravityParsedEvent.Result -> {
                        event.conversationId?.let { saveConversationId(projectId, it) }
                        if (event.status.equals("SUCCESS", ignoreCase = true)) {
                            if (!assistantTextSeen && !event.response.isNullOrBlank()) {
                                assistantTextSeen = true
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.response))
                            }
                            resultSeen = true
                        } else throw AntigravitySessionException(friendlyError(event.error ?: event.status))
                    }
                    null -> Unit
                }
            }
            InotifyLogTailer(native.outputFile).use { tailer ->
                outputLoop@ while (process.isAlive || tailer.hasUnreadBytes()) {
                    val lines = tailer.readLines(timeoutMs = 50L)
                    if (lines.isEmpty()) {
                        val now = System.currentTimeMillis()
                        pushForegroundProgress(
                            activeToolName?.let { name -> "Running $name: $activeToolDetail" }
                                ?: "Antigravity is working…",
                        )
                        if (activeToolName == "Bash" && activeToolDetail.contains("gradle", ignoreCase = true) &&
                            now - activeToolStartedAtMillis >= 10_000L && now - lastToolProgressAtMillis >= 10_000L
                        ) {
                            lastToolProgressAtMillis = now
                            eventBus.emit(
                                RuntimeEvent.ToolProgress(
                                    sessionId,
                                    "Bash",
                                    antigravityGradleProgress(activeToolDetail, now - activeToolStartedAtMillis),
                                ),
                            )
                        }
                        continue
                    }
                    for (line in lines) {
                        handleLine(line)
                        if (resultSeen) break@outputLoop
                    }
                }
                if (!resultSeen) {
                    for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                        handleLine(line)
                        if (resultSeen) break
                    }
                }
            }
            if (resultSeen) {
                stopAfterTerminalResult(process)
            } else {
                val exit = process.waitFor()
                check(exit == 0 && resultSeen) {
                    friendlyError(lastDiagnostic.ifBlank { "Antigravity exited with code $exit" })
                }
            }
            val paths = checkpoints.changedFiles(workspace, before)
            if (paths.isNotEmpty()) {
                checkpoints.saveChangedPaths(projectId, paths)
                val allPending = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, allPending)))
            } else if (checkpoints.readChangedPaths(projectId).isEmpty()) {
                acceptLastChanges(projectId)
            }
            emitCompleted(sessionId)
            finishForegroundRuntime(true, projectSlug, "Antigravity finished in $projectSlug")
        }.onFailure {
            val diagnostic = recentAntigravityDiagnostic(sessionStartedAtMillis)
            val message = if (userStopRequested) {
                "Stopped by user"
            } else {
                antigravityFriendlyError(it.message.orEmpty(), diagnostic)
            }
            emitFailure(sessionId, message)
            if (userStopRequested) cancelForegroundRuntime()
            else finishForegroundRuntime(false, projectSlug, message)
        }
        runCatching { spawnedOutputFile?.delete() }
        activeProcess = null
        activeSessionId = null
        activeProjectSlug = ""
        taskStartedAtElapsedRealtime = 0L
        RuntimeTaskController.stopAction = null
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // agy headless streaming runs with --dangerously-skip-permissions inside Chroot.
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailure(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() = activeSessionId?.let { stopSession(it) } ?: Unit

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertLatestTurn(projectId, workspace).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String): Unit = withContext(Dispatchers.IO) {
        checkpoints.acceptAll(projectId)
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, checkpoints.ensureWorkspace(projectId), paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertFile(projectId, workspace, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.acceptFile(projectId, workspace, path)
    }

    private suspend fun emitCompleted(sessionId: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
    }

    private suspend fun emitFailure(sessionId: String, reason: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
    }

    private fun startForegroundRuntime(projectName: String) {
        RuntimeExecutionServiceContract.startForeground(context, projectName)
    }

    /** Keeps long, silent Antigravity turns visibly active in Android's foreground service. */
    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsed = (now - taskStartedAtElapsedRealtime).coerceAtLeast(0L) / 1_000L
        val minutes = elapsed / 60L
        val seconds = elapsed % 60L
        RuntimeExecutionServiceContract.pushProgress(
            context,
            activeProjectSlug,
            "$detail · $minutes:${seconds.toString().padStart(2, '0')}",
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.finishForeground(context, completed, projectName, detail, "AntigravityBridge")
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.cancelForeground(context)
    }

    private fun friendlyError(raw: String): String = antigravityFriendlyError(raw)

    private fun recentAntigravityDiagnostic(sinceMillis: Long): String = runCatching {
        val logDir = File(
            installer.installedRuntime().rootfs,
            "root/.gemini/antigravity-cli/log",
        )
        val latest = logDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("cli-") && it.lastModified() >= sinceMillis - 2_000L }
            ?.maxByOrNull(File::lastModified)
            ?: return@runCatching ""
        latest.readTailText(12_000)
    }.getOrDefault("")

    private companion object {
        const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
    }
}

internal fun antigravityFriendlyError(raw: String, diagnostic: String = ""): String {
    val value = "$raw $diagnostic".replace(Regex("\\s+"), " ").trim()
    return when {
        ANTIGRAVITY_NETWORK_ERRORS.any { value.contains(it, ignoreCase = true) } ->
            "Antigravity could not reach Google. Check your internet or DNS connection, then try again."
        value.contains("authentication required", true) ||
            value.contains("authentication failed", true) ||
            value.contains("not signed in", true) ||
            value.contains("not logged in", true) ->
            "Antigravity needs Google sign-in. Open Settings → Coding agent."
        value.contains("out of credits", true) || value.contains("quota", true) ->
            "Your Antigravity account is out of credits. Check the account plan or wait for credits to reset."
        value.contains("timed out", true) || value.contains("timeout", true) ->
            "Antigravity reached the 60-minute task limit. Your files were kept."
        value.contains("model", true) && (value.contains("invalid", true) || value.contains("unknown", true)) ->
            "The selected Antigravity model is unavailable. Refresh models in Settings."
        value.isBlank() -> "Antigravity could not complete the task."
        else -> raw.replace(Regex("\\s+"), " ").trim().take(500)
    }
}

private val ANTIGRAVITY_NETWORK_ERRORS = listOf(
    "dial tcp",
    "i/o timeout",
    "network is unreachable",
    "no address associated with hostname",
    "temporary failure in name resolution",
    "unknownhost",
    "failed to fetch user info",
    "userinfo request failed",
)

private class AntigravitySessionException(message: String) : IllegalStateException(message)

internal fun antigravityCommand(model: String, effort: String, conversationId: String?): List<String> = buildList {
    add(RuntimeInstaller.AGY_GUEST_PATH)
    addAll(listOf("--input-format", "stream-json"))
    addAll(listOf("--output-format", "stream-json"))
    addAll(listOf("--print-timeout", "60m"))
    add("--dangerously-skip-permissions")
    addAntigravitySelection(model, effort)
    conversationId?.takeIf(String::isNotBlank)?.let {
        addAll(listOf("--conversation", it))
    } ?: add("--new-project")
}

private fun MutableList<String>.addAntigravitySelection(model: String, effort: String) {
    if (model.isNotBlank()) {
        addAll(listOf("--model", model))
    }
    if (effort in setOf("low", "medium", "high")) {
        addAll(listOf("--effort", effort))
    }
}

internal fun antigravityWorkspacePrompt(projectSlug: String, prompt: String): String {
    val safeSlug = projectSlug
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
    return """
    <pocketdev_workspace>
    The active project workspace is /workspace/$safeSlug. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.

    For Android projects, never use plain `gradle build` or start a Gradle daemon. Build only the debug APK. First check whether `app/build/outputs/apk/debug/` already contains an APK.
    - For a first build with no existing debug APK, allow dependency resolution and use:
      `timeout 15m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --init-script /root/.gradle/init.d/pocketdev-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 assembleDebug --console=plain --stacktrace`
    - For a later build with an existing debug APK, prefer the cached fast path:
      `timeout 5m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline --init-script /root/.gradle/init.d/pocketdev-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 assembleDebug --console=plain --stacktrace`
    NextCraft Forge bundles the standard Android dependencies locally. If and only if the cached build finishes with a clear missing-cached-dependency error, retry once online with `timeout 15m`, removing only `--offline` and retaining all other flags. Do not retry a timed-out or resource-failed build unchanged.
    Start the optimized Android build with `run_command` normally inside the Root Chroot workspace. If Antigravity moves the command into a managed task, immediately use `manage_task` to check its status and obtain its log path. Poll that managed task and tail its log with short commands about every 10 seconds so the user receives progress, then read the final output and exit status. Never wait silently on a long foreground command.
    </pocketdev_workspace>

    $prompt
""".trimIndent()
}

internal fun antigravityGradleProgress(command: String, elapsedMillis: Long): String {
    val elapsedSeconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L)
    return "Gradle is running · ${elapsedSeconds}s\n$command\nFull output will appear when the command completes."
}
```

## 4. `app/src/main/java/com/nextcraft/forge/runtime/AntigravityAuthController.kt` (377 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.AgentKind
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Drives only agy's official interactive OAuth flow inside Root Chroot; never reads the resulting credentials. */
class AntigravityAuthController(
    private val context: Context,
    initiallySignedIn: Boolean,
    initialAccountEmail: String,
    private val onSignedInChanged: (Boolean, String?) -> Unit,
) {
    private val installer = RuntimeInstaller(context)
    private val mutableState = MutableStateFlow(
        AntigravityAuthState(
            status = if (initiallySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
            message = initialAccountEmail.takeIf(String::isNotBlank)?.let { "Connected as $it" },
            accountEmail = initialAccountEmail.takeIf(String::isNotBlank),
        ),
    )
    val state: StateFlow<AntigravityAuthState> = mutableState.asStateFlow()
    private val authOutput = File(context.cacheDir, "antigravity-auth-output.log")
    private val logoutOutput = File(context.cacheDir, "antigravity-logout-output.log")
    @Volatile private var process: Process? = null
    @Volatile private var codeSubmitted = false

    init {
        // Remove output left by an app/process crash before starting a new OAuth flow.
        authOutput.delete()
        logoutOutput.delete()
    }

    private fun officialCredentialFile() = File(
        context.filesDir,
        "runtime/ubuntu/root/.gemini/antigravity-cli/antigravity-oauth-token",
    )

    private fun shellSingleQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun hasOfficialCredential(): Boolean {
        val file = officialCredentialFile()
        if (file.isFile) return true
        val quoted = shellSingleQuote(file.absolutePath)
        return runCatching {
            val proc = ProcessBuilder("su", "-c", "test -s $quoted").start()
            val finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.ERROR,
                message = "Install Antigravity CLI first in Settings.",
            )
            return@withContext
        }
        mutableState.value = AntigravityAuthState(
            AntigravityAuthStatus.STARTING,
            message = "Starting Antigravity sign-in…",
        )
        codeSubmitted = false
        authOutput.delete()
        val runtime = installer.installedRuntime()
        val workspace = File(context.filesDir, "workspaces/antigravity-auth").apply { mkdirs() }
        val running = installer.process(
            runtime.chrootExec,
            runtime.rootfs,
            workspace,
            // SSH_CONNECTION selects agy's official manual browser URL + one-time code flow.
            // NativeSpawn supplies a real PTY bridged via /dev/pts inside Root Chroot.
            mapOf(
                "SSH_CONNECTION" to "127.0.0.1 1 127.0.0.1 1",
                "TERM" to "xterm-256color",
                "NO_COLOR" to "1",
            ),
            listOf(RuntimeInstaller.AGY_GUEST_PATH),
            guestWorkspacePath = "/workspace/antigravity-auth",
            emulateHardLinks = false,
            outputFile = authOutput,
            pseudoTerminal = true,
            ptyRows = 40,
            ptyColumns = 120,
        )
        process = running
        val native = running as? NativeSpawnProcess ?: error("Unsupported Antigravity authentication process")
        var offset = 0L
        val output = StringBuilder()
        var handshakeReplies = 0
        var lastHandshakeReplyLength = 0
        var loginMenuAdvanced = false
        var colorScreenCompleted = false
        var renderingScreenCompleted = false
        var privacyScreenCompleted = false
        var workspaceTrustCompleted = false
        try {
            RandomAccessFile(native.outputFile, "r").use { file ->
                while (running.isAlive || native.outputFile.length() > offset) {
                    val available = native.outputFile.length() - offset
                    if (available <= 0) {
                        delay(80)
                        continue
                    }
                    val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                    file.seek(offset)
                    val count = file.read(bytes)
                    if (count <= 0) continue
                    val safeLength = completeUtf8PrefixLength(bytes, count).let {
                        if (it > 0) it else count
                    }
                    offset += safeLength
                    output.append(bytes.decodeToString(0, safeLength))
                    val clean = sanitizeTerminalOutput(output.toString()).takeLast(40_000)
                    // Antigravity's renderer asks a real terminal for DEC mode and
                    // Kitty keyboard-protocol status before it paints its UI.
                    if (handshakeReplies < 5 &&
                        output.length - lastHandshakeReplyLength > 200 &&
                        output.substring(lastHandshakeReplyLength).contains("\u001B[?u")
                    ) {
                        running.outputStream.write(TERMINAL_HANDSHAKE_REPLY.toByteArray())
                        running.outputStream.flush()
                        handshakeReplies++
                        lastHandshakeReplyLength = output.length
                    }
                    if (handshakeReplies > 0 &&
                        !loginMenuAdvanced &&
                        clean.contains("Select login method", true) &&
                        clean.contains("> 1. Google OAuth", true) &&
                        extractGoogleOAuthUrl(clean) == null
                    ) {
                        // agy's TUI (Ink) runs the PTY in raw mode and requires CR+LF for Enter.
                        running.outputStream.write("\r\n".toByteArray())
                        running.outputStream.flush()
                        loginMenuAdvanced = true
                        if (mutableState.value.status == AntigravityAuthStatus.STARTING) {
                            mutableState.value = mutableState.value.copy(
                                message = "Google OAuth selected. Generating sign-in link…",
                            )
                        }
                    }
                    val url = extractGoogleOAuthUrl(clean)
                    if (url != null && mutableState.value.authorizationUrl == null) {
                        mutableState.value = AntigravityAuthState(
                            AntigravityAuthStatus.AWAITING_CODE,
                            authorizationUrl = url,
                            message = "Open the Google link in your browser, sign in, and paste the verification code.",
                        )
                    }
                    if (isSignedInScreen(clean)) {
                        val email = extractSignedInEmail(clean)
                        onSignedInChanged(true, email)
                        mutableState.value = AntigravityAuthState(
                            AntigravityAuthStatus.SIGNED_IN,
                            message = email?.let { "Connected as $it" } ?: "Google account connected",
                            accountEmail = email,
                        )
                        // Leave the official CLI cleanly so it flushes credential/session state.
                        runCatching {
                            running.outputStream.write("/quit\r\n".toByteArray())
                            running.outputStream.flush()
                        }
                        repeat(20) {
                            if (!running.isAlive) return@repeat
                            delay(50)
                        }
                        if (running.isAlive) running.destroy()
                        break
                    }
                    if (!colorScreenCompleted && clean.contains("Choose your color scheme", true)) {
                        running.outputStream.write("\r\n".toByteArray())
                        running.outputStream.flush()
                        colorScreenCompleted = true
                    }
                    if (!renderingScreenCompleted &&
                        clean.contains("no flickering (altscreen)", true) &&
                        clean.contains("Native Terminal experience (inline)", true)
                    ) {
                        running.outputStream.write("\u001B[B\r\n".toByteArray())
                        running.outputStream.flush()
                        renderingScreenCompleted = true
                    }
                    if (!privacyScreenCompleted &&
                        clean.contains("Terms of Service & Data Use", true) &&
                        clean.contains("help improve Antigravity CLI", true)
                    ) {
                        val optOutAndFinish = if (clean.contains("[x] Yes", true)) {
                            " \t\t\r\n"
                        } else {
                            "\t\t\r\n"
                        }
                        running.outputStream.write(optOutAndFinish.toByteArray())
                        running.outputStream.flush()
                        privacyScreenCompleted = true
                        mutableState.value = mutableState.value.copy(
                            message = "Finishing Antigravity setup…",
                        )
                    }
                    if (!workspaceTrustCompleted &&
                        clean.contains("Do you trust the contents of this project", true) &&
                        clean.contains("Yes, I trust this folder", true)
                    ) {
                        running.outputStream.write("\r\n".toByteArray())
                        running.outputStream.flush()
                        workspaceTrustCompleted = true
                    }
                    if (clean.contains("authentication failed", true) || clean.contains("failed to exchange", true)) {
                        error("Antigravity authentication failed. Please try again.")
                    }
                }
            }
            if (!codeSubmitted && mutableState.value.status == AntigravityAuthStatus.STARTING) {
                val exit = running.waitFor()
                error("Antigravity sign-in exited early (code $exit).")
            }
        } catch (error: Throwable) {
            if (mutableState.value.status != AntigravityAuthStatus.SIGNED_IN) {
                mutableState.value = AntigravityAuthState(
                    AntigravityAuthStatus.ERROR,
                    message = error.message?.take(240) ?: "Antigravity sign-in failed.",
                )
            }
        } finally {
            runCatching { running.outputStream.close() }
            if (running.isAlive) {
                runCatching { running.destroy() }
                runCatching {
                    if (running.isAlive) running.destroyForcibly()
                }
            }
            runCatching { running.waitFor() }
            native.outputFile.delete() // OAuth terminal output is intentionally ephemeral.
            process = null
            codeSubmitted = false
        }
    }

    @Synchronized
    fun submitCode(code: String) {
        val value = code.trim()
        require(value.isNotBlank()) { "Paste the verification code from Google first." }
        val running = process ?: error("Sign-in session is not active. Start sign-in again.")
        check(running.isAlive) { "Sign-in session expired. Start sign-in again." }
        running.outputStream.write((value + "\r\n").toByteArray())
        running.outputStream.flush()
        codeSubmitted = true
        mutableState.value = mutableState.value.copy(
            status = AntigravityAuthStatus.COMPLETING,
            message = "Completing Google sign-in…",
        )
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        process?.let { running ->
            runCatching { running.destroy() }
            runCatching { if (running.isAlive) running.destroyForcibly() }
            runCatching { running.waitFor() }
        }
        val previousEmail = mutableState.value.accountEmail
        mutableState.value = AntigravityAuthState(
            status = AntigravityAuthStatus.STARTING,
            message = "Signing out…",
            accountEmail = previousEmail,
        )
        try {
            val credential = officialCredentialFile()
            if (credential.exists()) {
                credential.delete()
            }
            if (hasOfficialCredential()) {
                val quoted = shellSingleQuote(credential.absolutePath)
                runCatching {
                    val proc = ProcessBuilder("su", "-c", "rm -f $quoted").start()
                    if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                    }
                }
            }
            check(!hasOfficialCredential()) { "Antigravity sign-out is incomplete." }
            onSignedInChanged(false, null)
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.SIGNED_OUT,
                message = "Signed out of Antigravity.",
            )
        } catch (error: Throwable) {
            mutableState.value = AntigravityAuthState(
                status = AntigravityAuthStatus.SIGNED_IN,
                message = error.message?.take(240) ?: "Could not sign out of Antigravity.",
                accountEmail = previousEmail,
            )
            throw error
        } finally {
            process = null
            logoutOutput.delete()
        }
    }

    fun cancel() {
        val running = process
        if (running != null) {
            Thread {
                runCatching {
                    running.destroy()
                    Thread.sleep(200)
                    if (running.isAlive) running.destroyForcibly()
                    running.waitFor()
                }
            }.apply { isDaemon = true }.start()
        }
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT)
    }

    fun invalidateSession(message: String) {
        onSignedInChanged(false, null)
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.ERROR, message = message)
    }
}

internal fun extractGoogleOAuthUrl(output: String): String? {
    val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
    GOOGLE_OAUTH_URL.find(compact)?.value
        ?.takeIf { "client_id=" in it && "code_challenge=" in it }
        ?.let { return it }
    if (!output.contains("Select login method", true) &&
        (output.contains("browser", true) || output.contains("visit", true) ||
            output.contains("open", true) || output.contains("code", true) ||
            output.contains("paste", true))
    ) {
        val candidates = Regex("https://accounts\\.google\\.com/[^\\s\"']{10,}|https://oauth2\\.googleapis\\.com/[^\\s\"']{10,}")
            .findAll(compact)
            .map { it.value.trimEnd { char -> char !in URL_CHARACTERS } }
            .filter { it.length >= 30 && "." in it }
            .toList()
        return candidates.firstOrNull { "google" in it } ?: candidates.firstOrNull()
    }
    return null
}

internal fun isSignedInScreen(output: String): Boolean {
    if (output.contains("error", true) || output.contains("failed", true)) return false
    return output.contains("for shortcuts", true) ||
        (output.contains("Antigravity CLI", true) && output.contains("Google AI", true))
}

private fun extractSignedInEmail(output: String): String? =
    Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        .findAll(output)
        .map { it.value }
        .firstOrNull { !it.endsWith(".apps.googleusercontent.com", ignoreCase = true) }

private val URL_CHARACTERS = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
    "-._~:/?#[]@!$&'()*+,;=%").toSet()

private val GOOGLE_OAUTH_URL = Regex(
    "https://accounts\\.google\\.com/[^\\s\\\"'<>]*?[?&]state=[A-Za-z0-9._~-]+",
)

private const val TERMINAL_HANDSHAKE_REPLY = "\u001B[?2026;1\$y\u001B[?2027;1\$y\u001B[?1u\n"

private val AUTH_ANSI = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")
private fun sanitizeTerminalOutput(text: String): String = text
    .replace(AUTH_ANSI, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
```

## 5. `app/src/main/java/com/nextcraft/forge/runtime/DshRuntimeBridge.kt` (969 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import android.util.Log
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RuntimeBridge] driving the official DeepSeek Harness (`dsh`) inside the
 * NextCraft Forge Ubuntu 24.04 Root Chroot (`--profile sdk` JSON-RPC 2.0).
 */
class DshRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    private val rotateSecretFor: (ProviderProfile) -> String? = { profile ->
        com.nextcraft.forge.data.ApiKeyVault(context).rotateNext(profile.kind.name)
    },
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    @Volatile private var lastThinkingUpdateAt: Long = 0L

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        lastThinkingUpdateAt = 0L
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting DeepSeek Harness…")
        val secret = secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (secret.isBlank()) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "No API key is saved for ${provider.kind.title}."))
            return@withContext sessionId
        }
        if (provider.kind == ProviderKind.CLAUDE) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "Claude subscription login is not supported by DeepSeek Harness. Pick a key-based provider in Settings.",
                ),
            )
            return@withContext sessionId
        }

        var openRouterGateway: OpenRouterRoutingGateway? = null
        var spawnedOutputFile: File? = null
        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread({
                        runCatching {
                            running.destroy()
                            Thread.sleep(500)
                            if (running.isAlive) running.destroyForcibly()
                        }
                    }, "forge-dsh-stop").apply { isDaemon = true }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
                "DeepSeek Harness is not installed. Open Settings → Coding agent to install it."
            }
            installer.ensureDshAndroidCompatibility()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val baseRoute = DshRouteMapper.forProfile(provider)
            openRouterGateway = if (
                provider.kind == ProviderKind.LLM_ROUTER && provider.openRouterProviders.isNotEmpty()
            ) OpenRouterRoutingGateway(provider, secret) { rotateSecretFor(provider) }.start() else null
            val route = if (openRouterGateway != null && baseRoute.custom != null) {
                baseRoute.copy(custom = baseRoute.custom.copy(baseUrl = openRouterGateway!!.url))
            } else {
                baseRoute
            }
            writeDshSettings(installed.rootfs, route, provider)
            val environment = linkedMapOf(
                "DSH_HOME" to DSH_HOME_GUEST_PATH,
                "DSH_PERMISSION_MODE" to "danger-full-access",
                route.keyEnv to secret,
            )
            if (route.keyEnv != FALLBACK_KEY_ENV) environment.remove(FALLBACK_KEY_ENV)

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)
            val command = listOf("/usr/local/bin/dsh", "--profile", "sdk")
            Log.d("DshBridge", "Route: ${route.name}, Model: ${provider.model}")
            val process = installer.process(
                installed.chrootExec,
                installed.rootfs,
                workspace,
                environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
                emulateHardLinks = false,
            )
            activeProcess = process
            spawnedOutputFile = (process as? NativeSpawnProcess)?.outputFile
            if (userStopRequested) process.destroy()
            val sdkResult = runSdkSession(
                process = process,
                sessionId = sessionId,
                route = route,
                model = provider.model.ifBlank { route.defaultModel },
                guestWorkspacePath = guestWorkspacePath,
                prompt = contextPrompt,
            )
            val exit = process.waitFor()
            Log.d("DshBridge", "SDK process exited with code $exit")
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                Log.d("DshBridge", "Changed files (${changed.size})")
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (checkpoints.readChangedPaths(projectId).isEmpty()) {
                acceptLastChanges(projectId)
            }
            if (sdkResult.completed && sdkResult.failure.isBlank() && !userStopRequested) {
                emitCompletedOnce(sessionId)
                finishForegroundRuntime(
                    completed = true,
                    projectName = projectSlug,
                    detail = "DeepSeek Harness finished in $projectSlug",
                )
            } else {
                if (userStopRequested) throw DshSessionException("Stopped by user")
                error(sdkResult.failure.ifBlank { "DeepSeek Harness stopped with exit code $exit" })
            }
        }.onFailure { error ->
            Log.e("DshBridge", "Session failed: ${error::class.simpleName}")
            val message = friendlyError(error)
            emitFailureOnce(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = projectSlug,
                    detail = message,
                )
            }
        }
        runCatching { openRouterGateway?.close() }
        runCatching { spawnedOutputFile?.delete() }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    private suspend fun runSdkSession(
        process: Process,
        sessionId: String,
        route: DshRoute,
        model: String,
        guestWorkspacePath: String,
        prompt: String,
    ): DshSdkRunResult {
        val nativeProcess = process as? NativeSpawnProcess
            ?: error("Unsupported Android runtime process")
        val writer = process.outputStream.bufferedWriter()
        val parser = DshSdkProtocolParser(sessionId)
        var promptSent = false
        var sawRunning = false
        var completed = false
        var sawActivity = false
        var shutdownSent = false
        var shutdownSentAt = 0L
        var forcedShutdown = false
        var inputClosed = false
        var failure = ""

        fun send(method: String, id: Int, params: JSONObject? = null) {
            val frame = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
            if (params != null) frame.put("params", params)
            writer.write(frame.toString())
            writer.newLine()
            writer.flush()
        }

        fun closeInput() {
            if (inputClosed) return
            inputClosed = true
            runCatching { writer.close() }
        }

        fun finishTurnAndShutdown() {
            if (shutdownSent) return
            completed = sawActivity && failure.isBlank()
            if (!completed && failure.isBlank()) {
                failure = "DeepSeek Harness stopped before processing the prompt"
            }
            shutdownSent = true
            shutdownSentAt = android.os.SystemClock.elapsedRealtime()
            send("shutdown", SDK_SHUTDOWN_ID)
        }

        send(
            method = "initialize",
            id = SDK_INITIALIZE_ID,
            params = JSONObject()
                .put("cwd", guestWorkspacePath)
                .put("provider", route.name)
                .put("model", model),
        )

        suspend fun handle(protocolEvent: DshSdkProtocolEvent) {
            when (protocolEvent) {
                DshSdkProtocolEvent.Initialized -> if (!promptSent) {
                    send(
                        method = "session/prompt",
                        id = SDK_PROMPT_ID,
                        params = JSONObject()
                            .put("sessionId", sessionId)
                            .put(
                                "contentBlocks",
                                JSONArray().put(JSONObject().put("type", "text").put("text", prompt)),
                            ),
                    )
                    promptSent = true
                }
                DshSdkProtocolEvent.PromptAccepted -> Unit
                is DshSdkProtocolEvent.Status -> {
                    if (protocolEvent.running) {
                        sawRunning = true
                        pushForegroundProgress("DeepSeek Harness is working…")
                    } else if (sawRunning && !shutdownSent) {
                        finishTurnAndShutdown()
                    }
                }
                is DshSdkProtocolEvent.Reasoning -> {
                    sawActivity = true
                    emitReasoningSummary(
                        sessionId = sessionId,
                        text = protocolEvent.text,
                        blockId = protocolEvent.blockId,
                        startsNewBlock = protocolEvent.startsNewBlock,
                        isFinal = protocolEvent.isFinal,
                        force = protocolEvent.startsNewBlock || protocolEvent.isFinal,
                    )
                }
                is DshSdkProtocolEvent.ToolStarted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolStarted(sessionId, protocolEvent.name, protocolEvent.detail),
                    )
                }
                is DshSdkProtocolEvent.ToolCompleted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolCompleted(sessionId, protocolEvent.name, protocolEvent.summary),
                    )
                }
                is DshSdkProtocolEvent.AssistantText -> if (protocolEvent.text.isNotEmpty()) {
                    sawActivity = true
                    eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, protocolEvent.text))
                }
                is DshSdkProtocolEvent.Failed -> failure = protocolEvent.message
                DshSdkProtocolEvent.TurnCompleted -> {
                    sawActivity = true
                    finishTurnAndShutdown()
                }
                DshSdkProtocolEvent.ShutdownAcknowledged -> closeInput()
                DshSdkProtocolEvent.Ignored -> Unit
            }
        }

        suspend fun processDshLine(rawLine: String) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) return
            if (line.endsWith("... [truncated]") || line.length > MAX_DSH_PROTOCOL_LINE_CHARS) {
                val duplicate = parser.consumeDuplicateAssistantMessage(line)
                val hasTurnEnd = line.contains("\"turn/end\"") || line.contains("TurnCompleted")
                if (hasTurnEnd) {
                    handle(DshSdkProtocolEvent.TurnCompleted)
                } else if (!duplicate) {
                    handle(DshSdkProtocolEvent.Failed("DeepSeek Harness sent an oversized response"))
                }
                return
            }
            if (!parser.consumeDuplicateAssistantMessage(line)) {
                handle(parser.parseLine(line))
            }
        }

        InotifyLogTailer(nativeProcess.outputFile, maxLineBytes = MAX_DSH_PROTOCOL_LINE_CHARS).use { tailer ->
            while (process.isAlive || tailer.hasUnreadBytes()) {
                if (
                    process.isAlive &&
                    !forcedShutdown &&
                    shutdownSentAt > 0L &&
                    android.os.SystemClock.elapsedRealtime() - shutdownSentAt >= SDK_SHUTDOWN_TIMEOUT_MS
                ) {
                    closeInput()
                    forcedShutdown = true
                    Log.w("DshBridge", "SDK acknowledged completion but did not exit; stopping its runtime wrapper")
                    process.destroyForcibly()
                }
                for (line in tailer.readLines(timeoutMs = 50L)) {
                    processDshLine(line)
                }
            }
            for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                processDshLine(line)
            }
        }
        closeInput()
        return DshSdkRunResult(completed = completed, failure = failure)
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // Headless SDK runs with danger-full-access inside Chroot.
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertLatestTurn(projectId, workspace).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.acceptAll(projectId)
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertFile(projectId, workspace, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.acceptFile(projectId, workspace, path)
    }

    private fun writeDshSettings(rootfs: File, route: DshRoute, provider: ProviderProfile) {
        val home = File(rootfs, DSH_HOME_GUEST_PATH.removePrefix("/")).apply { mkdirs() }
        val body = buildString {
            appendLine("agent-default-model:")
            appendLine("  provider: ${route.name}")
            appendLine("  model: ${yamlQuote(provider.model.ifBlank { route.defaultModel })}")
            if (route.custom != null) {
                appendLine("llm-pi-ai:")
                appendLine("  providers:")
                appendLine("    ${route.name}:")
                appendLine("      apiKeyEnv: ${route.keyEnv}")
                appendLine("      api: ${route.custom.api}")
                appendLine("      baseURL: ${yamlQuote(route.custom.baseUrl)}")
                appendLine("      models:")
                appendLine("        - id: ${yamlQuote(provider.model.ifBlank { route.defaultModel })}")
            }
        }
        File(home, "settings.yaml").writeText(body)
    }

    private fun yamlQuote(value: String): String = "'${value.replace("'", "''")}'"

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        blockId: Long,
        startsNewBlock: Boolean,
        isFinal: Boolean,
        force: Boolean = false,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastThinkingUpdateAt < 400) return
        val summary = compactDshText(text, MAX_DSH_REASONING_BUFFER, MAX_DSH_REASONING_SUMMARY)
        if (summary.isBlank()) return
        lastThinkingUpdateAt = now
        eventBus.emit(
            RuntimeEvent.ReasoningSummary(
                sessionId = sessionId,
                summary = summary,
                blockId = blockId,
                startsNewBlock = startsNewBlock,
                isFinal = isFinal,
            ),
        )
        pushForegroundProgress("Thinking…")
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "Project",
                detail = "DeepSeek Harness finished",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "Project",
                    detail = reason,
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is DshSessionException && message.isMeaningfulDshText() -> message
            message.contains("authentication", true) ||
                message.contains("invalid api key", true) ||
                message.contains("autherror", true) ||
                message.contains("expired", true) ||
                message.contains("quota", true) ||
                message.contains("rate limit", true) ||
                listOf("401", "403", "429").any { code ->
                    message.contains(code) && (message.contains("auth", true) || message.contains("HTTP", true))
                } ->
                "The provider rejected the saved API key."
            message.contains("missing_credential", true) ->
                "No API key reached DeepSeek Harness. Re-save the provider key in Settings."
            message.contains("not installed", true) -> message.take(300)
            !message.isMeaningfulDshText() -> "DeepSeek Harness could not start."
            else -> message.take(500)
        }
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
    ): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                    !msg.text.startsWith("Failed to") &&
                    !msg.text.startsWith("Error:") &&
                    !msg.text.contains("API Error")
            }
            .dropLast(1)
            .recentWithinCharacterBudget(MAX_CONVERSATION_HISTORY_CHARACTERS)

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (installer.isStackInstalled(DevStack.ANDROID)) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
            sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
            sb.appendLine("NextCraft Forge globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this NextCraft Forge runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in NextCraft Forge Settings before building.")
        }
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        RuntimeExecutionServiceContract.pushProgress(context, activeProjectSlug, text)
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(projectName: String) {
        RuntimeExecutionServiceContract.startForeground(context, projectName)
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.finishForeground(context, completed, projectName, detail, "DshBridge")
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.cancelForeground(context)
    }

    private class DshSessionException(message: String) : IllegalStateException(message)

    companion object {
        const val DSH_HOME_GUEST_PATH = "/root/.dsh"
        const val FALLBACK_KEY_ENV = "MH_DSH_API_KEY"
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
        private const val SDK_INITIALIZE_ID = 1
        private const val SDK_PROMPT_ID = 2
        private const val SDK_SHUTDOWN_ID = 3
        private const val SDK_SHUTDOWN_TIMEOUT_MS = 3_000L
    }
}

private data class DshSdkRunResult(val completed: Boolean, val failure: String)

/** dsh provider route resolved from our saved provider profile. */
internal data class DshRoute(
    val name: String,
    val keyEnv: String,
    val defaultModel: String,
    val custom: DshCustomRoute? = null,
)

internal data class DshCustomRoute(val api: String, val baseUrl: String)

internal object DshRouteMapper {
    fun forProfile(profile: ProviderProfile): DshRoute {
        val model = profile.model.ifBlank { profile.kind.defaultModel }
        return when (profile.kind) {
            ProviderKind.DEEPSEEK -> DshRoute(
                name = "deepseek-official",
                keyEnv = "DEEPSEEK_API_KEY",
                defaultModel = model,
            )
            ProviderKind.ANTHROPIC -> DshRoute(
                name = "mh-anthropic",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", profile.resolvedBaseUrl),
            )
            ProviderKind.LLM_ROUTER -> DshRoute(
                name = "mh-openrouter",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(
                    "anthropic-messages",
                    profile.resolvedBaseUrl.trimEnd('/').removeSuffix("/v1"),
                ),
            )
            ProviderKind.KIMI -> DshRoute(
                name = "mh-kimi",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
            ProviderKind.OPENCODE_ZEN -> DshRoute(
                name = "opencode-zen",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-responses", profile.resolvedBaseUrl),
            )
            ProviderKind.NVIDIA_NIM -> DshRoute(
                name = "nvidia-nim",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-completions", profile.resolvedBaseUrl),
            )
            ProviderKind.CUSTOM -> DshRoute(
                name = "mh-custom",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
            ProviderKind.CLAUDE -> throw IllegalArgumentException("Claude subscription login is not supported by DeepSeek Harness")
        }
    }
}

/** One classified line of merged headless output (stdout+stderr share a capture file). */
internal sealed interface DshLine {
    data object ReasoningHeading : DshLine
    data class Reasoning(val text: String) : DshLine
    data class Diagnostic(val text: String) : DshLine
    data class Answer(val text: String) : DshLine
}

internal object DshHeadlessParser {
    fun parseLine(rawLine: String): DshLine {
        val line = rawLine.trim()
        if (line.startsWith("dsh:")) {
            val body = line.removePrefix("dsh:").trim()
            if (body.startsWith("reasoning:")) {
                val rest = body.removePrefix("reasoning:").trim()
                return if (rest.isBlank()) DshLine.ReasoningHeading else DshLine.Reasoning(rest)
            }
            return DshLine.Diagnostic(body.ifBlank { line })
        }
        return DshLine.Answer(line)
    }
}

internal sealed interface DshSdkProtocolEvent {
    data object Initialized : DshSdkProtocolEvent
    data object PromptAccepted : DshSdkProtocolEvent
    data class Status(val running: Boolean) : DshSdkProtocolEvent
    data class Reasoning(
        val blockId: Long,
        val text: String,
        val startsNewBlock: Boolean,
        val isFinal: Boolean,
    ) : DshSdkProtocolEvent
    data class ToolStarted(val callId: String, val name: String, val detail: String) : DshSdkProtocolEvent
    data class ToolCompleted(val callId: String, val name: String, val summary: String) : DshSdkProtocolEvent
    data class AssistantText(val text: String) : DshSdkProtocolEvent
    data class Failed(val message: String) : DshSdkProtocolEvent
    data object TurnCompleted : DshSdkProtocolEvent
    data object ShutdownAcknowledged : DshSdkProtocolEvent
    data object Ignored : DshSdkProtocolEvent
}

private fun String.isMeaningfulDshText(): Boolean =
    isNotBlank() && !trim().equals("null", ignoreCase = true)

/** Stateful parser for the pinned dsh SDK's newline-delimited JSON-RPC stream. */
internal class DshSdkProtocolParser(private val expectedSessionId: String) {
    private val reasoningByBlock = mutableMapOf<Long, StringBuilder>()
    private val textByBlock = mutableMapOf<Long, StringBuilder>()
    private var streamedTextSinceMessage = false
    private val toolNames = mutableMapOf<String, String>()

    fun consumeDuplicateAssistantMessage(line: CharSequence): Boolean {
        if (!streamedTextSinceMessage) return false
        if (!line.contains("\"method\":\"session.event\"") ||
            !line.contains("\"type\":\"assistant/message\"")
        ) return false
        streamedTextSinceMessage = false
        return true
    }

    fun parseLine(line: String): DshSdkProtocolEvent {
        val frame = runCatching { JSONObject(line) }.getOrNull()
            ?: return if (line.startsWith("dsh:", ignoreCase = true)) {
                DshSdkProtocolEvent.Failed(
                    line.removePrefix("dsh:").trim().meaningfulDshText(
                        "DeepSeek Harness reported an unspecified error",
                    ),
                )
            } else {
                DshSdkProtocolEvent.Ignored
            }

        if (frame.has("id")) {
            val id = frame.optInt("id", -1)
            frame.optJSONObject("error")?.let { error ->
                return DshSdkProtocolEvent.Failed(
                    error.optString("message").meaningfulDshText("DeepSeek Harness SDK request $id failed"),
                )
            }
            return when (id) {
                1 -> DshSdkProtocolEvent.Initialized
                2 -> DshSdkProtocolEvent.PromptAccepted
                3 -> DshSdkProtocolEvent.ShutdownAcknowledged
                else -> DshSdkProtocolEvent.Ignored
            }
        }

        val params = frame.optJSONObject("params") ?: return DshSdkProtocolEvent.Ignored
        return when (frame.optString("method")) {
            "session.status" -> {
                if (params.optString("sessionId") != expectedSessionId) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Status(params.optString("status") == "running")
            }
            "session.event" -> parseSessionEvent(params)
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseSessionEvent(params: JSONObject): DshSdkProtocolEvent {
        if (params.optString("sessionId") != expectedSessionId) return DshSdkProtocolEvent.Ignored
        val event = params.optJSONObject("event") ?: return DshSdkProtocolEvent.Ignored
        val data = event.optJSONObject("data") ?: return DshSdkProtocolEvent.Ignored
        return when (event.optString("type")) {
            "assistant/chunk" -> parseAssistantChunk(data)
            "assistant/message" -> {
                val content = data.optJSONObject("message")?.optJSONArray("content")
                val text = contentText(content)
                if (text.isBlank()) {
                    DshSdkProtocolEvent.Ignored
                } else if (streamedTextSinceMessage) {
                    streamedTextSinceMessage = false
                    DshSdkProtocolEvent.Ignored
                } else {
                    DshSdkProtocolEvent.AssistantText(text)
                }
            }
            "tool/call" -> {
                val callId = data.optString("callId")
                val rawName = data.optString("name").ifBlank { "Tool" }
                val arguments = data.optString("arguments")
                val displayName = displayToolName(rawName, arguments)
                toolNames[callId] = displayName
                DshSdkProtocolEvent.ToolStarted(callId, displayName, toolDetail(arguments))
            }
            "tool/result" -> {
                val message = data.optJSONObject("message")
                val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
                val callId = resultBlock?.optString("toolCallId").orEmpty()
                val name = toolNames.remove(callId) ?: "Tool"
                val error = data.optJSONObject("error")
                val text = contentText(resultBlock?.optJSONArray("content"))
                val summary = error?.optString("message").orEmpty()
                    .meaningfulDshText(text)
                    .let { compactDshText(it, MAX_DSH_TOOL_RAW_TEXT, 180) }
                    .ifBlank { "$name completed" }
                DshSdkProtocolEvent.ToolCompleted(callId, name, summary)
            }
            "turn/end" -> {
                val reason = data.optJSONObject("reason")
                when (reason?.optString("kind")) {
                    "error" -> DshSdkProtocolEvent.Failed(
                        reason.optJSONObject("error")?.optString("message").orEmpty()
                            .meaningfulDshText("DeepSeek Harness turn failed"),
                    )
                    "blocked" -> DshSdkProtocolEvent.Failed("DeepSeek Harness was blocked from completing the task")
                    else -> DshSdkProtocolEvent.TurnCompleted
                }
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun String.meaningfulDshText(fallback: String): String =
        if (isMeaningfulDshText()) trim() else fallback

    private fun parseAssistantChunk(data: JSONObject): DshSdkProtocolEvent {
        val chunk = data.optJSONObject("chunk") ?: return DshSdkProtocolEvent.Ignored
        val index = chunk.optInt("index", 0)
        val blockId = data.optInt("turn", 0) * 1_000_000L + data.optInt("step", 0) * 1_000L + index
        return when (chunk.optString("type")) {
            "text-delta" -> {
                val delta = chunk.optString("text")
                if (delta.isEmpty()) return DshSdkProtocolEvent.Ignored
                textByBlock.getOrPut(blockId) { StringBuilder() }
                    .appendBounded(delta, MAX_DSH_TEXT_BLOCK_BUFFER)
                streamedTextSinceMessage = true
                DshSdkProtocolEvent.AssistantText(delta)
            }
            "reasoning-delta" -> {
                val buffer = reasoningByBlock.getOrPut(blockId) { StringBuilder() }
                val starts = buffer.isEmpty()
                buffer.appendBounded(chunk.optString("text"), MAX_DSH_REASONING_BUFFER)
                DshSdkProtocolEvent.Reasoning(blockId, buffer.toString(), starts, isFinal = false)
            }
            "block-end" -> {
                val block = chunk.optJSONObject("block")
                if (block?.optString("type") == "text") {
                    val streamed = textByBlock.remove(blockId)?.toString().orEmpty()
                    val complete = block.optString("text")
                    val missingSuffix = if (streamed.isEmpty()) complete.takeLast(MAX_DSH_TEXT_BLOCK_BUFFER) else ""
                    if (missingSuffix.isBlank()) return DshSdkProtocolEvent.Ignored
                    streamedTextSinceMessage = true
                    return DshSdkProtocolEvent.AssistantText(missingSuffix)
                }
                if (block?.optString("type") != "reasoning") return DshSdkProtocolEvent.Ignored
                val text = block.optString("text")
                    .ifBlank { reasoningByBlock[blockId]?.toString().orEmpty() }
                    .takeLast(MAX_DSH_REASONING_BUFFER)
                val starts = blockId !in reasoningByBlock
                reasoningByBlock.remove(blockId)
                if (text.isBlank()) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Reasoning(blockId, text, starts, isFinal = true)
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun displayToolName(rawName: String, arguments: String): String {
        val operation = runCatching { JSONObject(arguments).optString("command") }.getOrDefault("")
        return when (rawName.lowercase()) {
            "bash", "shell" -> "Bash"
            "read", "view" -> "Read"
            "glob" -> "Glob"
            "grep", "search" -> "Grep"
            "write", "create" -> "Write"
            "edit", "str_replace_editor" -> when (operation.lowercase()) {
                "view" -> "Read"
                "create" -> "Write"
                else -> "Edit"
            }
            else -> rawName.replaceFirstChar { it.uppercase() }
        }
    }

    private fun toolDetail(arguments: String): String {
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
        val detail = parsed?.let { json ->
            listOf("path", "file_path", "command", "pattern", "query")
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf(String::isNotBlank) }
        }.orEmpty()
        return compactDshText(detail.ifBlank { arguments }, MAX_DSH_TOOL_RAW_TEXT, 240)
            .ifBlank { "Working in the project" }
    }

    private fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        val result = StringBuilder()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            val text = when (block.optString("type")) {
                "text" -> block.optString("text")
                "tool-result" -> contentText(block.optJSONArray("content"))
                else -> ""
            }
            if (text.isBlank()) continue
            if (result.isNotEmpty()) result.append('\n')
            result.appendBounded(text, MAX_DSH_CONTENT_TEXT)
        }
        return result.toString()
    }
}

private const val MAX_DSH_REASONING_BUFFER = 8_000
private const val MAX_DSH_REASONING_SUMMARY = 2_000
private const val MAX_DSH_TOOL_RAW_TEXT = 4_000
private const val MAX_DSH_TEXT_BLOCK_BUFFER = 8_000
private const val MAX_DSH_CONTENT_TEXT = 16_000
private const val MAX_DSH_PROTOCOL_LINE_CHARS = 256 * 1024

private fun StringBuilder.appendBounded(value: String, maxChars: Int) {
    if (value.length >= maxChars) {
        clear()
        append(value.takeLast(maxChars))
        return
    }
    append(value)
    val overflow = length - maxChars
    if (overflow > 0) delete(0, overflow)
}

private val DSH_SECRET_TOKEN_REGEX = Regex("sk-[A-Za-z0-9_-]{8,}")
private val DSH_KEY_VALUE_SECRET_REGEX = Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\",}]+)")
private val DSH_BEARER_SECRET_REGEX = Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\",}]+")

/** Collapses whitespace and redacts secrets while inspecting only a bounded tail of untrusted agent output. */
internal fun compactDshText(value: String, rawLimit: Int, outputLimit: Int): String {
    if (value.isEmpty() || rawLimit <= 0 || outputLimit <= 0) return ""
    val start = (value.length - rawLimit).coerceAtLeast(0)
    val result = StringBuilder(value.length - start)
    var pendingSpace = false
    for (index in start until value.length) {
        val char = value[index]
        if (char.isWhitespace()) {
            pendingSpace = result.isNotEmpty()
        } else {
            if (pendingSpace) result.append(' ')
            pendingSpace = false
            result.append(char)
        }
    }
    return result.toString()
        .replace(DSH_SECRET_TOKEN_REGEX, "sk-••••")
        .replace(DSH_KEY_VALUE_SECRET_REGEX, "$1$2••••")
        .replace(DSH_BEARER_SECRET_REGEX, "$1••••")
        .takeLast(outputLimit)
}
```

---

# Blok 4 — Pipeline Build Android Lokal, APK Installer, Foreground Services, Jaringan & Self-Updater

Blok 4 menyediakan fitur-fitur integrasi sistem Android dan jaringan yang membuat NextCraft Forge dapat **membangun proyek Android menjadi APK secara lokal di ponsel, menginstal APK dengan 1 ketukan, menjaga sesi agen tetap hidup di latar belakang, mengelola repositori GitHub, dan memperbarui dirinya sendiri**.

### Fungsi Utama Blok 4:
1. **Generator Proyek & Pipeline Build Android (`AndroidProjectTemplateGenerator.kt` & `AndroidBuildSupport.kt`)**:
   - Menghasilkan proyek Android siap-pakai (Jetpack Compose Material 3 maupun XML Views) lengkap dengan konfigurasi AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, dan Java 17 yang selaras dengan cache Maven offline bawaan.
   - Mendeteksi root proyek Android, menyusun perintah `gradle assembleDebug` yang dioptimalkan untuk ARM64 (`aapt2FromMavenOverride`, `--offline` fast-path jika APK debug sebelumnya sudah ada), mendeteksi tahapan build (`RESOLVING`, `COMPILING`, `RESOURCES`, `PACKAGING`), menemukan file APK hasil build (dengan proteksi path traversal pada `output-metadata.json`), dan mem-parsing baris error `kotlinc`/`javac`/`aapt2` menjadi daftar `AndroidBuildIssue` lengkap dengan path file dan nomor baris.
2. **Instalasi APK 1-Tap via Root & PackageInstaller (`AndroidAppInstaller.kt` & `AndroidAppInstallReceiver.kt`)**:
   - Pada perangkat root, menginstal APK hasil build secara senyap melalui streaming stdin ke `su -c "pm install -r -d -S <size>"` lalu langsung meluncurkan aplikasi yang baru diinstal.
   - Jika instalasi senyap gagal atau saat melakukan self-update, otomatis beralih ke sesi `PackageInstaller.Session` Android standar (atau intent `FileProvider` pada perangkat MIUI) dan menerima callback status instalasi melalui `AndroidAppInstallReceiver`.
3. **Foreground Services Eksekusi & Instalasi (`RuntimeExecutionService.kt` & `RuntimeSetupService.kt`)**:
   - Menjaga proses agen coding dan proses download/ekstraksi toolchain tetap berjalan meskipun pengguna berpindah aplikasi atau layar mati, menggunakan `PARTIAL_WAKE_LOCK`, notifikasi progres real-time, serta tombol aksi **Stop**.
4. **Klien Jaringan, Validasi Provider, Pemindai Port Lokal & Pembaruan Aplikasi (`ProviderApiClient.kt`, `LocalNetwork.kt`, `GitHubClient.kt`, `AppUpdater.kt`)**:
   - `ProviderApiClient`: Menguji koneksi API key dan mengambil daftar katalog model terbaru dari Anthropic, OpenAI, OpenRouter, DeepSeek, Kimi, OpenCode Zen, NVIDIA NIM, atau server lokal.
   - `LocalNetwork`: Memvalidasi kebijakan keamanan HTTP lokal (loopback, LAN RFC1918, Tailscale CGNAT `100.64.0.0/10`, IPv6 ULA, akhiran `.local`/`.lan`/`.home`/`.internal`/`.ts.net`) dan memindai port server dev lokal yang terbuka di `127.0.0.1` untuk fitur Live Preview.
   - `GitHubClient`: Mengambil profil akun dan daftar repositori GitHub pengguna menggunakan Personal Access Token (`X-GitHub-Api-Version: 2022-11-28`).
   - `AppUpdater`: Memeriksa `update-manifest.json`, mengunduh APK pembaruan NextCraft Forge, dan mewajibkan verifikasi integritas SHA-256 sebelum menginstal update.

## Daftar File dalam Blok Ini
- `app/src/main/java/com/nextcraft/forge/runtime/AndroidProjectTemplateGenerator.kt` (299 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/AndroidBuildSupport.kt` (338 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/AndroidAppInstaller.kt` (273 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/AndroidAppInstallReceiver.kt` (51 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/RuntimeExecutionService.kt` (232 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/RuntimeSetupService.kt` (425 baris)
- `app/src/main/java/com/nextcraft/forge/network/ProviderApiClient.kt` (364 baris)
- `app/src/main/java/com/nextcraft/forge/network/LocalNetwork.kt` (108 baris)
- `app/src/main/java/com/nextcraft/forge/network/GitHubClient.kt` (229 baris)
- `app/src/main/java/com/nextcraft/forge/update/AppUpdater.kt` (214 baris)

---

## 1. `app/src/main/java/com/nextcraft/forge/runtime/AndroidProjectTemplateGenerator.kt` (299 baris)

```kotlin
package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.projectSlug
import java.io.File
import java.nio.file.Files

object AndroidProjectTemplateGenerator {
    fun packageName(name: String): String {
        val raw = projectSlug(name).replace('-', '_').take(40).ifBlank { "app" }
        val suffix = if (raw.first().isLetter()) raw else "app_$raw"
        return "com.nextcraft.$suffix"
    }

    fun generate(destination: File, appName: String, template: AndroidTemplate) {
        val parent = requireNotNull(destination.parentFile) { "Destination must have a parent directory" }
        parent.mkdirs()
        val staging = File(parent, ".${destination.name}.android-template")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare the Android project" }
        try {
            val pkg = packageName(appName)
            writeCommon(staging, appName, pkg, template)
            when (template) {
                AndroidTemplate.COMPOSE -> writeCompose(staging, pkg)
                AndroidTemplate.XML -> writeXml(staging, pkg)
            }
            if (destination.exists()) {
                check(destination.listFiles().isNullOrEmpty()) { "Project folder is not empty" }
                check(destination.delete()) { "Could not replace the empty project folder" }
            }
            check(staging.renameTo(destination)) { "Could not finish creating the Android project" }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun writeCommon(root: File, appName: String, namespace: String, template: AndroidTemplate) {
        val compose = template == AndroidTemplate.COMPOSE
        write(
            root,
            "settings.gradle.kts",
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
            rootProject.name = ${kotlinString(appName)}
            include(":app")
            """,
        )
        write(
            root,
            "build.gradle.kts",
            """
            plugins {
                id("com.android.application") version "8.11.0" apply false
                id("org.jetbrains.kotlin.android") version "1.9.22" apply false
            }
            """,
        )
        write(
            root,
            "gradle.properties",
            """
            android.useAndroidX=true
            kotlin.code.style=official
            android.nonTransitiveRClass=true
            """,
        )
        write(
            root,
            "app/build.gradle.kts",
            """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "$namespace"
                compileSdk = 36

                defaultConfig {
                    applicationId = "$namespace"
                    minSdk = 28
                    targetSdk = 36
                    versionCode = 1
                    versionName = "1.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions { jvmTarget = "17" }
                buildFeatures { compose = $compose }
                ${if (compose) "composeOptions { kotlinCompilerExtensionVersion = \"1.5.10\" }" else ""}
            }

            dependencies {
                implementation("androidx.core:core-ktx:1.8.0")
                ${if (compose) """
                implementation("androidx.activity:activity-compose:1.5.1")
                implementation("androidx.compose.ui:ui:1.3.0")
                implementation("androidx.compose.foundation:foundation:1.3.0")
                implementation("androidx.compose.material3:material3:1.0.0")
                """.trimIndent() else """
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("com.google.android.material:material:1.9.0")
                """.trimIndent()}
            }
            """,
        )
        write(
            root,
            ".gitignore",
            """
            .gradle/
            local.properties
            **/build/
            *.iml
            """,
        )
        val projectJson = """{"schemaVersion":1,"template":"${template.name}","packageName":"$namespace"}"""
        write(root, ".pocketdev/android-project.json", projectJson)
        write(root, ".nextcraft/android-project.json", projectJson)
        write(
            root,
            "app/src/main/res/values/strings.xml",
            """
            <resources><string name="app_name">${xmlEscape(appName)}</string></resources>
            """,
        )
    }

    private fun writeCompose(root: File, namespace: String) {
        val packagePath = namespace.replace('.', '/')
        write(
            root,
            "app/src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:theme="@style/Theme.PocketDevApp" android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """,
        )
        write(
            root,
            "app/src/main/res/values/themes.xml",
            """
            <resources>
                <style name="Theme.PocketDevApp" parent="android:style/Theme.Material.Light.NoActionBar">
                    <item name="android:fontFamily">sans</item>
                    <item name="android:colorAccent">#6750A4</item>
                    <item name="android:statusBarColor">#FFFBFE</item>
                    <item name="android:navigationBarColor">#FFFBFE</item>
                    <item name="android:windowLightStatusBar">true</item>
                    <item name="android:windowLightNavigationBar">true</item>
                </style>
            </resources>
            """,
        )
        write(
            root,
            "app/src/main/java/$packagePath/MainActivity.kt",
            """
            package $namespace

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { MaterialTheme { Welcome() } }
                }
            }

            @Composable
            private fun Welcome() {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("Built with NextCraft Forge")
                }
            }
            """,
        )
    }

    private fun writeXml(root: File, namespace: String) {
        val packagePath = namespace.replace('.', '/')
        write(
            root,
            "app/src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:theme="@style/Theme.PocketDevApp" android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """,
        )
        write(
            root,
            "app/src/main/res/values/themes.xml",
            """
            <resources>
                <style name="Theme.PocketDevApp" parent="Theme.MaterialComponents.DayNight.NoActionBar">
                    <item name="colorPrimary">#6750A4</item>
                    <item name="android:statusBarColor">?android:colorBackground</item>
                    <item name="android:navigationBarColor">?android:colorBackground</item>
                    <item name="android:windowLightStatusBar">true</item>
                    <item name="android:windowLightNavigationBar">true</item>
                </style>
            </resources>
            """,
        )
        write(
            root,
            "app/src/main/res/values-night/themes.xml",
            """
            <resources>
                <style name="Theme.PocketDevApp" parent="Theme.MaterialComponents.DayNight.NoActionBar">
                    <item name="colorPrimary">#D0BCFF</item>
                    <item name="android:statusBarColor">?android:colorBackground</item>
                    <item name="android:navigationBarColor">?android:colorBackground</item>
                    <item name="android:windowLightStatusBar">false</item>
                    <item name="android:windowLightNavigationBar">false</item>
                </style>
            </resources>
            """,
        )
        write(
            root,
            "app/src/main/res/layout/activity_main.xml",
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent" android:gravity="center" android:orientation="vertical">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Built with NextCraft Forge" android:textSize="22sp" />
            </LinearLayout>
            """,
        )
        write(
            root,
            "app/src/main/java/$packagePath/MainActivity.kt",
            """
            package $namespace

            import android.os.Bundle
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)
                }
            }
            """,
        )
    }

    private fun write(root: File, relative: String, content: String) {
        val rootPath = root.canonicalFile.toPath()
        val target = File(root, relative)
        target.parentFile?.mkdirs()
        require(!Files.isSymbolicLink(target.toPath())) { "Refusing to write template through symlink" }
        require(target.canonicalFile.toPath().startsWith(rootPath)) { "Template path escapes root" }
        target.writeText(content.trimIndent().trim() + "\n")
    }

    private fun kotlinString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")}\""

    private fun xmlEscape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
```

## 2. `app/src/main/java/com/nextcraft/forge/runtime/AndroidBuildSupport.kt` (338 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class AndroidBuildPhase { IDLE, PREPARING, BUILDING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
enum class AndroidAction { NONE, BUILD, FORCE_BUILD, INSTALL, RUN, BUILD_AND_RUN, CLEAN }
enum class AndroidBuildStage {
    IDLE, PREPARING, RESOLVING, COMPILING, RESOURCES, PACKAGING, FINDING_APK,
    INSTALLING, LAUNCHING, COMPLETE, FAILED, CANCELLING, CANCELLED,
}
enum class AndroidIssueSeverity { INFO, WARNING, ERROR }

data class AndroidBuildIssue(
    val severity: AndroidIssueSeverity = AndroidIssueSeverity.ERROR,
    val title: String,
    val detail: String,
    val suggestion: String? = null,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

data class AndroidApkInfo(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val builtAtMillis: Long,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val stale: Boolean = false,
    val installed: Boolean = false,
    val installedMatches: Boolean = false,
)

enum class AndroidHealthStatus { RUNNING, PASSED, WARNING, FAILED }
enum class AndroidHealthFix {
    NONE, INSTALL_TOOLS, OPEN_INSTALL_SETTINGS, OPEN_NETWORK_SETTINGS,
    OPEN_STORAGE_SETTINGS, CLEAN, COPY_LOGCAT_COMMAND, REFRESH,
}

data class AndroidHealthCheck(
    val id: String,
    val title: String,
    val detail: String,
    val status: AndroidHealthStatus,
    val fix: AndroidHealthFix = AndroidHealthFix.NONE,
)

enum class AndroidLogLevel { VERBOSE, DEBUG, INFO, WARNING, ERROR, FATAL }

data class AndroidLogLine(
    val id: Long,
    val text: String,
    val level: AndroidLogLevel,
    val filePath: String? = null,
    val line: Int? = null,
)

data class AndroidLogcatState(
    val available: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val message: String? = null,
    val packageName: String? = null,
    val lines: List<AndroidLogLine> = emptyList(),
    val minimumLevel: AndroidLogLevel = AndroidLogLevel.VERBOSE,
    val query: String = "",
)

data class AndroidBuildRecord(
    val phase: AndroidBuildPhase = AndroidBuildPhase.IDLE,
    val message: String? = null,
    val log: String = "",
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val apkPath: String? = null,
    val apkSizeBytes: Long? = null,
    val action: AndroidAction = AndroidAction.NONE,
    val stage: AndroidBuildStage = AndroidBuildStage.IDLE,
    val issues: List<AndroidBuildIssue> = emptyList(),
    val recentDurationsMillis: List<Long> = emptyList(),
)

private val SAFE_GRADLE_TASK_REGEX = Regex("^[A-Za-z0-9_.:-]+(\\s+[A-Za-z0-9_.:-]+)*$")

internal fun androidGradleCommand(root: File, task: String): String {
    val cleanTask = task.trim()
    require(cleanTask.isNotEmpty() && SAFE_GRADLE_TASK_REGEX.matches(cleanTask)) {
        "Unsafe Gradle task name: '$task'"
    }
    val launcher = File(root, "gradlew")
        .takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        ?.let { "bash ./gradlew" }
        ?: "gradle"
    return "$launcher -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 " +
        "--init-script /root/.gradle/init.d/pocketdev-android.gradle " +
        "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
        "$cleanTask --console=plain --stacktrace"
}

internal fun findAndroidProjectRoot(workspace: File): File? {
    if (!workspace.isDirectory) return null
    val workspacePath = runCatching { workspace.canonicalFile.toPath() }.getOrNull() ?: return null
    val settingsNames = setOf("settings.gradle", "settings.gradle.kts", "settings.gradle.dcl")
    return workspace.walkTopDown()
        .onEnter { dir ->
            dir == workspace || (
                !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                )
        }
        .maxDepth(4)
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name in settingsNames }
        .mapNotNull(File::getParentFile)
        .sortedBy { it.absolutePath.length }
        .firstOrNull { root ->
            val rootPath = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return@firstOrNull false
            root.walkTopDown()
                .onEnter { dir ->
                    dir == root || (
                        !Files.isSymbolicLink(dir.toPath()) &&
                            runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                        )
                }
                .maxDepth(5)
                .any {
                    it.isFile &&
                        !Files.isSymbolicLink(it.toPath()) &&
                        it.invariantSeparatorsPath.endsWith("src/main/AndroidManifest.xml")
                }
        }
}

internal fun findDebugApk(projectRoot: File): File? {
    if (!projectRoot.isDirectory) return null
    val rootPath = runCatching { projectRoot.canonicalFile.toPath() }.getOrNull() ?: return null
    val metadata = projectRoot.walkTopDown()
        .onEnter { dir ->
            dir == projectRoot || (
                !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                )
        }
        .maxDepth(8)
        .filter {
            it.isFile &&
                !Files.isSymbolicLink(it.toPath()) &&
                it.name == "output-metadata.json" &&
                "/outputs/apk/" in it.invariantSeparatorsPath
        }
        .maxByOrNull(File::lastModified)
    if (metadata != null) {
        runCatching {
            val json = JSONObject(metadata.readText())
            val elements = json.optJSONArray("elements") ?: return@runCatching null
            (0 until elements.length()).asSequence()
                .mapNotNull { elements.optJSONObject(it)?.optString("outputFile") }
                .filter { it.isNotBlank() && !it.contains('\u0000') }
                .map { File(metadata.parentFile, it) }
                .firstOrNull { candidate ->
                    candidate.isFile &&
                        !Files.isSymbolicLink(candidate.toPath()) &&
                        candidate.length() > 0L &&
                        runCatching { candidate.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                }
        }.getOrNull()?.let { return it }
    }
    return projectRoot.walkTopDown()
        .onEnter { dir ->
            dir == projectRoot || (
                !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                )
        }
        .maxDepth(10)
        .filter {
            it.isFile &&
                !Files.isSymbolicLink(it.toPath()) &&
                it.extension.equals("apk", true) &&
                "/outputs/apk/debug/" in it.invariantSeparatorsPath &&
                runCatching { it.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
        }
        .maxByOrNull(File::lastModified)
}

/** Returns the last debug APK only when every build input is older than it. */
internal fun findReusableDebugApk(projectRoot: File): File? {
    val apk = findDebugApk(projectRoot) ?: return null
    val rootPath = runCatching { projectRoot.canonicalFile.toPath() }.getOrNull() ?: return null
    val ignoredDirectories = setOf("build", ".gradle", ".idea", ".git")
    val buildInputExtensions = setOf(
        "kt", "java", "xml", "gradle", "kts", "properties", "toml", "pro", "json", "jar", "aar",
    )
    val changedAfterApk = projectRoot.walkTopDown()
        .onEnter { directory ->
            directory == projectRoot || (
                directory.name !in ignoredDirectories &&
                    !Files.isSymbolicLink(directory.toPath()) &&
                    runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                )
        }
        .filter { file ->
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) return@filter false
            val relative = file.relativeTo(projectRoot).invariantSeparatorsPath
            val isAndroidSource = relative.startsWith("src/") || "/src/" in relative
            isAndroidSource || file.extension.lowercase() in buildInputExtensions ||
                file.name in setOf("gradlew", "gradlew.bat")
        }
        .any { it.lastModified() > apk.lastModified() }
    return apk.takeUnless { changedAfterApk }
}

internal fun isDebugApkStale(projectRoot: File, apk: File): Boolean = findReusableDebugApk(projectRoot) != apk

internal fun inferAndroidBuildStage(output: String, current: AndroidBuildStage): AndroidBuildStage {
    val text = output.lowercase(Locale.US)
    return when {
        "packagedebug" in text || ("assembledebug" in text && "> task" in text) -> AndroidBuildStage.PACKAGING
        ("merge" in text && "resource" in text) || "processdebugresources" in text || "aapt2" in text -> AndroidBuildStage.RESOURCES
        "compiledebugkotlin" in text || "compiledebugjava" in text || "kotlinc" in text || "javac" in text -> AndroidBuildStage.COMPILING
        "download" in text || "resolve" in text || "dependency" in text -> AndroidBuildStage.RESOLVING
        else -> current
    }
}

internal fun parseAndroidBuildIssues(output: String, projectRoot: File, exitCode: Int): List<AndroidBuildIssue> {
    val issues = mutableListOf<AndroidBuildIssue>()
    val location = Regex("(?m)^(.+?\\.(?:kt|java|xml|gradle|kts)):(\\d+)(?::(\\d+))?[: ]+(?:error: )?(.+)$", RegexOption.IGNORE_CASE)
    val rootCanonical = runCatching { projectRoot.canonicalFile }.getOrDefault(projectRoot)
    location.findAll(output).take(12).forEach { match ->
        val rawPath = match.groupValues[1].trim()
            .replace(Regex("^(?:e:|w:|error:|warning:)\\s*", RegexOption.IGNORE_CASE), "")
            .removePrefix("file://")
            .trim()
        val file = File(rawPath)
        val relative = when {
            file.isAbsolute && runCatching { file.canonicalFile.toPath().startsWith(rootCanonical.toPath()) }.getOrDefault(false) ->
                file.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
            rawPath.startsWith("/workspace/") -> rawPath.substringAfter("/workspace/").substringAfter('/', "")
            else -> rawPath.takeIf {
                val candidate = File(projectRoot, it)
                candidate.isFile && runCatching { candidate.canonicalFile.toPath().startsWith(rootCanonical.toPath()) }.getOrDefault(false)
            }
        }
        issues += AndroidBuildIssue(
            title = "Source error",
            detail = match.groupValues[4].trim().take(400),
            suggestion = "Open the file and correct the reported line.",
            filePath = relative,
            line = match.groupValues[2].toIntOrNull(),
            column = match.groupValues[3].toIntOrNull(),
        )
    }
    if (issues.isNotEmpty()) return issues.distinctBy { Triple(it.filePath, it.line, it.detail) }
    val summary = diagnoseAndroidBuildFailure(output, exitCode)
    val lower = output.lowercase(Locale.US)
    val suggestion = when {
        "no space left" in lower -> "Free device storage, then retry the build."
        "could not resolve" in lower || "could not get resource" in lower -> "Check the network connection or use a cached dependency version."
        "manifest merger failed" in lower -> "Review duplicate or conflicting manifest declarations."
        "aapt2" in lower || "resource linking failed" in lower -> "Review resource names and XML diagnostics in the build output."
        "outofmemory" in lower || "heap space" in lower -> "Close other apps and retry."
        else -> "Open the build details for the complete Gradle output."
    }
    return listOf(AndroidBuildIssue(title = "Build failed", detail = summary, suggestion = suggestion))
}

internal fun diagnoseAndroidBuildFailure(output: String, exitCode: Int): String {
    val text = output.lowercase(Locale.US)
    return when {
        "no space left on device" in text -> "The device does not have enough free storage for this build."
        "outofmemoryerror" in text || "java heap space" in text -> "Gradle ran out of memory. Close other apps and retry."
        "could not resolve" in text || "could not get resource" in text -> "A project dependency is unavailable. Connect to the internet or use an offline-cached version."
        "manifest merger failed" in text -> "Android manifest merge failed. Open the build log for the conflicting declaration."
        "aapt2" in text || "android resource linking failed" in text -> "Android resources could not be compiled. Open the build log for the file and line."
        "compilation error" in text || "compilation failed" in text || "unresolved reference" in text -> "Kotlin or Java compilation failed. Open the build log for source errors."
        "minimum supported gradle version" in text || "maximum supported gradle" in text -> "This project's Gradle and Android plugin versions are incompatible."
        else -> "Gradle build failed (exit code $exitCode)."
    }
}

/**
 * Checks whether the host has either `READ_LOGS` permission or working Root (`su`) access
 * for streaming package-scoped `logcat`.
 */
internal fun hasAndroidLogcatAccess(context: Context): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
        return true
    }
    return runCatching {
        val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val finished = process.waitFor(1_500L, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrDefault(false)
}

/**
 * Starts a UID-scoped `logcat` process for [packageName] using Root (`su -c`) when
 * `READ_LOGS` is not held directly by the app.
 */
internal fun startPackageLogcatProcess(
    context: Context,
    packageName: String,
    sinceMillis: Long? = null,
): Process {
    val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
    require(uid > 0) { "Invalid application UID for $packageName" }
    val since = sinceMillis?.takeIf { it > 0L }?.let {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(it))
    } ?: "1"
    val useRoot = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED
    return if (useRoot) {
        val safeSince = since.replace("'", "")
        val logCommand = "/system/bin/logcat --uid=$uid -v threadtime -T '$safeSince'"
        ProcessBuilder("su", "-c", logCommand).redirectErrorStream(true).start()
    } else {
        ProcessBuilder("/system/bin/logcat", "--uid=$uid", "-v", "threadtime", "-T", since)
            .redirectErrorStream(true)
            .start()
    }
}
```

## 3. `app/src/main/java/com/nextcraft/forge/runtime/AndroidAppInstaller.kt` (273 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AndroidInstallEvent(
    val operationId: String,
    val packageName: String?,
    val success: Boolean,
    val launched: Boolean,
    val message: String? = null,
)

/**
 * Installs a locally-built APK on the device.
 *
 * On rooted devices (NextCraft Forge's primary target), attempts a 1-tap silent
 * `su -c "pm install -r -d -S <size>"` stream install first (excluding self-updates
 * of NextCraft Forge itself), and automatically falls back to Android's standard
 * [PackageInstaller.Session] + [AndroidAppInstallReceiver] flow if root install fails.
 */
object AndroidAppInstaller {
    private val _events = MutableSharedFlow<AndroidInstallEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun openIfAlreadyInstalled(context: Context, apk: File): Boolean {
        if (!apk.isFile || Files.isSymbolicLink(apk.toPath())) return false
        val packageName = archivePackageName(context, apk) ?: return false
        if (installedFingerprint(context, packageName) != fingerprint(apk)) return false
        return launch(context, packageName)
    }

    fun install(
        context: Context,
        apk: File,
        launchAfterInstall: Boolean = true,
        operationId: String = UUID.randomUUID().toString(),
    ): String {
        require(
            apk.isFile &&
                !Files.isSymbolicLink(apk.toPath()) &&
                apk.extension.equals("apk", ignoreCase = true) &&
                apk.length() > 0L,
        ) {
            "APK file '${apk.name}' is missing or invalid."
        }
        val appContext = context.applicationContext ?: context
        val pkgName = archivePackageName(appContext, apk)
        val isSelfUpdate = pkgName == appContext.packageName

        Thread({
            val apkHash = runCatching { fingerprint(apk) }.getOrNull()
            if (!isSelfUpdate && pkgName != null && apkHash != null && installSilentlyViaRoot(apk)) {
                rememberInstalled(appContext, pkgName, apkHash)
                val launched = launchAfterInstall && launch(appContext, pkgName)
                publish(AndroidInstallEvent(operationId, pkgName, success = true, launched = launched))
                return@Thread
            }
            runCatching {
                installViaSessionOrIntent(appContext, apk, pkgName, apkHash ?: fingerprint(apk), launchAfterInstall, operationId)
            }.onFailure { error ->
                publish(
                    AndroidInstallEvent(
                        operationId = operationId,
                        packageName = pkgName,
                        success = false,
                        launched = false,
                        message = error.message ?: "APK installation failed.",
                    ),
                )
            }
        }, "forge-apk-installer").apply { isDaemon = true }.start()
        return operationId
    }

    /**
     * Streams [apk] over stdin to `su -c "pm install -r -d -S <bytes>"` so `system_server`
     * never needs direct SELinux filesystem access to the app's private data directory.
     */
    internal fun installSilentlyViaRoot(apk: File): Boolean = runCatching {
        val sizeBytes = apk.length()
        if (sizeBytes <= 0L) return@runCatching false
        val proc = ProcessBuilder("su", "-c", "pm install -r -d -S $sizeBytes")
            .redirectErrorStream(true)
            .start()
        try {
            apk.inputStream().buffered().use { input ->
                proc.outputStream.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            if (!proc.waitFor(60L, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@runCatching false
            }
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.exitValue() == 0 && out.contains("Success", ignoreCase = true)
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }.getOrDefault(false)

    private fun installViaSessionOrIntent(
        context: Context,
        apk: File,
        packageName: String?,
        apkHash: String,
        launchAfterInstall: Boolean,
        operationId: String,
    ) {
        if (isMiuiDevice()) {
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            }.getOrElse {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            }
            @Suppress("DEPRECATION")
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            publish(
                AndroidInstallEvent(
                    operationId = operationId,
                    packageName = packageName,
                    success = true,
                    launched = false,
                    message = "Opened system package installer.",
                ),
            )
            return
        }
        val installer = context.packageManager.packageInstaller
        val isUpdate = packageName?.let { isInstalled(context, it) } == true
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setSize(apk.length())
                setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setOriginatingUid(Process.myUid())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(
                        if (isUpdate) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                }
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, AndroidAppInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_APK_FINGERPRINT, apkHash)
                    .putExtra(EXTRA_OPERATION_ID, operationId)
                    .putExtra(EXTRA_LAUNCH_AFTER_INSTALL, launchAfterInstall)
                val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    const val ACTION_INSTALL_RESULT = "com.nextcraft.forge.action.APK_INSTALL_RESULT"
    const val EXTRA_APK_FINGERPRINT = "com.nextcraft.forge.extra.APK_FINGERPRINT"
    const val EXTRA_OPERATION_ID = "com.nextcraft.forge.extra.OPERATION_ID"
    const val EXTRA_LAUNCH_AFTER_INSTALL = "com.nextcraft.forge.extra.LAUNCH_AFTER_INSTALL"

    internal fun publish(event: AndroidInstallEvent) {
        _events.tryEmit(event)
    }

    fun packageName(context: Context, apk: File): String? = archivePackageName(context, apk)

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun installedMatches(context: Context, apk: File): Boolean {
        if (!apk.isFile || Files.isSymbolicLink(apk.toPath())) return false
        val packageName = archivePackageName(context, apk) ?: return false
        return installedFingerprint(context, packageName) == fingerprint(apk)
    }

    fun apkFingerprint(apk: File): String = fingerprint(apk)

    internal fun rememberInstalled(context: Context, packageName: String, fingerprint: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(packageName, fingerprint)
            .apply()
    }

    internal fun launch(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getLaunchIntentSenderForPackage(packageName).sendIntent(
                context, 0, null, null, null,
            )
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun archivePackageName(context: Context, apk: File): String? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName

    private fun installedFingerprint(context: Context, packageName: String): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(packageName, null)

    private fun fingerprint(apk: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private const val PREFERENCES = "installed_android_apks"

    private fun isMiuiDevice(): Boolean = Build.MANUFACTURER.lowercase(Locale.US) in
        setOf("xiaomi", "redmi", "poco")
}
```

## 4. `app/src/main/java/com/nextcraft/forge/runtime/AndroidAppInstallReceiver.kt` (51 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class AndroidAppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAppInstaller.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val operationId = intent.getStringExtra(AndroidAppInstaller.EXTRA_OPERATION_ID).orEmpty()
        val launchAfterInstall = intent.getBooleanExtra(AndroidAppInstaller.EXTRA_LAUNCH_AFTER_INSTALL, true)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val userAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (userAction != null) {
                // Prevent untrusted broadcast extras from targeting internal app components
                if (userAction.`package` == context.packageName || userAction.component?.packageName == context.packageName) {
                    return
                }
                userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(userAction) }
            }
            return
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?.takeIf(String::isNotBlank)
                ?: "APK installation failed."
            AndroidAppInstaller.publish(AndroidInstallEvent(operationId, null, false, false, message))
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: return
        intent.getStringExtra(AndroidAppInstaller.EXTRA_APK_FINGERPRINT)?.let { fingerprint ->
            AndroidAppInstaller.rememberInstalled(context, packageName, fingerprint)
        }
        val launched = launchAfterInstall && AndroidAppInstaller.launch(context, packageName)
        AndroidAppInstaller.publish(AndroidInstallEvent(operationId, packageName, true, launched))
        if (launchAfterInstall && !launched) {
            Toast.makeText(context, "Installed $packageName. Open it from the launcher.", Toast.LENGTH_LONG).show()
        }
    }
}
```

## 5. `app/src/main/java/com/nextcraft/forge/runtime/RuntimeExecutionService.kt` (232 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class RuntimeExecutionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var projectNameOverride: String? = null
    private var notificationTitleOverride: String? = null
    private val projectName: String
        get() = projectNameOverride ?: "Forge Workspace"
    private val notificationTitle: String
        get() = notificationTitleOverride ?: "Forge Runtime Active"
    private var canStop: Boolean = true
    private var taskRunning: Boolean = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val incomingProjectName = intent?.getStringExtra(EXTRA_PROJECT_NAME)
            ?: intent?.getStringExtra(LEGACY_EXTRA_PROJECT_NAME)
        val incomingTitle = intent?.getStringExtra(EXTRA_TITLE)
            ?: intent?.getStringExtra(LEGACY_EXTRA_TITLE)
        val incomingDetail = intent?.getStringExtra(EXTRA_DETAIL)
            ?: intent?.getStringExtra(LEGACY_EXTRA_DETAIL)

        incomingProjectName?.takeIf(String::isNotBlank)?.let { projectNameOverride = it }

        when (action) {
            ACTION_STOP, LEGACY_ACTION_STOP -> {
                RuntimeTaskController.requestStop()
                getSystemService(NotificationManager::class.java)?.notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification("Stopping active task…", includeStop = false),
                )
            }
            ACTION_PROGRESS, LEGACY_ACTION_PROGRESS -> {
                if (!taskRunning) return START_NOT_STICKY
                incomingTitle?.takeIf(String::isNotBlank)?.let { notificationTitleOverride = it }
                if (intent?.hasExtra(EXTRA_CAN_STOP) == true) {
                    canStop = intent.getBooleanExtra(EXTRA_CAN_STOP, true)
                } else if (intent?.hasExtra(LEGACY_EXTRA_CAN_STOP) == true) {
                    canStop = intent.getBooleanExtra(LEGACY_EXTRA_CAN_STOP, true)
                }
                val detail = incomingDetail?.takeIf(String::isNotBlank)
                    ?: "Working in $projectName"
                getSystemService(NotificationManager::class.java)?.notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(detail, includeStop = canStop),
                )
            }
            ACTION_COMPLETE, LEGACY_ACTION_COMPLETE -> finishTask(
                title = "Task completed",
                detail = incomingDetail?.takeIf(String::isNotBlank) ?: "Finished task in $projectName",
                failed = false,
            )
            ACTION_FAILED, LEGACY_ACTION_FAILED -> finishTask(
                title = "Task needs attention",
                detail = incomingDetail?.takeIf(String::isNotBlank) ?: "Could not finish the active task.",
                failed = true,
            )
            ACTION_CANCELLED, LEGACY_ACTION_CANCELLED -> {
                taskRunning = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Reset per-task overrides on fresh start so previous non-stoppable or custom-titled
                // tasks never bleed into subsequent coding sessions.
                notificationTitleOverride = incomingTitle?.takeIf(String::isNotBlank)
                canStop = when {
                    intent?.hasExtra(EXTRA_CAN_STOP) == true -> intent.getBooleanExtra(EXTRA_CAN_STOP, true)
                    intent?.hasExtra(LEGACY_EXTRA_CAN_STOP) == true -> intent.getBooleanExtra(LEGACY_EXTRA_CAN_STOP, true)
                    else -> true
                }
                taskRunning = true
                val detail = incomingDetail?.takeIf(String::isNotBlank)
                    ?: "Working in $projectName"
                startForeground(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(detail, includeStop = canStop),
                )
                acquireWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    private fun runningNotification(detail: String, includeStop: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(notificationTitle)
            .setContentText(detail)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (includeStop) {
            val stopIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, RuntimeExecutionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop task", stopIntent)
        }
        return builder.build()
    }

    private fun finishTask(title: String, detail: String, failed: Boolean) {
        taskRunning = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun smallIconRes(): Int =
        applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync

    private fun openAppIntent(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: runCatching { Intent(this, Class.forName("$packageName.MainActivity")) }.getOrNull()
            ?: runCatching { Intent(this, Class.forName("com.nextcraft.forge.MainActivity")) }.getOrNull()
            ?: Intent().setPackage(packageName)
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.nextcraft.forge:active-coding-task")
            ?.apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = RuntimeExecutionServiceContract.ACTION_START
        const val ACTION_STOP = "com.nextcraft.forge.runtime.action.STOP"
        const val ACTION_PROGRESS = RuntimeExecutionServiceContract.ACTION_PROGRESS
        const val ACTION_COMPLETE = RuntimeExecutionServiceContract.ACTION_COMPLETE
        const val ACTION_FAILED = RuntimeExecutionServiceContract.ACTION_FAILED
        const val ACTION_CANCELLED = RuntimeExecutionServiceContract.ACTION_CANCELLED

        const val EXTRA_PROJECT_NAME = RuntimeExecutionServiceContract.EXTRA_PROJECT_NAME
        const val EXTRA_DETAIL = RuntimeExecutionServiceContract.EXTRA_DETAIL
        const val EXTRA_TITLE = "com.nextcraft.forge.runtime.extra.TITLE"
        const val EXTRA_CAN_STOP = "com.nextcraft.forge.runtime.extra.CAN_STOP"

        private const val LEGACY_ACTION_STOP = "com.nextcraft.forge.STOP_RUNTIME"
        private const val LEGACY_ACTION_PROGRESS = "com.nextcraft.forge.PROGRESS_RUNTIME"
        private const val LEGACY_ACTION_COMPLETE = "com.nextcraft.forge.COMPLETE_RUNTIME"
        private const val LEGACY_ACTION_FAILED = "com.nextcraft.forge.FAIL_RUNTIME"
        private const val LEGACY_ACTION_CANCELLED = "com.nextcraft.forge.CANCEL_RUNTIME"
        private const val LEGACY_EXTRA_PROJECT_NAME = "project_name"
        private const val LEGACY_EXTRA_DETAIL = "detail"
        private const val LEGACY_EXTRA_TITLE = "title"
        private const val LEGACY_EXTRA_CAN_STOP = "can_stop"

        private const val RUNNING_CHANNEL_ID = "runtime"
        private const val RESULT_CHANNEL_ID = "task-results"
        private const val RUNNING_NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        private const val MAX_WAKE_LOCK_MS = 90 * 60 * 1_000L

        fun ensureNotificationChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    RUNNING_CHANNEL_ID,
                    "Active coding tasks",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows live progress while an agent or build task is running."
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    "Task results",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Notifies when background coding or build tasks complete."
                },
            )
        }
    }
}
```

## 6. `app/src/main/java/com/nextcraft/forge/runtime/RuntimeSetupService.kt` (425 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import java.io.File
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeSetupStatus { IDLE, RUNNING, COMPLETE, ERROR, CANCELLED }

data class RuntimeSetupSnapshot(
    val status: RuntimeSetupStatus = RuntimeSetupStatus.IDLE,
    val message: String = "Preparing your private coding workspace",
    val progress: Float = 0f,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val indeterminate: Boolean = false,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val offline: Boolean = false,
)

object RuntimeSetupController {
    private val mutableSnapshot = MutableStateFlow(RuntimeSetupSnapshot())
    val snapshot: StateFlow<RuntimeSetupSnapshot> = mutableSnapshot.asStateFlow()

    @Synchronized
    fun restore(context: Context) {
        val file = stateFile(context)
        if (!file.isFile) return
        runCatching {
            val json = JSONObject(file.readText())
            val logsJson = json.optJSONArray("logs") ?: JSONArray()
            val logs = (0 until logsJson.length()).map { logsJson.optString(it) }
            val rawProgress = json.optDouble("progress", 0.0).toFloat()
            val safeProgress = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
            mutableSnapshot.value = RuntimeSetupSnapshot(
                status = runCatching { RuntimeSetupStatus.valueOf(json.optString("status")) }
                    .getOrDefault(RuntimeSetupStatus.IDLE),
                message = json.optString("message", DEFAULT_PREPARING_MESSAGE).ifBlank { DEFAULT_PREPARING_MESSAGE },
                progress = safeProgress,
                downloadedBytes = json.optLongOrNull("downloadedBytes"),
                totalBytes = json.optLongOrNull("totalBytes"),
                indeterminate = json.optBoolean("indeterminate"),
                logs = logs.takeLast(MAX_LOG_LINES),
                errorMessage = json.optString("errorMessage").takeIf(String::isNotBlank),
                offline = json.optBoolean("offline"),
            )
        }
    }

    @Synchronized
    fun begin(context: Context) {
        val previous = mutableSnapshot.value.logs
        val logs = (previous + "— Resuming NextCraft Forge setup —").takeLast(MAX_LOG_LINES)
        set(
            context,
            RuntimeSetupSnapshot(
                status = RuntimeSetupStatus.RUNNING,
                message = DEFAULT_PREPARING_MESSAGE,
                progress = 0.01f,
                logs = logs,
            ),
        )
    }

    @Synchronized
    fun update(context: Context, event: RuntimeInstallProgress) {
        val current = mutableSnapshot.value
        val line = event.terminalLine ?: when {
            event.downloadedBytes != null && event.totalBytes != null ->
                "Downloading: %.1f / %.1f MB".format(event.downloadedBytes / MB, event.totalBytes / MB)
            event.event == RuntimeInstallEvent.STAGE -> "• ${event.message}"
            else -> null
        }
        val sanitizedLine = line?.takeIf(String::isNotBlank)?.let(::sanitize)
        val isDownloadUpdate = event.downloadedBytes != null && event.totalBytes != null
        val logs = when {
            sanitizedLine == null -> current.logs
            isDownloadUpdate && current.logs.lastOrNull()?.startsWith(DOWNLOAD_PREFIX) == true ->
                (current.logs.dropLast(1) + sanitizedLine).takeLast(MAX_LOG_LINES)
            else -> (current.logs + sanitizedLine).takeLast(MAX_LOG_LINES)
        }
        if (!isDownloadUpdate || current.logs.lastOrNull()?.startsWith(DOWNLOAD_PREFIX) != true) {
            appendLog(context, sanitizedLine)
        }
        val safeFraction = if (event.fraction.isFinite()) event.fraction.coerceIn(0f, 1f) else current.progress
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.RUNNING,
                message = when (event.event) {
                    RuntimeInstallEvent.STAGE, RuntimeInstallEvent.DOWNLOAD -> event.message
                    else -> current.message
                },
                progress = maxOf(current.progress, safeFraction),
                downloadedBytes = event.downloadedBytes,
                totalBytes = event.totalBytes,
                indeterminate = event.indeterminate,
                logs = logs,
                errorMessage = null,
                offline = false,
            ),
        )
    }

    @Synchronized
    fun complete(context: Context) {
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.COMPLETE,
                message = "Linux chroot runtime is ready",
                progress = 1f,
                indeterminate = false,
                downloadedBytes = null,
                totalBytes = null,
                logs = (current.logs + "✓ Setup completed successfully").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    @Synchronized
    fun fail(context: Context, error: Throwable) {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        val offline = causes.any {
            it is UnknownHostException ||
                it.message.orEmpty().contains("unable to resolve host", true) ||
                it.message.orEmpty().contains("no address associated with hostname", true)
        }
        val interruptedDpkg = causes.any {
            it.message.orEmpty().contains("dpkg was interrupted", true) ||
                it.message.orEmpty().contains("dpkg --configure -a", true)
        }
        val friendly = when {
            offline -> "No internet connection. Connect to Wi-Fi or mobile data and retry."
            interruptedDpkg -> "Package installation was interrupted. Retry setup to repair automatically."
            else -> error.message.orEmpty().lineSequence().lastOrNull { it.isNotBlank() }
                ?.take(220)
                ?: "Runtime setup could not complete."
        }
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.ERROR,
                errorMessage = friendly,
                offline = offline,
                indeterminate = false,
                logs = (current.logs + "✕ $friendly").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    @Synchronized
    fun cancelled(context: Context) {
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.CANCELLED,
                message = "Runtime setup paused",
                indeterminate = false,
                logs = (current.logs + "• Setup paused safely").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    fun fullLog(context: Context): String = logFile(context).readTailText(MAX_LOG_BYTES.toInt())

    private fun set(context: Context, value: RuntimeSetupSnapshot) {
        mutableSnapshot.value = value
        persist(context, value)
    }

    private fun persist(context: Context, value: RuntimeSetupSnapshot) {
        runCatching {
            val safeProgress = if (value.progress.isFinite()) value.progress.coerceIn(0f, 1f) else 0f
            val json = JSONObject()
                .put("status", value.status.name)
                .put("message", value.message)
                .put("progress", safeProgress.toDouble())
                .put("indeterminate", value.indeterminate)
                .put("offline", value.offline)
                .put("logs", JSONArray(value.logs))
            value.downloadedBytes?.let { json.put("downloadedBytes", it) }
            value.totalBytes?.let { json.put("totalBytes", it) }
            value.errorMessage?.let { json.put("errorMessage", it) }
            val target = stateFile(context)
            val staged = File(target.parentFile, "${target.name}.tmp")
            staged.writeText(json.toString())
            if (!staged.renameTo(target)) {
                target.delete()
                if (!staged.renameTo(target)) {
                    android.util.Log.e("SetupController", "Failed to persist setup state — data may be lost")
                }
            }
        }
    }

    private fun appendLog(context: Context, line: String?) {
        if (line.isNullOrBlank()) return
        runCatching {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            file.appendText(sanitize(line) + "\n")
            if (file.length() > MAX_LOG_BYTES) {
                val tail = file.readTailText(MAX_LOG_BYTES.toInt())
                file.writeText(tail.substringAfter('\n', tail))
            }
        }
    }

    private fun sanitize(line: String): String = line.filter { it == '\t' || it.code >= 32 }.take(500)
    private fun stateFile(context: Context) = File(context.filesDir, "setup/runtime-setup-state.json").apply { parentFile?.mkdirs() }
    private fun logFile(context: Context) = File(context.filesDir, "setup/runtime-setup.log")
    private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null

    private const val DEFAULT_PREPARING_MESSAGE = "Preparing your private coding workspace"
    private const val MAX_LOG_LINES = 400
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val MB = 1_048_576.0
    private const val DOWNLOAD_PREFIX = "Downloading:"
}

class RuntimeSetupService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var installJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        RuntimeSetupController.restore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            installJob?.cancel(CancellationException("Stopped by user"))
            RuntimeSetupController.cancelled(this)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, setupNotification(RuntimeSetupController.snapshot.value))
        acquireWakeLock()
        if (installJob?.isActive != true) {
            val stacks = intent?.getStringExtra(EXTRA_STACKS).orEmpty().split(',')
                .mapNotNull { name -> runCatching { DevStack.valueOf(name.trim()) }.getOrNull() }
                .toSet()
            val agent = AgentKind.fromStored(intent?.getStringExtra(EXTRA_AGENT)?.trim())
            RuntimeSetupController.begin(this)
            installJob = scope.launch {
                try {
                    RuntimeInstaller(this@RuntimeSetupService).ensureInstalled(stacks, agent) { progress ->
                        RuntimeSetupController.update(this@RuntimeSetupService, progress)
                        updateNotification(progress.event == RuntimeInstallEvent.COMMAND_COMPLETED)
                    }
                    AppPreferences(this@RuntimeSetupService).runtimeSetupComplete = true
                    RuntimeSetupController.complete(this@RuntimeSetupService)
                    showFinishedNotification(success = true)
                } catch (_: CancellationException) {
                    RuntimeSetupController.cancelled(this@RuntimeSetupService)
                } catch (error: Throwable) {
                    RuntimeSetupController.fail(this@RuntimeSetupService, error)
                    showFinishedNotification(success = false)
                } finally {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, setupNotification(RuntimeSetupController.snapshot.value))
    }

    private fun setupNotification(state: RuntimeSetupSnapshot): Notification {
        val latest = state.logs.lastOrNull().orEmpty().take(180)
        val bodyText = latest.ifBlank { state.message }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle("Setting up Linux chroot runtime")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (state.indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, (state.progress * 100).toInt().coerceIn(0, 100), false)
        }
        builder.addAction(
            0,
            "Pause",
            PendingIntent.getService(
                this,
                102,
                Intent(this, RuntimeSetupService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return builder.build()
    }

    private fun showFinishedNotification(success: Boolean) {
        val state = RuntimeSetupController.snapshot.value
        val title = if (success) "Linux chroot runtime is ready" else "Runtime setup needs attention"
        val detail = if (success) {
            "Your Ubuntu ARM64 chroot environment and coding tools are ready."
        } else {
            state.errorMessage ?: "Runtime setup encountered an error."
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun smallIconRes(): Int =
        applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync

    private fun openAppIntent(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: runCatching { Intent(this, Class.forName("$packageName.MainActivity")) }.getOrNull()
            ?: runCatching { Intent(this, Class.forName("com.nextcraft.forge.MainActivity")) }.getOrNull()
            ?: Intent().setPackage(packageName)
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            101,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.nextcraft.forge:runtime-setup")
            ?.apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.nextcraft.forge.START_SETUP"
        const val ACTION_STOP = "com.nextcraft.forge.STOP_SETUP"
        const val EXTRA_STACKS = "selected_stacks"
        const val EXTRA_AGENT = "selected_agent"
        private const val CHANNEL_ID = "runtime-setup"
        private const val NOTIFICATION_ID = 51
        private const val RESULT_NOTIFICATION_ID = 52
        private const val NOTIFICATION_THROTTLE_MS = 750L
        private const val MAX_WAKE_LOCK_MS = 45 * 60 * 1_000L

        fun ensureNotificationChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Runtime setup",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows download and extraction progress during Linux chroot setup."
                },
            )
        }
    }
}
```

## 7. `app/src/main/java/com/nextcraft/forge/network/ProviderApiClient.kt` (364 baris)

```kotlin
package com.nextcraft.forge.network

import android.content.Context
import com.nextcraft.forge.model.ProviderProtocol
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DiscoveredModel(
    val id: String,
    val displayName: String = id,
    val isFree: Boolean = false,
)

sealed interface ModelDiscoveryResult {
    data class Success(val models: List<DiscoveredModel>, val endpoint: String) : ModelDiscoveryResult
    data class Failure(val message: String, val providerMessage: String? = null) : ModelDiscoveryResult
}

sealed interface ConnectionValidation {
    data class Success(val message: String) : ConnectionValidation
    data class Failure(
        val message: String,
        val providerMessage: String? = null,
        val label: String = "Failed",
    ) : ConnectionValidation
}

class ProviderApiClient(@Suppress("UNUSED_PARAMETER") private val context: Context? = null) {

    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure("Enter a base URL first.")
        }

        var authError = false
        var lastMessage = "This provider did not expose a model list. You can enter a custom model name."
        var lastProviderMessage: String? = null
        for (endpoint in modelEndpoints(baseUrl, protocol)) {
            // OpenRouter's complete catalog is public. Fetch it anonymously even when
            // OpenRouter is configured through Custom API so an account-scoped key does
            // not reduce discovery to the models allowed by that key's preferences.
            val discoveryKey = if (isOpenRouterCatalogEndpoint(endpoint)) "" else apiKey
            val response = request(endpoint, "GET", discoveryKey, protocol = protocol)
            when {
                response.code == 401 || response.code == 403 -> {
                    authError = true
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.code in 200..299 -> {
                    val models = ModelResponseParser.parse(response.body)
                    if (models.isNotEmpty()) return@withContext ModelDiscoveryResult.Success(models, endpoint)
                    lastMessage = "The provider replied, but its model list was empty or unsupported."
                }
                response.code > 0 && response.code != 404 -> {
                    lastMessage = friendlyHttpError(response.code)
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.error != null -> lastMessage = response.error
            }
        }
        ModelDiscoveryResult.Failure(
            if (authError) {
                "Check the saved API key, then try refreshing again."
            } else {
                lastMessage
            },
            lastProviderMessage,
        )
    }

    suspend fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        @Suppress("UNUSED_PARAMETER") discoveredModels: List<DiscoveredModel> = emptyList(),
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || model.isBlank()) {
            return@withContext ConnectionValidation.Failure("Base URL, model, and API key are required.")
        }
        val endpoint = messagesEndpoint(baseUrl, protocol)
        val body = validationBody(model, protocol, openRouterProviderOrder, openRouterAllowFallbacks)
        val response = request(
            endpoint = endpoint,
            method = "POST",
            apiKey = apiKey,
            body = body,
            protocol = protocol,
            connectTimeoutMs = 12_000,
            readTimeoutMs = 45_000,
        )
        when {
            response.code in 200..299 -> ConnectionValidation.Success("Connection verified.")
            response.code == 401 || response.code == 403 -> ConnectionValidation.Failure(
                "Check this API key or select another saved key.",
                providerErrorMessage(response.body),
                "Rejected",
            )
            response.code == 404 -> ConnectionValidation.Failure(
                "Check the Base URL and selected gateway protocol.",
                providerErrorMessage(response.body),
                "Endpoint error",
            )
            response.code == 400 && response.body.contains("model", ignoreCase = true) ->
                ConnectionValidation.Failure(
                    "Refresh the model list or select a different model.",
                    providerErrorMessage(response.body),
                    "Model error",
                )
            response.code == 429 -> ConnectionValidation.Failure(
                "Wait a moment, then retry or use another API key.",
                providerErrorMessage(response.body),
                "Rate limited",
            )
            response.code in 500..599 -> ConnectionValidation.Failure(
                "The provider is temporarily unavailable. Try again shortly.",
                providerErrorMessage(response.body),
                "Provider error",
            )
            response.code > 0 -> ConnectionValidation.Failure(
                "Review the model, protocol, and endpoint settings.",
                providerErrorMessage(response.body),
                "Request failed",
            )
            response.error?.contains("timeout", ignoreCase = true) == true ||
                response.error?.contains("timed out", ignoreCase = true) == true ->
                ConnectionValidation.Failure(
                    "Check your connection and try again.",
                    response.error,
                    "Timed out",
                )
            else -> ConnectionValidation.Failure(
                "Check your internet connection and provider settings.",
                response.error,
                "Network error",
            )
        }
    }

    private fun request(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String? = null,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResult {
        if (!LocalNetwork.isPlainHttpAllowed(endpoint)) {
            return HttpResult(
                0,
                "",
                "Plain http:// is only allowed for local network addresses. Use https:// for this server.",
            )
        }
        return runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                if (endpoint.startsWith("https://opencode.ai/zen/")) {
                    setRequestProperty("User-Agent", "opencode/1.18.20")
                    setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
                }
                if (
                    apiKey.isNotBlank() &&
                    protocol != ProviderProtocol.OPENROUTER &&
                    protocol != ProviderProtocol.OPENAI_CHAT &&
                    protocol != ProviderProtocol.OPENAI_RESPONSES
                ) {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                if (body != null) doOutput = true
            }
            try {
                if (body != null) {
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    buildString {
                        val buffer = CharArray(8 * 1024)
                        while (length < MAX_PROVIDER_RESPONSE_CHARS) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_PROVIDER_RESPONSE_CHARS - length))
                            if (count <= 0) break
                            append(buffer, 0, count)
                        }
                    }
                }.orEmpty()
                HttpResult(code, responseBody)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { HttpResult(0, "", it.message ?: "Network connection failed") }
    }

    private fun modelEndpoints(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        val withoutV1 = base.removeSuffix("/v1")
        val withoutAnthropic = base.removeSuffix("/anthropic")
        val candidates = when (protocol) {
            ProviderProtocol.OPENROUTER -> listOf("$withoutV1/v1/models", "$base/models")
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> listOf("$base/models", "$withoutV1/v1/models")
            else -> listOf("$withoutV1/v1/models", "$base/models", "$withoutAnthropic/models", "$withoutAnthropic/v1/models")
        }
        return candidates.distinct()
    }

    private fun messagesEndpoint(baseUrl: String, protocol: ProviderProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        val withoutV1 = base.removeSuffix("/v1")
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> "$withoutV1/v1/messages"
            ProviderProtocol.OPENAI_CHAT -> "${base.removeSuffix("/chat/completions")}/chat/completions"
            ProviderProtocol.OPENAI_RESPONSES -> "${base.removeSuffix("/responses")}/responses"
            else -> "$withoutV1/v1/messages"
        }
    }

    private fun isOpenRouterCatalogEndpoint(endpoint: String): Boolean = runCatching {
        val url = URL(endpoint)
        url.host.equals("openrouter.ai", ignoreCase = true) &&
            url.path.trimEnd('/').endsWith("/models")
    }.getOrDefault(false)

    internal fun validationBody(
        model: String,
        protocol: ProviderProtocol,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", "Hello, reply with 1 word."),
                            ),
                        ),
                ),
            )
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("max_tokens", VALIDATION_MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", VALIDATION_MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .also { body ->
                val providers = openRouterProviderOrder.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                if (protocol == ProviderProtocol.OPENROUTER && providers.isNotEmpty()) {
                    body.put(
                        "provider",
                        JSONObject()
                            .put("order", JSONArray(providers))
                            .put("allow_fallbacks", openRouterAllowFallbacks),
                    )
                }
            }
            .toString()
    }

    private fun friendlyHttpError(code: Int): String = when (code) {
        429 -> "The provider rate limit was reached. Wait a moment and try again."
        in 500..599 -> "The provider is temporarily unavailable (HTTP $code)."
        else -> "The provider returned HTTP $code. Check the URL and account access."
    }

    private fun providerErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val extracted = runCatching {
            val root = JSONObject(body)
            when (val error = root.opt("error")) {
                is JSONObject -> error.optString("message").ifBlank { error.optString("detail") }
                is String -> error
                else -> root.optString("message").ifBlank { root.optString("detail") }
            }
        }.getOrNull().orEmpty()
        if (extracted.isBlank()) return null
        return extracted
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)

    private companion object {
        const val VALIDATION_MAX_TOKENS = 8
        const val MAX_PROVIDER_RESPONSE_CHARS = 2 * 1024 * 1024
    }
}

object ModelResponseParser {
    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val trimmed = json.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> if (item.isNotBlank()) add(DiscoveredModel(item))
                    is JSONObject -> {
                        val id = item.optString("id")
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("model") }
                        if (id.isNotBlank()) {
                            val label = item.optString("display_name")
                                .ifBlank { item.optString("displayName") }
                                .ifBlank { id }
                            val pricing = item.optJSONObject("pricing")
                            val free = id.endsWith(":free", ignoreCase = true) || pricing?.let {
                                listOf("prompt", "completion", "request").all { field ->
                                    it.optString(field, "0").toDoubleOrNull() == 0.0
                                }
                            } == true
                            add(DiscoveredModel(id, label, free))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.sortedWith(
            compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() },
        )
    }.getOrDefault(emptyList())
}
```

## 8. `app/src/main/java/com/nextcraft/forge/network/LocalNetwork.kt` (108 baris)

```kotlin
package com.nextcraft.forge.network

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Plain-HTTP policy and localhost port-discovery utilities for NextCraft Forge.
 *
 * Self-hosted model servers (llama.cpp, LM Studio, Ollama, vLLM, LocalFormatGateway, etc.)
 * and local dev servers inside the Ubuntu chroot speak plain HTTP on loopback, LAN, or Tailscale.
 * Public internet endpoints must always use HTTPS.
 */
object LocalNetwork {
    private val localSuffixes = listOf(
        ".local",
        ".lan",
        ".home",
        ".home.arpa",
        ".internal",
        ".localdomain",
        ".ts.net",
    )

    val DEFAULT_PREVIEW_PORTS: List<Int> = listOf(
        3000,
        4173,
        4200,
        5000,
        5173,
        8000,
        8080,
        8081,
        8787,
        8888,
    )

    fun isPlainHttpAllowed(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.takeIf(String::isNotBlank) ?: return false
        return when (scheme) {
            "https" -> true
            "http" -> isLocalHost(host)
            else -> false
        }
    }

    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost
            .trim()
            .trim('[', ']')
            .substringBefore('%') // Strip IPv6 scope IDs like %wlan0
            .lowercase()
            .trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "0.0.0.0" || localSuffixes.any(host::endsWith)) return true
        if (host.contains(':')) return isLocalIpv6(host)
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val (a, b) = octets.take(2).map(String::toInt)
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) // Tailscale / carrier-grade NAT
        }
        return false
    }

    private fun isLocalIpv6(host: String): Boolean {
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (host.startsWith("::ffff:")) {
            val mappedIpv4 = host.removePrefix("::ffff:")
            if (mappedIpv4.contains('.')) return isLocalHost(mappedIpv4)
        }
        val firstHextet = host.substringBefore(':')
        if (firstHextet.length == 4 && firstHextet.all { it in '0'..'9' || it in 'a'..'f' }) {
            val prefix2 = firstHextet.take(2)
            val prefix3 = firstHextet.take(3)
            if (prefix2 == "fc" || prefix2 == "fd") return true // Unique Local (fc00::/7)
            if (prefix3 == "fe8" || prefix3 == "fe9" || prefix3 == "fea" || prefix3 == "feb") return true // Link-Local (fe80::/10)
        }
        return false
    }

    /**
     * Probes whether a TCP server is listening on `127.0.0.1:[port]` (shared with the chroot via host netns).
     */
    fun isLocalPortOpen(port: Int, timeoutMs: Int = 150): Boolean {
        if (port !in 1..65535) return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs.coerceAtLeast(25))
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Scans candidate localhost ports and returns those currently accepting TCP connections.
     */
    fun scanOpenLocalPorts(
        ports: Iterable<Int> = DEFAULT_PREVIEW_PORTS,
        timeoutMs: Int = 120,
    ): List<Int> = ports.distinct().filter { port -> isLocalPortOpen(port, timeoutMs) }
}
```

## 9. `app/src/main/java/com/nextcraft/forge/network/GitHubClient.kt` (229 baris)

```kotlin
package com.nextcraft.forge.network

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

data class GitHubAccount(val login: String, val avatarUrl: String)

data class GitHubRepository(
    val fullName: String,
    val cloneUrl: String,
    val private: Boolean,
    val defaultBranch: String,
    val description: String,
    val updatedAt: String,
)

sealed interface GitHubTokenPoll {
    data class Success(val accessToken: String) : GitHubTokenPoll
    data class Pending(val slowDown: Boolean = false) : GitHubTokenPoll
    data class Failure(val message: String) : GitHubTokenPoll
}

class GitHubClient(@Suppress("UNUSED_PARAMETER") private val context: Context? = null) {

    fun requestDeviceCode(
        clientId: String = DEFAULT_CLIENT_ID,
        scope: String = DEFAULT_SCOPES,
    ): GitHubDeviceCode {
        val resolvedClientId = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        val resolvedScope = scope.trim().ifEmpty { DEFAULT_SCOPES }
        val json = postForm(
            "https://github.com/login/device/code",
            mapOf(
                "client_id" to resolvedClientId,
                "scope" to resolvedScope,
            ),
        )
        return GitHubDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            expiresInSeconds = json.optLong("expires_in", 900L),
            intervalSeconds = json.optLong("interval", 5L).coerceAtLeast(5L),
        )
    }

    fun pollDeviceToken(clientId: String = DEFAULT_CLIENT_ID, deviceCode: String): GitHubTokenPoll {
        val resolvedClientId = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to resolvedClientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        json.optString("access_token").takeIf(String::isNotBlank)?.let { return GitHubTokenPoll.Success(it) }
        return when (val error = json.optString("error")) {
            "authorization_pending" -> GitHubTokenPoll.Pending()
            "slow_down" -> GitHubTokenPoll.Pending(slowDown = true)
            "access_denied" -> GitHubTokenPoll.Failure("GitHub authorization was cancelled")
            "expired_token" -> GitHubTokenPoll.Failure("The GitHub sign-in code expired. Try again.")
            else -> GitHubTokenPoll.Failure(
                json.optString("error_description").ifBlank {
                    error.ifBlank { "GitHub sign-in failed" }
                },
            )
        }
    }

    fun account(token: String): GitHubAccount {
        val json = getJson("https://api.github.com/user", token) as? JSONObject
            ?: error("Unexpected GitHub user profile response")
        return GitHubAccount(json.getString("login"), json.optString("avatar_url"))
    }

    fun createRepository(
        token: String,
        name: String,
        description: String = "",
        private: Boolean = true,
        autoInit: Boolean = false,
    ): GitHubRepository {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Repository name must not be empty" }
        val payload = JSONObject()
            .put("name", cleanName)
            .put("description", description.trim())
            .put("private", private)
            .put("auto_init", autoInit)
        val item = postJson("https://api.github.com/user/repos", token, payload) as? JSONObject
            ?: error("Unexpected GitHub create repository response")
        return GitHubRepository(
            fullName = item.getString("full_name"),
            cloneUrl = item.getString("clone_url"),
            private = item.optBoolean("private", private),
            defaultBranch = item.optString("default_branch", "main").ifBlank { "main" },
            description = item.optString("description"),
            updatedAt = item.optString("updated_at"),
        )
    }

    fun repositories(token: String): List<GitHubRepository> {
        val result = LinkedHashMap<String, GitHubRepository>()
        var page = 1
        while (page <= 10) {
            val array = getJson(
                "https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100&page=$page",
                token,
            ) as? JSONArray ?: error("Unexpected GitHub repository list response")
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val fullName = item.optString("full_name")
                val cloneUrl = item.optString("clone_url")
                if (fullName.isBlank() || cloneUrl.isBlank()) continue
                val repo = GitHubRepository(
                    fullName = fullName,
                    cloneUrl = cloneUrl,
                    private = item.optBoolean("private"),
                    defaultBranch = item.optString("default_branch", "main").ifBlank { "main" },
                    description = item.optString("description"),
                    updatedAt = item.optString("updated_at"),
                )
                result[repo.fullName] = repo
            }
            if (array.length() < 100) break
            page++
        }
        return result.values.toList()
    }

    private fun postForm(endpoint: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            readResponse(connection) as? JSONObject ?: error("Unexpected GitHub OAuth response")
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(endpoint: String, token: String, payload: JSONObject): Any {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(endpoint: String, token: String): Any {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): Any {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            buildString {
                val buffer = CharArray(8 * 1024)
                while (length < MAX_GITHUB_RESPONSE_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_GITHUB_RESPONSE_CHARS - length))
                    if (count <= 0) break
                    append(buffer, 0, count)
                }
            }
        }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
            error(message?.takeIf(String::isNotBlank) ?: "GitHub returned HTTP $status")
        }
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }

    companion object {
        const val DEFAULT_CLIENT_ID = "178c6fc778ccc68e1d6a"
        const val DEFAULT_SCOPES = "repo read:user user:email workflow"
        private const val USER_AGENT = "NextCraft-Forge-Android"
        private const val MAX_GITHUB_RESPONSE_CHARS = 4 * 1024 * 1024
    }
}
```

## 10. `app/src/main/java/com/nextcraft/forge/update/AppUpdater.kt` (214 baris)

```kotlin
package com.nextcraft.forge.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

class AppUpdater(
    private val context: Context,
    /**
     * Optional manifest URL override. When non-empty, used in place of
     * `BuildConfig.APP_UPDATE_MANIFEST_URL`.
     */
    private val manifestUrlOverride: String = "",
) {
    fun check(): AppUpdateInfo? {
        val manifestUrl = manifestUrlOverride.ifBlank { defaultManifestUrl() }.trim()
        if (!manifestUrl.startsWith("https://")) return null
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                buildString {
                    val buffer = CharArray(4 * 1024)
                    while (length < MAX_MANIFEST_CHARS) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST_CHARS - length))
                        if (count <= 0) break
                        append(buffer, 0, count)
                    }
                }
            }
            val root = JSONObject(body)
            val versionCode = root.optLong("versionCode")
            if (versionCode <= currentVersionCode()) return null
            val variant = appVariant()
            val artifact = root.optJSONObject("artifacts")?.optJSONObject(variant)
                ?: root.optJSONObject(variant)
                ?: root
            val url = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }.trim()
            if (!url.startsWith("https://")) return null
            AppUpdateInfo(
                versionCode = versionCode,
                versionName = root.optString("versionName", versionCode.toString()),
                apkUrl = url,
                sha256 = artifact.optString("sha256").trim().lowercase(),
                sizeBytes = artifact.optLong("sizeBytes", -1L),
                notes = root.optString("notes"),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
        require(info.apkUrl.startsWith("https://")) { "Update APK URL must use HTTPS." }
        val variant = appVariant()
        val directory = File(context.filesDir, "updates").also { it.mkdirs() }
        val partial = File(directory, "nextcraft-forge-$variant.apk.part")
        val target = File(directory, "nextcraft-forge-$variant.apk")
        try {
            val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                val code = connection.responseCode
                check(code in 200..299) { "Update download failed (HTTP $code)." }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            progress(downloaded, total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            check(info.sha256.isNotBlank()) {
                "Update manifest is missing SHA-256 checksum — refusing install."
            }
            val actual = sha256(partial)
            check(actual.equals(info.sha256, ignoreCase = true)) {
                "Downloaded update failed SHA-256 checksum verification."
            }
            verifyApk(partial, info.versionCode)
            if (target.exists() && !target.delete()) {
                error("Could not replace previous staged update APK.")
            }
            check(partial.renameTo(target)) { "Could not finalize downloaded update APK." }
            return target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not a valid Android APK archive.")
        check(archive.packageName == context.packageName) {
            "Downloaded APK package (${archive.packageName}) does not match installed app (${context.packageName})."
        }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        check(archiveVersion == expectedVersionCode && archiveVersion > currentVersionCode()) {
            "Downloaded APK version ($archiveVersion) does not match expected update version ($expectedVersionCode)."
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners
        } else {
            archive.signatures
        }
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.apkContentsSigners
        } else {
            installed.signatures
        }
        check(
            !archiveSignatures.isNullOrEmpty() &&
                !installedSignatures.isNullOrEmpty() &&
                archiveSignatures.map { sha256(it.toByteArray()) }.toSet() ==
                installedSignatures.map { sha256(it.toByteArray()) }.toSet(),
        ) {
            "Downloaded APK signing certificate does not match the installed app."
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Long {
        buildConfigLong("VERSION_CODE")?.let { return it }
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }.getOrDefault(1L)
    }

    private fun appVariant(): String =
        buildConfigString("APP_VARIANT")?.takeIf(String::isNotBlank) ?: "debug"

    private fun defaultManifestUrl(): String =
        buildConfigString("APP_UPDATE_MANIFEST_URL").orEmpty()

    private fun resolveBuildConfigClass(): Class<*>? =
        runCatching { Class.forName("${context.packageName}.BuildConfig") }.getOrNull()
            ?: runCatching { Class.forName("com.nextcraft.forge.BuildConfig") }.getOrNull()

    private fun buildConfigString(fieldName: String): String? = runCatching {
        val cls = resolveBuildConfigClass() ?: return@runCatching null
        cls.getField(fieldName).get(null) as? String
    }.getOrNull()

    private fun buildConfigLong(fieldName: String): Long? = runCatching {
        val cls = resolveBuildConfigClass() ?: return@runCatching null
        when (val value = cls.getField(fieldName).get(null)) {
            is Long -> value
            is Int -> value.toLong()
            else -> null
        }
    }.getOrNull()

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_MANIFEST_CHARS = 256 * 1024
    }
}
```
