package com.tinhcd.myesalessfa.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.ui.theme.AppLanguage
import com.tinhcd.myesalessfa.core.ui.theme.LanguageSettings
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class LanguageUiState(
    val language: AppLanguage,
    /** Set once the new language is stored and its menu labels fetched: time to redraw. */
    val switched: Boolean = false,
    val switching: Boolean = false,
)

@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val settings: LanguageSettings,
    private val config: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LanguageUiState(settings.language))
    val state: StateFlow<LanguageUiState> = _state.asStateFlow()

    fun select(language: AppLanguage) {
        if (language == _state.value.language || _state.value.switching) return
        settings.set(language)
        _state.value = _state.value.copy(language = language, switching = true)
        viewModelScope.launch {
            // The menus' labels come from the server in the language asked for.
            // Fetched before the redraw so the menus do not come back in the old
            // language; a slow or missing network is not worth holding the
            // switch for, and the next config refresh will catch up.
            withTimeoutOrNull(4_000) { config.refresh() }
            _state.value = _state.value.copy(switching = false, switched = true)
        }
    }

    /** The redraw was asked for. The ViewModel outlives the recreated activity, so without this it would ask again on every one. */
    fun onSwitched() {
        _state.value = _state.value.copy(switched = false)
    }
}

/** Ngôn ngữ / Language: Vietnamese or English, each written in itself. */
@Composable
fun LanguageCard(viewModel: LanguageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.switched) {
        if (state.switched) {
            viewModel.onSwitched()
            context.findActivity()?.recreate()
        }
    }
    LanguageCard(language = state.language, enabled = !state.switching, onSelect = viewModel::select)
}

@Composable
fun LanguageCard(language: AppLanguage, enabled: Boolean = true, onSelect: (AppLanguage) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppLanguage.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == language,
                        onClick = { onSelect(option) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, AppLanguage.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        label = { Text(option.nativeName) },
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
