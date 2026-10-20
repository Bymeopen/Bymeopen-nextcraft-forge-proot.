plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.x ships the Compose compiler as a plugin that must match the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
