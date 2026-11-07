package com.tinhcd.myesalessfa.feature

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinhcd.myesalessfa.core.ui.theme.AppLanguage
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.ThemeMode
import com.tinhcd.myesalessfa.feature.auth.LoginContent
import com.tinhcd.myesalessfa.feature.auth.LoginUiState
import com.tinhcd.myesalessfa.feature.settings.AppearanceCard
import com.tinhcd.myesalessfa.feature.settings.LanguageCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Screens drawn on the JVM through Robolectric, so they run in CI with no
 * device: what the rep sees, in each language, and what a tap sends back.
 *
 * Vietnamese by default, as the app is; the English test says so itself.
 * A plain Application rather than the Hilt one: these draw stateless content
 * and need none of the app's start-up work.
 *
 * The segmented choices are tapped through their click action, as TalkBack
 * does: the segments overlap by a pixel and a synthetic touch at the centre
 * is not delivered to them under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "vi")
class ScreensUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `sign-in stays disabled until both fields are filled`() {
        compose.setContent {
            MyeSalesTheme { LoginContent(LoginUiState(username = "nvbh01"), {}, {}, {}) }
        }

        compose.onNodeWithText("Đăng nhập").assertIsNotEnabled()
    }

    @Test
    fun `sign-in is enabled and sends when both fields are filled`() {
        var sent = 0
        compose.setContent {
            MyeSalesTheme { LoginContent(LoginUiState(username = "nvbh01", password = "secret"), {}, {}, { sent++ }) }
        }

        compose.onNodeWithText("Đăng nhập").assertIsEnabled().performClick()

        assertEquals(1, sent)
    }

    @Test
    fun `a sign-in error is shown to the rep`() {
        compose.setContent {
            MyeSalesTheme {
                LoginContent(LoginUiState(username = "nvbh01", password = "x", error = "Sai tên đăng nhập hoặc mật khẩu"), {}, {}, {})
            }
        }

        compose.onNodeWithText("Sai tên đăng nhập hoặc mật khẩu").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `sign-in reads in English when the app speaks English`() {
        compose.setContent {
            MyeSalesTheme { LoginContent(LoginUiState(), {}, {}, {}) }
        }

        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNodeWithText("Username").assertIsDisplayed()
    }

    @Test
    fun `appearance marks the current choice and reports a new one`() {
        var picked: ThemeMode? = null
        compose.setContent {
            MyeSalesTheme { AppearanceCard(mode = ThemeMode.SYSTEM, onSelect = { picked = it }) }
        }

        compose.onNodeWithText("Hệ thống").assertIsSelected()
        compose.onNodeWithText("Tối").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(ThemeMode.DARK, picked)
    }

    @Test
    fun `language names each option in its own language`() {
        var picked: AppLanguage? = null
        compose.setContent {
            MyeSalesTheme { LanguageCard(language = AppLanguage.VI, onSelect = { picked = it }) }
        }

        compose.onNodeWithText("Tiếng Việt").assertIsSelected()
        compose.onNodeWithText("English").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(AppLanguage.EN, picked)
    }
}
