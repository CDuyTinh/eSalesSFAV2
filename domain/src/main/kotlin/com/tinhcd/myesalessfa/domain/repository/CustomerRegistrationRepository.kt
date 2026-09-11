package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerDraft
import com.tinhcd.myesalessfa.domain.model.CustomerOptions
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationBoard
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationDetail
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.RegistrationStatus
import com.tinhcd.myesalessfa.domain.model.NamedRef
import com.tinhcd.myesalessfa.domain.model.RegisteredCustomer

/** Registering an outlet the rep met in the field. */
interface CustomerRegistrationRepository {

    suspend fun options(): DataResult<CustomerOptions>

    suspend fun districts(provinceId: String): DataResult<List<NamedRef>>

    suspend fun wards(districtId: String): DataResult<List<NamedRef>>

    /**
     * The code and the approval status are the server's to decide, so what comes
     * back is not simply the draft echoed: it is the row that now exists.
     */
    suspend fun register(draft: CustomerDraft): DataResult<RegisteredCustomer>

    // And what became of the ones already filed.

    suspend fun board(
        statuses: List<RegistrationStatus> = emptyList(),
        query: String = "",
    ): DataResult<CustomerRegistrationBoard>

    suspend fun detail(customerId: String): DataResult<CustomerRegistrationDetail>

    /** Corrects one still waiting for approval. */
    suspend fun update(
        customerId: String,
        draft: CustomerDraft,
    ): DataResult<CustomerRegistrationDetail>

    /**
     * Files one photo into its slot, replacing whatever was there.
     *
     * The avatar follows automatically when the slot is the one that claims it —
     * the server keeps the two in step so a half-done pair cannot exist.
     */
    suspend fun setPhoto(
        customerId: String,
        typeId: String,
        storagePath: String,
        point: GeoPoint? = null,
        fileSize: Int? = null,
    ): DataResult<CustomerRegistrationDetail>

    suspend fun delete(customerId: String): DataResult<Unit>
}
