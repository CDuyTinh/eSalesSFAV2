package com.tinhcd.myesalessfa.domain.usecase

import com.tinhcd.myesalessfa.domain.getOrNull
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.model.VisitWorkflow
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import java.time.LocalDate
import javax.inject.Inject

/**
 * The visit the rep is inside right now, if they are inside one.
 *
 * Exists so the Công việc sheet can be a shortcut into the call in progress
 * rather than a menu that ignores it — a rep halfway through a shop should not
 * have to go back to the route and find that shop again to reach its next step.
 */
data class OpenVisit(
    val visitId: String,
    val customerId: String,
    val customerName: String,
    val workflow: VisitWorkflow,
)

/**
 * Finds the open visit and loads its steps.
 *
 * Returns null rather than a [com.tinhcd.myesalessfa.domain.DataResult] on
 * purpose: every caller so far is offering a shortcut, and a shortcut that
 * cannot be built is simply absent. Reporting a failure here would put an error
 * on a sheet the rep opened to reach something else entirely, about a section
 * they may not have been looking for.
 */
class GetOpenVisitUseCase @Inject constructor(
    private val routeRepository: RouteRepository,
    private val workflowRepository: WorkflowRepository,
) {
    suspend operator fun invoke(on: LocalDate = LocalDate.now()): OpenVisit? {
        val stops = routeRepository.getRoute(on).getOrNull() ?: return null

        // In visit order, so that if two ever were open at once the rep lands on
        // the earlier stop — the one they are more likely to have forgotten.
        // The server closes the previous visit on check-in, so this should be at
        // most one; taking `first` is about behaving sanely if that ever slips
        // rather than about a case anyone expects.
        val stop = stops.firstOrNull { it.status == VisitStatus.IN_PROGRESS } ?: return null
        val visitId = stop.visitId ?: return null

        val workflow = workflowRepository.workflow(visitId).getOrNull() ?: return null

        return OpenVisit(
            visitId = visitId,
            customerId = stop.customer.id,
            customerName = stop.customer.name,
            workflow = workflow,
        )
    }
}
