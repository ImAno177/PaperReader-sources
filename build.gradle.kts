plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}

group = "dev.paperreader.extensions"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()
