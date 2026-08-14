package com.tinhcd.myesalessfa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyeSalesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    StartupScreen(modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

/**
 * Placeholder while the Login -> Route -> Check-in slice is built. It exists to
 * prove the wiring end to end: Hilt builds the ViewModel, which reaches
 * :domain, which is backed by the Supabase implementation in :data.
 */
@Composable
private fun StartupScreen(modifier: Modifier = Modifier) {
    val viewModel: StartupViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("eSales SFA", style = MaterialTheme.typography.titleLarge)
        Text(state.message, style = MaterialTheme.typography.bodyLarge)
    }
}
