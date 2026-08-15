package com.tinhcd.myesalessfa.data.remote.dto

import java.util.Locale

/**
 * Which language to ask /bootstrap for.
 *
 * Labels come from the server's translation table, not strings.xml, exactly as in
 * the legacy app. Only the languages actually seeded are honoured — asking for one
 * that is not there would return an empty label set, and every screen would fall
 * back to raw keys.
 *
 * Shared because both the profile read and the config refresh call /bootstrap and
 * must not disagree about the language, or the second would overwrite the first's
 * labels with a different set.
 */
internal fun activeLanguage(): String {
    val device = Locale.getDefault().language.lowercase()
    return if (device in SUPPORTED_LANGUAGES) device else DEFAULT_LANGUAGE
}

private const val DEFAULT_LANGUAGE = "vi"
private val SUPPORTED_LANGUAGES = setOf("vi", "en")
