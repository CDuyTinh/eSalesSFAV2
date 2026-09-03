package com.tinhcd.myesalessfa.feature.incall.steps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onDone: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    // Asked for when the rep first reaches for the microphone rather than on entry:
    // most feedback is typed, and a permission dialog in front of a form nobody
    // intends to record into is the kind of prompt people learn to dismiss.
    var micDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micDenied = !granted
        if (granted) viewModel.startRecording()
    }

    Scaffold(
        topBar = {
            StepHeader(
                title = state.title.ifBlank { "Phản hồi khách hàng" },
                onBack = onDone,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (state.loading) {
                LoadingBox()
                return@Box
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.topics.isNotEmpty()) {
                    Text(
                        "Nội dung phản hồi về",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    // Optional on purpose: a rep must never be unable to report
                    // something because head office has not classified it yet.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.topics.forEach { topic ->
                            FilterChip(
                                selected = state.draft.topicId == topic.id,
                                onClick = { viewModel.onTopicChange(topic.id) },
                                label = { Text(topic.name) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.draft.note,
                    onValueChange = viewModel::onNoteChange,
                    label = { Text("Khách hàng nói gì") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.draft.charsStillNeeded > 0) {
                    Text(
                        "Còn thiếu ${state.draft.charsStillNeeded} ký tự",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.draft.allowAudio) {
                    AudioSection(
                        state = state,
                        micDenied = micDenied,
                        onRecord = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        onStop = viewModel::stopRecording,
                        onPlay = viewModel::playRecording,
                        onStopPlayback = viewModel::stopPlayback,
                        onDelete = viewModel::deleteRecording,
                    )
                }

                state.error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                PrimaryButton(
                    text = if (state.submitting) "Đang lưu..." else "Hoàn thành bước này",
                    onClick = viewModel::submit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                )

                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Quay lại")
                }
            }
        }
    }
}

/**
 * Record, listen back, or throw it away.
 *
 * Playback exists because a rep should be able to hear what they are about to send:
 * a recording made in a noisy shop may be unusable, and finding that out at head
 * office is finding it out too late.
 */
@Composable
private fun AudioSection(
    state: FeedbackUiState,
    micDenied: Boolean,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onPlay: () -> Unit,
    onStopPlayback: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Ghi âm (tùy chọn)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            when {
                state.recording -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Đang ghi ${state.recordingSeconds}s",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text("Dừng", Modifier.padding(start = 6.dp))
                    }
                }

                state.draft.hasAudio -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Đã ghi ${state.draft.audioSeconds}s",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = if (state.playing) onStopPlayback else onPlay) {
                        Icon(
                            if (state.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                        )
                        Text(if (state.playing) "Dừng" else "Nghe", Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Xoá bản ghi")
                    }
                }

                else -> OutlinedButton(onClick = onRecord, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text("Ghi âm", Modifier.padding(start = 6.dp))
                }
            }

            if (micDenied) {
                Text(
                    "Chưa cho phép dùng micro. Vẫn có thể gửi phản hồi bằng chữ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Said plainly, because the alternative is a rep recording a two-minute
            // account and discovering the limit only from a truncated file.
            Text(
                "Tối đa 2 phút. Bản ghi không thay cho phần nội dung.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
