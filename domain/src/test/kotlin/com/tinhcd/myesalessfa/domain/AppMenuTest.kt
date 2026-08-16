package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.AppMenu
import com.tinhcd.myesalessfa.domain.model.MenuEntry
import com.tinhcd.myesalessfa.domain.model.MenuKind
import com.tinhcd.myesalessfa.domain.model.SupportedMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMenuTest {

    private fun tab(
        code: String,
        kind: MenuKind = MenuKind.PAGE,
        implemented: Boolean = true,
        order: Int = 0,
    ) = MenuEntry(
        code = code,
        titleKey = "menu_${code.lowercase()}",
        title = code,
        order = order,
        kind = kind,
        implemented = implemented,
    )

    @Test
    fun `lands on the first renderable page`() {
        val menu = AppMenu(
            tabs = listOf(
                tab(SupportedMenu.DASH_BOARD, order = 0),
                tab(SupportedMenu.CHECK_IN, order = 2),
            ),
        )
        assertEquals(SupportedMenu.DASH_BOARD, menu.defaultTab?.code)
    }

    @Test
    fun `skips a sheet tab when choosing where to land`() {
        // A sheet has no content of its own, so landing on it would show nothing.
        val menu = AppMenu(
            tabs = listOf(
                tab(SupportedMenu.PREPARATION, kind = MenuKind.SHEET, order = 0),
                tab(SupportedMenu.CHECK_IN, order = 1),
            ),
        )
        assertEquals(SupportedMenu.CHECK_IN, menu.defaultTab?.code)
    }

    @Test
    fun `skips a page this build cannot render`() {
        // Head office enabled a tab a released app has never heard of.
        val menu = AppMenu(
            tabs = listOf(
                tab("FORECAST", implemented = false, order = 0),
                tab(SupportedMenu.CHECK_IN, order = 1),
            ),
        )
        assertEquals(SupportedMenu.CHECK_IN, menu.defaultTab?.code)
    }

    @Test
    fun `falls back to the first tab when nothing is renderable`() {
        val menu = AppMenu(tabs = listOf(tab("FORECAST", implemented = false)))
        assertEquals("FORECAST", menu.defaultTab?.code)
    }

    @Test
    fun `an empty menu has no landing tab rather than a made-up one`() {
        assertNull(AppMenu(tabs = emptyList()).defaultTab)
    }

    @Test
    fun `the fallback menu still lets a rep work`() {
        // Whatever else is missing before the cache fills, the visit list is the
        // one tab the job cannot be done without.
        val fallback = AppMenu.Fallback
        assertTrue(fallback.tabs.isNotEmpty())
        assertEquals(SupportedMenu.CHECK_IN, fallback.defaultTab?.code)
    }
}
