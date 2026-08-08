package com.vibecaster.desktop

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.awt.Desktop
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * "Sign in with Google" for the desktop app — the standard installed-app
 * loopback flow: open the browser, catch the redirect on 127.0.0.1, exchange
 * the code (with PKCE) for a Google ID token, then hand that token to the
 * same [com.vibecaster.sync.AuthManager.signInGoogle] the phone app uses.
 *
 * Client id/secret come from secrets.properties at build time (generated
 * OrbitSecrets in :core) — never committed. Env vars override.
 */
object GoogleAuthDesktop {

    private val clientId = System.getenv("ORBIT_GOOGLE_DESKTOP_CLIENT_ID")?.takeIf { it.isNotBlank() }
        ?: com.vibecaster.sync.OrbitSecrets.GOOGLE_DESKTOP_CLIENT_ID
    private val clientSecret = System.getenv("ORBIT_GOOGLE_DESKTOP_CLIENT_SECRET")?.takeIf { it.isNotBlank() }
        ?: com.vibecaster.sync.OrbitSecrets.GOOGLE_DESKTOP_CLIENT_SECRET

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    class CancelledException : Exception("Sign-in was cancelled in the browser")

    /**
     * Blocking (call on an IO thread). Opens the browser and waits up to
     * 3 minutes for the user to finish. Returns a Google ID token.
     */
    fun signIn(): String {
        val random = SecureRandom()
        val verifierBytes = ByteArray(48).also { random.nextBytes(it) }
        val verifier = b64url(verifierBytes)
        val challenge = b64url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        val state = b64url(ByteArray(16).also { random.nextBytes(it) })

        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 180_000
            val redirect = "http://127.0.0.1:${server.localPort}"
            val authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=${enc(clientId)}" +
                "&redirect_uri=${enc(redirect)}" +
                "&response_type=code" +
                "&scope=${enc("openid email profile")}" +
                "&code_challenge=$challenge" +
                "&code_challenge_method=S256" +
                "&state=$state" +
                "&prompt=select_account"
            OrbitLog.log("google sign-in: opening browser, listening on $redirect")
            Desktop.getDesktop().browse(URI(authUrl))

            // Browsers sometimes open extra connections (favicon, preconnect) —
            // keep accepting until we see the actual redirect (or time out).
            var code: String? = null
            var error: String? = null
            var attempts = 0
            while (code == null && error == null && attempts < 10) {
                attempts++
                val socket = server.accept()
                socket.use { s ->
                    val reader = s.inputStream.bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    val path = requestLine.split(" ").getOrNull(1) ?: return@use
                    val query = path.substringAfter('?', "")
                    val params = query.split("&").mapNotNull {
                        val k = it.substringBefore('=')
                        val v = it.substringAfter('=', "")
                        if (k.isBlank()) null
                        else k to URLDecoder.decode(v, "UTF-8")
                    }.toMap()
                    if (params["state"] != null && params["state"] != state) return@use
                    when {
                        params.containsKey("code") -> code = params["code"]
                        params.containsKey("error") -> error = params["error"]
                        else -> return@use   // favicon or noise — ignore silently
                    }
                    val message =
                        if (code != null) "You're signed in. You can close this tab and return to Orbit."
                        else "Sign-in was cancelled. You can close this tab."
                    val html = "<html><body style=\"font-family:sans-serif;background:#14101f;" +
                        "color:#eee;display:flex;align-items:center;justify-content:center;height:100vh\">" +
                        "<div style=\"text-align:center\"><h1 style=\"color:#a78bfa\">Orbit</h1>" +
                        "<p>$message</p></div></body></html>"
                    val out = s.getOutputStream()
                    out.write((
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n" + html
                        ).toByteArray())
                    out.flush()
                }
            }
            if (error != null) throw CancelledException()
            val authCode = code ?: throw IllegalStateException(
                "No response from the browser — the sign-in window may have been closed."
            )

            // Exchange the code for tokens; we only need the ID token.
            val form = FormBody.Builder()
                .add("code", authCode)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("redirect_uri", redirect)
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
                .build()
            val req = Request.Builder().url("https://oauth2.googleapis.com/token").post(form).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    OrbitLog.log("google token exchange failed: HTTP ${resp.code} $text")
                    error("Google token exchange failed (HTTP ${resp.code})")
                }
                return JSONObject(text).getString("id_token")
            }
        }
    }
}
