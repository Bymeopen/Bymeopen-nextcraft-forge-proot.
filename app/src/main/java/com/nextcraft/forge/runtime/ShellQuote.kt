package com.nextcraft.forge.runtime

/** Wraps [value] in single quotes so a POSIX shell reads it as one literal word. */
internal fun shellSingleQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
