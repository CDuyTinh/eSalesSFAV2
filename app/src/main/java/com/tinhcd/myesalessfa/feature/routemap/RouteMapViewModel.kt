package com.tinhcd.myesalessfa.feature.routemap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.usecase.GetTodayRouteUseCase
import com.tinhcd.myesalessfa.domain.usecase.Haversine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RouteMapUiState(
    val loading: Boolean = true,
    /** Only stops that can actually be drawn. */
    val stops: List<RouteStop> = emptyList(),
    /** On the route but with no coordinates, so nothing can be plotted for them. */
    val unmapped: Int = 0,
    val me: GeoPoint? = null,
    val selected: RouteStop? = null,
    val error: String? = null,
) {
    /**
     * How far the selected outlet is from where the rep is standing, in metres.
     *
     * Straight-line, and the label says so. A road distance would need a routing
     * call the app does not make, and quoting one as if it were driving distance
     * would have a rep budgeting the wrong amount of time.
     */
    val selectedDistanceM: Double?
        get() {
            val me = me ?: return null
            val stop = selected ?: return null
            val lat = stop.customer.lat ?: return null
            val lng = stop.customer.lng ?: return null
            return Haversine.distanceM(me.lat, me.lng, lat, lng)
        }
}

/**
 * Today's stops on a map.
 *
 * Reuses [GetTodayRouteUseCase], so the pins are the same stops in the same order
 * as the list tab — including the outlets the rep registered themselves. A second
 * query shaped for the map would eventually disagree with the list about what the
 * day contains, and the rep would have no way to tell which was right.
 */
@HiltViewModel
class RouteMapViewModel @Inject constructor(
    private val getTodayRoute: GetTodayRouteUseCase,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(RouteMapUiState())
    val state: StateFlow<RouteMapUiState> = _state.asStateFlow()

    init {
        load()
        locate()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = getTodayRoute()) {
                is DataResult.Success -> {
                    // An outlet with no position cannot be drawn. Counted rather
                    // than dropped in silence: a map showing eight of eleven
                    // stops, saying nothing, is a map that lies by omission.
                    val (mappable, missing) = result.data.partition {
                        it.customer.lat != null && it.customer.lng != null
                    }
                    _state.update {
                        it.copy(loading = false, stops = mappable, unmapped = missing.size)
                    }
                }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được tuyến hôm nay")
                    }
            }
        }
    }

    fun locate() {
        viewModelScope.launch {
            val point = runCatching { locationProvider.currentLocation() }.getOrNull()
            _state.update { it.copy(me = point) }
        }
    }

    fun select(stop: RouteStop?) = _state.update { it.copy(selected = stop) }
}
