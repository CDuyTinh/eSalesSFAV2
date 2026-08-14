package com.tinhcd.myesalessfa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyeSalesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val viewModel: RootViewModel = hiltViewModel()
                    val start by viewModel.startDestination.collectAsStateWithLifecycle()

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
            val user = authRepository.currentUser.first()
            _startDestination.value = if (user == null) Routes.LOGIN else Routes.ROUTE
        }
    }
}
