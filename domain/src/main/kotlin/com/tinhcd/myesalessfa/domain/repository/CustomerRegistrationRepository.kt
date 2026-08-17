package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerDraft
import com.tinhcd.myesalessfa.domain.model.CustomerOptions
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
}
