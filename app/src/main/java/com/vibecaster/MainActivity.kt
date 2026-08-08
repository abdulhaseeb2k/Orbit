package com.vibecaster

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.vibecaster.ui.AppRoot
import com.vibecaster.ui.theme.VibeCasterTheme

class MainActivity : ComponentActivity() {

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Permissions are requested in context by the onboarding flow
        // (report §4) instead of an unexplained popup at launch.
        val viewModel: MainViewModel by viewModels()
        setContent {
            val mode by viewModel.themeMode.collectAsStateWithLifecycle()
            VibeCasterTheme(mode) {
                AppRoot(viewModel)
            }
        }
    }
}
