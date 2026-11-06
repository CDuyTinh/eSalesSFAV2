package com.tinhcd.myesalessfa.core.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import com.tinhcd.myesalessfa.core.ui.theme.LanguageSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strings for code with no activity at hand — ViewModels building a message —
 * read in the language the rep chose, not the phone's.
 *
 * The application context follows the phone's language, so its resources
 * cannot be used directly; this keeps a context configured for the chosen one
 * and swaps it when the choice changes. A message is built in the language of
 * the moment it is built: one already on screen keeps its wording across a
 * switch, the next one comes in the new language. For messages that must
 * follow a switch, a ViewModel hands the screen [UiText] instead.
 */
@Singleton
class AppStrings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    @Volatile private var cached: Pair<String, Resources>? = null

    operator fun invoke(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) resources().getString(id) else resources().getString(id, *args)

    private fun resources(): Resources {
        val tag = LanguageSettings.stored(context).tag
        cached?.let { (t, r) -> if (t == tag) return r }
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config).resources.also { cached = tag to it }
    }
}
