package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.LoyaltyLevelDto
import com.tinhcd.myesalessfa.data.remote.dto.LoyaltyRegistrationPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.LoyaltyService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.LoyaltyCountsBy
import com.tinhcd.myesalessfa.domain.model.LoyaltyLevel
import com.tinhcd.myesalessfa.domain.model.LoyaltyProgram
import com.tinhcd.myesalessfa.domain.model.LoyaltyProgramOffer
import com.tinhcd.myesalessfa.domain.model.LoyaltySnapshot
import com.tinhcd.myesalessfa.domain.repository.LoyaltyRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoyaltyRepositoryImpl @Inject constructor(
    private val service: LoyaltyService,
) : LoyaltyRepository {

    override suspend fun load(customerId: String): DataResult<LoyaltySnapshot> = try {
        val body = service.load(customerId).orThrow()

        DataResult.Success(
            LoyaltySnapshot(
                joined = body.joined.map { row ->
                    LoyaltyProgram(
                        programId = row.programId,
                        programCode = row.programCode,
                        programName = row.programName,
                        specification = row.specification,
                        countsBy = LoyaltyCountsBy.fromWire(row.countsBy),
                        fromDate = row.fromDate,
                        toDate = row.toDate,
                        level = LoyaltyLevel(
                            levelId = row.levelId,
                            levelCode = row.levelCode,
                            levelName = row.levelName,
                            targetFrom = row.targetFrom,
                            targetTo = row.targetTo,
                            rewardBasisPoints = row.rewardBasisPoints,
                        ),
                        status = row.status,
                        registeredAt = row.registeredAt,
                        achieved = row.achieved,
                        remaining = row.remaining,
                    )
                },
                open = body.open.map { row ->
                    LoyaltyProgramOffer(
                        programId = row.programId,
                        programCode = row.programCode,
                        programName = row.programName,
                        specification = row.specification,
                        countsBy = LoyaltyCountsBy.fromWire(row.countsBy),
                        regisFromDate = row.regisFromDate,
                        regisToDate = row.regisToDate,
                        levels = row.levels.map { it.toDomain() },
                    )
                },
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun register(
        visitId: String,
        programId: String,
        levelId: String,
        reason: String?,
    ): DataResult<Unit> = try {
        service.register(
            LoyaltyRegistrationPayload(
                visitId = visitId,
                programId = programId,
                levelId = levelId,
                reason = reason?.trim()?.ifBlank { null },
            ),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun LoyaltyLevelDto.toDomain() = LoyaltyLevel(
    levelId = levelId,
    levelCode = levelCode,
    levelName = levelName,
    targetFrom = targetFrom,
    targetTo = targetTo,
    rewardBasisPoints = rewardBasisPoints,
    slotsLeft = slotsLeft,
)
