package com.tinhcd.myesalessfa.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A message a ViewModel hands the screen, in whichever language the screen is
 * drawn in.
 *
 * A ViewModel has no activity context, so it cannot read the chosen language's
 * strings itself; and a String it built would stay in the old language across
 * a language switch, since the ViewModel outlives the recreated activity. It
 * names the resource instead and the screen reads it.
 *
 * [Raw] is for text that is already final: a message the server wrote.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText

    @Composable
    fun asString(): String = when (this) {
        is Res -> stringResource(id, *args.toTypedArray())
        is Raw -> value
    }
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())
