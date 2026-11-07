package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.NfcRegistration
import com.tinhcd.myesalessfa.domain.model.NfcTagOwner

/** NFC tags fixed inside outlets, as a proof of presence that does not lean on GPS. */
interface NfcTagRepository {
    /** Who [uid] belongs to; success with null for a tag nobody registered. */
    suspend fun ownerOf(uid: String): DataResult<NfcTagOwner?>

    /** Binds [uid] to [customerId]; the rep must be checked in there. */
    suspend fun register(customerId: String, uid: String): DataResult<NfcRegistration>
}
