package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.AuditPhotoPayload
import com.tinhcd.myesalessfa.data.remote.dto.CompetitorAnswerPayload
import com.tinhcd.myesalessfa.data.remote.dto.CompetitorSurveyPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.MarketInfoService
import com.tinhcd.myesalessfa.data.remote.storage.PhotoUploader
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CompetitorCriterion
import com.tinhcd.myesalessfa.domain.model.CompetitorEntry
import com.tinhcd.myesalessfa.domain.model.CompetitorPairing
import com.tinhcd.myesalessfa.domain.model.CompetitorSurveyDefinition
import com.tinhcd.myesalessfa.domain.model.DraftCompetitorSurvey
import com.tinhcd.myesalessfa.domain.model.MarketInfoSurvey
import com.tinhcd.myesalessfa.domain.model.MarketSurveyKind
import com.tinhcd.myesalessfa.domain.repository.MarketInfoRepository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bytes first, then the rows — the order the display and POSM steps use, and for
 * the same reason: the server stores the object names it is given, so a row
 * written before its photo would point at nothing.
 */
@Singleton
class MarketInfoRepositoryImpl @Inject constructor(
    private val service: MarketInfoService,
    private val uploader: PhotoUploader,
    private val session: SessionStore,
) : MarketInfoRepository {

    override suspend fun surveys(
        customerId: String,
        visitId: String,
    ): DataResult<List<MarketInfoSurvey>> = try {
        val body = service.surveys(customerId, visitId).orThrow()

        DataResult.Success(
            body.surveys.map {
                MarketInfoSurvey(
                    kind = MarketSurveyKind.fromWire(it.kind),
                    id = it.id,
                    code = it.code,
                    name = it.name,
                    fromDate = it.fromDate,
                    toDate = it.toDate,
                    isCompleted = it.isCompleted,
                    itemCount = it.itemCount,
                )
            },
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun competitorSurvey(
        surveyId: String,
        visitId: String,
    ): DataResult<DraftCompetitorSurvey> = try {
        val body = service.competitorSurvey(visitId = visitId, surveyId = surveyId).orThrow()

        val pairings = body.items.map {
            CompetitorPairing(
                productId = it.productId,
                productCode = it.productCode,
                productName = it.productName,
                competitorProductId = it.competitorProductId,
                competitorProduct = it.competitorProduct,
                competitorName = it.competitorName,
            )
        }

        val definition = CompetitorSurveyDefinition(
            id = body.id,
            code = body.code,
            name = body.name,
            criteria = body.criteria.map {
                CompetitorCriterion(
                    id = it.id,
                    code = it.code,
                    name = it.name,
                    hint = it.hint,
                    isRequired = it.isRequired,
                )
            },
            pairings = pairings,
        )

        // What this visit already answered comes back as the draft's starting
        // state, so reopening a survey shows the earlier answers and their photos
        // rather than an empty grid the rep would fill in twice.
        val entries = body.items.flatMap { item ->
            item.answers.map { (criteriaId, answer) ->
                DraftCompetitorSurvey.cellKey(
                    item.productId, item.competitorProductId, criteriaId,
                ) to
                    CompetitorEntry(
                        content = answer.content,
                        alreadyStored = answer.photos,
                    )
            }
        }.toMap()

        DataResult.Success(
            DraftCompetitorSurvey(
                visitId = visitId,
                definition = definition,
                entries = entries,
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun submit(draft: DraftCompetitorSurvey): DataResult<Unit> = try {
        // The rep owns the storage folder the policies authorise on, so without a
        // session there is nowhere to upload to. Failing here beats uploading
        // somewhere unowned.
        val salespersonId = session.current.value?.id
            ?: error("no signed-in salesperson to attribute the photos to")

        val cells = draft.filledCells()

        val answers = cells.map { cell ->
            val uploaded = cell.entry.photos.map { photo ->
                AuditPhotoPayload(
                    storagePath = uploader.upload(
                        salespersonId = salespersonId,
                        visitId = draft.visitId,
                        localPath = photo.localPath,
                    ),
                    takenAt = Instant.ofEpochMilli(photo.takenAtEpochMs)
                        .atOffset(ZoneOffset.UTC).toString(),
                    lat = photo.lat,
                    lng = photo.lng,
                    fileSize = photo.sizeBytes,
                )
            }

            // Photos an earlier submission of this visit left behind travel again by
            // name. The server replaces a cell's photos with whatever it is sent, so
            // omitting them would delete evidence the rep never asked to remove.
            val kept = cell.entry.alreadyStored.map {
                AuditPhotoPayload(
                    storagePath = it,
                    takenAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                )
            }

            CompetitorAnswerPayload(
                productId = cell.pairing.productId,
                competitorProductId = cell.pairing.competitorProductId,
                criteriaId = cell.criterion.id,
                content = cell.entry.content.trim(),
                photos = kept + uploaded,
            )
        }

        service.submit(
            CompetitorSurveyPayload(
                visitId = draft.visitId,
                surveyId = draft.definition.id,
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                answers = answers,
            ),
        ).orThrow()

        // Only now, and from the draft rather than the payload: the payload carries
        // storage object names, not paths on this device. Deleting earlier would
        // leave a failed submit with nothing to re-upload.
        cells.forEach { cell -> cell.entry.photos.forEach { uploader.deleteLocal(it.localPath) } }

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
