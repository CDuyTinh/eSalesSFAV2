package com.tinhcd.myesalessfa.feature.checkin

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tinhcd.myesalessfa.R
import com.tinhcd.myesalessfa.domain.model.ReasonKind

/** The row label for a reason the check-in or the punch needs. */
@Composable
internal fun ReasonKind.label(): String = stringResource(
    when (this) {
        ReasonKind.GPS_OUT_OF_RANGE -> R.string.reason_out_of_range
        ReasonKind.GPS_LOW_ACCURACY -> R.string.reason_low_accuracy
        ReasonKind.GPS_UNAVAILABLE -> R.string.reason_no_location
        else -> R.string.reason_generic
    },
)

/** The sentence above a reason list, saying what went wrong. */
@Composable
internal fun ReasonKind.prompt(): String = stringResource(
    when (this) {
        ReasonKind.GPS_OUT_OF_RANGE -> R.string.prompt_out_of_range
        ReasonKind.GPS_LOW_ACCURACY -> R.string.prompt_low_accuracy
        ReasonKind.GPS_UNAVAILABLE -> R.string.prompt_no_location
        ReasonKind.OUTLET_CLOSED -> R.string.prompt_outlet_closed
        ReasonKind.NO_ORDER -> R.string.prompt_no_order
        ReasonKind.PHOTO_SKIPPED -> R.string.prompt_photo_skipped
        // Not a check-in gate at all: it classifies what feedback is about, and no
        // gate resolves to it. A neutral sentence rather than an exception.
        ReasonKind.FEEDBACK_TOPIC -> R.string.prompt_generic
    },
)
