package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.DisplayProgramOfferDto
import com.tinhcd.myesalessfa.data.remote.dto.LoyaltyOpenDto
import com.tinhcd.myesalessfa.data.remote.dto.TradeRegistrationDto
import com.tinhcd.myesalessfa.data.remote.dto.TradeRegistrationPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.TradeRegistrationService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DisplayLevelOffer
import com.tinhcd.myesalessfa.domain.model.DisplayProgramOffer
import com.tinhcd.myesalessfa.domain.model.LoyaltyCountsBy
import com.tinhcd.myesalessfa.domain.model.LoyaltyLevel
import com.tinhcd.myesalessfa.domain.model.LoyaltyProgramOffer
import com.tinhcd.myesalessfa.domain.model.RegisterableCustomer
import com.tinhcd.myesalessfa.domain.model.TradeKind
import com.tinhcd.myesalessfa.domain.model.TradeProgramOffers
import com.tinhcd.myesalessfa.domain.model.TradeProgramRef
import com.tinhcd.myesalessfa.domain.model.TradeRegistration
import com.tinhcd.myesalessfa.domain.model.TradeRegistrationBoard
import com.tinhcd.myesalessfa.domain.model.TradeRegistrationStatus
import com.tinhcd.myesalessfa.domain.repository.TradeRegistrationRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TradeRegistrationRepositoryImpl @Inject constructor(
    private val service: TradeRegistrationService,
) : TradeRegistrationRepository {

    override suspend fun board(
        kinds: List<TradeKind>,
        programIds: List<String>,
        statuses: List<TradeRegistrationStatus>,
        query: String,
    ): DataResult<TradeRegistrationBoard> = try {
        val body = service.board(
            kinds = kinds.takeIf { it.isNotEmpty() }?.joinToString(",") { it.wire },
            programIds = programIds.takeIf { it.isNotEmpty() }?.joinToString(","),
            statuses = statuses.takeIf { it.isNotEmpty() }?.joinToString(",") { it.wire },
            query = query.trim().ifBlank { null },
        ).orThrow()

        DataResult.Success(
            TradeRegistrationBoard(
                items = body.items.map { it.toDomain() },
                // An unknown name is dropped rather than mapped onto a real
                // one: a wrong count is worse than a missing chip.
                counts = body.counts.mapNotNull { (wire, n) ->
                    TradeRegistrationStatus.entries
                        .firstOrNull { it.wire == wire }?.let { it to n }
                }.toMap(),
                kindCounts = body.kindCounts.mapNotNull { (wire, n) ->
                    TradeKind.entries.firstOrNull { it.wire == wire }?.let { it to n }
                }.toMap(),
                programs = body.programs.mapNotNull { row ->
                    TradeKind.entries.firstOrNull { it.wire == row.kind }?.let { kind ->
                        TradeProgramRef(
                            id = row.id,
                            kind = kind,
                            code = row.code,
                            name = row.name,
                        )
                    }
                },
                slotsTotal = body.slotsTotal,
                slotsUsed = body.slotsUsed,
                total = body.total,
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun customers(query: String): DataResult<List<RegisterableCustomer>> = try {
        DataResult.Success(
            service.customers(query.trim()).orThrow().customers.map {
                RegisterableCustomer(
                    id = it.id,
                    code = it.code,
                    name = it.name,
                    address = it.address,
                )
            },
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun offersFor(customerId: String): DataResult<TradeProgramOffers> = try {
        val body = service.offers(customerId).orThrow()
        DataResult.Success(
            TradeProgramOffers(
                display = body.display.map { it.toDomain() },
                loyalty = body.loyalty.map { it.toDomain() },
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun register(
        kind: TradeKind,
        customerId: String,
        programId: String,
        levelId: String,
        portion: Int,
        reason: String?,
    ): DataResult<Unit> = try {
        service.register(
            TradeRegistrationPayload(
                kind = kind.wire,
                customerId = customerId,
                programId = programId,
                levelId = levelId,
                portion = portion,
                reason = reason?.trim()?.ifBlank { null },
            ),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun TradeRegistrationDto.toDomain() = TradeRegistration(
    id = id,
    kind = TradeKind.fromWire(kind),
    registeredAt = registeredAt,
    status = TradeRegistrationStatus.fromWire(status),
    portion = portion,
    customerId = customerId,
    customerCode = customerCode,
    customerName = customerName,
    phone = phone,
    address = address,
    avatarUrl = avatarUrl,
    programId = programId,
    programCode = programCode,
    programName = programName,
    levelName = levelName,
    reason = reason,
)

private fun DisplayProgramOfferDto.toDomain() = DisplayProgramOffer(
    programId = programId,
    programCode = programCode,
    programName = programName,
    specification = specification,
    regisFromDate = regisFromDate,
    regisToDate = regisToDate,
    levels = levels.map {
        DisplayLevelOffer(
            levelId = it.levelId,
            levelCode = it.levelCode,
            levelName = it.levelName,
            requiredFaces = it.requiredFaces,
            bonusAmount = it.bonusAmount,
            slotsLeft = it.slotsLeft,
        )
    },
)

private fun LoyaltyOpenDto.toDomain() = LoyaltyProgramOffer(
    programId = programId,
    programCode = programCode,
    programName = programName,
    specification = specification,
    countsBy = LoyaltyCountsBy.fromWire(countsBy),
    regisFromDate = regisFromDate,
    regisToDate = regisToDate,
    levels = levels.map {
        LoyaltyLevel(
            levelId = it.levelId,
            levelCode = it.levelCode,
            levelName = it.levelName,
            targetFrom = it.targetFrom,
            targetTo = it.targetTo,
            rewardBasisPoints = it.rewardBasisPoints,
            slotsLeft = it.slotsLeft,
        )
    },
)
