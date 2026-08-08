package com.vibecaster.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.vibecaster.MainViewModel
import com.vibecaster.sync.FirebaseConfig
import com.vibecaster.ui.theme.Cyan
import com.vibecaster.ui.theme.LocalVibePalette
import com.vibecaster.ui.theme.Pink
import com.vibecaster.ui.theme.Violet
import com.vibecaster.ui.theme.VioletDeep
import kotlinx.coroutines.launch

/**
 * Sign in / account management. Fully optional — "No account needed. Ever."
 * stays true; an account only adds cross-device playlist sync.
 */
@UnstableApi
@Composable
fun AccountScreen(vm: MainViewModel) {
    val user by vm.authUser.collectAsStateWithLifecycle()
    val busy by vm.syncBusy.collectAsStateWithLifecycle()
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val autoDl by vm.autoDownloadSynced.collectAsStateWithLifecycle()

    val palette = LocalVibePalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgMid, palette.bgBottom)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.dismissAccount() }) {
                    Text(if (user == null) "Not now" else "Done",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))

            // Orbit badge
            Box(
                Modifier
                    .size(84.dp)
                    .background(Brush.linearGradient(listOf(VioletDeep, Pink)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("8", style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black, color = Color.White)
            }
            Spacer(Modifier.height(20.dp))

            if (user == null) SignInBody(vm, busy, status)
            else SignedInBody(vm, user?.email.orEmpty(), busy, status, autoDl)

            Spacer(Modifier.height(32.dp))
        }
    }

    // Friendly offline dialog (Orbit style) for user-initiated actions.
    val showNetError by vm.showNetworkError.collectAsStateWithLifecycle()
    if (showNetError) NetworkErrorDialog(onDismiss = { vm.dismissNetworkError() })
}

/** "No internet" popup — same design language as OrbitConfirmDialog. */
@Composable
fun NetworkErrorDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(24.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .background(Brush.linearGradient(listOf(VioletDeep, Cyan)), CircleShape)
                ) {
                    Icon(Icons.Rounded.WifiOff, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("No internet connection",
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Orbit couldn't reach the server. Check your Wi-Fi or mobile data and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) { Text("Got it", fontWeight = FontWeight.Bold, color = Color.White) }
            }
        }
    }
}

