package com.vibecaster.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer app version.
 *
 * IMPORTANT: set [REPO] to your own GitHub "username/repository" and create
 * releases with tags like "v1.1" (attach the APK as a release asset).
 */
object UpdateRepository {

    private const val REPO = "abdulhaseeb2k/Orbit"

    /**
     * An update URL is only ever opened if it really belongs to [REPO].
     * Without this, whoever owns the repo name the app points at controls
     * what the "Update" button downloads.
     */
    fun isTrustedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!u.scheme.equals("https", ignoreCase = true)) return false
        val host = u.host?.lowercase() ?: return false
        val path = u.path ?: return false
        return when (host) {
            "github.com" -> path.startsWith("/$REPO/")
            "objects.githubusercontent.com" -> true
            else -> false
        }
    }

    data class UpdateInfo(
        val version: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String?
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Returns update info when a newer release exists, else null. */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException(
                    if (resp.code == 404) "Repository or release not found — check the REPO setting."
                    else "GitHub API error: HTTP ${resp.code}"
                )
            }
            val o = JSONObject(resp.body?.string() ?: return@use null)
            val tag = o.optString("tag_name").removePrefix("v").removePrefix("V").trim()
            if (tag.isBlank()) return@use null
            if (!isNewer(tag, currentVersion.removePrefix("v").trim())) return@use null

            // Prefer a direct APK asset when the release has one.
            val assets = o.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url").ifBlank { null }
                            ?.takeIf { isTrustedUrl(it) }
                        break
                    }
                }
            }
            UpdateInfo(
                version = tag,
                notes = o.optString("body").take(1500),
                pageUrl = o.optString("html_url"),
                apkUrl = apkUrl
            )
        }
    }

    /** Numeric segment-by-segment comparison: "1.10" > "1.9" > "1". */
    internal fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
