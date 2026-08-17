package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.NamedRefDto
import com.tinhcd.myesalessfa.data.remote.dto.NewCustomerDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.CustomerRegistrationService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerDraft
import com.tinhcd.myesalessfa.domain.model.CustomerOptions
import com.tinhcd.myesalessfa.domain.model.NamedRef
import com.tinhcd.myesalessfa.domain.model.RegisteredCustomer
import com.tinhcd.myesalessfa.domain.repository.CustomerRegistrationRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomerRegistrationRepositoryImpl @Inject constructor(
    private val service: CustomerRegistrationService,
) : CustomerRegistrationRepository {

    override suspend fun options(): DataResult<CustomerOptions> = try {
        val dto = service.options().orThrow()
        DataResult.Success(
            CustomerOptions(
                classes = dto.classes.map { it.toDomain() },
                channels = dto.channels.map { it.toDomain() },
                shopTypes = dto.shopTypes.map { it.toDomain() },
                provinces = dto.provinces.map { it.toDomain() },
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun districts(provinceId: String): DataResult<List<NamedRef>> = try {
        DataResult.Success(service.districts(provinceId).orThrow().districts.map { it.toDomain() })
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun wards(districtId: String): DataResult<List<NamedRef>> = try {
        DataResult.Success(service.wards(districtId).orThrow().wards.map { it.toDomain() })
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * Blank optional fields are sent as null rather than as "".
     *
     * An empty string in `phone` would be a phone number as far as every later
     * reader is concerned — a report counting outlets with a contact number would
     * count it, and nobody would be able to ring it.
     */
    override suspend fun register(draft: CustomerDraft): DataResult<RegisteredCustomer> = try {
        val ack = service.submit(
            NewCustomerDto(
                name = draft.name.trim(),
                phone = draft.phone.trim().ifBlank { null },
                address = draft.address.trim(),
                wardId = draft.wardId,
                lat = draft.point?.lat,
                lng = draft.point?.lng,
                classId = draft.classId,
                channelId = draft.channelId,
                shopTypeId = draft.shopTypeId,
                note = draft.note.trim().ifBlank { null },
            ),
        ).orThrow()

        DataResult.Success(
            RegisteredCustomer(
                id = ack.customer.id,
                code = ack.customer.code,
                name = ack.customer.name,
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun NamedRefDto.toDomain() = NamedRef(id = id, code = code, name = name)
