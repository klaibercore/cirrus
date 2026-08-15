package dev.klaiber.cirrus

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.spotify.SpotifySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Supplies the configuration needed before any screen is composed.
 *
 * Null means "not read yet", and the distinction matters: the defaults say onboarding has not been
 * done, so treating an unread store as the truth would flash the welcome wizard at every existing
 * user for one frame on every cold start.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val spotify: SpotifySession,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _signInResult = MutableStateFlow<String?>(null)

    /** One line to show once, after coming back from Spotify's consent screen. */
    val signInResult: StateFlow<String?> = _signInResult.asStateFlow()

    /**
     * Finishes a Spotify sign-in from the redirect Android just handed us.
     *
     * Spotify can come back with `error=access_denied` instead of a code — somebody pressed Cancel,
     * which is not a failure and should not be reported as one.
     */
    fun onRedirect(uri: Uri) {
        if (uri.scheme != "cirrus" || uri.host != "spotify") return

        val error = uri.getQueryParameter("error")
        if (error != null) {
            _signInResult.value = if (error == "access_denied") {
                "Spotify sign-in cancelled."
            } else {
                "Spotify refused the sign-in: $error"
            }
            return
        }

        val code = uri.getQueryParameter("code") ?: return
        val state = uri.getQueryParameter("state").orEmpty()
        viewModelScope.launch {
            _signInResult.value = spotify.completeSignIn(code, state).fold(
                onSuccess = { name -> "Connected to Spotify as $name." },
                onFailure = { failure -> failure.message ?: "Could not finish the Spotify sign-in." },
            )
        }
    }

    fun clearSignInResult() {
        _signInResult.value = null
    }
}
