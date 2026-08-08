import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing credentials live in local.properties (gitignored) or env vars —
// never commit passwords to version control.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    localProps.getProperty(name) ?: System.getenv(name) ?: ""

android {
    namespace = "com.vibecaster"
    compileSdk = 37

    defaultConfig {
        // NOTE: applicationId changed with the Orbit rebrand. This installs as
        // a NEW app — the old VibeCaster install must be uninstalled manually,
        // and its downloads/playlists do not carry over. The code namespace
        // stays com.vibecaster (internal only, invisible to users).
        applicationId = "com.orbit.music"
        minSdk = 31
        targetSdk = 37
        versionCode = 6
        versionName = "1.3.0"
    }

    // The keystore is never committed. On a fresh clone without one, release
    // builds simply stay unsigned instead of failing with a cryptic error —
    // generate your own key and point local.properties at it to sign.
    val keystoreFile = rootProject.file("release.keystore")
    val hasKeystore = keystoreFile.exists() && secret("RELEASE_STORE_PASSWORD").isNotEmpty()
    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS").ifEmpty { "orbit" }
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // proguard-rules.pro already keeps NewPipeExtractor + Rhino.
            // If the first minified build misbehaves at runtime, set these
            // back to false and report what broke.
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // NewPipeExtractor uses Java 10+ APIs (e.g. Collectors.toUnmodifiableList)
        // that older Android runtimes lack; desugaring backports them.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Shared logic (Track + matchKey, YouTubeResolver) — same code the
    // desktop app uses. Packages are unchanged (com.vibecaster.*), so no
    // import changes were needed anywhere in this module.
    implementation(project(":core"))

    // The _nio variant is required by NewPipeExtractor: it also backports
    // java.net/java.nio APIs like URLDecoder.decode(String, Charset).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Icons (last released version of the icons artifact)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Lifecycle + ViewModel for Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Media playback (ExoPlayer + background session)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    // YouTube stream extraction
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Album art / thumbnails
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Google Sign-In via Credential Manager (for Firebase sync login).
    // Firebase itself is used over REST from :core — no Firebase SDK needed.
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
