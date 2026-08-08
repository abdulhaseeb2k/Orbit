// :desktop — Orbit for Windows/macOS/Linux (Compose Multiplatform for Desktop).
// Run from the project root:  .\gradlew.bat :desktop:run
// Package a Windows installer: .\gradlew.bat :desktop:packageMsi
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)          // Compose compiler (bundled with Kotlin)
    alias(libs.plugins.compose.multiplatform)   // Compose Desktop runtime + packaging
}

dependencies {
    implementation(project(":core"))            // shared: Track, matchKey, YouTubeResolver
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation("org.json:json:20240303")   // same JSON schema as Android's org.json
}

compose.desktop {
    application {
        mainClass = "com.vibecaster.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Orbit"
            packageVersion = "1.3.0"
            description = "Orbit — 8D Audio Experience (desktop)"
            vendor = "Abdul Haseeb"
            copyright = "Copyright (c) 2026 Abdul Haseeb. Licensed under GPL-3.0."
            // Ships everything under desktop/resources/<platform>/ inside the
            // installer — fetchTools puts ffmpeg + yt-dlp there, so packaged
            // builds need NO downloads and NO manual installs at all.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            // Branding for the packaged app. Without these, jpackage stamps the
            // generic Java "duke" icon onto Orbit.exe, the Start-menu entry, the
            // taskbar and the installer window. The runtime window/tray icons
            // (painterResource("orbit.png") in Main.kt) are a separate thing and
            // never affected the executable itself.
            // orbit.ico is multi-resolution (16-256px) so Explorer, the taskbar
            // and Alt-Tab each pick a crisp size instead of rescaling one bitmap.
            windows {
                iconFile.set(project.file("icons/orbit.ico"))
                menuGroup = "Orbit"
                shortcut = true          // desktop shortcut
                menu = true              // Start-menu entry
                dirChooser = true        // let the user pick the install folder
                // Fixed UpgradeCode: MSIs with the same value upgrade in place.
                // Leave it alone — changing it makes Windows treat a new build
                // as a separate product and install it side by side.
                upgradeUuid = "8c23ddd8-c4ae-4420-a0fd-1732ee2041d6"
            }
            macOS {
                iconFile.set(project.file("icons/orbit.icns"))
                bundleID = "io.github.abdulhaseeb2k.orbit"
            }
            linux {
                iconFile.set(project.file("icons/orbit.png"))
            }
        }
    }
}

/**
 * Downloads ffmpeg (essentials) + yt-dlp into resources/windows-x64/tools
 * so Windows installers are fully self-contained. Runs automatically before
 * any packaging task; skips files that are already there. Dev `:desktop:run`
 * does NOT depend on this — ToolBootstrap handles dev machines at runtime.
 * (resources/ is gitignored — these are ~100 MB of binaries.)
 */
val fetchTools = tasks.register("fetchTools") {
    val toolsDir = project.layout.projectDirectory.dir("resources/windows-x64/tools").asFile
    outputs.dir(toolsDir)
    doLast {
        toolsDir.mkdirs()
        fun fetch(url: String, dest: java.io.File) {
            logger.lifecycle("fetchTools: downloading ${dest.name}…")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.inputStream.use { i -> dest.outputStream().use { i.copyTo(it) } }
        }
        val ytDlp = toolsDir.resolve("yt-dlp.exe")
        if (!ytDlp.isFile) fetch(
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe", ytDlp
        )
        if (!toolsDir.resolve("ffmpeg.exe").isFile || !toolsDir.resolve("ffprobe.exe").isFile) {
            val zip = toolsDir.resolve("ffmpeg.zip")
            fetch("https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip", zip)
            logger.lifecycle("fetchTools: extracting ffmpeg…")
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val n = e.name.replace('\\', '/')
                    if (!e.isDirectory &&
                        (n.endsWith("/bin/ffmpeg.exe") || n.endsWith("/bin/ffprobe.exe"))
                    ) {
                        toolsDir.resolve(n.substringAfterLast('/'))
                            .outputStream().use { o -> z.copyTo(o) }
                    }
                }
            }
            zip.delete()
            check(toolsDir.resolve("ffmpeg.exe").isFile) { "ffmpeg.exe missing from archive" }
        }
        logger.lifecycle("fetchTools: tools ready in $toolsDir")
    }
}

// Every packaging/distributable task ships the tools.
tasks.configureEach {
    if (name in setOf(
            "prepareAppResources", "prepareReleaseAppResources",
            "createDistributable", "createReleaseDistributable",
            "packageMsi", "packageReleaseMsi", "packageExe", "packageReleaseExe",
            "packageDistributionForCurrentOS", "packageReleaseDistributionForCurrentOS"
        )
    ) dependsOn(fetchTools)
}
