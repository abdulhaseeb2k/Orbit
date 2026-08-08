// :core — shared, pure-JVM logic used by BOTH the Android app and the desktop
// app: Track model + matchKey identity, and YouTube search/resolve.
// RULE: no android.* imports in this module, ever.
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // `api` so consumers (:app, :desktop) see these types transitively.
    api(libs.newpipe.extractor)
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    // org.json: compileOnly because Android ships it in the OS (a runtime
    // copy would clash); the desktop module provides it as a real dependency.
    compileOnly("org.json:json:20240303")
}

/**
 * API keys / OAuth client ids live in secrets.properties (GITIGNORED — copy
 * secrets.properties.example and fill it in). This task generates
 * OrbitSecrets.kt from it, so no key ever sits in committed source.
 * Both the Android app and the desktop app read the values through :core.
 */
val generateOrbitSecrets = tasks.register("generateOrbitSecrets") {
    val propsFile = rootProject.file("secrets.properties")
    val outDir = layout.buildDirectory.dir("generated/orbitsecrets")
    if (propsFile.exists()) inputs.file(propsFile)
    outputs.dir(outDir)
    doLast {
        val p = Properties()
        if (propsFile.exists()) propsFile.inputStream().use { p.load(it) }
        fun v(key: String) = p.getProperty(key) ?: System.getenv(key) ?: ""
        val out = outDir.get().file("com/vibecaster/sync/OrbitSecrets.kt").asFile
        out.parentFile.mkdirs()
        out.writeText(
            """
            |package com.vibecaster.sync
            |
            |/** GENERATED from secrets.properties — do not edit, do not commit. */
            |object OrbitSecrets {
            |    const val FIREBASE_API_KEY = "${v("ORBIT_FIREBASE_API_KEY")}"
            |    const val GOOGLE_WEB_CLIENT_ID = "${v("ORBIT_GOOGLE_WEB_CLIENT_ID")}"
            |    const val GOOGLE_DESKTOP_CLIENT_ID = "${v("ORBIT_GOOGLE_DESKTOP_CLIENT_ID")}"
            |    const val GOOGLE_DESKTOP_CLIENT_SECRET = "${v("ORBIT_GOOGLE_DESKTOP_CLIENT_SECRET")}"
            |}
            |""".trimMargin()
        )
        if (!propsFile.exists()) logger.warn(
            "secrets.properties not found — OrbitSecrets generated EMPTY. " +
                "Copy secrets.properties.example to secrets.properties and fill it in."
        )
    }
}

kotlin {
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/orbitsecrets"))
}

tasks.named("compileKotlin") { dependsOn(generateOrbitSecrets) }
