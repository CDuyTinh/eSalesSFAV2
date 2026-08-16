package com.tinhcd.myesalessfa.feature.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.model.AppMenu
import com.tinhcd.myesalessfa.domain.model.MenuEntry
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShellUiState(
    val menu: AppMenu = AppMenu.Fallback,
    val selectedTab: String? = null,
    /** The sheet tab whose entries are showing, or null when none is open. */
    val openSheet: MenuEntry? = null,
    val me: Salesperson? = null,
    val signedOut: Boolean = false,
    /** Set when a rep taps something the server offers but this build lacks. */
    val unavailableMessage: String? = null,
)

/**
 * Owns the shell: which tab is showing, which sheet is open, and who is signed in.
 *
 * The tab list is configuration rather than code, so the bar is whatever the
 * server sent. Nothing here knows the names of the tabs.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ShellUiState())
    val state: StateFlow<ShellUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val menu = configRepository.menu()
            _state.update {
                it.copy(menu = menu, selectedTab = it.selectedTab ?: menu.defaultTab?.code)
            }
        }
        viewModelScope.launch {
            authRepository.sessionState.collect { session ->
                _state.update { it.copy(me = (session as? SessionState.SignedIn)?.rep) }
            }
        }
    }

    /**
     * A page tab swaps the content; a sheet tab opens its entries and leaves the
     * page where it was. Conflating the two is what left the legacy shell holding
     * three empty containers so its indices would line up.
     */
    fun onTabSelected(tab: MenuEntry) {
        if (!tab.implemented) {
            _state.update { it.copy(unavailableMessage = tab.title) }
            return
        }
        when (tab.kind) {
            com.tinhcd.myesalessfa.domain.model.MenuKind.PAGE ->
                _state.update { it.copy(selectedTab = tab.code) }

            com.tinhcd.myesalessfa.domain.model.MenuKind.SHEET ->
                _state.update { it.copy(openSheet = tab) }
        }
    }

    fun onSheetEntrySelected(entry: MenuEntry) {
        // Nothing behind any of them yet. Saying so is better than a blank screen
        // the rep has to guess their way out of.
        _state.update { it.copy(openSheet = null, unavailableMessage = entry.title) }
    }

    fun dismissSheet() = _state.update { it.copy(openSheet = null) }

    fun dismissUnavailable() = _state.update { it.copy(unavailableMessage = null) }

    /** Re-read after a refresh, so a tab head office added appears without a restart. */
    fun reloadMenu() {
        viewModelScope.launch {
            val menu = configRepository.menu()
            _state.update { current ->
                val stillThere = menu.tabs.any { it.code == current.selectedTab }
                current.copy(
                    menu = menu,
                    selectedTab = if (stillThere) current.selectedTab else menu.defaultTab?.code,
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _state.update { it.copy(signedOut = true) }
        }
    }
}
