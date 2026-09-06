package com.tinhcd.myesalessfa.domain.model

/**
 * Khuyến mãi.
 *
 * What a basket has earned. Computed on the server and never on the device: the
 * rep's phone may have been in a bag since Tuesday and cannot know which
 * campaigns ended on Wednesday. What the device *does* decide is the choice — a
 * rule offering "30.000 đ or a box of Oreo" can only be answered by the person
 * standing in the shop.
 */

/** OM_Discount.DiscType. Also the order the server evaluates them in. */
enum class PromotionScope(val wireValue: String, val label: String) {
    LINE("line", "Theo sản phẩm"),
    GROUP("group", "Theo nhóm"),
    ORDER("order", "Theo đơn hàng"),
    ;

    companion object {
        fun fromWire(value: String?): PromotionScope =
            entries.firstOrNull { it.wireValue == value } ?: LINE
    }
}

/** OM_DiscSeq.DiscFor. */
enum class PromotionReward(val wireValue: String) {
    FREE_ITEM("free_item"),
    AMOUNT("amount"),
    PERCENT("percent"),
    AMOUNT_OR_ITEM("amount_or_item"),
    ;

    companion object {
        fun fromWire(value: String?): PromotionReward =
            entries.firstOrNull { it.wireValue == value } ?: AMOUNT
    }
}

/** One item a rule is offering. [chosen] is the server's default, not a decision. */
data class PromotionGift(
    val productId: String,
    val productCode: String,
    val productName: String,
    /** The code the server matches on; [uomName] is what the rep reads. */
    val uomCode: String,
    val uomName: String,
    val qty: Int,
    /**
     * What the depot can actually give, in this gift's own unit.
     *
     * A promotion can be earned and still not be deliverable. The rep is told
     * rather than handed a smaller gift silently: the promise they make to the
     * customer is theirs to make, and finding out from the shop is the worst
     * possible place to find out.
     */
    val availableQty: Int = 0,
    val chosen: Boolean,
) {
    /** Fewer in the depot than the rule just promised. */
    val isShort: Boolean get() = availableQty < qty
}

/**
 * One level of one rule, earned by the basket as it stands.
 *
 * [portion] is how many times the level was earned — "buy 10" against a basket
 * of 30 is three portions, and the gift quantities already have it multiplied
 * in, so nothing downstream needs to multiply again.
 */
data class EarnedPromotion(
    val sequenceId: String,
    val programCode: String,
    val programName: String,
    val sequenceName: String,
    val scope: PromotionScope,
    val reward: PromotionReward,
    val breakId: String,
    val breakName: String,
    val portion: Int,
    /** Money off, already multiplied out. Zero for a goods-only reward. */
    val discountAmount: Long,
    /** Whole percent, when the reward is a percentage. */
    val percent: Double?,
    /** True when the rep still has something to decide. */
    val needsChoice: Boolean,
    val gifts: List<PromotionGift>,
) {
    val isGoods: Boolean
        get() = reward == PromotionReward.FREE_ITEM ||
            reward == PromotionReward.AMOUNT_OR_ITEM

    /** Whether this rule lets the rep swap money for goods. */
    val offersEither: Boolean get() = reward == PromotionReward.AMOUNT_OR_ITEM
}

/**
 * The rep's answer to one rule that asked a question.
 *
 * Sent with the order and honoured by `submit_order`, which recomputes
 * everything around it. A forged choice can pick a different gift from the same
 * rule; it cannot invent a rule, a level or an amount.
 */
data class PromotionChoice(
    val sequenceId: String,
    /** False takes the goods instead of the money, where the rule offers both. */
    val takeAmount: Boolean = true,
    val freeProductId: String? = null,
    val freeUomCode: String? = null,
)

/**
 * Everything the basket earned, plus what the rep has decided about it.
 *
 * [choices] lives here rather than beside the cart because it is only meaningful
 * against a particular set of earned rules: change the basket, and a choice
 * about a rule that no longer applies is not worth keeping.
 */
