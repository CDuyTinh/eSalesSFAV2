package com.tinhcd.myesalessfa.domain.model

/**
 * How a bottom-bar tab behaves when tapped.
 *
 * The legacy app ran both through one list of pages and left three of the five
 * slots holding an empty container, purely so the indices lined up. The two are
 * genuinely different things, so they are different things here.
 */
enum class MenuKind {
    /** Swaps the content area. */
    PAGE,

    /** Opens a sheet of entries and leaves the current page where it is. */
    SHEET,
}

data class MenuEntry(
    val code: String,
    val titleKey: String,
    /** Resolved through the translation cache; falls back to [titleKey]. */
    val title: String,
    val order: Int,
    val kind: MenuKind,
    /** False for entries the server enabled that this build has no screen for. */
    val implemented: Boolean,
    val children: List<MenuEntry> = emptyList(),
)

/**
 * The shell as head office configured it: which tabs exist, in what order, and
 * what sits inside the ones that open a sheet.
 *
 * A market that does not sell on credit drops RECEIVABLE from `menu_item` and the
 * entry disappears — no release, exactly as with `sales_step`.
 */
data class AppMenu(
    val tabs: List<MenuEntry>,
) {
    /** The tab to land on after sign-in: the first page, else the first tab. */
    val defaultTab: MenuEntry?
        get() = tabs.firstOrNull { it.kind == MenuKind.PAGE && it.implemented }
            ?: tabs.firstOrNull()

    fun tab(code: String): MenuEntry? = tabs.firstOrNull { it.code == code }

    companion object {
        /**
         * Used only until the cache is filled. Deliberately just the visit list:
         * it is the one tab a rep cannot do their job without, and showing a
         * hopeful full bar that then rearranges is worse than showing one that
         * grows.
         */
        val Fallback = AppMenu(
            tabs = listOf(
                MenuEntry(
                    code = SupportedMenu.CHECK_IN,
                    titleKey = "menu_check_in",
                    title = "menu_check_in",
                    order = 0,
                    kind = MenuKind.PAGE,
                    implemented = true,
                ),
            ),
        )
    }
}

/**
 * What this build can actually render, in one place — the same arrangement as
 * [SupportedSteps] and for the same reason: the server may enable something a
 * released app has never heard of, and the shell has to say so rather than open
 * a blank screen.
 */
object SupportedMenu {
    const val DASH_BOARD = "DASH_BOARD"
    const val PREPARATION = "PREPARATION"
    const val CHECK_IN = "CHECK_IN"
    const val TASKS = "TASKS"
    const val OTHER = "OTHER"

    /** Entries inside a sheet, for the ones this build actually has a screen for. */
    const val NEW_CUSTOMER = "NEW_CUSTOMER"
    const val REPORT = "REPORT"
    const val RECEIVABLE = "RECEIVABLE"
    const val DAILY_SALES_TARGET = "DAILY_SALES_TARGET"
    const val SALES_FOCUS = "SALES_FOCUS"
    const val SITE = "SITE"
    const val WORKING_NOTE = "WORKING_NOTE"
    const val LEAVE_APPLICATION = "LEAVE_APPLICATION"

    /** Tabs that swap the content area. Everything else opens a sheet. */
    val pages: Set<String> = setOf(DASH_BOARD, CHECK_IN)

    /**
     * Codes with a screen behind them today. The three sheet tabs are here
     * because the sheet itself is the screen; of their children, eight have one
     * so far — the whole of Chuẩn bị, four of the five under Khác, and one
     * under Công việc.
     */
    val implemented: Set<String> = setOf(
        DASH_BOARD,
        PREPARATION,
        CHECK_IN,
        TASKS,
        OTHER,
        NEW_CUSTOMER,
        REPORT,
        RECEIVABLE,
        DAILY_SALES_TARGET,
        SALES_FOCUS,
        SITE,
        WORKING_NOTE,
        LEAVE_APPLICATION,
    )
}
