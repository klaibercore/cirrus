package dev.klaiber.cirrus

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.klaiber.cirrus.domain.model.ThemeMode
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

            // The theme is drawn from the moment there is a window; only the navigation graph waits
            // for the store, because where it starts depends on whether setup has happened.
            CirrusTheme(themeMode = settings?.themeMode ?: ThemeMode.SYSTEM) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    settings?.let { loaded ->
                        CirrusApp(
                            sharedPayload = sharedPayload,
                            startWithSetup = !loaded.onboardingCompleted,
                        )
                    }
                }
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
