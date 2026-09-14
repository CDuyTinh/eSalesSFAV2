package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.RegisterableCustomer
import com.tinhcd.myesalessfa.domain.model.TradeKind
import com.tinhcd.myesalessfa.domain.model.TradeProgramOffers
import com.tinhcd.myesalessfa.domain.model.TradeRegistrationBoard
import com.tinhcd.myesalessfa.domain.model.TradeRegistrationStatus

/**
 * Đăng ký chương trình — signing outlets up outside a visit.
 *
 * Registering from inside a call still goes through the customer hub, which
 * derives the outlet from the visit. This is the other way in, where the rep
 * picks the outlet themselves.
 */
interface TradeRegistrationRepository {

    suspend fun board(
        kinds: List<TradeKind> = emptyList(),
        programIds: List<String> = emptyList(),
        statuses: List<TradeRegistrationStatus> = emptyList(),
        query: String = "",
    ): DataResult<TradeRegistrationBoard>

    /** The outlets this rep may sign up, narrowed by what was typed. */
    suspend fun customers(query: String = ""): DataResult<List<RegisterableCustomer>>

    /** Everything one outlet may still join, both kinds at once. */
    suspend fun offersFor(customerId: String): DataResult<TradeProgramOffers>

    suspend fun register(
        kind: TradeKind,
        customerId: String,
        programId: String,
        levelId: String,
        portion: Int = 1,
        reason: String? = null,
    ): DataResult<Unit>
}