/** "just now" / "5m ago" / "3h ago" / "2d ago" */
private fun agoText(thenMs: Long): String {
    if (thenMs <= 0) return "recently"
    val mins = (System.currentTimeMillis() - thenMs) / 60_000
    return when {
        mins < 2 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 48 * 60 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

/** Compose's LocalContext can be a wrapper — Credential Manager needs the Activity. */
private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Google sign-in, three attempts in order — each next one is more "visible":
 * 1. Accounts already authorized for Orbit (one-tap, auto-select).
 * 2. Any Google account on the phone (bottom sheet).
 * 3. The full "Sign in with Google" account picker (works even when the
 *    bottom-sheet flow reports NoCredentialException, which is common).
 * Only NoCredentialException advances the chain; a user cancel stops it.
 */
private suspend fun requestGoogleCredential(context: Context): GetCredentialResponse {
    val cm = CredentialManager.create(context)
    val attempts: List<CredentialOption> = listOf(
        GetGoogleIdOption.Builder()
            .setServerClientId(FirebaseConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .build(),
        GetGoogleIdOption.Builder()
            .setServerClientId(FirebaseConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .build(),
        GetSignInWithGoogleOption.Builder(FirebaseConfig.GOOGLE_WEB_CLIENT_ID).build()
    )
    var last: Exception? = null
    for ((i, option) in attempts.withIndex()) {
        try {
            return cm.getCredential(
                context,
                GetCredentialRequest.Builder().addCredentialOption(option).build()
            )
        } catch (e: NoCredentialException) {
            Log.w("OrbitAuth", "Google attempt ${i + 1} -> NoCredential, trying next")
            last = e
        }
    }
    throw last ?: NoCredentialException("No Google credential available")
}

/** Turns Credential Manager's cryptic failures into something actionable. */
private fun googleErrorHint(e: Exception): String {
    val msg = e.message.orEmpty()
    return when {
        e is NoCredentialException ->
            "No Google account found on this phone. Add one in Android Settings → " +
                "Passwords & accounts, or use email sign-in below."
        msg.contains("28444") || msg.contains("Developer console", ignoreCase = true) ->
            "This build isn't authorized for Google sign-in (SHA-1 mismatch in Firebase). " +
                "Use email sign-in, and rebuild after checking the fingerprints."
        msg.contains("Play Services", ignoreCase = true) || msg.contains("GmsCore", ignoreCase = true) ->
            "Google Play Services needs an update on this phone — update it in the Play Store, then try again."
        else -> "Google sign-in failed: ${msg.ifBlank { e.javaClass.simpleName }}"
    }
}

// Official Google "G" (the real 4-color mark, from Google's sign-in assets).
private val G_SEGMENTS = listOf(
    "M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844c-.209 1.125-.843 2.078-1.796 2.717v2.258h2.908c1.702-1.567 2.684-3.874 2.684-6.615z" to Color(0xFF4285F4),
    "M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332C2.438 15.983 5.482 18 9 18z" to Color(0xFF34A853),
    "M3.964 10.71c-.18-.54-.282-1.117-.282-1.71s.102-1.17.282-1.71V4.958H.957C.347 6.173 0 7.548 0 9s.348 2.827.957 4.042l3.007-2.332z" to Color(0xFFFBBC05),
    "M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0 5.482 0 2.438 2.017.957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58z" to Color(0xFFEA4335)
)

@Composable
private fun GoogleLogo(size: Dp) {
    val paths = remember {
        G_SEGMENTS.map { (d, c) -> PathParser().parsePathString(d).toPath() to c }
    }
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 18f
        scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            paths.forEach { (p, c) -> drawPath(p, c) }
        }
    }
}

@UnstableApi
@Composable
private fun SignInBody(vm: MainViewModel, busy: Boolean, status: String?) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    /** True from the moment the button is tapped until the flow ends —
     *  so the user sees a spinner and can't tap twice. */
    var googleLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun googleSignIn() {
        if (googleLoading) return
        googleLoading = true
        scope.launch {
            try {
                val activity = context.findActivity() ?: context
                val result = requestGoogleCredential(activity)
                val cred = result.credential
                if (cred is CustomCredential &&
                    cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    vm.signInWithGoogleToken(GoogleIdTokenCredential.createFrom(cred.data).idToken)
                } else {
                    Log.e("OrbitAuth", "Unexpected credential type: ${cred.type}")
                    vm.reportAuthError("Google sign-in returned an unexpected credential.")
                }
            } catch (_: GetCredentialCancellationException) {
                // user closed the sheet — not an error
            } catch (e: Exception) {
                Log.e("OrbitAuth", "Google sign-in failed", e)
                if (com.vibecaster.sync.isNetworkError(e)) vm.reportNetworkError()
                else vm.reportAuthError(googleErrorHint(e))
            } finally {
                googleLoading = false
            }
        }
    }

    Text("Sync your Orbit", style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        "One account — the same playlists on every device you sign in on, " +
            "downloaded automatically. Optional, free.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))

    // Official Google button style: white pill, thin outline, real G mark.
    Button(
        onClick = { googleSignIn() },
        enabled = !busy && !googleLoading,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.85f)
        ),
        border = BorderStroke(1.dp, Color(0xFF747775)),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        if (googleLoading || busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color(0xFF4285F4), strokeWidth = 2.dp
            )
            Spacer(Modifier.width(12.dp))
            Text("Signing in…", color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium)
        } else {
            GoogleLogo(20.dp)
            Spacer(Modifier.width(12.dp))
            Text("Continue with Google", color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium)
        }
    }

    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text("  or with email  ", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
    Spacer(Modifier.height(18.dp))

    OutlinedTextField(
        value = email, onValueChange = { email = it },
        label = { Text("Email") }, singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = password, onValueChange = { password = it },
        label = { Text(if (creating) "Choose a password (6+ characters)" else "Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))

    Button(
        onClick = { vm.signInEmail(email.trim(), password, isNew = creating) },
        enabled = !busy && email.isNotBlank() && password.isNotBlank(),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Violet),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        if (busy) CircularProgressIndicator(
            Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(if (creating) "Create account" else "Sign in",
            fontWeight = FontWeight.Bold, color = Color.White)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { creating = !creating }) {
            Text(if (creating) "I already have an account" else "New here? Create an account",
                color = Cyan, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { if (email.isNotBlank()) vm.sendPasswordReset(email.trim()) }) {
            Text("Forgot password?", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    status?.let {
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Password link")) Cyan
                        else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp))
        }
    }
}

@UnstableApi
@Composable
private fun SignedInBody(
    vm: MainViewModel, email: String, busy: Boolean, status: String?, autoDl: Boolean
) {
    Text("You're synced", style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(email, style = MaterialTheme.typography.bodyMedium, color = Cyan)
    Spacer(Modifier.height(24.dp))

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudSync, null, tint = Violet)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Playlists & history", style = MaterialTheme.typography.titleSmall)
                    Text(status ?: "Sync automatically on every launch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { vm.syncNow(manual = true) }) { Text("Sync now", color = Cyan) }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-download synced playlists",
                        style = MaterialTheme.typography.titleSmall)
                    Text("Songs added on other devices download here by themselves",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoDl, onCheckedChange = { vm.setAutoDownloadSynced(it) })
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    // Devices signed in to this account — see them all, kick any of them.
    val devices by vm.devices.collectAsStateWithLifecycle()
    val devicesLoading by vm.devicesLoading.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadDevices() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your devices", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                if (devicesLoading) CircularProgressIndicator(
                    Modifier.size(16.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { vm.loadDevices() }) {
                    Icon(Icons.Rounded.Refresh, "Refresh devices",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (devices.isEmpty() && !devicesLoading) {
                Text("Device list appears after the first sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            devices.forEach { d ->
                val isThis = d.id == vm.thisDeviceId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Icon(
                        if (d.platform == "android") Icons.Rounded.Smartphone
                        else Icons.Rounded.Computer,
                        contentDescription = d.platform,
                        tint = if (isThis) Cyan else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isThis) "${d.name} • this device" else d.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isThis) Cyan else MaterialTheme.colorScheme.onSurface
                        )
                        Text("Active ${agoText(d.lastSeenMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!isThis) {
                        TextButton(onClick = { vm.revokeDevice(d) }) {
                            Text("Sign out", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Text(
                "A signed-out device disconnects the next time it opens the app or syncs.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(14.dp))

    // Desktop password — set IN-APP (no email, so nothing lands in spam).
    var desktopPw by remember { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Password, null, tint = Violet)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Desktop password", style = MaterialTheme.typography.titleSmall)
                    Text("Set it here — then sign in on your PC with your email + this password. No emails involved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = desktopPw, onValueChange = { desktopPw = it },
                    label = { Text("New password (6+)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { vm.setDesktopPassword(desktopPw); desktopPw = "" },
                    enabled = !busy && desktopPw.length >= 6,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet)
                ) { Text("Save", color = Color.White) }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    OutlinedButton(
        onClick = { vm.signOut() },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Rounded.AccountCircle, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text("Sign out", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        "Signing out keeps everything on this phone — it only stops syncing.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp)
    )
}
