package com.tinhcd.myesalessfa.feature.reports

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

/** The legacy report menu, all six entries. */
enum class ReportTab(val label: String) {
    ACTIVITIES("Hoạt động"),
    SALES("Doanh số"),
    ORDERS("Đơn hàng"),
    SKU("SKU"),
    TRADE("Thương mại"),
    WORK_RESULT("Kết quả"),
}

/** Which cut of the month's money is on screen. */
enum class SalesCut(val label: String) {
    CUSTOMER("Theo khách hàng"),
    PRODUCT("Theo sản phẩm"),
}

data class ReportsUiState(
    val tab: ReportTab = ReportTab.ACTIVITIES,
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
 * All six reports, loaded on demand.
 *
 * Each tab keeps whatever it last fetched, so switching back and forth does not
 * re-read: a rep comparing the day against the month is the whole reason the
 * six live on one screen, and making that comparison cost a round trip each way
 * would discourage the only thing this screen is for.
 *
 * The four window-based reports share one date range on purpose. A rep asking
 * "what did I do this month" wants the same month answered six ways, and a
 * per-tab range is how two tabs end up quietly describing two different months.
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val repository: ReportRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportsUiState())
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    init {
        loadActivities()
    }

    fun onTabSelected(tab: ReportTab) {
        _state.update { it.copy(tab = tab, error = null) }
        loadIfEmpty(tab)
    }

    private fun loadIfEmpty(tab: ReportTab) {
        val s = _state.value
        when (tab) {
            ReportTab.ACTIVITIES -> if (s.activities == null) loadActivities()
            ReportTab.SALES -> if (s.sales == null) loadSales()
            ReportTab.ORDERS -> if (s.orders == null) loadOrders()
            ReportTab.SKU -> if (s.sku == null) loadSku()
            ReportTab.TRADE -> if (s.trade == null) loadTrade()
            ReportTab.WORK_RESULT -> if (s.workResult == null) loadWorkResult()
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

    /**
     * Moving the window drops all four cached reports, not just the one showing.
     *
     * Keeping the others would leave a tab holding last month's figures under
     * this month's date range — which is exactly the disagreement the shared
     * window exists to prevent.
     */
    fun onWindowChanged(window: ReportWindow) {
        if (!window.isValid) return
        _state.update {
            it.copy(
                window = window,
                orders = null,
                sku = null,
                trade = null,
                workResult = null,
            )
        }
        loadIfEmpty(_state.value.tab)
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

    /** Re-reads whichever report is showing. */
    fun refresh() {
        when (_state.value.tab) {
            ReportTab.ACTIVITIES -> loadActivities()
            ReportTab.SALES -> loadSales()
            ReportTab.ORDERS -> loadOrders()
            ReportTab.SKU -> loadSku()
            ReportTab.TRADE -> loadTrade()
            ReportTab.WORK_RESULT -> loadWorkResult()
        }
    }

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
