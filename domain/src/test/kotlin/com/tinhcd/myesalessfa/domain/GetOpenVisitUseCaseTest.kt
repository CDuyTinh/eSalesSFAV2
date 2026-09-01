package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.SalesStep
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.model.VisitWorkflow
import com.tinhcd.myesalessfa.domain.model.WorkflowStep
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import com.tinhcd.myesalessfa.domain.usecase.GetOpenVisitUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GetOpenVisitUseCaseTest {

    private val today = LocalDate.of(2026, 9, 2)

    private fun customer(id: String, name: String) = Customer(
        id = id,
        code = id,
        name = name,
        address = null,
        phone = null,
        lat = null,
        lng = null,
        avatarUrl = null,
        checkInRadiusM = null,
    )

    private fun stop(
        id: String,
        name: String,
        status: VisitStatus,
        visitId: String?,
        order: Int,
    ) = RouteStop(
        customer = customer(id, name),
        visitOrder = order,
        status = status,
        visitId = visitId,
        checkInAtEpochMs = null,
        checkOutAtEpochMs = null,
    )

    private class FakeRoute(private val result: DataResult<List<RouteStop>>) : RouteRepository {
        override suspend fun getRoute(date: LocalDate) = result
        override suspend fun getStop(customerId: String, date: LocalDate) =
            DataResult.Success(null as RouteStop?)
    }

    private class FakeWorkflow(
        private val result: DataResult<VisitWorkflow>,
    ) : WorkflowRepository {
        var askedFor: String? = null

        override suspend fun workflow(visitId: String): DataResult<VisitWorkflow> {
            askedFor = visitId
            return result
        }

        override suspend fun step(formId: String) = DataResult.Success(null as SalesStep?)
        override suspend fun completeStep(
            visitId: String,
            formId: String,
            payload: Map<String, String>,
        ) = DataResult.Success(Unit)
    }

    private val workflow = VisitWorkflow(visitId = "v2", steps = emptyList<WorkflowStep>())

    @Test
    fun findsTheVisitInProgress() = runTest {
        val route = FakeRoute(
            DataResult.Success(
                listOf(
                    stop("c1", "Tap hoa Mot", VisitStatus.COMPLETED, "v1", 1),
                    stop("c2", "Tap hoa Hai", VisitStatus.IN_PROGRESS, "v2", 2),
                    stop("c3", "Tap hoa Ba", VisitStatus.PLANNED, null, 3),
                ),
            ),
        )
        val flow = FakeWorkflow(DataResult.Success(workflow))

        val open = GetOpenVisitUseCase(route, flow)(today)

        assertEquals("v2", open?.visitId)
        assertEquals("c2", open?.customerId)
        assertEquals("Tap hoa Hai", open?.customerName)
        // The workflow is read for that visit and no other.
        assertEquals("v2", flow.askedFor)
    }

    /** The ordinary case: the rep is between shops, so there is no shortcut. */
    @Test
    fun returnsNullWhenNoVisitIsOpen() = runTest {
        val route = FakeRoute(
            DataResult.Success(
                listOf(
                    stop("c1", "Tap hoa Mot", VisitStatus.COMPLETED, "v1", 1),
                    stop("c3", "Tap hoa Ba", VisitStatus.PLANNED, null, 3),
                ),
            ),
        )

        assertNull(GetOpenVisitUseCase(route, FakeWorkflow(DataResult.Success(workflow)))(today))
    }

    /**
     * A failed route read must not surface as an error. The sheet was opened to
     * reach something else, and a shortcut that cannot be built is simply absent.
     */
    @Test
    fun returnsNullWhenTheRouteCannotBeRead() = runTest {
        val route = FakeRoute(DataResult.Failure(AppError.Network()))

        assertNull(GetOpenVisitUseCase(route, FakeWorkflow(DataResult.Success(workflow)))(today))
    }

    /** Same for the steps: no steps, no shortcut, no complaint. */
    @Test
    fun returnsNullWhenTheWorkflowCannotBeRead() = runTest {
        val route = FakeRoute(
            DataResult.Success(
                listOf(stop("c2", "Tap hoa Hai", VisitStatus.IN_PROGRESS, "v2", 2)),
            ),
        )

        assertNull(GetOpenVisitUseCase(route, FakeWorkflow(DataResult.Failure(AppError.Network())))(today))
    }

    /**
     * A visit marked in progress with no id is contradictory data. Better to
     * offer no shortcut than to open a step against an empty visit id.
     */
    @Test
    fun returnsNullWhenTheOpenStopHasNoVisitId() = runTest {
        val route = FakeRoute(
            DataResult.Success(
                listOf(stop("c2", "Tap hoa Hai", VisitStatus.IN_PROGRESS, null, 2)),
            ),
        )

        assertNull(GetOpenVisitUseCase(route, FakeWorkflow(DataResult.Success(workflow)))(today))
    }
}
