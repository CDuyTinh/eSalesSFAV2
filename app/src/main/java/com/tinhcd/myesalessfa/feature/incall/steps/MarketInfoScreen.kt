package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.domain.model.CompetitorCriterion
import com.tinhcd.myesalessfa.domain.model.CompetitorPairing
import com.tinhcd.myesalessfa.domain.model.DraftCompetitorSurvey
import com.tinhcd.myesalessfa.domain.model.MarketInfoSurvey
import com.tinhcd.myesalessfa.domain.model.MarketSurveyKind

/**
 * Thông tin thị trường: the surveys this outlet owes today.
 *
 * The step is a list because the legacy's is. Several surveys can be live for a
 * branch at once and the outlet owes all of them, so the rep needs to see what is
 * left before deciding what to do — a single form could only ever have shown one
 * of them, which is what this screen replaces.
 *
 * Two kinds sit in that list. A questionnaire opens the survey screen every other
 * questionnaire step uses; a competitor survey opens the grid below it, which
 * belongs to this step alone.
 */
@Composable
fun MarketInfoScreen(
    onDone: () -> Unit,
    onOpenQuestionnaire: (surveyTypeId: String) -> Unit,
    viewModel: MarketInfoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state.page) {
        MarketInfoPage.LIST -> SurveyListPage(
            state = state,
            onBack = onDone,
            onOpen = { survey ->
                when (survey.kind) {
                    MarketSurveyKind.MARKET -> onOpenQuestionnaire(survey.id)
                    MarketSurveyKind.COMPETITOR -> viewModel.onOpenCompetitorSurvey(survey)
                }
            },
            onRetry = viewModel::reload,
        )

        MarketInfoPage.COMPETITOR -> CompetitorSurveyPage(
            state = state,
            viewModel = viewModel,
            onBack = viewModel::onLeaveSurvey,
        )
    }
}

@Composable
private fun SurveyListPage(
    state: MarketInfoUiState,
    onBack: () -> Unit,
    onOpen: (MarketInfoSurvey) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            StepHeader(
                title = state.title.ifBlank { "Thông tin thị trường" },
                subtitle = if (state.surveys.isEmpty()) {
                    null
                } else {
                    "Đã làm ${state.doneCount}/${state.surveys.size} khảo sát"
                },
                onBack = onBack,
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))

            state.error != null && state.surveys.isEmpty() ->
                ErrorBox(state.error, onRetry = onRetry, modifier = Modifier.padding(padding))

            // Not an error and not a dead end: a branch running no surveys today
            // leaves the step with nothing to do, and the server has already
            // marked it done rather than leaving the rep stuck on an empty screen.
            state.nothingToDo -> EmptyMarketInfo(Modifier.padding(padding))

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.surveys, key = { "${it.kind}-${it.id}" }) { survey ->
                    SurveyCard(survey = survey, onClick = { onOpen(survey) })
                }
            }
        }
    }
}

@Composable
private fun SurveyCard(survey: MarketInfoSurvey, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The two kinds get different icons because they are different jobs —
            // a rep who has done both knows which is which before reading the name.
            Icon(
                imageVector = when (survey.kind) {
                    MarketSurveyKind.MARKET -> Icons.Default.Insights
                    MarketSurveyKind.COMPETITOR -> Icons.Default.Storefront
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = survey.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (survey.kind) {
                        MarketSurveyKind.MARKET -> "Bảng câu hỏi - ${survey.itemCount} câu"
                        MarketSurveyKind.COMPETITOR ->
                            "Khảo sát đối thủ - ${survey.itemCount} sản phẩm"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (survey.isCompleted) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Đã làm",
                    tint = Color(0xFF2E7D32),
                )
            }
        }
    }
}

