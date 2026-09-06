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
    val uomCode: String,
    val qty: Int,
    val chosen: Boolean,
)

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

    fun withChoice(choice: PromotionChoice): PromotionSummary =
        copy(choices = choices + (choice.sequenceId to choice))

    /**
     * Keeps only the answers that still have a question. A basket edited down to
     * where a rule no longer applies must not carry that rule's answer into the
     * order.
     */
    fun prunedTo(fresh: List<EarnedPromotion>): PromotionSummary {
        val live = fresh.map { it.sequenceId }.toSet()
        return copy(earned = fresh, choices = choices.filterKeys { it in live })
    }
}
