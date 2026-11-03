package com.tinhcd.myesalessfa.push

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Opens the screen a tapped notification named, once the signed-in shell is on
 * the stack: tapped while signed out, it waits through the login. A screen this
 * build does not know ([destination] returns null) just leaves the app open.
 */
@Composable
fun FollowPushRoute(navController: NavHostController, shellRoute: String, destination: (String) -> String?) {
    val pending by PushRoute.pending.collectAsStateWithLifecycle()
    val entry by navController.currentBackStackEntryAsState()
    LaunchedEffect(pending, entry) {
        val screen = pending ?: return@LaunchedEffect
        val signedIn = runCatching { navController.getBackStackEntry(shellRoute) }.isSuccess
        if (!signedIn) return@LaunchedEffect
        PushRoute.consume()
        destination(screen)?.let { navController.navigate(it) { launchSingleTop = true } }
    }
}
