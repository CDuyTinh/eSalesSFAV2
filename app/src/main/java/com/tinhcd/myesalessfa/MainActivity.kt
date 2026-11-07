package com.tinhcd.myesalessfa

import android.content.Context
import android.content.Intent
import com.tinhcd.myesalessfa.push.PushRoute
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.LanguageSettings
import com.tinhcd.myesalessfa.core.ui.theme.ThemeSettings
import com.tinhcd.myesalessfa.core.ui.theme.isDark
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
 * The least time the splash stays up. A session restored from disk resolves at once,
 * and the splash then flashed past too fast to read as anything but a flicker.
 */
private const val SPLASH_MIN_MS = 1_200L

/**
 * The most it waits for the session to be restored, which can involve a network call
 * (the HTTP client allows twenty seconds to connect). Past this the app shows a copy
 * of the splash it draws itself, rather than holding the system's for so long it
 * looks frozen.
 */
private const val SPLASH_MAX_MS = 8_000L

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // singleTop: a notification tapped while the app is open arrives here
    // instead of starting a second copy of the activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PushRoute.from(intent)
    }

    /**
     * Owned by the activity rather than created inside setContent, because the splash
     * has to read its state from outside the composition — the keep-on-screen condition
     * is evaluated before there is any composition to read from.
     */
    private val rootViewModel: RootViewModel by viewModels()

    @Inject lateinit var themeSettings: ThemeSettings

    // Ngôn ngữ: every resource this activity reads comes from a context set to
    // the app's language, not the phone's.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageSettings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        PushRoute.from(intent)

        val launchedAt = SystemClock.elapsedRealtime()
        splash.setKeepOnScreenCondition {
            val elapsed = SystemClock.elapsedRealtime() - launchedAt
            elapsed < SPLASH_MIN_MS || (rootViewModel.startDestination.value == null && elapsed < SPLASH_MAX_MS)
        }

        // Every screen opens on the brand-blue header, in light theme too, so the
        // status bar icons are always the light ones; the default picks dark
        // icons for a light theme and they vanish into the blue.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            val mode by themeSettings.mode.collectAsStateWithLifecycle()
            val dark = mode.isDark()
            // The navigation bar follows the app's choice, not the phone's: a rep
            // who picked Tối on a light phone would otherwise get dark buttons on
            // a dark bar.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            MyeSalesTheme(darkTheme = dark) {
                val start by rootViewModel.startDestination.collectAsStateWithLifecycle()

                // Null while the stored session is being restored. Showing the
                // login form first and yanking it away a moment later would be
                // worse; so is a spinner after the splash. The splash continues.
                when (val destination = start) {
                    null -> SplashHold()
                    // The background only: every screen draws its own bars and insets,
                    // so a Scaffold here had no padding to give.
                    else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        AppNavHost(startDestination = destination)
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

/**
 * The splash, drawn by the app: the same background and mark, at the size the
 * system shows it, so passing from one to the other cannot be seen. Only
 * reached when restoring the session outlasts SPLASH_MAX_MS.
 */
@Composable
private fun SplashHold() {
    Box(
        Modifier.fillMaxSize().background(colorResource(R.color.splash_background)),
        contentAlignment = Alignment.Center,
    ) {
        // The system shows ic_launcher_foreground (this mark at 70%) in a 288dp
        // icon box; the mark's own canvas puts the shelf in 60 of 108 units.
        Image(painterResource(R.drawable.ic_splash_logo), contentDescription = null, modifier = Modifier.size(202.dp))
    }
}
