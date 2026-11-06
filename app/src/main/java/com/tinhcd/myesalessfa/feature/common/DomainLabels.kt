package com.tinhcd.myesalessfa.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tinhcd.myesalessfa.R
import com.tinhcd.myesalessfa.domain.model.LevelCapCut
import com.tinhcd.myesalessfa.domain.model.NotificationKind
import com.tinhcd.myesalessfa.domain.model.OrderReportStatus
import com.tinhcd.myesalessfa.domain.model.PasswordRule
import com.tinhcd.myesalessfa.domain.model.PosmCondition
import com.tinhcd.myesalessfa.domain.model.PosmMovementKind
import com.tinhcd.myesalessfa.domain.model.ProductSort
import com.tinhcd.myesalessfa.domain.model.PromotionScope
import com.tinhcd.myesalessfa.domain.model.RegistrationStatus
import com.tinhcd.myesalessfa.domain.model.StockScope
import com.tinhcd.myesalessfa.domain.model.TradeKind
import com.tinhcd.myesalessfa.domain.model.TradeRegistrationStatus
import com.tinhcd.myesalessfa.domain.model.TradeReportKind
import com.tinhcd.myesalessfa.domain.model.WorkPlanObjectKind
import com.tinhcd.myesalessfa.domain.model.WorkPlanStatus

/*
 * The names of the domain's enums, in the app's language. The domain keeps a
 * Vietnamese `viLabel` for logs and tests; screens use these.
 */

@Composable
fun RegistrationStatus.label(): String = stringResource(
    when (this) {
        RegistrationStatus.PENDING -> R.string.enum_registration_status_pending
        RegistrationStatus.APPROVED -> R.string.enum_registration_status_approved
        RegistrationStatus.REJECTED -> R.string.enum_registration_status_rejected
    },
)

@Composable
fun NotificationKind.label(): String = stringResource(
    when (this) {
        NotificationKind.PROMOTION -> R.string.enum_notification_kind_promotion
        NotificationKind.MANUAL_PROMOTION -> R.string.enum_notification_kind_manual_promotion
        NotificationKind.DISPLAY -> R.string.enum_notification_kind_display
        NotificationKind.LOYALTY -> R.string.enum_notification_kind_loyalty
        NotificationKind.POSM -> R.string.enum_notification_kind_posm
        NotificationKind.WORK_NOTE -> R.string.enum_notification_kind_work_note
        NotificationKind.LEAVE_RESULT -> R.string.enum_notification_kind_leave_result
        NotificationKind.CUSTOMER_RESULT -> R.string.enum_notification_kind_customer_result
        NotificationKind.TRADE_RESULT -> R.string.enum_notification_kind_trade_result
        NotificationKind.WORK_PLAN -> R.string.enum_notification_kind_work_plan
    },
)

@Composable
fun ProductSort.label(): String = stringResource(
    when (this) {
        ProductSort.DEFAULT -> R.string.enum_product_sort_default
        ProductSort.PRICE_ASC -> R.string.enum_product_sort_price_asc
        ProductSort.IN_BASKET -> R.string.enum_product_sort_in_basket
    },
)

@Composable
fun PasswordRule.label(): String = stringResource(
    when (this) {
        PasswordRule.LENGTH -> R.string.enum_password_rule_length
        PasswordRule.LOWERCASE -> R.string.enum_password_rule_lowercase
        PasswordRule.UPPERCASE -> R.string.enum_password_rule_uppercase
        PasswordRule.DIGIT -> R.string.enum_password_rule_digit
        PasswordRule.SPECIAL -> R.string.enum_password_rule_special
    },
)

@Composable
fun PosmCondition.label(): String = stringResource(
    when (this) {
        PosmCondition.USABLE -> R.string.enum_posm_condition_usable
        PosmCondition.REPAIRABLE -> R.string.enum_posm_condition_repairable
        PosmCondition.UNUSABLE -> R.string.enum_posm_condition_unusable
    },
)

@Composable
fun PosmMovementKind.label(): String = stringResource(
    when (this) {
        PosmMovementKind.DELIVERY -> R.string.enum_posm_movement_kind_delivery
        PosmMovementKind.RECALL -> R.string.enum_posm_movement_kind_recall
    },
)

@Composable
fun PromotionScope.label(): String = stringResource(
    when (this) {
        PromotionScope.LINE -> R.string.enum_promotion_scope_line
        PromotionScope.GROUP -> R.string.enum_promotion_scope_group
        PromotionScope.ORDER -> R.string.enum_promotion_scope_order
    },
)

@Composable
fun OrderReportStatus.label(): String = stringResource(
    when (this) {
        OrderReportStatus.NEW -> R.string.enum_order_report_status_new
        OrderReportStatus.CONFIRMED -> R.string.enum_order_report_status_confirmed
        OrderReportStatus.CANCELLED -> R.string.enum_order_report_status_cancelled
    },
)

@Composable
fun TradeReportKind.label(): String = stringResource(
    when (this) {
        TradeReportKind.DISPLAY -> R.string.enum_trade_report_kind_display
        TradeReportKind.LOYALTY -> R.string.enum_trade_report_kind_loyalty
    },
)

@Composable
fun StockScope.label(): String = stringResource(
    when (this) {
        StockScope.PURCHASED -> R.string.enum_stock_scope_purchased
        StockScope.MUST_STOCK -> R.string.enum_stock_scope_must_stock
        StockScope.ALL -> R.string.enum_stock_scope_all
    },
)

@Composable
fun TradeKind.label(): String = stringResource(
    when (this) {
        TradeKind.DISPLAY -> R.string.enum_trade_kind_display
        TradeKind.LOYALTY -> R.string.enum_trade_kind_loyalty
    },
)

@Composable
fun TradeRegistrationStatus.label(): String = stringResource(
    when (this) {
        TradeRegistrationStatus.PENDING -> R.string.enum_trade_registration_status_pending
        TradeRegistrationStatus.APPROVED -> R.string.enum_trade_registration_status_approved
        TradeRegistrationStatus.REJECTED -> R.string.enum_trade_registration_status_rejected
    },
)

@Composable
fun WorkPlanStatus.label(): String = stringResource(
    when (this) {
        WorkPlanStatus.DRAFT -> R.string.enum_work_plan_status_draft
        WorkPlanStatus.SUBMITTED -> R.string.enum_work_plan_status_submitted
        WorkPlanStatus.APPROVED -> R.string.enum_work_plan_status_approved
        WorkPlanStatus.REJECTED -> R.string.enum_work_plan_status_rejected
        WorkPlanStatus.COMPLETED -> R.string.enum_work_plan_status_completed
        WorkPlanStatus.CANCELLED -> R.string.enum_work_plan_status_cancelled
    },
)

@Composable
fun WorkPlanObjectKind.label(): String = stringResource(
    when (this) {
        WorkPlanObjectKind.CUSTOMER -> R.string.enum_work_plan_object_kind_customer
        WorkPlanObjectKind.SALESPERSON -> R.string.enum_work_plan_object_kind_salesperson
        WorkPlanObjectKind.BRANCH -> R.string.enum_work_plan_object_kind_branch
    },
)

/** Which limits cut a promotion level, as LevelCapCut.reasons says them in Vietnamese. */
@Composable
fun LevelCapCut.reasonsText(): String = listOfNotNull(
    stringResource(R.string.cap_out_of_quota).takeIf { outOfQuota },
    stringResource(R.string.cap_out_of_budget).takeIf { outOfBudget },
    stringResource(R.string.cap_out_of_stock).takeIf { outOfStock },
).joinToString(", ")
