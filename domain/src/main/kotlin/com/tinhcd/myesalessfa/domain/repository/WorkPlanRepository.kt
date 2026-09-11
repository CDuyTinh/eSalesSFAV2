package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftWorkPlan
import com.tinhcd.myesalessfa.domain.model.WorkPlanBoard
import com.tinhcd.myesalessfa.domain.model.WorkPlanDetail
import com.tinhcd.myesalessfa.domain.model.WorkPlanObject
import com.tinhcd.myesalessfa.domain.model.WorkPlanStatus

interface WorkPlanRepository {

    /** The list, the counts, the calendar and the catalogue in one call. */
    suspend fun board(
        fromDate: String? = null,
        toDate: String? = null,
        statuses: List<WorkPlanStatus> = emptyList(),
        assignedToMe: Boolean? = null,
    ): DataResult<WorkPlanBoard>

    suspend fun detail(planId: String): DataResult<WorkPlanDetail>

    suspend fun save(draft: DraftWorkPlan): DataResult<WorkPlanDetail>

    /** Drives the state machine. [reason] is required to complete or cancel. */
    suspend fun setStatus(
        planId: String,
        status: WorkPlanStatus,
        reason: String? = null,
    ): DataResult<WorkPlanDetail>

    suspend fun delete(planId: String): DataResult<Unit>

    /** The outlets this rep may plan a day with, narrowed by what was typed. */
    suspend fun plannableCustomers(query: String = ""): DataResult<List<WorkPlanObject>>
}
