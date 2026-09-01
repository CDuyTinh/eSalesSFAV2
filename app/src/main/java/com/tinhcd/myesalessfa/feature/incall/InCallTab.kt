package com.tinhcd.myesalessfa.feature.incall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.domain.model.SupportedSteps
import com.tinhcd.myesalessfa.domain.model.WorkflowStep

/**
 * Công việc — the steps this visit still owes, and the check-out that closes it.
 *
 * A grid of three, the way the legacy screen laid it out. That shape earns its
 * place here: the steps are a set to be worked through in roughly any order, not
 * a sequence, and a grid shows all seven at once where the list this replaced
 * showed four and hid the rest below the fold. A rep deciding what to do next
 * can only decide between the things they can see.
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
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = "Đã xong ${workflow.doneCount}/${workflow.steps.size} bước",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                        )

                        StepGrid(
                            steps = workflow.steps,
                            onOpenStep = onOpenStep,
                        )

                        Spacer(Modifier.height(16.dp))
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
                            // Kept full width at the bottom rather than moved to
                            // the header chip the legacy uses. Check-out ends the
                            // visit; it should be the largest target on the tab,
                            // not a 33dp pill beside the back button.
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

/**
 * Three across, in one card, in config order.
 *
 * Rows are built by hand rather than with a LazyVerticalGrid because the grid
 * lives inside a scrolling column — nesting a lazy scroller in one is a crash,
 * and there are seven items, not seven hundred.
 */
@Composable
private fun StepGrid(steps: List<WorkflowStep>, onOpenStep: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            steps.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { step ->
                        StepTile(
                            step = step,
                            onClick = { onOpenStep(step.step.formId) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps the last row's tiles the same width as every other
                    // row's, instead of stretching two items across three slots.
                    repeat(3 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTile(
    step: WorkflowStep,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(enabled = step.canOpen, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = when {
                    step.isDone -> scheme.primaryContainer
                    !step.canOpen -> scheme.surfaceVariant
                    else -> scheme.primaryContainer
                },
                modifier = Modifier
                    .padding(6.dp)
                    .size(50.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = step.step.formId.stepIcon(),
                        contentDescription = null,
                        tint = if (step.canOpen || step.isDone) {
                            scheme.onPrimaryContainer
                        } else {
                            scheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // One badge, in this order of precedence: done beats everything,
            // then the lock, then the asterisk. Two badges on one tile would
            // overlap in a 16dp corner and say less than either alone.
            when {
                step.isDone -> Badge(
                    icon = Icons.Default.Check,
                    background = DoneGreen,
                    contentDescription = "Đã xong",
                )

                !step.canOpen -> Badge(
                    icon = Icons.Default.Lock,
                    background = scheme.outline,
                    contentDescription = "Chưa mở được",
                )

                step.step.isRequired -> Text(
                    text = "*",
                    color = scheme.error,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                )

                else -> Unit
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = step.title,
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (step.canOpen || step.isDone) {
                scheme.onSurface
            } else {
                scheme.onSurfaceVariant
            },
        )

        // The one thing a grid cannot show that the list did: which step is
        // holding this one shut. Worth the extra line — without it a locked tile
        // is a dead end with no way to work out what opens it.
        val waitingOn = step.waitingOn
        val note = when {
            !step.implemented -> "Chưa có"
            waitingOn != null -> "Cần ${waitingOn.title}"
            else -> null
        }
        if (note != null) {
            Text(
                text = note,
                textAlign = TextAlign.Center,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = scheme.error,
            )
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, background: Color, contentDescription: String) {
    Surface(shape = CircleShape, color = background, modifier = Modifier.size(18.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** The legacy ships a drawn icon per step; these are the nearest Material ones. */
private fun String.stepIcon(): ImageVector = when (this) {
    SupportedSteps.OUTSIDE_CHECKING -> Icons.Default.Storefront
    SupportedSteps.STOCK_OUTLET -> Icons.Default.Inventory2
    SupportedSteps.TAKE_ORDER -> Icons.Default.ShoppingCart
    SupportedSteps.DISPLAY_REMARK -> Icons.Default.PhotoCamera
    SupportedSteps.POSM_STATUS -> Icons.Default.Campaign
    SupportedSteps.MARKET_INFO -> Icons.Default.Insights
    SupportedSteps.FEEDBACK -> Icons.Default.Feedback
    // A step head office enabled that this build has no screen for. It is drawn
    // rather than skipped, because the rep should see the visit has one.
    else -> Icons.Default.Assignment
}

/** Legacy uses #04A489 for the done tick, which reads on both themes. */
private val DoneGreen = Color(0xFF04A489)

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun StepGridPreview() {
    MyeSalesTheme {
        StepGrid(steps = emptyList(), onOpenStep = {})
    }
}
