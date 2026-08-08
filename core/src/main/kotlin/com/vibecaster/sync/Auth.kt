package com.vibecaster.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Firebase project identifiers. The key values are injected at build time
 * from secrets.properties (gitignored) via the generated [OrbitSecrets] —
 * nothing sensitive is committed to the repo. Env vars override everything.
 */
object FirebaseConfig {
    val projectId: String =
        System.getenv("ORBIT_FIREBASE_PROJECT_ID") ?: "orbit-f03fc"
    val apiKey: String =
        System.getenv("ORBIT_FIREBASE_API_KEY")?.takeIf { it.isNotBlank() }
            ?: OrbitSecrets.FIREBASE_API_KEY

    /** OAuth "Web client" id — used by Android's Credential Manager Google Sign-In. */
    val GOOGLE_WEB_CLIENT_ID: String =
        System.getenv("ORBIT_GOOGLE_WEB_CLIENT_ID")?.takeIf { it.isNotBlank() }
            ?: OrbitSecrets.GOOGLE_WEB_CLIENT_ID
}

data class AuthSession(
    val idToken: String,
    val refreshToken: String,
    val uid: String,
    val email: String,
    val emailVerified: Boolean,
    val displayName: String?,
    /** Epoch ms when idToken expires (Firebase tokens live ~1h). */
    val expiresAtMs: Long
) {
    val firstName: String? get() = displayName?.substringBefore(" ")

    fun toJson(): String = JSONObject()
        .put("idToken", idToken).put("refreshToken", refreshToken)
        .put("uid", uid).put("email", email)
        .put("emailVerified", emailVerified)
        .put("displayName", displayName ?: JSONObject.NULL)
        .put("expiresAtMs", expiresAtMs).toString()

    companion object {
        fun fromJson(s: String): AuthSession? = try {
            val o = JSONObject(s)
            AuthSession(
                idToken = o.getString("idToken"),
                refreshToken = o.getString("refreshToken"),
                uid = o.getString("uid"),
                email = o.optString("email", ""),
                emailVerified = o.optBoolean("emailVerified", false),
                displayName = if (o.isNull("displayName")) null else o.getString("displayName"),
                expiresAtMs = o.optLong("expiresAtMs", 0L)
            )
        } catch (_: Exception) { null }
    }
}

class AuthException(val code: String, message: String) : Exception(message)

/**
 * True when the failure is connectivity (no internet / DNS / timeout) rather
 * than a server "no" — the UIs show a friendly offline dialog for these.
 */
fun isNetworkError(t: Throwable?): Boolean {
    var c = t
    var depth = 0
    while (c != null && depth < 8) {
        if (c is java.io.IOException && c !is AuthException) return true
        c = c.cause; depth++
    }
    return false
}

/**
 * Firebase Authentication over plain REST (identitytoolkit) — the SAME code
 * runs on Android and desktop, no Firebase SDK needed anywhere.
 *
 * Persistence is injected so each platform stores the session its own way
 * (SharedPreferences on Android, %APPDATA%\Orbit\auth.json on desktop).
 */
