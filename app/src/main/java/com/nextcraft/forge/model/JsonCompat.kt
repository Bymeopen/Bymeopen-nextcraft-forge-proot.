package com.nextcraft.forge.model

import org.json.JSONObject

/**
 * Android's org.json returns the string "null" from optString() for a JSON null (the reference
 * implementation returns the fallback). Provider and agent streams send explicit nulls routinely
 * (`"content": null`, `"reasoning_content": null`), so treat null and absent alike.
 */
internal fun JSONObject.stringOrEmpty(name: String, fallback: String = ""): String =
    if (isNull(name)) fallback else optString(name, fallback)
