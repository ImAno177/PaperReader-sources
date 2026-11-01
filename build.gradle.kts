plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.android.library") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

group = "dev.paperreader.extensions"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()
