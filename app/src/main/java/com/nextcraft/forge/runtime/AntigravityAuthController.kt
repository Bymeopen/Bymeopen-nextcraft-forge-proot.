package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.AgentKind
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
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
            message = initialAccountEmail.takeIf(String::isNotBlank)?.let { context.getString(com.nextcraft.forge.R.string.auth_connected_as, it) },
            accountEmail = initialAccountEmail.takeIf(String::isNotBlank),
        ),
    )
    val state: StateFlow<AntigravityAuthState> = mutableState.asStateFlow()
    private val authOutput = File(context.cacheDir, "antigravity-auth-output.log")
    private val logoutOutput = File(context.cacheDir, "antigravity-logout-output.log")
    @Volatile private var process: Process? = null
    @Volatile private var codeSubmitted = false
    private val loginStarting = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Signed-in state to return to when a re-login attempt is cancelled. */
    @Volatile private var stateBeforeLogin: AntigravityAuthState? = null

    init {
        // Remove output left by an app/process crash before starting a new OAuth flow.
        authOutput.delete()
        logoutOutput.delete()
    }

    private fun officialCredentialFile() = File(
        context.filesDir,
        "runtime/ubuntu/root/.gemini/antigravity-cli/antigravity-oauth-token",
    )

    fun hasOfficialCredential(): Boolean = officialCredentialFile().let { it.isFile && it.length() > 0L }

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        // process?.isAlive alone is check-then-act: two quick taps could each spawn an agy.
        if (!loginStarting.compareAndSet(false, true)) return@withContext
        try {
            beginLoginLocked()
        } finally {
            loginStarting.set(false)
        }
    }

    private suspend fun beginLoginLocked() {
        if (process?.isAlive == true) return
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.ERROR,
                message = "Install Antigravity CLI first in Settings.",
            )
            return
        }
        stateBeforeLogin = mutableState.value.takeIf { it.status == AntigravityAuthStatus.SIGNED_IN }
        mutableState.value = AntigravityAuthState(
            AntigravityAuthStatus.STARTING,
            message = "Starting Antigravity sign-in…",
        )
        codeSubmitted = false
        authOutput.delete()
        // Spawning can throw (missing runtime, no root); callers launch this unguarded, so report it as state.
        val running = try {
            val runtime = installer.installedRuntime()
            val workspace = File(context.filesDir, "workspaces/antigravity-auth").apply { mkdirs() }
            val spawned = installer.process(
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
            if (spawned !is NativeSpawnProcess) {
                runCatching { spawned.destroyForcibly() }
                error("Unsupported Antigravity authentication process")
            }
            spawned
        } catch (error: Throwable) {
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.ERROR,
                message = error.message?.take(240) ?: "Could not start Antigravity sign-in.",
            )
            return
        }
        process = running
        val native = running
        var offset = 0L
        val output = StringBuilder()
        var handshakeReplies = 0
        var lastHandshakeReplyLength = 0
        var loginMenuAdvanced = false
        var colorScreenCompleted = false
        var renderingScreenCompleted = false
        var privacyScreenCompleted = false
        var workspaceTrustCompleted = false
        val deadline = android.os.SystemClock.elapsedRealtime() + LOGIN_TIMEOUT_MS
        try {
            RandomAccessFile(native.outputFile, "r").use { file ->
                while (running.isAlive || native.outputFile.length() > offset) {
                    // An abandoned flow would otherwise keep agy alive in the chroot indefinitely.
                    if (android.os.SystemClock.elapsedRealtime() > deadline) {
                        error("Sign-in timed out. Start sign-in again.")
                    }
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
                    if (output.length > MAX_AUTH_OUTPUT_CHARS) {
                        // The TUI repaints constantly; only the recent tail is ever inspected (and re-sanitized per chunk).
                        val drop = output.length - KEEP_AUTH_OUTPUT_CHARS
                        output.delete(0, drop)
                        lastHandshakeReplyLength = (lastHandshakeReplyLength - drop).coerceAtLeast(0)
                    }
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
                            message = email?.let { context.getString(com.nextcraft.forge.R.string.auth_connected_as, it) } ?: context.getString(com.nextcraft.forge.R.string.auth_google_connected),
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
            when (mutableState.value.status) {
                AntigravityAuthStatus.STARTING -> if (!codeSubmitted) {
                    val exit = running.waitFor()
                    error("Antigravity sign-in exited early (code $exit).")
                }
                AntigravityAuthStatus.AWAITING_CODE ->
                    error("Sign-in session ended before a code was submitted. Start sign-in again.")
                // agy can finish and exit without ever painting the screen we match on.
                AntigravityAuthStatus.COMPLETING -> if (hasOfficialCredential()) {
                    onSignedInChanged(true, null)
                    mutableState.value = AntigravityAuthState(
                        AntigravityAuthStatus.SIGNED_IN,
                        message = "Google account connected",
                    )
                } else {
                    error("Antigravity sign-in ended before completing. Please try again.")
                }
                else -> Unit
            }
        } catch (cancelled: CancellationException) {
            // The caller went away (screen closed, scope cleared): not a sign-in failure. Restore the previous state
            // instead of showing an error, and let the cancellation reach the parent scope.
            if (mutableState.value.status != AntigravityAuthStatus.SIGNED_IN) {
                mutableState.value = stateBeforeLogin ?: AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT)
            }
            throw cancelled
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
        try {
            val running = process ?: error("Sign-in session is not active. Start sign-in again.")
            check(running.isAlive) { "Sign-in session expired. Start sign-in again." }
            running.outputStream.write((value + "\r\n").toByteArray())
            running.outputStream.flush()
        } catch (error: Throwable) {
            // Callers swallow this exception, so surface it through state or the user sees nothing happen.
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.ERROR,
                message = error.message?.take(240) ?: "Could not submit the verification code.",
            )
            throw error
        }
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
        // Cancelling a re-login must not make an already connected account look signed out.
        mutableState.value = stateBeforeLogin ?: AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT)
    }

    fun invalidateSession(message: String) {
        onSignedInChanged(false, null)
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.ERROR, message = message)
    }
}

private const val LOGIN_TIMEOUT_MS = 15 * 60 * 1_000L
private const val MAX_AUTH_OUTPUT_CHARS = 200_000
private const val KEEP_AUTH_OUTPUT_CHARS = 100_000

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
    // [output] is a long tail of the TUI, so a transient "error"/"failed" from an earlier retry used to keep this
    // false for the rest of the flow. Only text after the newest signed-in screen can veto it.
    val shortcuts = output.lastIndexOf("for shortcuts", ignoreCase = true)
    val banner = if (output.contains("Google AI", true)) output.lastIndexOf("Antigravity CLI", ignoreCase = true) else -1
    val screenStart = maxOf(shortcuts, banner)
    if (screenStart < 0) return false
    val after = output.substring(screenStart)
    return !after.contains("error", true) && !after.contains("failed", true)
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
