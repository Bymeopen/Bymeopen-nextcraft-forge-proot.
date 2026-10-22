package com.nextcraft.forge.runtime

import java.io.File
import java.nio.file.Files

/** One row of a directory listing. Symbolic links are listed but never followed. */
internal data class BrowserEntry(val name: String, val isDirectory: Boolean, val isLink: Boolean, val sizeBytes: Long)

internal enum class BrowserDenial { OUTSIDE_APP_DATA, MISSING, NO_ACCESS }

internal sealed interface BrowserListing {
    data class Entries(val items: List<BrowserEntry>, val viaRoot: Boolean) : BrowserListing
    data class Denied(val reason: BrowserDenial) : BrowserListing
}

internal sealed interface BrowserRead {
    data class Text(val text: String, val truncated: Boolean, val sizeBytes: Long) : BrowserRead
    data class Binary(val sizeBytes: Long) : BrowserRead
    data class Denied(val reason: BrowserDenial) : BrowserRead
}

/**
 * Reads folders and files inside the app's own data (projects, the Ubuntu home, the whole Ubuntu root). Under PRoot
 * every rootfs file is already owned by the app's own UID, so a plain read always works inside the app data
 * directory ([RootPathGuard]); there is no root to fall back to. Read-only: nothing here writes or deletes.
 */
internal object FileBrowser {
    private const val BINARY_SNIFF_BYTES = 4096

    fun list(dir: File): BrowserListing {
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return BrowserListing.Denied(BrowserDenial.MISSING)
        if (!RootPathGuard.isInsideAppData(canonical.path)) return BrowserListing.Denied(BrowserDenial.OUTSIDE_APP_DATA)
        val direct = canonical.listFiles() ?: return BrowserListing.Denied(
            if (canonical.exists()) BrowserDenial.NO_ACCESS else BrowserDenial.MISSING,
        )
        return BrowserListing.Entries(sorted(direct.map(::entryOf)), viaRoot = false)
    }

    fun read(file: File, maxBytes: Int = 256 * 1024): BrowserRead {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return BrowserRead.Denied(BrowserDenial.MISSING)
        if (!RootPathGuard.isInsideAppData(canonical.path)) return BrowserRead.Denied(BrowserDenial.OUTSIDE_APP_DATA)
        if (canonical.isDirectory) return BrowserRead.Denied(BrowserDenial.MISSING)
        val direct = canonical.takeIf { it.isFile && it.canRead() } ?: return BrowserRead.Denied(BrowserDenial.NO_ACCESS)
        return decode(direct.readHeadBytes(maxBytes), direct.length())
    }

    internal fun decode(bytes: ByteArray, size: Long): BrowserRead {
        if (bytes.take(BINARY_SNIFF_BYTES).any { it == 0.toByte() }) return BrowserRead.Binary(size)
        val truncated = size > bytes.size
        // A cut in the middle of a multi-byte character would show a replacement mark at the end.
        val usable = if (truncated) completeUtf8PrefixLength(bytes, bytes.size) else bytes.size
        return BrowserRead.Text(String(bytes, 0, usable, Charsets.UTF_8), truncated, size)
    }

    /** Parses `ls -lA` output (Android toybox or GNU): one entry per line; `total` and device files are skipped. */
    internal fun parseLsLong(output: String): List<BrowserEntry> {
        val line = Regex("""^([-dl])\S{9}[.+]?\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(?:\d{4}-\d{2}-\d{2}|\w{3}\s+\d+)\s+(?:\d{2}:\d{2}|\d{4})\s+(.+)$""")
        return output.lineSequence().mapNotNull { raw ->
            val match = line.matchEntire(raw.trimEnd()) ?: return@mapNotNull null
            val type = match.groupValues[1]
            val name = match.groupValues[3].substringBefore(" -> ")
            if (name == "." || name == "..") return@mapNotNull null
            BrowserEntry(name, isDirectory = type == "d", isLink = type == "l", sizeBytes = match.groupValues[2].toLongOrNull() ?: 0L)
        }.toList()
    }

    private fun entryOf(file: File): BrowserEntry {
        val link = Files.isSymbolicLink(file.toPath())
        return BrowserEntry(file.name, isDirectory = !link && file.isDirectory, isLink = link, sizeBytes = if (file.isFile) file.length() else 0L)
    }

    /** Folders first, then by name ignoring case. */
    private fun sorted(items: List<BrowserEntry>): List<BrowserEntry> =
        items.sortedWith(compareByDescending<BrowserEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
