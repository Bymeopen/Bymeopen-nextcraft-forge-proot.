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
import java.util.concurrent.TimeUnit
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

    // Bounded pool: caps concurrent connections instead of one unbounded thread per accept().
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        0, MAX_CONNECTIONS, 30L, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
    ) { task -> Thread(task, "forge-format-request").apply { isDaemon = true } }

    private fun acceptLoop() {
        var failures = 0
        while (running.get()) {
            val socket = try {
                server.accept().also { failures = 0 }
            } catch (_: Exception) {
                // Closed server ends the loop; transient errors (e.g. EMFILE) back off instead of busy-spinning.
                if (!running.get() || server.isClosed || ++failures > MAX_ACCEPT_FAILURES) break
                Thread.sleep(ACCEPT_BACKOFF_MS)
                continue
            }
            runCatching {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.tcpNoDelay = true
            }
            try {
                workers.execute { socket.use(::handle) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                runCatching { socket.close() }
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
        workers.shutdown()
    }

    private companion object {
        const val MAX_CONNECTIONS = 32
        const val MAX_ACCEPT_FAILURES = 50
        const val ACCEPT_BACKOFF_MS = 100L
        const val MAX_REQUEST_BYTES = 8 * 1024 * 1024
        const val MAX_UPSTREAM_RESPONSE_CHARS = 4 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
