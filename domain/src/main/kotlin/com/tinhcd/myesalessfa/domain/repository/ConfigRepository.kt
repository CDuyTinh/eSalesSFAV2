package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind

interface ConfigRepository {
    suspend fun checkInPolicy(): CheckInPolicy

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
