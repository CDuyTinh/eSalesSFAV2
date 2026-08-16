package com.tinhcd.myesalessfa

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.navigation.AppNavHost
import com.tinhcd.myesalessfa.navigation.Routes
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Held long enough to hide the hand-off into the first real screen, and no longer.
 *
 * Restoring the session can involve a network call, and the HTTP client allows twenty
 * seconds to connect. Keeping the splash up for that would look like a frozen launch,
 * so once this budget passes the app falls through to its ordinary loading state, which
 * at least says out loud that it is still working.
 */
private const val SPLASH_HOLD_MS = 800L

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Owned by the activity rather than created inside setContent, because the splash
     * has to read its state from outside the composition — the keep-on-screen condition
     * is evaluated before there is any composition to read from.
     */
    private val rootViewModel: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        val launchedAt = SystemClock.elapsedRealtime()
        splash.setKeepOnScreenCondition {
            rootViewModel.startDestination.value == null &&
                SystemClock.elapsedRealtime() - launchedAt < SPLASH_HOLD_MS
        }

        enableEdgeToEdge()
        setContent {
            MyeSalesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val start by rootViewModel.startDestination.collectAsStateWithLifecycle()

                    // Null while the stored session is being restored. Showing
                    // the login form first and yanking it away a moment later
                    // is worse than a brief spinner.
                    when (val destination = start) {
                        null -> LoadingBox(Modifier.padding(padding))
                        else -> AppNavHost(startDestination = destination)
                    }
                }
            }
        }
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    authRepository: AuthRepository,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination: StateFlow<String?> = _startDestination.asStateFlow()

    init {
        viewModelScope.launch {
            // Keyed on whether a session exists, not on whether the rep's profile
            // arrived. Those were the same question when this read a nullable rep,
            // and a single failed /bootstrap at launch therefore looked identical to
            // being signed out — a rep with a good session got the login screen and
            // a wrong explanation. A session with no profile still belongs on the
            // route screen, which says so and offers to retry.
            _startDestination.value = when (authRepository.sessionState.first()) {
                is SessionState.SignedOut -> Routes.LOGIN
                is SessionState.SignedIn -> Routes.SHELL
            }
        }
    }
}
