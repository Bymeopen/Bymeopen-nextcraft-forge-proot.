package com.nextcraft.forge.runtime

import org.json.JSONObject

/** One line of `rea doctor`: a capability that was tested for real. [status] is "ok", "warn" or "fail". */
data class ReaCheck(val id: String, val label: String, val status: String, val detail: String, val hint: String)

data class ReaError(val code: String, val message: String, val hint: String)

/** The JSON object every `rea ... --json` command prints on its last stdout line (schema 1). */
data class ReaResponse(
    val command: String,
    val ok: Boolean,
    val mode: String?,
    val data: JSONObject?,
    val warnings: List<String>,
    val error: ReaError?,
) {
    /** The doctor's capability list; empty for other commands. */
    val checks: List<ReaCheck>
        get() {
            val array = data?.optJSONArray("checks") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ReaCheck(
                    id = item.optString("id"),
                    label = item.optString("label"),
                    status = item.optString("status"),
                    detail = item.optString("detail"),
                    hint = item.optString("hint"),
                )
            }
        }
}

object ReaJson {
    const val SCHEMA = 1

    /**
     * Takes the last line of [output] that is a REA result. The chroot merges stderr into the output, so notices and
     * warnings can precede it; lines that are not JSON, or JSON of another shape, are skipped.
     */
    fun parse(output: String): ReaResponse? {
        for (line in output.lineSequence().map(String::trim).toList().asReversed()) {
            if (!line.startsWith("{") || !line.endsWith("}")) continue
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.optInt("schema", -1) != SCHEMA || !json.has("command")) continue
            val warnings = json.optJSONArray("warnings")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
            val error = json.optJSONObject("error")?.let {
                ReaError(it.optString("code"), it.optString("message"), it.optString("hint"))
            }
            return ReaResponse(
                command = json.optString("command"),
                ok = json.optBoolean("ok", false),
                mode = json.optString("mode").takeIf { it.isNotEmpty() },
                data = json.optJSONObject("data"),
                warnings = warnings,
                error = error,
            )
        }
        return null
    }
}
