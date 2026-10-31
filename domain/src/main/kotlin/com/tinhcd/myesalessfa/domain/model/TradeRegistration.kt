package com.tinhcd.myesalessfa.domain.model

/** Which kind of programme a registration is for. Legacy TradeType D / A. */
enum class TradeKind(val wire: String, val label: String) {
    DISPLAY("display", "Trưng bày"),
    LOYALTY("loyalty", "Tích lũy");

    companion object {
        fun fromWire(value: String): TradeKind =
            entries.firstOrNull { it.wire == value } ?: DISPLAY
    }
}

/** Where a registration stands with head office. */
enum class TradeRegistrationStatus(val wire: String, val label: String) {
    PENDING("pending", "Chờ duyệt"),
    APPROVED("approved", "Đã duyệt"),
    REJECTED("rejected", "Bị từ chối");

    companion object {
        fun fromWire(value: String): TradeRegistrationStatus =
            entries.firstOrNull { it.wire == value } ?: PENDING
    }
}

/** A programme this rep has signed an outlet up for. */
data class TradeRegistration(
    val id: String,
    val kind: TradeKind,
    val registeredAt: String,
    val status: TradeRegistrationStatus,
    val portion: Int,
    val customerId: String,
    val customerCode: String,
    val customerName: String,
    val phone: String? = null,
    val address: String? = null,
    val avatarUrl: String? = null,
    val programId: String,
    val programCode: String,
    val programName: String,
    val levelName: String? = null,
    val reason: String? = null,
    /** Why the supervisor rejected it, when they said. */
    val decisionNote: String? = null,
)

/** One entry in the programme filter row. */
data class TradeProgramRef(
    val id: String,
    val kind: TradeKind,
    val code: String,
    val name: String,
)

/**
 * The registration list and everything around it.
 *
 * [counts], [kindCounts] and the slot figures deliberately ignore the filters:
 * a chip has to keep showing its own number while a different chip is the one
 * filtering, and the slot header describes what head office gave this rep
 * rather than whatever slice is on screen.
 */
data class TradeRegistrationBoard(
    val items: List<TradeRegistration> = emptyList(),
    val counts: Map<TradeRegistrationStatus, Int> = emptyMap(),
    val kindCounts: Map<TradeKind, Int> = emptyMap(),
    val programs: List<TradeProgramRef> = emptyList(),
    val slotsTotal: Int = 0,
    val slotsUsed: Int = 0,
    val total: Int = 0,
) {
    fun count(status: TradeRegistrationStatus): Int = counts[status] ?: 0
    fun count(kind: TradeKind): Int = kindCounts[kind] ?: 0

    val presentStatuses: List<TradeRegistrationStatus>
        get() = TradeRegistrationStatus.entries.filter { count(it) > 0 }

    val slotsLeft: Int get() = (slotsTotal - slotsUsed).coerceAtLeast(0)

    /**
     * How much of the allocation is spent. Null when head office set no ceiling
     * at all — nought per cent of nothing is not a figure worth drawing.
     */
    val slotProgress: Float?
        get() = if (slotsTotal <= 0) null
        else (slotsUsed.toFloat() / slotsTotal).coerceIn(0f, 1f)

    /** Programmes of one kind, for a filter row that follows the kind tab. */
    fun programsOf(kind: TradeKind?): List<TradeProgramRef> =
        if (kind == null) programs else programs.filter { it.kind == kind }
}

/** An outlet this rep may sign up, for the picker. */
data class RegisterableCustomer(
    val id: String,
    val code: String,
    val name: String,
    val address: String? = null,
)

/**
 * What one outlet may still join, both kinds together.
 *
 * The customer hub asks per kind on two tabs; here the rep has already picked
 * an outlet and wants everything open to it at once.
 */
data class TradeProgramOffers(
    val display: List<DisplayProgramOffer> = emptyList(),
    val loyalty: List<LoyaltyProgramOffer> = emptyList(),
) {
    val isEmpty: Boolean get() = display.isEmpty() && loyalty.isEmpty()
    val total: Int get() = display.size + loyalty.size
}
