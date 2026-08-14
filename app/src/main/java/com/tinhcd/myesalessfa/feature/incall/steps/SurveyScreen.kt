package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.domain.model.AnswerType
import com.tinhcd.myesalessfa.domain.model.DraftSurvey
import com.tinhcd.myesalessfa.domain.model.SurveyQuestion

/**
 * One screen for every questionnaire step.
 *
 * It renders whatever the definition describes: groups in order, a control chosen by
 * each question's answer type, options with their scores. It never names a
 * questionnaire, which is what lets `posm_status` and `market_info` — and the next one
 * — share it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyScreen(
    onDone: () -> Unit,
    viewModel: SurveyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    val survey = state.survey

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(survey?.definition?.name ?: "Khao sat")
                        if (survey != null && survey.definition.isScored) {
                            Text(
                                "Diem ${survey.totalScore}/${survey.definition.maxScore}" +
                                    if (survey.definition.passScore > 0) {
                                        if (survey.isPassing) " - Dat" else " - Chua dat"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                state.loading -> LoadingBox()
                survey == null -> ErrorBox(
                    state.error ?: "Khong co bo cau hoi",
                    onRetry = viewModel::load,
                )

                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        survey.definition.groups.forEach { group ->
                            item(key = "group-${group.name}") {
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }

                            // Photo questions are dropped rather than shown as broken
                            // controls; renderableQuestions is what filters them.
                            items(
                                group.questions.filter { it.answerType.isSupported },
                                key = { it.id },
                            ) { question ->
                                QuestionCard(
                                    question = question,
                                    survey = survey,
                                    viewModel = viewModel,
                                )
                            }
                        }

                        item(key = "note") {
                            OutlinedTextField(
                                value = survey.note,
                                onValueChange = viewModel::onNoteChange,
                                label = { Text("Ghi chu chung (tuy chon)") },
                                minLines = 2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp)
                                    .padding(top = 8.dp),
                            )
                        }
                    }

                    SurveyFooter(
                        survey = survey,
                        error = state.error,
                        submitting = state.submitting,
                        onSubmit = viewModel::submit,
                        onBack = onDone,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: SurveyQuestion,
    survey: DraftSurvey,
    viewModel: SurveyViewModel,
) {
    val answer = survey.answerFor(question.id)
    val answered = survey.isAnswered(question)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                question.content + if (question.isRequired) " *" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            // Only where a score is actually at stake, so an informational
            // questionnaire does not sprout meaningless zeros.
            if (question.maxScore > 0) {
                Text(
                    "Toi da ${question.maxScore} diem",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(Modifier.padding(top = 8.dp)) {
                when (question.answerType) {
                    AnswerType.YES_NO -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = answer?.bool == true,
                            onClick = { viewModel.onYesNo(question.id, true) },
                            label = { Text("Co") },
                        )
                        FilterChip(
                            selected = answer?.bool == false,
                            onClick = { viewModel.onYesNo(question.id, false) },
                            label = { Text("Khong") },
                        )
                    }

                    AnswerType.SINGLE -> question.options.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = option.id in answer?.chosenOptionIds.orEmpty(),
                                onClick = { viewModel.onSingleOption(question.id, option.id) },
                            )
                            Text(option.content, Modifier.weight(1f))
                            if (option.score > 0) {
                                Text(
                                    "${option.score}d",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    AnswerType.MULTI -> question.options.forEach { option ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) {
                            FilterChip(
                                selected = option.id in answer?.chosenOptionIds.orEmpty(),
                                onClick = { viewModel.onToggleOption(question.id, option.id) },
                                label = {
                                    Text(
                                        option.content +
                                            if (option.score > 0) " (${option.score}d)" else "",
                                    )
                                },
                            )
                        }
                    }

                    AnswerType.NUMBER -> OutlinedTextField(
                        value = answer?.number?.let { formatNumber(it) } ?: "",
                        onValueChange = { viewModel.onNumber(question.id, it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    AnswerType.TEXT -> OutlinedTextField(
                        value = answer?.text.orEmpty(),
                        onValueChange = { viewModel.onText(question.id, it) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Filtered out before reaching here; the branch exists so a new
                    // answer type cannot be added without the compiler saying so.
                    AnswerType.PHOTO -> Unit
                }
            }

            if (question.isRequired && !answered) {
                Text(
                    "Chua tra loi",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SurveyFooter(
    survey: DraftSurvey,
    error: String?,
    submitting: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp)) {
            if (survey.definition.isScored) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Diem",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${survey.totalScore}/${survey.definition.maxScore}" +
                            " (${survey.scorePercent}%)",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (survey.definition.passScore > 0 && !survey.isPassing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            if (survey.unanswered.isNotEmpty()) {
                Text(
                    "Con ${survey.unanswered.size} cau bat buoc chua tra loi",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            PrimaryButton(
                text = "Hoan thanh buoc nay",
                onClick = onSubmit,
                enabled = survey.canSubmit,
                loading = submitting,
                modifier = Modifier.padding(top = 8.dp),
            )

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) { Text("Quay lai") }
        }
    }
}

/** Drops a trailing `.0` so a whole number does not read like a decimal. */
private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
