package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.CustomerPhotoTypeDto
import com.tinhcd.myesalessfa.data.remote.dto.CustomerRegistrationCommandPayload
import com.tinhcd.myesalessfa.data.remote.dto.CustomerRegistrationDetailDto
import com.tinhcd.myesalessfa.data.remote.dto.CustomerRegistrationRowDto
import com.tinhcd.myesalessfa.data.remote.dto.NamedRefDto
import com.tinhcd.myesalessfa.data.remote.dto.NewCustomerDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.storage.PhotoUploader
import com.tinhcd.myesalessfa.data.remote.service.CustomerRegistrationService
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerDraft
import com.tinhcd.myesalessfa.domain.model.CustomerOptions
import com.tinhcd.myesalessfa.domain.model.CustomerPhotoType
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationBoard
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationDetail
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationPhoto
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationRow
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.RegistrationStatus
import com.tinhcd.myesalessfa.domain.model.NamedRef
import com.tinhcd.myesalessfa.domain.model.RegisteredCustomer
import com.tinhcd.myesalessfa.domain.repository.CustomerRegistrationRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomerRegistrationRepositoryImpl @Inject constructor(
    private val service: CustomerRegistrationService,
    private val uploader: PhotoUploader,
    private val session: SessionStore,
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

    override suspend fun board(
        statuses: List<RegistrationStatus>,
        query: String,
    ): DataResult<CustomerRegistrationBoard> = try {
        val body = service.registrations(
            statuses = statuses.takeIf { it.isNotEmpty() }?.joinToString(",") { it.wire },
            query = query.trim().ifBlank { null },
        ).orThrow()

        DataResult.Success(
            CustomerRegistrationBoard(
                items = body.items.map { it.toDomain() },
                // An unknown status name is dropped rather than mapped onto a
                // real one: a wrong count is worse than a missing chip.
                counts = body.counts.mapNotNull { (wire, n) ->
                    RegistrationStatus.entries.firstOrNull { it.wire == wire }?.let { it to n }
                }.toMap(),
                photoTypes = body.photoTypes.map { it.toDomain() },
                total = body.total,
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun detail(customerId: String): DataResult<CustomerRegistrationDetail> = try {
        DataResult.Success(service.registration(customerId).orThrow().registration.toDomain())
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun update(
        customerId: String,
        draft: CustomerDraft,
    ): DataResult<CustomerRegistrationDetail> = try {
        val payload = buildMap<String, JsonElement> {
            put("id", JsonPrimitive(customerId))
            put("name", JsonPrimitive(draft.name.trim()))
            put("address", JsonPrimitive(draft.address.trim()))
            put("phone", draft.phone.trim().ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("note", draft.note.trim().ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
            draft.wardId?.let { put("ward_id", JsonPrimitive(it)) }
            draft.classId?.let { put("class_id", JsonPrimitive(it)) }
            draft.channelId?.let { put("channel_id", JsonPrimitive(it)) }
            draft.shopTypeId?.let { put("shop_type_id", JsonPrimitive(it)) }
        }

        val ack = service.command(
            CustomerRegistrationCommandPayload(
                action = "update",
                registration = JsonObject(payload),
            ),
        ).orThrow()

        DataResult.Success(
            requireNotNull(ack.registration) { "the server saved nothing" }.toDomain(),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * The bytes go to Storage first, and only the object name is recorded.
     *
     * A registration has no visit, so the path convention's middle segment is the
     * customer instead -- what the bucket policy authorises on is the first
     * segment, the rep, and that is unchanged.
     */
    override suspend fun setPhoto(
        customerId: String,
        typeId: String,
        storagePath: String,
        point: GeoPoint?,
        fileSize: Int?,
    ): DataResult<CustomerRegistrationDetail> = try {
        val salespersonId = session.current.value?.id
            ?: error("no signed-in salesperson to attribute the photo to")

        val objectName = uploader.upload(
            salespersonId = salespersonId,
            visitId = customerId,
            localPath = storagePath,
        )

        val ack = service.command(
            CustomerRegistrationCommandPayload(
                action = "photo",
                customerId = customerId,
                typeId = typeId,
                storagePath = objectName,
                lat = point?.lat,
                lng = point?.lng,
                fileSize = fileSize,
            ),
        ).orThrow()

        // The audit has landed; the local copy is just taking up space now.
        uploader.deleteLocal(storagePath)

        DataResult.Success(
            requireNotNull(ack.registration) { "the server returned no registration" }.toDomain(),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun delete(customerId: String): DataResult<Unit> = try {
        service.command(
            CustomerRegistrationCommandPayload(action = "delete", customerId = customerId),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun CustomerPhotoTypeDto.toDomain() = CustomerPhotoType(
    id = id,
    code = code,
    name = name,
    sampleUrl = sampleUrl,
    isRequired = isRequired,
    isAvatar = isAvatar,
)

private fun CustomerRegistrationRowDto.toDomain() = CustomerRegistrationRow(
    id = id,
    code = code,
    name = name,
    phone = phone,
    address = address,
    status = RegistrationStatus.fromWire(status),
    note = note,
    createdAt = createdAt,
    avatarUrl = avatarUrl,
    photoCount = photoCount,
)

private fun CustomerRegistrationDetailDto.toDomain() = CustomerRegistrationDetail(
    row = CustomerRegistrationRow(
        id = id,
        code = code,
        name = name,
        phone = phone,
        address = address,
        status = RegistrationStatus.fromWire(status),
        note = note,
        createdAt = createdAt,
        avatarUrl = avatarUrl,
        photoCount = photos.size,
    ),
    contactName = contactName,
    provinceId = provinceId,
    districtId = districtId,
    wardId = wardId,
    classId = classId,
    channelId = channelId,
    shopTypeId = shopTypeId,
    // Both or neither: half a coordinate is not a position. The accuracy is
    // not stored on the customer row -- only where the rep was standing -- so
    // it comes back unknown rather than invented.
    point = if (lat != null && lng != null) GeoPoint(lat, lng, accuracyM = null) else null,
    photos = photos.map {
        CustomerRegistrationPhoto(
            id = it.id,
            typeId = it.typeId,
            typeCode = it.typeCode,
            typeName = it.typeName,
            storagePath = it.storagePath,
            url = it.url,
            takenAt = it.takenAt,
        )
    },
)

private fun NamedRefDto.toDomain() = NamedRef(id = id, code = code, name = name)
