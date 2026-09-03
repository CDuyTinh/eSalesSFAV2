package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton

@Composable
fun NoteStepScreen(
    onDone: () -> Unit,
    viewModel: NoteStepViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    Scaffold(
        topBar = {
            StepHeader(
                title = state.title.ifBlank { "Bước công việc" },
                onBack = onDone,
            )
        },
    ) { padding ->
        if (state.loading) {
            // The step's own rules decide whether the note is mandatory, so the
            // form cannot be shown before they are known.
            LoadingBox(Modifier.padding(padding))
        } else {
            NoteForm(
                state = state,
                onNoteChange = viewModel::onNoteChange,
                onSubmit = viewModel::submit,
                onBack = onDone,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun NoteForm(
    state: NoteStepUiState,
    onNoteChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (state.isRequired) {
                "Ghi nhận nội dung tại điểm bán (bắt buộc)"
            } else {
                "Ghi nhận nội dung tại điểm bán"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val remaining = state.requiredLength - state.note.trim().length
        OutlinedTextField(
            value = state.note,
            onValueChange = onNoteChange,
            label = { Text("Nội dung") },
            minLines = 4,
            isError = state.error != null,
            // Says why the button is dead instead of leaving the rep guessing.
            supportingText = if (remaining > 0) {
                { Text("Còn thiếu $remaining ký tự") }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        )

        if (state.error != null) {
            Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        PrimaryButton(
            text = "Hoàn thành bước này",
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.submitting,
        )

        // An optional step stays skippable; leaving without finishing a required
        // one is allowed too, it just keeps check-out closed.
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Quay lại") }

        if (state.isRequired) {
            Text(
                "Bước bắt buộc: chưa hoàn thành sẽ không check-out được",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