data class PromotionSummary(
    val orderAmount: Long = 0,
    val earned: List<EarnedPromotion> = emptyList(),
    /** The nearest levels still out of reach. Advice, not a commitment. */
    val suggestions: List<PromotionSuggestion> = emptyList(),
    /** Rules a budget could not pay for. Advice about why, not money. */
    val outOfBudget: List<OutOfBudgetPromotion> = emptyList(),
    /** Discounts the rep may apply by hand here. Loaded once, not per keystroke. */
    val manualCatalogue: List<ManualPromotion> = emptyList(),
    /** The ones they have applied, by catalogue id. */
    val appliedManual: Map<String, AppliedManualPromotion> = emptyMap(),
    val choices: Map<String, PromotionChoice> = emptyMap(),
) {
    val isEmpty: Boolean get() = earned.isEmpty()

    /** Rules still waiting on the rep. The order can be sent without answering. */
    val undecided: List<EarnedPromotion>
        get() = earned.filter { it.needsChoice && !choices.containsKey(it.sequenceId) }

    /**
     * Money off, as the server will compute it once the choices are applied.
     *
     * Taking goods on an either-or rule forfeits its money, so the figure the rep
     * reads has to follow the choice rather than the server's default — otherwise
     * the total moves under them when the order is confirmed.
     */
    val totalDiscount: Long
        get() = earned.sumOf { promo ->
            if (promo.offersEither && choices[promo.sequenceId]?.takeAmount == false) {
                0L
            } else {
                promo.discountAmount
            }
        }

    /** What is actually being given away, once the choices are applied. */
    fun giftsOf(promo: EarnedPromotion): List<PromotionGift> {
        if (!promo.isGoods) return emptyList()

        val choice = choices[promo.sequenceId]
        if (promo.offersEither && choice?.takeAmount != false) return emptyList()

        val picked = choice?.freeProductId
        return when {
            picked != null -> promo.gifts.filter { it.productId == picked }
            else -> promo.gifts.filter { it.chosen }
        }
    }

    /**
     * Money the rep has given by hand, against a gross the caller supplies.
     *
     * The gross has to come from outside because a percentage entry is taken on
     * the order, and this type knows about rules rather than about baskets. The
     * server computes the same figure from the lines it books, so the two agree
     * as long as both are given the same gross — which is why the caller here is
     * the draft order that owns those lines.
     */
    fun manualDiscountOn(gross: Long): Long = appliedManual.values.sumOf { applied ->
        val entry = manualCatalogue.firstOrNull { it.id == applied.promotionId }
        when (entry?.type) {
            ManualPromotionType.PERCENT -> gross * entry.value / 100
            // An editable entry is a ceiling, so the applied figure never rises
            // above it — and the server refuses one that tries.
            ManualPromotionType.AMOUNT ->
                (applied.amount ?: entry.value).coerceAtMost(entry.value)

            else -> 0L
        }
    }

    fun withManual(applied: AppliedManualPromotion): PromotionSummary =
        copy(appliedManual = appliedManual + (applied.promotionId to applied))

    fun withoutManual(promotionId: String): PromotionSummary =
        copy(appliedManual = appliedManual - promotionId)

    fun withChoice(choice: PromotionChoice): PromotionSummary =
        copy(choices = choices + (choice.sequenceId to choice))

    /**
     * Keeps only the answers that still have a question. A basket edited down to
     * where a rule no longer applies must not carry that rule's answer into the
     * order.
     */
    fun prunedTo(
        fresh: List<EarnedPromotion>,
        freshSuggestions: List<PromotionSuggestion> = emptyList(),
        freshOutOfBudget: List<OutOfBudgetPromotion> = emptyList(),
    ): PromotionSummary {
        val live = fresh.map { it.sequenceId }.toSet()
        return copy(
            earned = fresh,
            suggestions = freshSuggestions,
            outOfBudget = freshOutOfBudget,
            choices = choices.filterKeys { it in live },
        )
    }
}

/** One thing the customer would get by clearing the next level of a rule. */
data class SuggestionReward(
    val productName: String,
    val uomCode: String,
    val qty: Int,
)

/**
 * "Mua thêm 2 thùng nữa được tặng 1."
 *
 * The nearest level this basket has not reached, per rule. Shown while the
 * basket is still open rather than on the confirmation page: a promotion the rep
 * reads after they have finished choosing is a receipt, and one they read while
 * choosing is an argument the customer is still in the room to hear.
 */
data class PromotionSuggestion(
    val sequenceId: String,
    val programName: String,
    val sequenceName: String,
    val breakName: String,
    val scope: PromotionScope,
    val reward: PromotionReward,
    /** One of these is meaningful, depending on how the level is measured. */
    val neededQty: Int,
    val neededAmount: Long,
    /**
     * The product to buy more of, for a per-product rule. Null for group and
     * order rules, where the answer is "spend more", not "buy more of this".
     */
    val productName: String?,
    val uomCode: String?,
    /** Money off, when clearing the level pays in money. */
    val rewardAmount: Long,
    val rewardItems: List<SuggestionReward>,
) {
    /** Measured in units rather than dong — the two read differently to a rep. */
    val isByQty: Boolean get() = neededQty > 0
}

/** OM_DiscDescr.PromoType. */
enum class ManualPromotionType(val wireValue: String) {
    PERCENT("percent"),
    AMOUNT("amount"),
    FREE_ITEM("free_item"),
    ;

    companion object {
        fun fromWire(value: String?): ManualPromotionType =
            entries.firstOrNull { it.wireValue == value } ?: AMOUNT
    }
}

/** One item a manual promotion gives away. */
data class ManualPromotionItem(
    val productName: String,
    val uomName: String,
    val qty: Int,
)

/**
 * A discount head office approved in advance and left the rep to apply by
 * judgement — closing a difficult shop, a gesture on a late delivery.
 *
 * [allowEdit] is the only place a rep may move a number, and the catalogue value
 * is then a ceiling rather than a fixed amount.
 */
data class ManualPromotion(
    val id: String,
    val code: String,
    val name: String,
    val type: ManualPromotionType,
    /** Percent, dong, or unread for goods — as the catalogue stores it. */
    val value: Long,
    val allowEdit: Boolean,
    val items: List<ManualPromotionItem>,
) {
    val isGoods: Boolean get() = type == ManualPromotionType.FREE_ITEM
}

/**
 * One applied manual discount. [amount] is only sent for an editable entry, and
 * the server refuses anything above the catalogue's ceiling rather than clamping
 * it — a rep who typed 500.000 and got 50.000 would find out from the customer.
 */
data class AppliedManualPromotion(
    val promotionId: String,
    val amount: Long? = null,
)

/**
 * A rule the basket qualified for that a budget could not pay for.
 *
 * Shown rather than hidden. A promotion that silently stops applying looks like
 * a bug to the person standing in the shop, and the rep is the one who has to
 * explain it to the customer.
 */
data class OutOfBudgetPromotion(
    val sequenceId: String,
    val programName: String,
    val sequenceName: String,
    val budgetName: String,
)
