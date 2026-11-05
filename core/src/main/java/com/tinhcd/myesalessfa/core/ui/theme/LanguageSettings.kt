package com.tinhcd.myesalessfa.core.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The languages the app is written in. Each names itself, so either can be found by someone who reads only it. */
enum class AppLanguage(val tag: String, val nativeName: String) {
    VI("vi", "Tiếng Việt"),
    EN("en", "English"),
}

/**
 * Ngôn ngữ: which language the app speaks, chosen in the app rather than
 * taken from the phone. A rep's phone is often set to English while they read
 * Vietnamese, and the other way round.
 *
 * Applied by wrapping the activity's context ([wrap], from attachBaseContext)
 * and setting the JVM default, which is also what picks the server's label
 * set for the menus. A change recreates the activity so every screen redraws in
 * the new language.
 */
@Singleton
class LanguageSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val language: AppLanguage get() = stored(context)

    fun set(language: AppLanguage) {
        prefs(context).edit(commit = true) { putString(KEY, language.tag) }
        Locale.setDefault(Locale.forLanguageTag(language.tag))
    }

    companion object {
        private const val KEY = "language"

        private fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

        /** The stored choice, or the phone's language when it is one the app speaks, else Vietnamese. */
        fun stored(context: Context): AppLanguage {
            val tag = prefs(context).getString(KEY, null) ?: Locale.getDefault().language
            return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.VI
        }

        /** [base] re-configured for the stored language. Read before Hilt exists, hence static. */
        fun wrap(base: Context): Context {
            val locale = Locale.forLanguageTag(stored(base).tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            return base.createConfigurationContext(config)
        }
    }
}
