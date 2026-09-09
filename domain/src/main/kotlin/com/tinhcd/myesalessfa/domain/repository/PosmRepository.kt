package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftPosmCheck
import com.tinhcd.myesalessfa.domain.model.DraftPosmMovement
import com.tinhcd.myesalessfa.domain.model.DraftPosmRegistration
import com.tinhcd.myesalessfa.domain.model.PosmAtCustomer
import com.tinhcd.myesalessfa.domain.model.PosmCatalogueEntry
import com.tinhcd.myesalessfa.domain.model.PosmRegistration

/**
 * What is in the shop and what the shop is waiting for, in one read — the rep
 * looks at both together, and loading them separately would show one of them
 * stale.
 */
data class PosmSnapshot(
    val placed: List<PosmAtCustomer>,
    val registrations: List<PosmRegistration>,
    /** What the outlet may still be signed up for, and where each stands. */
    val catalogue: List<PosmCatalogueEntry> = emptyList(),
)

interface PosmRepository {
    suspend fun load(customerId: String, visitId: String): DataResult<PosmSnapshot>

    /**
     * Sends one asset's check, photos and all. The photos are uploaded first and
     * the row written second, so a stored check always has its evidence behind it.
     */
    suspend fun submit(check: DraftPosmCheck): DataResult<Unit>

    /**
     * Marks the step done for an outlet that holds no POSM at all. The rep has
     * looked and there is nothing to count, which is an answer the step needs a
     * way to record — otherwise it sits unfinished forever.
     */
    suspend fun completeEmpty(visitId: String): DataResult<Unit>

    /**
     * Puts in a request for POSM this outlet does not have. Head office rules on
     * it; nothing here can approve it.
     */
    suspend fun register(draft: DraftPosmRegistration): DataResult<Unit>

    /**
     * Hands assets over or takes them back. The photos are uploaded first and the
     * rows written second, so a stored handover always has its evidence behind it.
     */
    suspend fun move(draft: DraftPosmMovement): DataResult<Unit>
}
