package dev.klaiber.cirrus

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.klaiber.cirrus.ui.CirrusApp
import dev.klaiber.cirrus.ui.SharedPayload
import dev.klaiber.cirrus.ui.theme.CirrusTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val sharedPayload = intent.toSharedPayload()

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            CirrusTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.useDynamicColor,
            ) {
                CirrusApp(sharedPayload = sharedPayload)
            }
        }
    }
}

/** Maps an ACTION_SEND intent onto the payload the chat screen knows how to prefill. */
private fun Intent.toSharedPayload(): SharedPayload {
    if (action != Intent.ACTION_SEND) return SharedPayload()
    return SharedPayload(
        text = getStringExtra(Intent.EXTRA_TEXT),
        imageUri = extraStreamCompat(),
    )
}

@Suppress("DEPRECATION")
private fun Intent.extraStreamCompat(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
