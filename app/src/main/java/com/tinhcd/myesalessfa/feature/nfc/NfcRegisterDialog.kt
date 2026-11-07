package com.tinhcd.myesalessfa.feature.nfc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.R
import com.tinhcd.myesalessfa.core.ui.UiText
import com.tinhcd.myesalessfa.core.ui.theme.status
import com.tinhcd.myesalessfa.core.ui.uiText
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.NfcRegistration
import com.tinhcd.myesalessfa.domain.repository.NfcTagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NfcRegisterUiState(
    val busy: Boolean = false,
    val message: UiText? = null,
    /** The last tap bound the tag (or found it already bound here). */
    val done: Boolean = false,
)

/**
 * Binds a tag to the customer being visited. Lives on the visit's own
 * navigation entry, so the customer comes from the route's arguments.
 */
@HiltViewModel
class NfcRegisterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val nfcTags: NfcTagRepository,
) : ViewModel() {

    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(NfcRegisterUiState())
    val state: StateFlow<NfcRegisterUiState> = _state.asStateFlow()

    fun onTag(uid: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }

        viewModelScope.launch {
            val (message, done) = when (val result = nfcTags.register(customerId, uid)) {
                is DataResult.Success -> when (val r = result.data) {
                    NfcRegistration.Registered -> uiText(R.string.nfc_register_done) to true
                    NfcRegistration.Unchanged -> uiText(R.string.nfc_register_unchanged) to true
                    is NfcRegistration.Taken -> uiText(R.string.nfc_register_taken, r.customerName.orEmpty()) to false
                    NfcRegistration.NoVisit -> uiText(R.string.nfc_register_no_visit) to false
                    NfcRegistration.Invalid -> uiText(R.string.nfc_register_invalid) to false
                }

                is DataResult.Failure -> uiText(R.string.nfc_register_failed) to false
            }
            _state.update { it.copy(busy = false, message = message, done = done) }
        }
    }

    fun reset() = _state.update { NfcRegisterUiState() }
}

/**
 * "Gắn thẻ NFC": hold the phone to the sticker fixed in the shop, and it is
 * bound to this customer. Stays open after a refusal so the rep can try
 * another tag.
 */
@Composable
fun NfcRegisterDialog(
    onDismiss: () -> Unit,
    viewModel: NfcRegisterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val close = {
        viewModel.reset()
        onDismiss()
    }

    NfcTagReader(onTag = viewModel::onTag)

    AlertDialog(
        onDismissRequest = close,
        icon = { Icon(Icons.Default.Nfc, contentDescription = null) },
        title = { Text(stringResource(R.string.nfc_register_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.nfc_register_hint),
                    textAlign = TextAlign.Center,
                )
                if (state.busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                state.message?.let {
                    Text(
                        it.asString(),
                        color = if (state.done) MaterialTheme.status.success else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = close) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}
