package com.tinhcd.myesalessfa.feature.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.ActivityReport
import com.tinhcd.myesalessfa.domain.model.OrderReport
import com.tinhcd.myesalessfa.domain.model.OrderReportStatus
import com.tinhcd.myesalessfa.domain.model.ReportWindow
import com.tinhcd.myesalessfa.domain.model.SalesReport
import com.tinhcd.myesalessfa.domain.model.SkuReport
import com.tinhcd.myesalessfa.domain.model.TradeReport
import com.tinhcd.myesalessfa.domain.model.TradeReportKind
import com.tinhcd.myesalessfa.domain.model.WorkResultReport
import com.tinhcd.myesalessfa.domain.repository.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * The legacy report menu, all six entries.
 *
 * The order is fixed here rather than read from the server. Over there the menu
 * comes from `menuAppConfig.reportMenu`, but the rebuild's menu_item tree stops
 * at REPORT -- it has no children -- so there is nothing to read and inventing a
 * table to hold six constants would be a table nobody edits.
 */
enum class ReportKind(
    val wire: String,
    val label: String,
    val description: String,
) {
    ACTIVITIES("activities", "Hoạt động ngày", "Ghé thăm, có đơn, đóng cửa trong ngày"),
    SALES("sales", "Doanh số tháng", "Doanh thu tháng theo khách hàng và sản phẩm"),
    ORDERS("orders", "Đơn hàng", "Đơn đã đặt trong kỳ và trạng thái xử lý"),
    SKU("sku", "Sản lượng SKU", "Số lượng bán ra theo từng mặt hàng"),
    TRADE("trade", "Chương trình thương mại", "Lượt đăng ký trưng bày và tích lũy"),
    WORK_RESULT("work_result", "Kết quả công việc", "Kế hoạch đã hoàn thành hoặc đã hủy");

    companion object {
        fun fromWire(value: String?): ReportKind =
            entries.firstOrNull { it.wire == value } ?: ACTIVITIES
    }
}

/** Which cut of the month's money is on screen. */
enum class SalesCut(val label: String) {
    CUSTOMER("Theo khách hàng"),
    PRODUCT("Theo sản phẩm"),
}

data class ReportsUiState(
    /** Fixed for the life of the screen: one route, one report. */
    val kind: ReportKind = ReportKind.ACTIVITIES,
    val cut: SalesCut = SalesCut.CUSTOMER,
    val date: LocalDate = LocalDate.now(),
    val month: LocalDate = LocalDate.now().withDayOfMonth(1),
    /** The four window-based reports share one range, as the rep thinks of it. */
    val window: ReportWindow = ReportWindow.thisMonth(),

    val activities: ActivityReport? = null,
    val sales: SalesReport? = null,
    val orders: OrderReport? = null,
    val sku: SkuReport? = null,
    val trade: TradeReport? = null,
    val workResult: WorkResultReport? = null,

    val orderStatus: OrderReportStatus? = null,
    val skuQuery: String = "",
    val tradeKind: TradeReportKind? = null,

    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * One report, named by the route.
 *
 * The six used to share a screen behind a tab row. They are six different
 * questions asked at six different moments, and stacking them meant every one
 * paid for the other five: a scrolling tab row nobody could read at a glance,
 * and a view model holding five reports the rep had not asked for. Now the menu
 * names them and each gets a route of its own.
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ReportRepository,
) : ViewModel() {

    private val kind: ReportKind = ReportKind.fromWire(savedStateHandle["kind"])

    private val _state = MutableStateFlow(ReportsUiState(kind = kind))
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    init {
        load(kind)
    }

    private fun load(kind: ReportKind) {
        when (kind) {
            ReportKind.ACTIVITIES -> loadActivities()
            ReportKind.SALES -> loadSales()
            ReportKind.ORDERS -> loadOrders()
            ReportKind.SKU -> loadSku()
            ReportKind.TRADE -> loadTrade()
            ReportKind.WORK_RESULT -> loadWorkResult()
        }
    }

    fun onCutSelected(cut: SalesCut) = _state.update { it.copy(cut = cut) }

    fun onDateChanged(date: LocalDate) {
        _state.update { it.copy(date = date, activities = null) }
        loadActivities()
    }

    fun onMonthChanged(month: LocalDate) {
        _state.update { it.copy(month = month.withDayOfMonth(1), sales = null) }
        loadSales()
    }

    fun onWindowChanged(window: ReportWindow) {
        if (!window.isValid) return
        _state.update { it.copy(window = window) }
        load(kind)
    }

    fun onOrderStatus(status: OrderReportStatus?) = _state.update {
        it.copy(orderStatus = if (it.orderStatus == status) null else status)
    }

    /** Filtered server-side, so a rep with hundreds of SKUs is not shipped them all. */
    fun onSkuQuery(value: String) {
        _state.update { it.copy(skuQuery = value) }
        loadSku()
    }

    fun onTradeKind(kind: TradeReportKind?) = _state.update {
        it.copy(tradeKind = if (it.tradeKind == kind) null else kind)
    }

    fun refresh() = load(kind)

    // -------------------------------------------------------------------------

    private fun loadActivities() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.activities(_state.value.date)) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, activities = result.data) }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được nhật ký hoạt động")
                    }
            }
        }
    }

    private fun loadSales() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.sales(_state.value.month)) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, sales = result.data) }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được báo cáo doanh số")
                    }
            }
        }
    }

    private fun loadOrders() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // Unfiltered: the status chips narrow the list that came back, and
            // the counts beside them have to keep counting the whole window.
            when (val result = repository.orders(_state.value.window)) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, orders = result.data) }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được báo cáo đơn hàng")
                    }
            }
        }
    }

    private fun loadSku() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val s = _state.value
            when (val result = repository.sku(s.window, s.skuQuery)) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, sku = result.data) }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được báo cáo SKU")
                    }
            }
        }
    }

    private fun loadTrade() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.trade(_state.value.window)) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, trade = result.data) }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được báo cáo thương mại")
                    }
            }
        }
    }

    private fun loadWorkResult() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.workResult(_state.value.window)) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, workResult = result.data) }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được báo cáo kết quả công việc")
                    }
            }
        }
    }
}