@Composable
private fun EmptyMarketInfo(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Insights,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Hôm nay không có khảo sát nào",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Khảo sát đối thủ
// -----------------------------------------------------------------------------

/**
 * One competitor survey: a card per product pairing, a field per criterion.
 *
 * Laid out down the page rather than as a true grid. A phone cannot show a matrix
 * of four products by three criteria without shrinking both to nothing, and the
 * rep works it one shelf at a time anyway — they stand in front of our product,
 * look at the rival beside it, and fill in that card.
 */
@Composable
private fun CompetitorSurveyPage(
    state: MarketInfoUiState,
    viewModel: MarketInfoViewModel,
    onBack: () -> Unit,
) {
    val draft = state.draft ?: return

    Scaffold(
        topBar = {
            StepHeader(
                title = draft.definition.name,
                subtitle = "Đã ghi ${draft.definition.pairings.size - draft.outstanding.size}" +
                    "/${draft.definition.pairings.size} sản phẩm",
                onBack = onBack,
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.padding(16.dp)) {
                    if (state.error != null) {
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    PrimaryButton(
                        text = if (state.submitting) "Đang gửi..." else "Gửi khảo sát",
                        onClick = viewModel::onSubmit,
                        // Every required criterion on every pairing, which is the
                        // same rule the server counts completion by — so a grid the
                        // button lets through is one the step accepts.
                        enabled = draft.canSubmit && !state.submitting && !state.capturing,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (!draft.canSubmit) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Còn ${draft.outstanding.size} sản phẩm chưa ghi đủ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(draft.definition.pairings, key = { it.key }) { pairing ->
                PairingCard(
                    draft = draft,
                    pairing = pairing,
                    capturing = state.capturing,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun PairingCard(
    draft: DraftCompetitorSurvey,
    pairing: CompetitorPairing,
    capturing: Boolean,
    viewModel: MarketInfoViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        pairing.productName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // The rival is named on its own line and with its company,
                    // because the rep is being asked about a specific pack on a
                    // specific shelf, not about the brand in general.
                    Text(
                        "So với ${pairing.competitorProduct} (${pairing.competitorName})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (draft.isComplete(pairing)) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Đã ghi",
                        tint = Color(0xFF2E7D32),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            draft.definition.criteria.forEach { criterion ->
                CriterionField(
                    draft = draft,
                    pairing = pairing,
                    criterion = criterion,
                    capturing = capturing,
                    viewModel = viewModel,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CriterionField(
    draft: DraftCompetitorSurvey,
    pairing: CompetitorPairing,
    criterion: CompetitorCriterion,
    capturing: Boolean,
    viewModel: MarketInfoViewModel,
) {
    val entry = draft.entry(pairing, criterion)

    // Created immediately before the camera is launched, so the file the camera
    // writes into exists by the time it resolves the uri.
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved -> viewModel.onPhotoTaken(saved) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = entry.content,
            onValueChange = { viewModel.onContent(pairing, criterion, it) },
            label = {
                Text(criterion.name + if (criterion.isRequired) " *" else "")
            },
            supportingText = criterion.hint?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(8.dp))

        // Optional throughout: a shop that will not let a rep photograph a rival's
        // shelf is common enough that requiring one would stop the survey being
        // filed at all.
        IconButton(
            onClick = {
                val target = viewModel.newPhotoTarget(pairing, criterion)
                camera.launch(target.uri)
            },
            enabled = !capturing && entry.photoCount < draft.photoMax,
        ) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = "Chụp ảnh ${criterion.name}",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (entry.photoCount > 0) {
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${entry.photoCount} ảnh",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Only the shots taken on this screen can be taken back here. The ones
            // an earlier submission left are on the server, and removing them is a
            // different act from discarding a photo that has not been sent.
            entry.photos.forEach { photo ->
                Spacer(Modifier.width(4.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        Modifier.padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("mới", style = MaterialTheme.typography.labelSmall)
                        IconButton(
                            onClick = {
                                viewModel.onRemovePhoto(pairing, criterion, photo.localPath)
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Bỏ ảnh",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
