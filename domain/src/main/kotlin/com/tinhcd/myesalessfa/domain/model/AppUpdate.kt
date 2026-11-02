package com.tinhcd.myesalessfa.domain.model

/**
 * Which builds head office wants in use, from app_setting
 * (`sfa_version_latest`, `sfa_version_min`, `sfa_update_url`, `sfa_update_note`).
 *
 * The app is not published on a store, so it cannot ask one what is current
 * (the legacy's new_version_plus / in_app_update); head office says instead.
 * Version codes, not names: they only ever go up, and "1.10" against "1.9" is
 * a comparison a string gets wrong.
 */
data class AppUpdatePolicy(
    val latestCode: Int?,
    val minCode: Int?,
    /** Where to get the new build; null opens the app's store page. */
    val url: String?,
    /** What changed, in a line or two, for the dialog. */
    val note: String?,
) {
    fun check(currentCode: Int): AppUpdateNeed = when {
        minCode != null && currentCode < minCode -> AppUpdateNeed.REQUIRED
        latestCode != null && currentCode < latestCode -> AppUpdateNeed.OPTIONAL
        else -> AppUpdateNeed.NONE
    }
}

enum class AppUpdateNeed {
    NONE,

    /** A newer build exists; the rep may carry on with this one. */
    OPTIONAL,

    /** This build is below the minimum head office supports: update to continue. */
    REQUIRED,
}
