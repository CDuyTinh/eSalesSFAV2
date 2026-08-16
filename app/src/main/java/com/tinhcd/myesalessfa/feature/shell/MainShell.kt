package com.tinhcd.myesalessfa.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.MenuEntry
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.SupportedMenu
import com.tinhcd.myesalessfa.feature.dashboard.DashboardScreen
import com.tinhcd.myesalessfa.feature.route.RouteScreen
import kotlinx.coroutines.launch

/**
 * The frame everything after sign-in sits inside: a drawer, a content area, and a
 * bottom bar built from whatever tabs the server configured.
 *
 * Two kinds of tab, kept apart deliberately. A page tab swaps the content area; a
 * sheet tab opens a list of entries and leaves the page alone. The app this
 * replaces ran both through one list of pages and parked an empty container in
 * three of the five slots so the indices would line up — the shape of that bug is
 * visible in its source to this day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    onOpenStop: (RouteStop) -> Unit,
    onSignedOut: () -> Unit,
    viewModel: ShellViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ShellDrawer(
                name = state.me?.fullName,
                branch = state.me?.branchName,
                code = state.me?.code,
                onSignOut = {
                    scope.launch { drawerState.close() }
                    viewModel.signOut()
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (state.selectedTab) {
                    SupportedMenu.DASH_BOARD -> DashboardScreen(
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )

                    SupportedMenu.CHECK_IN -> RouteScreen(
                        onOpenStop = onOpenStop,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onReferenceDataRefreshed = viewModel::reloadMenu,
                    )

                    // Only reachable if the server names a page tab this build does
                    // not know. The bar still shows it; opening it says so.
                    else -> UnknownTab()
                }
            }

            ShellBottomBar(
                tabs = state.menu.tabs,
                selected = state.selectedTab,
                onSelect = viewModel::onTabSelected,
            )
        }
    }

    val sheet = state.openSheet
    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SheetMenu(tab = sheet, onSelect = viewModel::onSheetEntrySelected)
        }
    }

    state.unavailableMessage?.let { label ->
        NotBuiltDialog(label = label, onDismiss = viewModel::dismissUnavailable)
    }
}

// -----------------------------------------------------------------------------
// Bottom bar
// -----------------------------------------------------------------------------

/**
 * Hand-built rather than a NavigationBar, because the tab count is not known at
 * compile time and a sheet tab must not stay latched after it is tapped.
 */
@Composable
private fun ShellBottomBar(
    tabs: List<MenuEntry>,
    selected: String?,
    onSelect: (MenuEntry) -> Unit,
) {
    if (tabs.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
        ) {
            tabs.forEach { tab ->
                val isSelected = tab.code == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = iconFor(tab.code),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = when {
                            !tab.implemented -> MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.4f)

                            isSelected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * Icons stay on the device. The server sends a code and a label; shipping icon
 * names too would mean a release every time head office picked a different one,
 * and an unknown code would render nothing at all.
 */
private fun iconFor(code: String): ImageVector = when (code) {
    SupportedMenu.DASH_BOARD -> Icons.Default.Analytics
    SupportedMenu.PREPARATION -> Icons.AutoMirrored.Filled.PlaylistAddCheck
    SupportedMenu.CHECK_IN -> Icons.Default.Storefront
    SupportedMenu.TASKS -> Icons.Default.Inventory2
    SupportedMenu.OTHER -> Icons.Default.MoreHoriz
    else -> Icons.Default.Group
}

// -----------------------------------------------------------------------------
// Sheet
// -----------------------------------------------------------------------------

@Composable
private fun SheetMenu(tab: MenuEntry, onSelect: (MenuEntry) -> Unit) {
    Column(Modifier.navigationBarsPadding()) {
        Text(
            text = tab.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        HorizontalDivider()

        if (tab.children.isEmpty()) {
            Text(
                text = "Chua co muc nao",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }

        tab.children.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = if (entry.implemented) {
                        Icons.AutoMirrored.Filled.PlaylistAddCheck
                    } else {
                        Icons.Default.Lock
                    },
                    contentDescription = null,
                    tint = if (entry.implemented) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                    if (!entry.implemented) {
                        Text(
                            text = "Chua xay dung trong ban nay",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Drawer
// -----------------------------------------------------------------------------

@Composable
private fun ShellDrawer(
    name: String?,
    branch: String?,
    code: String?,
    onSignOut: () -> Unit,
) {
    val brand = MaterialTheme.brand
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Surface(color = brand.header, contentColor = brand.onHeader) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(20.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(brand.onHeader.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Person, contentDescription = null)
                }
                Text(
                    text = name ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = listOfNotNull(code, branch).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = brand.onHeader.copy(alpha = 0.85f),
                )
            }
        }

        NavigationDrawerItem(
            label = { Text("Dang xuat") },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            selected = false,
            onClick = onSignOut,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
        )
    }
}

// -----------------------------------------------------------------------------
// Placeholders
// -----------------------------------------------------------------------------

@Composable
private fun UnknownTab() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Muc nay chua co trong ban nay",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotBuiltDialog(label: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Da hieu") }
        },
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(label) },
        text = {
            Text(
                "Chuc nang nay da duoc bat tren he thong nhung ban ung dung hien tai " +
                    "chua co man hinh cho no.",
            )
        },
    )
}

