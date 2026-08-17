package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.AppMenu
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.WorkDayPolicy

interface ConfigRepository {
    suspend fun checkInPolicy(): CheckInPolicy

    /** The depot equivalent: its own radius, and when a clock-in counts as late. */
    suspend fun workDayPolicy(): WorkDayPolicy

    /**
     * The shell's tabs and their sheet entries, already translated and ordered.
     * Falls back to [AppMenu.Fallback] rather than an empty bar when the cache
     * has not been filled yet.
     */
    suspend fun menu(): AppMenu

    suspend fun reasons(kind: ReasonKind): List<ReasonCode>

    /** Translated label for [key], falling back to [key] itself. */
    suspend fun translate(key: String): String

    /**
     * Step form id -> the step that must be completed before it opens, derived
     * from settings. Empty when the market imposes no ordering between steps.
     */
    suspend fun stepPrerequisites(): Map<String, String>

    /** Pulls settings, reason codes, workflow steps and translations locally. */
    suspend fun refresh(): DataResult<Unit>
}
