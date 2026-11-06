package com.tinhcd.myesalessfa.feature.reports

import androidx.activity.compose.BackHandler
import com.tinhcd.myesalessfa.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.ReportWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The report's filter, opened from the funnel on its toolbar.
 *
 * The legacy app keeps the period off the report itself and asks for it on a
 * page of its own (`daily_activities_filter_form.dart`, `filter_by_month.dart`):
 * a header band with rounded lower corners, the fields in a card over its edge,
 * and one button at the foot. Nothing reloads until that button — a rep picking
 * "from" then "to" should not fetch the half-chosen range in between.
 *
 * What is asked depends on the report: one day for the day's activities, one
 * month for the month's sales, a from–to range for the other four.
 */
@Composable
internal fun ReportFilterScreen(
    kind: ReportKind,
    date: LocalDate,
    month: LocalDate,
    window: ReportWindow,
    onDismiss: () -> Unit,
    onApplyDate: (LocalDate) -> Unit,
    onApplyMonth: (LocalDate) -> Unit,
    onApplyWindow: (ReportWindow) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val today = LocalDate.now()
    var draftDate by rememberSaveable { mutableStateOf(date) }
    var draftMonth by rememberSaveable { mutableStateOf(month.withDayOfMonth(1)) }
    var draftFrom by rememberSaveable { mutableStateOf(window.fromDate) }
    var draftTo by rememberSaveable { mutableStateOf(window.toDate) }

    val windowError = when {
        draftTo.isAfter(today) -> stringResource(R.string.sfb_179)
        draftFrom.isAfter(draftTo) -> stringResource(R.string.sfb_180)
        else -> null
    }
    val canApply = when (kind) {
        ReportKind.ACTIVITIES -> !draftDate.isAfter(today)
        ReportKind.SALES -> !draftMonth.isAfter(today.withDayOfMonth(1))
        else -> windowError == null
    }

    fun reset() {
        draftDate = today
        draftMonth = today.withDayOfMonth(1)
        ReportWindow.thisMonth(today).let {
            draftFrom = it.fromDate
            draftTo = it.toDate
        }
    }

    fun apply() {
        when (kind) {
            ReportKind.ACTIVITIES -> onApplyDate(draftDate)
            ReportKind.SALES -> onApplyMonth(draftMonth)
            else -> onApplyWindow(ReportWindow(draftFrom, draftTo))
        }
        onDismiss()
    }

    // A Surface rather than a bare Box: it takes the touches, so nothing on the
    // report underneath can be tapped through the filter.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(FilterHeaderHeight)
                    .background(
                        Brush.linearGradient(listOf(MaterialTheme.brand.header, MaterialTheme.brand.header.darken())),
                        RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    ),
            )
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = MaterialTheme.brand.onHeader)
                    }
                    Text(
                        stringResource(R.string.sfa_131),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.brand.onHeader,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    TextButton(onClick = ::reset) {
                        Text(stringResource(R.string.sfa_127), color = MaterialTheme.brand.onHeader)
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    FilterCard(title = titleFor(kind)) {
                        when (kind) {
                            ReportKind.ACTIVITIES -> DayFilter(draftDate, today) { draftDate = it }
                            ReportKind.SALES -> MonthFilter(draftMonth, today) { draftMonth = it }
                            else -> WindowFilter(
                                from = draftFrom,
                                to = draftTo,
                                today = today,
                                error = windowError,
                                onFrom = { draftFrom = it },
                                onTo = { draftTo = it },
                                onPreset = {
                                    draftFrom = it.fromDate
                                    draftTo = it.toDate
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = ::apply,
                    enabled = canApply,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.brand.header,
                        contentColor = MaterialTheme.brand.onHeader,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .height(50.dp),
                ) { Text(stringResource(R.string.order_apply), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

/** The status bar, the title row, and the top of the card that sits over it. */
private val FilterHeaderHeight = 150.dp

private val FieldDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun titleFor(kind: ReportKind): String = when (kind) {
    ReportKind.ACTIVITIES -> "Ngày ghé thăm"
    ReportKind.SALES -> "Tháng báo cáo"
    else -> "Khoảng thời gian"
}

@Composable
private fun FilterCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

// -----------------------------------------------------------------------------
// One day
// -----------------------------------------------------------------------------

@Composable
private fun DayFilter(date: LocalDate, today: LocalDate, onDate: (LocalDate) -> Unit) {
    PresetRow(
        presets = listOf(stringResource(R.string.dashboard_today) to today, stringResource(R.string.sfb_184) to today.minusDays(1)),
        selected = date,
        onSelect = onDate,
    )
    DateField(label = stringResource(R.string.sfa_128), date = date, today = today, onDate = onDate)
}

// -----------------------------------------------------------------------------
// One month
// -----------------------------------------------------------------------------

/**
 * A year and its twelve months. Material has no month picker, and a day picker
 * would ask for a day the report then ignores.
 */
@Composable
private fun MonthFilter(month: LocalDate, today: LocalDate, onMonth: (LocalDate) -> Unit) {
    val thisMonth = today.withDayOfMonth(1)
    var year by rememberSaveable { mutableStateOf(month.year) }

    PresetRow(
        presets = listOf(stringResource(R.string.range_this_month) to thisMonth, stringResource(R.string.sfa_180) to thisMonth.minusMonths(1)),
        selected = month,
        onSelect = {
            year = it.year
            onMonth(it)
        },
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = { year-- }) {
            Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.sfa_129))
        }
        Text(
            stringResource(R.string.sft_93, year),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { year++ }, enabled = year < today.year) {
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.sfa_130))
        }
    }

    (1..12).chunked(4).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { m ->
                val candidate = LocalDate.of(year, m, 1)
                val selected = candidate == month
                val enabled = !candidate.isAfter(thisMonth)
                Surface(
                    onClick = { onMonth(candidate) },
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) MaterialTheme.brand.header else MaterialTheme.colorScheme.surface,
                    contentColor = when {
                        selected -> MaterialTheme.brand.onHeader
                        enabled -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    },
                    border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Th $m", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// A range
// -----------------------------------------------------------------------------

@Composable
private fun WindowFilter(
    from: LocalDate,
    to: LocalDate,
    today: LocalDate,
    error: String?,
    onFrom: (LocalDate) -> Unit,
    onTo: (LocalDate) -> Unit,
    onPreset: (ReportWindow) -> Unit,
) {
    val lastMonth = today.withDayOfMonth(1).minusMonths(1)
    val presets = listOf(
        stringResource(R.string.range_this_month) to ReportWindow.thisMonth(today),
        stringResource(R.string.sfa_180) to ReportWindow(lastMonth, lastMonth.withDayOfMonth(lastMonth.lengthOfMonth())),
        stringResource(R.string.sfb_185) to ReportWindow(today.minusDays(6), today),
        stringResource(R.string.sfb_186) to ReportWindow(today.minusDays(29), today),
    )
    PresetRow(presets = presets, selected = ReportWindow(from, to), onSelect = onPreset)

    DateField(label = stringResource(R.string.sfa_70), date = from, today = today, onDate = onFrom)
    DateField(label = stringResource(R.string.sfa_71), date = to, today = today, onDate = onTo)

    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

// -----------------------------------------------------------------------------
// Pieces
// -----------------------------------------------------------------------------

@Composable
private fun <T> PresetRow(presets: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        presets.forEach { (label, value) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/** A labelled date that opens the calendar; days after today cannot be chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, date: LocalDate, today: LocalDate, onDate: (LocalDate) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }

    Surface(
        onClick = { picking = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(date.format(FieldDateFormat), style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.brand.header)
        }
    }

    if (picking) {
        val todayMillis = today.toUtcMillis()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.toUtcMillis(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        picking = false
                    },
                ) { Text(stringResource(R.string.sfa_72)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.sfa_35)) } },
        ) { DatePicker(state = pickerState) }
    }
}

/** The picker counts in UTC midnights, whatever the phone's zone. */
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

@Preview(name = "Bộ lọc khoảng thời gian", showBackground = true, heightDp = 700)
@Composable
private fun WindowFilterPreview() {
    MyeSalesTheme {
        ReportFilterScreen(
            kind = ReportKind.ORDERS,
            date = LocalDate.now(),
            month = LocalDate.now(),
            window = ReportWindow.thisMonth(),
            onDismiss = {},
            onApplyDate = {},
            onApplyMonth = {},
            onApplyWindow = {},
        )
    }
}

@Preview(name = "Bộ lọc tháng", showBackground = true, heightDp = 700)
@Composable
private fun MonthFilterPreview() {
    MyeSalesTheme {
        ReportFilterScreen(
            kind = ReportKind.SALES,
            date = LocalDate.now(),
            month = LocalDate.now(),
            window = ReportWindow.thisMonth(),
            onDismiss = {},
            onApplyDate = {},
            onApplyMonth = {},
            onApplyWindow = {},
        )
    }
}
