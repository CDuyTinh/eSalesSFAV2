package com.tinhcd.myesalessfa.domain.model

/**
 * Where a plan stands.
 *
 * Six states, one set. The legacy keeps two tables for what is one object and
 * spells the same six states with two different letters — C means finished in
 * `OM_MakingPlan` and called off in `OM_WorkingPlan`. That divergence is not
 * carried over.
 */
enum class WorkPlanStatus(val wire: String, val label: String) {
    DRAFT("draft", "Nháp"),
    SUBMITTED("submitted", "Chờ duyệt"),
    APPROVED("approved", "Đã duyệt"),
    REJECTED("rejected", "Bị từ chối"),
    COMPLETED("completed", "Hoàn thành"),
    CANCELLED("cancelled", "Đã hủy");

    /** Still asking the rep for something. */
    val isOpen: Boolean get() = this == DRAFT || this == SUBMITTED || this == APPROVED

    companion object {
        fun fromWire(value: String): WorkPlanStatus =
            entries.firstOrNull { it.wire == value } ?: DRAFT
    }
}

/** Who or what a plan is with. Null on the category means the work has no object. */
enum class WorkPlanObjectKind(val wire: String, val label: String) {
    CUSTOMER("customer", "Khách hàng"),
    SALESPERSON("salesperson", "Nhân viên"),
    BRANCH("branch", "Nhà phân phối");

    companion object {
        fun fromWire(value: String?): WorkPlanObjectKind? =
            entries.firstOrNull { it.wire == value }
    }
}

/** One row of the work-type catalogue. `OM_WorkWithCategory`. */
data class WorkWithCategory(
    val id: String,
    val code: String,
    val name: String,
    val fieldTraining: Boolean = false,
    val tradeProgram: Boolean = false,
    val objectKind: WorkPlanObjectKind? = null,
    val minSales: Long? = null,
    val maxSales: Long? = null,
) {
    val needsObject: Boolean get() = objectKind != null
}

/** A customer, rep or distributor the plan is with. */
data class WorkPlanObject(
    val kind: WorkPlanObjectKind,
    val targetId: String,
    val name: String,
    val code: String? = null,
    val address: String? = null,
)

/** One entry in the trail of what happened to a plan. */
data class WorkPlanEvent(
    val status: WorkPlanStatus,
    val at: String,
    val actor: String? = null,
    val reason: String? = null,
)

/** A plan as the list shows it. */
data class WorkPlan(
    val id: String,
    val categoryId: String,
    val categoryCode: String,
    val categoryName: String,
    val fieldTraining: Boolean = false,
    val objectKind: WorkPlanObjectKind? = null,
    val content: String,
    val note: String? = null,
    val place: String? = null,
    val fromAt: String,
    val toAt: String,
    val status: WorkPlanStatus,
    val result: String? = null,
    val reason: String? = null,
    /** Somebody else planned this day. The legacy's `IsAssignToMe`. */
    val assignedToMe: Boolean = false,
    val objectCount: Int = 0,
) {
    /** Only a draft the rep wrote themselves can be edited or thrown away. */
    val canEdit: Boolean get() = status == WorkPlanStatus.DRAFT && !assignedToMe
    val canSubmit: Boolean get() = status == WorkPlanStatus.DRAFT
    val canClose: Boolean
        get() = status == WorkPlanStatus.SUBMITTED || status == WorkPlanStatus.APPROVED
    val canCancel: Boolean get() = status.isOpen
}

/** A plan opened on its own, with everything hanging off it. */
data class WorkPlanDetail(
    val plan: WorkPlan,
    val objects: List<WorkPlanObject> = emptyList(),
    val history: List<WorkPlanEvent> = emptyList(),
    val photos: List<String> = emptyList(),
    val tradeProgram: Boolean = false,
    val submittedAt: String? = null,
    val approvedAt: String? = null,
    val rejectedAt: String? = null,
    val completedAt: String? = null,
    val cancelledAt: String? = null,
)

/** One cell of the month grid: the day's most urgent status, and how many. */
data class WorkPlanDay(
    val day: String,
    val status: WorkPlanStatus,
    val planCount: Int,
)

/**
 * Everything the screen needs in one shape.
 *
 * [counts] is deliberately not derived from [items]: it counts the whole window
 * regardless of which status chip is selected, so a chip keeps showing its own
 * number while another one is filtering the list.
 */
data class WorkPlanBoard(
    val items: List<WorkPlan> = emptyList(),
    val counts: Map<WorkPlanStatus, Int> = emptyMap(),
    val calendar: List<WorkPlanDay> = emptyList(),
    val categories: List<WorkWithCategory> = emptyList(),
    val total: Int = 0,
) {
    fun count(status: WorkPlanStatus): Int = counts[status] ?: 0

    /** Chips for statuses that actually occur, in enum order. */
    val presentStatuses: List<WorkPlanStatus>
        get() = WorkPlanStatus.entries.filter { count(it) > 0 }

    fun dayOf(isoDay: String): WorkPlanDay? = calendar.firstOrNull { it.day == isoDay }
}

/**
 * The add/edit form.
 *
 * [canSave] is the legacy form's own checklist in one place: a work type, some
 * content, a window that ends after it starts, and — when the type asks for one
 * — at least one object.
 */
data class DraftWorkPlan(
    /** Null while the plan is new. */
    val id: String? = null,
    val category: WorkWithCategory? = null,
    val content: String = "",
    val note: String = "",
    val place: String = "",
    /** ISO-8601 with an offset, as the server stores them. */
    val fromAt: String = "",
    val toAt: String = "",
    val objects: List<WorkPlanObject> = emptyList(),
) {
    val objectKind: WorkPlanObjectKind? get() = category?.objectKind

    /** Changing the work type drops objects that no longer belong to it. */
    fun withCategory(next: WorkWithCategory): DraftWorkPlan = copy(
        category = next,
        objects = if (next.objectKind == null || next.objectKind != category?.objectKind) {
            emptyList()
        } else {
            objects
        },
    )

    fun withObject(target: WorkPlanObject): DraftWorkPlan =
        if (objects.any { it.targetId == target.targetId }) this
        else copy(objects = objects + target)

    fun withoutObject(targetId: String): DraftWorkPlan =
        copy(objects = objects.filterNot { it.targetId == targetId })

    fun toggleObject(target: WorkPlanObject): DraftWorkPlan =
        if (objects.any { it.targetId == target.targetId }) withoutObject(target.targetId)
        else withObject(target)

    val windowIsSane: Boolean
        get() = fromAt.isNotBlank() && toAt.isNotBlank() && toAt > fromAt

    val objectsSettled: Boolean
        get() = category != null && (!category.needsObject || objects.isNotEmpty())

    val canSave: Boolean
        get() = category != null &&
            content.isNotBlank() &&
            windowIsSane &&
            objectsSettled
}
