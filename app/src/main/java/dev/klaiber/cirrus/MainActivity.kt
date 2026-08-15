package dev.klaiber.cirrus

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

    /**
     * The redirect Android has most recently delivered, as state the composition can watch.
     *
     * A `MutableState` rather than a field, because the OAuth return arrives through
     * [onNewIntent] — a callback on the Activity, outside any composition — and the thing that has
     * to react to it is a ViewModel obtained inside one.
     */
    private var redirect by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val sharedPayload = intent.toSharedPayload()
        // A cold start from the redirect is the case where the browser trip outlived the process.
        redirect = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val signInResult by viewModel.signInResult.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(redirect) {
                redirect?.let { uri ->
                    viewModel.onRedirect(uri)
                    redirect = null
                }
            }

            // Shown here rather than on the settings screen, because coming back from the browser
            // can land on any screen — including a cold start, where the settings screen that
            // began the sign-in no longer exists.
            LaunchedEffect(signInResult) {
                signInResult?.let { message ->
                    snackbarHostState.showSnackbar(message)
                    viewModel.clearSignInResult()
                }
            }

            // The theme is drawn from the moment there is a window; only the navigation graph waits
            // for the store, because where it starts depends on whether setup has happened.
            CirrusTheme(themeMode = settings?.themeMode ?: ThemeMode.SYSTEM) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize()) {
                    settings?.let { loaded ->
                        CirrusApp(
                            sharedPayload = sharedPayload,
                            startWithSetup = !loaded.onboardingCompleted,
                        )
                    }
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    }
                }
            }
        }
    }

    /** The ordinary case: Cirrus was still alive behind the browser. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) redirect = intent.data
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
