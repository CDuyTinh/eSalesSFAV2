package com.tinhcd.myesalessfa.feature.incall.steps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.domain.model.FeedbackRecording

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

    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved -> viewModel.onPhotoTaken(saved) }

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

                PhotoSection(
                    state = state,
                    onAdd = {
                        val target = viewModel.newPhotoTarget()
                        camera.launch(target.uri)
                    },
                    onRemove = viewModel::onRemovePhoto,
                )

                if (state.draft.allowAudio) {
                    AudioSection(
                        state = state,
                        micDenied = micDenied,
                        onRecord = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        onStop = viewModel::stopRecording,
                        onPlay = viewModel::playRecording,
                        onStopPlayback = viewModel::stopPlayback,
                        onRemove = viewModel::onRemoveRecording,
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
 * What the customer is complaining about, photographed.
 *
 * Half of what a rep is told at the counter is about something they are standing in
 * front of — a split case, a rival's new shelf, a chiller that has stopped — and a
 * report with no picture of it is an assertion. `OM_FeedBackCustomerImage` is where
 * these end up.
 */
@Composable
private fun PhotoSection(
    state: FeedbackUiState,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val draft = state.draft

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (draft.photoMin > 0) "Hình ảnh" else "Hình ảnh (tùy chọn)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                draft.photos.forEach { photo ->
                    Box {
                        AsyncImage(
                            model = photo.localPath,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(84.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp),
                                ),
                        )
                        // Nothing is uploaded yet, so a shot the rep does not want
                        // goes with the file behind it.
                        IconButton(
                            onClick = { onRemove(photo.localPath) },
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Bỏ ảnh",
                                    modifier = Modifier
                                        .padding(2.dp)
                                        .size(16.dp),
                                )
                            }
                        }
                    }
                }

                if (draft.canAddPhoto) {
                    OutlinedButton(
                        onClick = onAdd,
                        enabled = !state.capturing && !state.recording,
                        modifier = Modifier.size(84.dp),
                    ) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = "Chụp ảnh")
                    }
                }
            }

            Text(
                if (draft.photosStillNeeded > 0) {
                    "Cần thêm ${draft.photosStillNeeded} ảnh"
                } else {
                    "${draft.photos.size}/${draft.photoMax} ảnh"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (draft.photosStillNeeded > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Record, listen back, or throw it away — several times over.
 *
 * Several because one clip is capped and a customer still talking at the cap should
 * carry on into the next one rather than be cut off; `OM_FeedBackCustomerRecords`
 * holds a list for the same reason. Playback exists because a rep should be able to
 * hear what they are about to send: a recording made in a noisy shop may be unusable,
 * and finding that out at head office is finding it out too late.
 */
@Composable
private fun AudioSection(
    state: FeedbackUiState,
    micDenied: Boolean,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onPlay: (String) -> Unit,
    onStopPlayback: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val draft = state.draft

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

            draft.recordings.forEachIndexed { index, clip ->
                RecordingRow(
                    index = index + 1,
                    clip = clip,
                    playing = state.playingPath == clip.localPath,
                    enabled = !state.recording,
                    onPlay = { onPlay(clip.localPath) },
                    onStopPlayback = onStopPlayback,
                    onRemove = { onRemove(clip.localPath) },
                )
            }

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

                // Off once the step's whole budget is spent, rather than letting the
                // rep record something the server will refuse.
                draft.canRecord -> OutlinedButton(
                    onClick = onRecord,
                    enabled = !state.capturing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text(
                        if (draft.hasAudio) "Ghi thêm" else "Ghi âm",
                        Modifier.padding(start = 6.dp),
                    )
                }

                else -> Text(
                    "Đã ghi đủ thời lượng cho phép",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (micDenied) {
                Text(
                    "Chưa cho phép dùng micro. Vẫn có thể gửi phản hồi bằng chữ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Said plainly, because the alternative is a rep recording a long account
            // and discovering the limit only from a truncated file.
            Text(
                "Mỗi lần tối đa ${draft.audioMaxSeconds / 60} phút, " +
                    "tổng ${draft.audioTotalSeconds / 60} phút. " +
                    "Bản ghi không thay cho phần nội dung.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordingRow(
    index: Int,
    clip: FeedbackRecording,
    playing: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onStopPlayback: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Đoạn $index - ${clip.seconds}s",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = if (playing) onStopPlayback else onPlay,
            enabled = enabled,
        ) {
            Icon(
                if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
            )
            Text(if (playing) "Dừng" else "Nghe", Modifier.padding(start = 6.dp))
        }
        TextButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Default.Delete, contentDescription = "Xoá đoạn $index")
        }
    }
}
