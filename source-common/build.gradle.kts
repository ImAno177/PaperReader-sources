plugins {
    id("com.android.library")
}

android {
    namespace = "dev.paperreader.extensions.sources.common"
    compileSdk = 36

    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("dev.paperreader:extension-api:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
