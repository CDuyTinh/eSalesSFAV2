package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.NfcRegisterDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.NfcTagService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.NfcRegistration
import com.tinhcd.myesalessfa.domain.model.NfcTagOwner
import com.tinhcd.myesalessfa.domain.repository.NfcTagRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NfcTagRepositoryImpl @Inject constructor(
    private val service: NfcTagService,
) : NfcTagRepository {

    override suspend fun ownerOf(uid: String): DataResult<NfcTagOwner?> = try {
        val owner = service.lookup(uid).orThrow().owner
        DataResult.Success(owner?.let { NfcTagOwner(it.customerId, it.code, it.name) })
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun register(customerId: String, uid: String): DataResult<NfcRegistration> = try {
        val answer = service.register(NfcRegisterDto(customerId, uid)).orThrow()
        DataResult.Success(
            when (answer.result) {
                "registered" -> NfcRegistration.Registered
                "unchanged" -> NfcRegistration.Unchanged
                "taken" -> NfcRegistration.Taken(answer.customerName)
                "no_visit" -> NfcRegistration.NoVisit
                else -> NfcRegistration.Invalid
            },
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
