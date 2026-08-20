import com.android.build.api.variant.BuildConfigField

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.serialization") }
val hostSigner = providers.gradleProperty("paperReaderHostSignerSha256").orElse("0".repeat(64)).get()
require(hostSigner.matches(Regex("[0-9a-fA-F]{64}")))
val keystorePath = providers.gradleProperty("extensionKeystorePath").orNull
android { namespace = "dev.paperreader.extensions.sources.crossref"; compileSdk = 36
    defaultConfig { applicationId = "dev.paperreader.extensions.sources.crossref"; minSdk = 28; targetSdk = 36; versionCode = providers.gradleProperty("releaseVersionCode").orElse("1").get().toInt(); versionName = providers.gradleProperty("releaseVersion").orElse("0.1.0").get() }
    buildFeatures { buildConfig = true }
    signingConfigs { if (keystorePath != null) create("extensionRelease") { storeFile = file(keystorePath); storePassword = providers.gradleProperty("extensionKeystorePassword").get(); keyAlias = providers.gradleProperty("extensionKeyAlias").get(); keyPassword = providers.gradleProperty("extensionKeyPassword").get() } }
    buildTypes { getByName("release") { if (keystorePath != null) signingConfig = signingConfigs.getByName("extensionRelease") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
androidComponents { onVariants(selector().all()) { variant -> variant.buildConfigFields?.put("PAPERREADER_HOST_SIGNER_SHA256", BuildConfigField("String", "\"$hostSigner\"", "Pinned host signing certificate digest")) } }
dependencies { implementation(project(":source-common")); implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0"); implementation("org.jsoup:jsoup:1.23.1"); testImplementation("junit:junit:4.13.2") }
