package com.tinhcd.myesalessfa.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AddBusiness
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.MenuEntry
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.SupportedMenu
import com.tinhcd.myesalessfa.domain.usecase.OpenVisit
import com.tinhcd.myesalessfa.feature.incall.StepTiles
import com.tinhcd.myesalessfa.domain.model.WorkDay
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
    onOpenCustomer: (RouteStop) -> Unit,
    /** A step of the visit in progress, reached from the work sheet. */
    onOpenVisitStep: (visitId: String, customerId: String, formId: String) -> Unit,
    onOpenMap: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenWorkDay: () -> Unit,
    /** A sheet entry this build has a screen for. The code, not the label. */
    onOpenMenuEntry: (String) -> Unit,
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
                workDay = state.workDay,
                onOpenAccount = {
                    scope.launch { drawerState.close() }
                    onOpenAccount()
                },
                onOpenWorkDay = {
                    scope.launch { drawerState.close() }
                    onOpenWorkDay()
                },
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

                    // The route is behind the depot, as it was in the app this
                    // replaces: a visit records a rep who is on shift, and the
                    // shift has to have been started for that to be true.
                    SupportedMenu.CHECK_IN -> if (state.routeBlocked) {
                        DayNotStartedPanel(
                            branchName = state.workDay?.branch?.name,
                            onOpenWorkDay = onOpenWorkDay,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                        )
                    } else {
                        RouteScreen(
                            onOpenStop = onOpenStop,
                            onOpenCustomer = onOpenCustomer,
                            onOpenMap = onOpenMap,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onReferenceDataRefreshed = viewModel::reloadMenu,
                        )
                    }

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
            SheetMenu(
                tab = sheet,
                // Only the work sheet gets the shortcut. The visit's steps under
                // Khác would be the same tiles answering a question nobody asked
                // there.
                openVisit = state.openVisit.takeIf { sheet.code == SupportedMenu.TASKS },
                onSelect = { entry ->
                    if (viewModel.onSheetEntrySelected(entry)) onOpenMenuEntry(entry.code)
                },
                onOpenVisitStep = { visit, formId ->
                    viewModel.dismissSheet()
                    onOpenVisitStep(visit.visitId, visit.customerId, formId)
                },
            )
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

/**
 * A sheet tab's entries, as tiles four across.
 *
 * Was a vertical list of rows. Tiles are what the app this replaces uses for all
 * three of these sheets, and the reason holds up: these are a handful of
 * destinations, not a list to be read down, and four across puts every one of
 * them above the fold where a list of eight did not.
 *
 * [openVisit] is only passed for the Công việc sheet, and only shows when the
 * rep is actually inside a call.
 */
@Composable
private fun SheetMenu(
    tab: MenuEntry,
    openVisit: OpenVisit?,
    onSelect: (MenuEntry) -> Unit,
    onOpenVisitStep: (OpenVisit, String) -> Unit,
) {
    Column(
        Modifier
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = tab.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        HorizontalDivider()

        if (tab.children.isEmpty()) {
            Text(
                text = "Chưa có mục nào",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }

        Column(Modifier.padding(vertical = 8.dp)) {
            tab.children.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { entry ->
                        MenuTile(
                            entry = entry,
                            onClick = { onSelect(entry) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (openVisit != null) {
            HorizontalDivider()

            Text(
                text = "Công việc",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp),
            )
            Text(
                text = openVisit.customerName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )

            // Four across here against three on the customer screen, as the
            // legacy has it: this is a shortcut list glanced at, not the surface
            // the visit is worked from.
            StepTiles(
                steps = openVisit.workflow.steps,
                onOpenStep = { formId -> onOpenVisitStep(openVisit, formId) },
                columns = 4,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * One destination on a sheet.
 *
 * A locked padlock replaces the icon for an entry the server offers and this
 * build has no screen for, rather than the entry being hidden — head office
 * configured it, and a rep who has been told it exists should see why it does
 * not open rather than wonder where it went.
 */
@Composable
private fun MenuTile(entry: MenuEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (entry.implemented) scheme.primaryContainer else scheme.surfaceVariant,
            modifier = Modifier.size(50.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (entry.implemented) entry.code.menuIcon() else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (entry.implemented) {
                        scheme.onPrimaryContainer
                    } else {
                        scheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = entry.title,
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (entry.implemented) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
}

/** The legacy ships a drawn icon per entry; these are the nearest Material ones. */
private fun String.menuIcon(): ImageVector = when (this) {
    SupportedMenu.NEW_CUSTOMER -> Icons.Default.AddBusiness
    SupportedMenu.REPORT -> Icons.Default.Assessment
    SupportedMenu.RECEIVABLE -> Icons.Default.Payments
    SupportedMenu.DAILY_SALES_TARGET -> Icons.Default.Flag
    SupportedMenu.SALES_FOCUS -> Icons.Default.Star
    SupportedMenu.SITE -> Icons.Default.Warehouse
    SupportedMenu.WORKING_NOTE -> Icons.AutoMirrored.Filled.StickyNote2
    SupportedMenu.LEAVE_APPLICATION -> Icons.Default.EventBusy
    else -> Icons.AutoMirrored.Filled.PlaylistAddCheck
}

// -----------------------------------------------------------------------------
// Drawer
// -----------------------------------------------------------------------------

@Composable
private fun ShellDrawer(
    name: String?,
    branch: String?,
    code: String?,
    workDay: WorkDay?,
    onOpenAccount: () -> Unit,
    onOpenWorkDay: () -> Unit,
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
            label = { Text("Thông tin tài khoản") },
            icon = { Icon(Icons.Default.Person, contentDescription = null) },
            selected = false,
            onClick = onOpenAccount,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
        )

        // Only while there is a day to end. Signing out is not the same act — it
        // drops the session and leaves the day open behind it — so the two are
        // never offered as if they were interchangeable.
        if (workDay?.isOpen == true) {
            NavigationDrawerItem(
                label = { Text("Kết thúc ngày bán hàng") },
                icon = { Icon(Icons.Default.EventAvailable, contentDescription = null) },
                selected = false,
                onClick = onOpenWorkDay,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
            )
        }

        NavigationDrawerItem(
            label = { Text("Đăng xuất") },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            selected = false,
            onClick = onSignOut,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
        )
    }
}

/**
 * What the visit tab shows before the day has been opened.
 *
 * A panel rather than a dialog. The legacy app put a modal here and a rep who
 * dismissed it landed on an empty container with nothing to explain it; this
 * states the situation and keeps the only useful action on screen.
 */
@Composable
private fun DayNotStartedPanel(
    branchName: String?,
    onOpenWorkDay: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.brand.header, contentColor = MaterialTheme.brand.onHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "Mở menu")
                }
                Text(
                    text = "Viếng thăm",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(
                Icons.Default.EventAvailable,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Chưa bắt đầu ngày bán hàng",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = branchName
                    ?.let { "Chấm công tại $it để mở tuyến hôm nay." }
                    ?: "Chấm công tại chi nhánh để mở tuyến hôm nay.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PrimaryButton(text = "Bắt đầu ngày bán hàng", onClick = onOpenWorkDay)
        }
    }
}

// -----------------------------------------------------------------------------
// Placeholders
// -----------------------------------------------------------------------------

@Composable
private fun UnknownTab() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Mục này chưa có trong bản này",
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
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Đã hiểu") }
        },
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(label) },
        text = {
            Text(
                "Chức năng này đã được bật trên hệ thống nhưng bản ứng dụng hiện tại " +
                    "chưa có màn hình cho nó.",
            )
        },
    )
}

