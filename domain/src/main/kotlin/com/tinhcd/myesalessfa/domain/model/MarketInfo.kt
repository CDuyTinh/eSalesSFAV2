package com.tinhcd.myesalessfa.domain.model

/**
 * Thông tin thị trường.
 *
 * The step holds a list, not a form. An outlet owes every survey live for its
 * branch today, and they come in two kinds that have almost nothing in common:
 *
 *  - [MarketSurveyKind.MARKET] is a questionnaire, answered on the shared survey
 *    screen like any other.
 *  - [MarketSurveyKind.COMPETITOR] is a grid: for each of our products, against
 *    the rival sitting next to it, record each criterion and photograph it.
 *
 * The list is what the rep reads first, so both kinds arrive in one shape with
 * one done-or-not flag, whatever is behind them.
 */
enum class MarketSurveyKind {
    MARKET,
    COMPETITOR,
    ;

    companion object {
        /**
         * An unknown kind reads as a questionnaire rather than crashing: a server
         * that gains a third kind should not take the step down with it. It will
         * open the survey screen and report having no questions, which is honest
         * about what this build can do with it.
         */
        fun fromWire(raw: String?): MarketSurveyKind =
            if (raw == "competitor") COMPETITOR else MARKET
    }
}

data class MarketInfoSurvey(
    val kind: MarketSurveyKind,
    val id: String,
    val code: String,
    val name: String,
    val fromDate: String?,
    val toDate: String?,
    val isCompleted: Boolean,
    /** Questions for a questionnaire, product pairings for a competitor survey. */
    val itemCount: Int,
)

// -----------------------------------------------------------------------------
// The competitor grid
// -----------------------------------------------------------------------------

/**
 * One thing asked about a rival's product: its shelf price, its facings, whether
 * it is running an offer.
 *
 * Free text, as `OM_CompetitorSurveyResult.Content` is. The criteria vary by
 * campaign, and a typed field would only ever fit the campaign that invented it.
 */
data class CompetitorCriterion(
    val id: String,
    val code: String,
    val name: String,
    val hint: String?,
    val isRequired: Boolean,
)

/** Our product and the rival product it is being compared against. */
data class CompetitorPairing(
    val productId: String,
    val productCode: String,
    val productName: String,
    val competitorProductId: String,
    val competitorProduct: String,
    val competitorName: String,
) {
    /** Identifies a pairing across the draft without carrying the whole row. */
    val key: String get() = "$productId|$competitorProductId"
}

data class CompetitorSurveyDefinition(
    val id: String,
    val code: String,
    val name: String,
    val criteria: List<CompetitorCriterion>,
    val pairings: List<CompetitorPairing>,
) {
    val requiredCriteria: List<CompetitorCriterion> get() = criteria.filter { it.isRequired }
}

data class CompetitorPhoto(
    val localPath: String,
    val takenAtEpochMs: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val sizeBytes: Long = 0,
)

/**
 * One cell of the grid: what the rep found, and what they photographed.
 *
 * [alreadyStored] are photos a previous submission of this visit left behind, held
 * as storage object names. They are kept apart from [photos] because they are not
 * on this device to upload again, and a redo that dropped them would delete
 * evidence the rep never asked to remove.
 */
data class CompetitorEntry(
    val content: String = "",
    val photos: List<CompetitorPhoto> = emptyList(),
    val alreadyStored: List<String> = emptyList(),
) {
    val isAnswered: Boolean get() = content.isNotBlank()

    val photoCount: Int get() = photos.size + alreadyStored.size
}

/**
 * The grid the rep is filling in for one competitor survey.
 *
 * Keyed by pairing and criterion, which is exactly how the server keys the result
 * rows, so nothing has to be matched up again on the way out.
 */