class AuthManager(
    private val persist: (String?) -> Unit,
    restore: () -> String?
) {
    @Volatile
    var session: AuthSession? = restore()?.let { AuthSession.fromJson(it) }
        private set

    val isSignedIn: Boolean get() = session != null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun idpUrl(op: String) =
        "https://identitytoolkit.googleapis.com/v1/accounts:$op?key=${FirebaseConfig.apiKey}"

    private fun post(url: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                val code = json.optJSONObject("error")?.optString("message") ?: "HTTP_${resp.code}"
                throw AuthException(code, friendlyError(code))
            }
            return json
        }
    }

    private fun friendlyError(code: String): String = when {
        code.startsWith("EMAIL_EXISTS") -> "An account with this email already exists — sign in instead."
        code.startsWith("EMAIL_NOT_FOUND") -> "No account found with this email."
        code.startsWith("INVALID_PASSWORD") ||
            code.startsWith("INVALID_LOGIN_CREDENTIALS") -> "Wrong email or password."
        code.startsWith("WEAK_PASSWORD") -> "Password is too weak — use at least 6 characters."
        code.startsWith("INVALID_EMAIL") -> "That doesn't look like a valid email address."
        code.startsWith("USER_DISABLED") -> "This account has been disabled."
        code.startsWith("TOO_MANY_ATTEMPTS") -> "Too many attempts — try again in a few minutes."
        code.startsWith("INVALID_IDP_RESPONSE") -> "Google sign-in failed — try again."
        else -> "Sign-in failed ($code). Check your connection and try again."
    }

    private fun sessionFrom(o: JSONObject): AuthSession {
        val expiresIn = o.optString("expiresIn", "3600").toLongOrNull() ?: 3600L
        return AuthSession(
            idToken = o.getString("idToken"),
            refreshToken = o.getString("refreshToken"),
            uid = o.getString("localId"),
            email = o.optString("email", ""),
            emailVerified = o.optBoolean("emailVerified", false),
            displayName = o.optString("displayName").ifBlank { null },
            expiresAtMs = System.currentTimeMillis() + (expiresIn - 300) * 1000
        )
    }

    private fun store(s: AuthSession?) {
        session = s
        runCatching { persist(s?.toJson()) }
    }

    suspend fun signUpEmail(email: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        val o = post(idpUrl("signUp"), JSONObject()
            .put("email", email.trim()).put("password", password).put("returnSecureToken", true))
        sessionFrom(o).also { store(it) }
    }

    suspend fun signInEmail(email: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        val o = post(idpUrl("signInWithPassword"), JSONObject()
            .put("email", email.trim()).put("password", password).put("returnSecureToken", true))
        sessionFrom(o).also { store(it) }
    }

    /** Sign in with a Google ID token (from Android Credential Manager). */
    suspend fun signInGoogle(googleIdToken: String): AuthSession = withContext(Dispatchers.IO) {
        val o = post(idpUrl("signInWithIdp"), JSONObject()
            .put("postBody", "id_token=$googleIdToken&providerId=google.com")
            .put("requestUri", "http://localhost")
            .put("returnSecureToken", true)
            .put("returnIdpCredential", true))
        sessionFrom(o).also { store(it) }
    }

    /**
     * Emails a password-reset link. Fallback only — Firebase's default emails
     * often land in spam, so the primary flow is [setPassword] (in-app, no
     * email at all).
     */
    suspend fun sendPasswordReset(email: String) = withContext(Dispatchers.IO) {
        post(idpUrl("sendOobCode"), JSONObject()
            .put("requestType", "PASSWORD_RESET").put("email", email.trim()))
        Unit
    }

    /**
     * Sets/changes the signed-in account's password directly — NO email
     * involved. This is how a Google-signed-in phone user adds a password so
     * the same account works on desktop. Firebase may answer
     * CREDENTIAL_TOO_OLD_LOGIN_AGAIN if the sign-in is old; the caller should
     * ask the user to sign in again in that case.
     */
    suspend fun setPassword(newPassword: String): Unit = withContext(Dispatchers.IO) {
        val token = freshIdToken() ?: throw AuthException("NOT_SIGNED_IN", "Sign in first.")
        val o = try {
            post(idpUrl("update"), JSONObject()
                .put("idToken", token).put("password", newPassword)
                .put("returnSecureToken", true))
        } catch (e: AuthException) {
            if (e.code.startsWith("CREDENTIAL_TOO_OLD")) throw AuthException(
                e.code, "For security, sign out and sign in again, then set the password."
            ) else throw e
        }
        // update returns fresh tokens — keep the session alive with them
        val s = session
        if (s != null && o.has("idToken") && o.has("refreshToken")) {
            val expiresIn = o.optString("expiresIn", "3600").toLongOrNull() ?: 3600L
            store(s.copy(
                idToken = o.getString("idToken"),
                refreshToken = o.getString("refreshToken"),
                expiresAtMs = System.currentTimeMillis() + (expiresIn - 300) * 1000
            ))
        }
    }

    /** Returns a valid (refreshed if needed) idToken, or null when signed out. */
    suspend fun freshIdToken(): String? = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext null
        if (System.currentTimeMillis() < s.expiresAtMs) return@withContext s.idToken
        try {
            val body = "grant_type=refresh_token&refresh_token=${s.refreshToken}"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url("https://securetoken.googleapis.com/v1/token?key=${FirebaseConfig.apiKey}")
                .post(body).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // Refresh token revoked/expired -> sign the user out cleanly.
                    store(null); return@withContext null
                }
                val o = JSONObject(text)
                val expiresIn = o.optString("expires_in", "3600").toLongOrNull() ?: 3600L
                val updated = s.copy(
                    idToken = o.getString("id_token"),
                    refreshToken = o.optString("refresh_token", s.refreshToken),
                    expiresAtMs = System.currentTimeMillis() + (expiresIn - 300) * 1000
                )
                store(updated)
                updated.idToken
            }
        } catch (_: Exception) {
            null // network hiccup: caller just skips this sync round
        }
    }

    fun signOut() = store(null)
}
