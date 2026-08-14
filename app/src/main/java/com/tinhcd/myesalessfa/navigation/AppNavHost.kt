package com.tinhcd.myesalessfa.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tinhcd.myesalessfa.domain.model.SupportedSteps
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.feature.auth.LoginScreen
import com.tinhcd.myesalessfa.feature.checkin.CheckInScreen
import com.tinhcd.myesalessfa.feature.incall.InCallScreen
import com.tinhcd.myesalessfa.feature.incall.steps.DisplayAuditScreen
import com.tinhcd.myesalessfa.feature.incall.steps.NoteStepScreen
import com.tinhcd.myesalessfa.feature.incall.steps.StockCountScreen
import com.tinhcd.myesalessfa.feature.incall.steps.SurveyScreen
import com.tinhcd.myesalessfa.feature.incall.steps.TakeOrderScreen
import com.tinhcd.myesalessfa.feature.route.RouteScreen

object Routes {
    const val LOGIN = "login"
    const val ROUTE = "route"
    const val CHECK_IN = "checkin/{customerId}"
    const val IN_CALL = "incall/{visitId}/{customerId}"
    // The customer travels with the step, not just the visit: take_order prices
    // against the outlet's customer class, and re-deriving it from the visit
    // would be a second round trip inside a screen that already has one.
    const val STEP = "step/{visitId}/{customerId}/{formId}"

    fun checkIn(customerId: String) = "checkin/$customerId"
    fun inCall(visitId: String, customerId: String) = "incall/$visitId/$customerId"

    fun step(visitId: String, customerId: String, formId: String) =
        "step/$visitId/$customerId/$formId"
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
                onOpenStop = { stop ->
                    // Where a tap goes depends on how far the visit has got.
                    // Already checked in means the rep wants the work list, not
                    // the check-in form again.
                    val visitId = stop.visitId
                    if (stop.status == VisitStatus.IN_PROGRESS && visitId != null) {
                        navController.navigate(Routes.inCall(visitId, stop.customer.id))
                    } else {
                        navController.navigate(Routes.checkIn(stop.customer.id))
                    }
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
            // Back to the route list, which reloads and now shows the stop as
            // in progress. Going straight into the workflow would need the new
            // visit id, which a queued check-in does not have yet.
            CheckInScreen(onDone = { navController.popBackStack() })
        }

        composable(
            route = Routes.IN_CALL,
            arguments = listOf(
                navArgument("visitId") { type = NavType.StringType },
                navArgument("customerId") { type = NavType.StringType },
            ),
        ) { entry ->
            val visitId = entry.arguments?.getString("visitId").orEmpty()
            val customerId = entry.arguments?.getString("customerId").orEmpty()
            InCallScreen(
                onOpenStep = { formId ->
                    navController.navigate(Routes.step(visitId, customerId, formId))
                },
                onCheckedOut = { navController.popBackStack(Routes.ROUTE, inclusive = false) },
            )
        }

        composable(
            route = Routes.STEP,
            arguments = listOf(
                navArgument("visitId") { type = NavType.StringType },
                navArgument("customerId") { type = NavType.StringType },
                navArgument("formId") { type = NavType.StringType },
            ),
        ) { entry ->
            // One screen serves both implemented steps today. When a step needs
            // its own form, add a branch here — the registry of what this build
            // can render lives in SupportedSteps.
            when (entry.arguments?.getString("formId")) {
                SupportedSteps.OUTSIDE_CHECKING, SupportedSteps.FEEDBACK ->
                    NoteStepScreen(onDone = { navController.popBackStack() })

                SupportedSteps.TAKE_ORDER ->
                    TakeOrderScreen(onDone = { navController.popBackStack() })

                SupportedSteps.STOCK_OUTLET ->
                    StockCountScreen(onDone = { navController.popBackStack() })

                SupportedSteps.DISPLAY_REMARK ->
                    DisplayAuditScreen(onDone = { navController.popBackStack() })

                // Every questionnaire step shares this screen. Adding another is a
                // survey_type row naming its form id, plus that id in SupportedSteps —
                // no branch of its own.
                in SupportedSteps.surveyFormIds ->
                    SurveyScreen(onDone = { navController.popBackStack() })

                // The step list already refuses to open these, but answering an
                // unknown step with a note form would record the wrong shape of
                // data under its name — worse than not opening at all.
                else -> LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
    }
}
