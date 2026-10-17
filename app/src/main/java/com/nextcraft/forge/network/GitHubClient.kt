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
