package com.tinhcd.myesalessfa.core.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Giao diện: follow the phone, or keep one look whatever the phone says. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Whether [mode] comes out dark on this phone right now. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The rep's choice of look, kept on the phone.
 *
 * SharedPreferences rather than DataStore because it is read once, synchronously,
 * before the first frame: an asynchronous read would draw the first screen in the
 * system's theme and then flip it, a flash on every cold start.
 */
@Singleton
class ThemeSettings @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
    )
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun set(mode: ThemeMode) {
        prefs.edit { putString(KEY, mode.name) }
        _mode.value = mode
    }

    private companion object {
        const val KEY = "theme_mode"
    }
}
