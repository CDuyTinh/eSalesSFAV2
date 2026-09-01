package com.tinhcd.myesalessfa.feature.incall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.domain.model.WorkflowStep

/**
 * Công việc — the steps this visit still owes, and the check-out that closes it.
 *
 * A tab rather than a screen of its own since the customer hub took over. It
 * only exists while a visit is open, so it is composed conditionally: its view
 * model reads a visit id that does not exist before check-in.
 */
@Composable
fun InCallTab(
    onOpenStep: (formId: String) -> Unit,
    onCheckedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InCallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Re-read on every entry: coming back from a step must reflect it.
    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(state.checkedOut) {
        if (state.checkedOut) onCheckedOut()
    }

    Box(modifier.fillMaxSize()) {
        when {
            state.loading -> LoadingBox()
            state.workflow == null ->
                ErrorBox(state.error ?: "Không có dữ liệu", onRetry = viewModel::load)

            else -> {
                val workflow = state.workflow!!
                Column(Modifier.fillMaxSize()) {
                    // Was the app bar subtitle before this became a tab. Kept,
                    // because it is the one line that says how much of the visit
                    // is left without counting the ticks.
                    Text(
                        text = "Đã xong ${workflow.doneCount}/${workflow.steps.size} bước",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(workflow.steps, key = { it.step.formId }) { step ->
                            StepRow(
                                step = step,
                                onClick = { onOpenStep(step.step.formId) },
                            )
                        }
                    }

                    Surface(shadowElevation = 8.dp) {
                        Column(Modifier.padding(16.dp)) {
                            if (!workflow.canCheckOut) {
                                Text(
                                    text = "Còn bước bắt buộc chưa hoàn thành: " +
                                        workflow.blockingSteps.joinToString { it.title },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            if (state.error != null) {
                                Text(
                                    state.error.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            PrimaryButton(
                                text = "Check-out",
                                onClick = viewModel::checkOut,
                                enabled = workflow.canCheckOut,
                                loading = state.submitting,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: WorkflowStep, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = step.canOpen, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    step.isDone -> scheme.primary
                    !step.canOpen -> scheme.surfaceVariant
                    else -> scheme.primaryContainer
                },
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        step.isDone -> Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                        )

                        // Locked covers both "no screen for it" and "another
                        // step has to happen first" — from the rep's side they
                        // are the same thing: not tappable yet.
                        !step.canOpen -> Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                        )

                        else -> Text(
                            step.step.order.toString(),
                            color = scheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (step.isDone) TextDecoration.LineThrough else null,
                )
                val waitingOn = step.waitingOn
                val note = when {
                    !step.implemented -> "Chưa xây dựng trong bản này"
                    // Says which step, so the rep is not left guessing what
                    // unlocks this one.
                    waitingOn != null -> "Cần làm \"${waitingOn.title}\" trước"
                    step.step.isRequired -> "Bắt buộc"
                    else -> "Tùy chọn"
                }
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (step.canOpen) {
                        scheme.onSurfaceVariant
                    } else {
                        scheme.error
                    },
                )
            }

            if (step.canOpen) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
