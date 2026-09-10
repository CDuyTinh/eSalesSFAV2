package com.tinhcd.myesalessfa.domain.model

/**
 * Chương trình tích lũy.
 *
 * The second trade type a rep signs an outlet up for, and the one the customer
 * screen's programme tab was still missing. A display programme measures facings
 * on a shelf; a loyalty programme measures trade — buy between so much and so
 * much of the counted products over the window, and earn a percentage back.
 *
 * Progress is the interesting number. The legacy's own detail proc returns
 * `Actual = 0` and leaves the figure to a back-office table; here it is derived
 * from the orders, so it agrees with the money by construction.
 */
enum class LoyaltyCountsBy(val wireValue: String) {
    /** Dong of counted product bought. */
    AMOUNT("amount"),

    /** Base units of it, for a programme that counts cases rather than money. */
    QUANTITY("quantity"),
    ;

    companion object {
        /**
         * An unknown basis reads as money rather than crashing: a server that
         * gains a third way of counting should not take the tab down with it, and
         * money is what every programme in the data actually uses.
         */
        fun fromWire(raw: String?): LoyaltyCountsBy =
            if (raw == "quantity") QUANTITY else AMOUNT
    }
}

/** One band of trade, and what it pays. */
data class LoyaltyLevel(
    val levelId: String,
    val levelCode: String,
    val levelName: String,
    val targetFrom: Long,
    /** Null on the top band: the legacy's "and everything above this". */
    val targetTo: Long?,
    /** PercentBonus, in basis points — 250 is two and a half percent. */
    val rewardBasisPoints: Int,
    /** Null where head office set this rep no ceiling at the band. */
    val slotsLeft: Int? = null,
) {
    /** False only where a ceiling exists and is spent. */
    val available: Boolean get() = slotsLeft == null || slotsLeft > 0
}

/** A programme this outlet is in, and how far it has got. */
data class LoyaltyProgram(
    val programId: String,
    val programCode: String,
    val programName: String,
    val specification: String?,
    val countsBy: LoyaltyCountsBy,
    val fromDate: String,
    val toDate: String,
    val level: LoyaltyLevel,
    /** pending | approved. A rejected signup never reaches the client. */
    val status: String,
    val registeredAt: String?,
    /** Counted products bought inside the window: dong, or base units. */
    val achieved: Long,
    /** Left to reach the band. Zero once it is met. */
    val remaining: Long,
) {
    val isPending: Boolean get() = status == "pending"

    val isMet: Boolean get() = remaining == 0L

    /**
     * How far along the band the outlet is, nought to one.
     *
     * Measured against the band's floor rather than its ceiling, because the
     * floor is what the reward turns on: an outlet at 4.9 of 5 million has
     * earned nothing, and a bar that reads nearly full is the honest picture of
     * that. Full once the floor is crossed, whatever the ceiling.
     */
    val progress: Float
        get() = when {
            targetOrNull == null || targetOrNull == 0L -> 0f
            else -> (achieved.toFloat() / targetOrNull!!.toFloat()).coerceIn(0f, 1f)
        }

    private val targetOrNull: Long? get() = level.targetFrom.takeIf { it > 0 }

    /** The reward as a percentage, for a screen that has to say it in words. */
    val rewardPercent: Double get() = level.rewardBasisPoints / 100.0
}

/** A programme this outlet is not in yet and could join today. */
data class LoyaltyProgramOffer(
    val programId: String,
    val programCode: String,
    val programName: String,
    val specification: String?,
    val countsBy: LoyaltyCountsBy,
    val regisFromDate: String?,
    val regisToDate: String?,
    val levels: List<LoyaltyLevel>,
) {
    /** Nothing left to give at any band: shown, not offered. */
    val anyAvailable: Boolean get() = levels.any { it.available }
}

/** Both halves of the tab, which are read together and so are fetched together. */
data class LoyaltySnapshot(
    val joined: List<LoyaltyProgram> = emptyList(),
    val open: List<LoyaltyProgramOffer> = emptyList(),
)
