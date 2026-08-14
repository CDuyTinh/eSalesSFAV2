package com.tinhcd.myesalessfa.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tinhcd.myesalessfa.feature.auth.LoginScreen
import com.tinhcd.myesalessfa.feature.checkin.CheckInScreen
import com.tinhcd.myesalessfa.feature.route.RouteScreen

object Routes {
    const val LOGIN = "login"
    const val ROUTE = "route"
    const val CHECK_IN = "checkin/{customerId}"

    fun checkIn(customerId: String) = "checkin/$customerId"
}

@Composable
fun AppNavHost(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.LOGIN) {
            LoginScreen(
                onSignedIn = {
                    navController.navigate(Routes.ROUTE) {
                        // No going back to the login form with the hardware
                        // button once a session exists.
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.ROUTE) {
            RouteScreen(
                onOpenCheckIn = { customerId ->
                    navController.navigate(Routes.checkIn(customerId))
                },
                onSignedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.CHECK_IN,
            arguments = listOf(navArgument("customerId") { type = NavType.StringType }),
        ) {
            CheckInScreen(onDone = { navController.popBackStack() })
        }
    }
}