data class DraftCompetitorSurvey(
    val visitId: String,
    val definition: CompetitorSurveyDefinition,
    val entries: Map<String, CompetitorEntry> = emptyMap(),
    /** From the step config, as the POSM and display steps take theirs. */
    val photoMax: Int = 4,
) {
    fun entry(pairing: CompetitorPairing, criterion: CompetitorCriterion): CompetitorEntry =
        entries[cellKey(pairing, criterion)] ?: CompetitorEntry()

    fun withContent(
        pairing: CompetitorPairing,
        criterion: CompetitorCriterion,
        content: String,
    ): DraftCompetitorSurvey = withEntry(pairing, criterion) { it.copy(content = content) }

    /** Ignored once the ceiling is reached, so no path can slip past it. */
    fun withPhoto(
        pairing: CompetitorPairing,
        criterion: CompetitorCriterion,
        photo: CompetitorPhoto,
    ): DraftCompetitorSurvey = withEntry(pairing, criterion) {
        if (it.photoCount < photoMax) it.copy(photos = it.photos + photo) else it
    }

    fun withoutPhoto(
        pairing: CompetitorPairing,
        criterion: CompetitorCriterion,
        localPath: String,
    ): DraftCompetitorSurvey = withEntry(pairing, criterion) {
        it.copy(photos = it.photos.filterNot { photo -> photo.localPath == localPath })
    }

    /** How many of this pairing's required criteria have an answer. */
    fun answeredIn(pairing: CompetitorPairing): Int =
        definition.requiredCriteria.count { entry(pairing, it).isAnswered }

    fun isComplete(pairing: CompetitorPairing): Boolean =
        answeredIn(pairing) == definition.requiredCriteria.size

    /**
     * Pairings still owing an answer. The server counts completion the same way —
     * every required criterion on every pairing — so a grid this says is finished
     * is one the step will accept as finishing the survey.
     */
    val outstanding: List<CompetitorPairing>
        get() = definition.pairings.filterNot { isComplete(it) }

    /**
     * True once every pairing is answered.
     *
     * A survey with no pairings behind it counts as done rather than stuck: head
     * office can publish an empty survey, and a rep who cannot get past it is
     * stranded on a screen with nothing to fill in.
     */
    val canSubmit: Boolean get() = outstanding.isEmpty()

    /** Total bytes queued for upload, which is what the rep waits on. */
    val totalSizeBytes: Long
        get() = entries.values.sumOf { entry -> entry.photos.sumOf { it.sizeBytes } }

    /**
     * The cells worth sending: anything the rep actually answered.
     *
     * An untouched optional criterion is left out rather than sent blank. The
     * server stores what it is given, and an empty row would read later as "asked
     * and found nothing" rather than "not asked".
     */
    fun filledCells(): List<CompetitorCell> =
        definition.pairings.flatMap { pairing ->
            definition.criteria.mapNotNull { criterion ->
                val entry = entry(pairing, criterion)
                if (!entry.isAnswered) null else CompetitorCell(pairing, criterion, entry)
            }
        }

    private fun withEntry(
        pairing: CompetitorPairing,
        criterion: CompetitorCriterion,
        change: (CompetitorEntry) -> CompetitorEntry,
    ): DraftCompetitorSurvey {
        val key = cellKey(pairing, criterion)
        return copy(entries = entries + (key to change(entries[key] ?: CompetitorEntry())))
    }

    private fun cellKey(pairing: CompetitorPairing, criterion: CompetitorCriterion) =
        cellKey(pairing.productId, pairing.competitorProductId, criterion.id)

    companion object {
        /**
         * How a cell is addressed, in one place.
         *
         * The data layer needs it too, to seed the draft with what the server
         * already holds, and two spellings of the same key would put those answers
         * in cells the screen never reads.
         */
        fun cellKey(productId: String, competitorProductId: String, criteriaId: String) =
            "$productId|$competitorProductId|$criteriaId"
    }
}

/** One answered cell on its way to the server. */
data class CompetitorCell(
    val pairing: CompetitorPairing,
    val criterion: CompetitorCriterion,
    val entry: CompetitorEntry,
)
