package com.tinhcd.myesalessfa.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.SupportedMenu
import com.tinhcd.myesalessfa.domain.model.SupportedSteps
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.feature.account.AccountScreen
import com.tinhcd.myesalessfa.feature.auth.LoginScreen
import com.tinhcd.myesalessfa.feature.checkin.CheckInScreen
import com.tinhcd.myesalessfa.feature.incall.InCallScreen
import com.tinhcd.myesalessfa.feature.incall.steps.DisplayAuditScreen
import com.tinhcd.myesalessfa.feature.incall.steps.FeedbackScreen
import com.tinhcd.myesalessfa.feature.incall.steps.NoteStepScreen
import com.tinhcd.myesalessfa.feature.incall.steps.StockCountScreen
import com.tinhcd.myesalessfa.feature.incall.steps.SurveyScreen
import com.tinhcd.myesalessfa.feature.incall.steps.TakeOrderScreen
import com.tinhcd.myesalessfa.feature.newcustomer.NewCustomerScreen
import com.tinhcd.myesalessfa.feature.receivables.ReceivablesScreen
import com.tinhcd.myesalessfa.feature.routemap.RouteMapScreen
import com.tinhcd.myesalessfa.feature.reports.ReportsScreen
import com.tinhcd.myesalessfa.feature.shell.MainShell
import com.tinhcd.myesalessfa.feature.workday.WorkDayScreen

object Routes {
    const val LOGIN = "login"

    /**
     * Everything after sign-in lives inside the shell. Its tabs are not routes:
     * they are configuration, and the bar is built from whatever the server sent.
     */
    const val SHELL = "shell"

    /** Opening and closing the selling day. No argument: it is always today's. */
    const val WORK_DAY = "workday"
    const val NEW_CUSTOMER = "newcustomer"
    const val REPORTS = "reports"
    const val RECEIVABLES = "receivables"
    const val ROUTE_MAP = "routemap"
    const val ACCOUNT = "account"
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
                    navController.navigate(Routes.SHELL) {
                        // No going back to the login form with the hardware
                        // button once a session exists.
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.SHELL) {
            MainShell(
                onOpenMap = { navController.navigate(Routes.ROUTE_MAP) },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                onOpenWorkDay = { navController.navigate(Routes.WORK_DAY) },
                onOpenMenuEntry = { code ->
                    // The shell has already refused anything not in SupportedMenu,
                    // so an unknown code here would be a registry that disagrees
                    // with itself rather than a rep pressing something odd.
                    when (code) {
                        SupportedMenu.NEW_CUSTOMER -> navController.navigate(Routes.NEW_CUSTOMER)
                        SupportedMenu.REPORT -> navController.navigate(Routes.REPORTS)
                        SupportedMenu.RECEIVABLE -> navController.navigate(Routes.RECEIVABLES)
                    }
                },
                onOpenStop = { stop -> navController.navigateToStop(stop) },
                onSignedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.ACCOUNT) {
            // No sign-out on success. Supabase keeps the session alive across a
            // password change, and throwing the rep back to the login screen
            // mid-route would cost them their place for no security gained.
            AccountScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ROUTE_MAP) {
            RouteMapScreen(
                // Same destination rule as the list: how far the visit has got
                // decides whether a tap opens the check-in or the work list. It
                // lives in one place so the two screens cannot diverge.
                onOpenStop = { stop -> navController.navigateToStop(stop) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.RECEIVABLES) {
            ReceivablesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.REPORTS) {
            ReportsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.NEW_CUSTOMER) {
            // Back to the shell. The outlet is already on today's route by the
            // time this closes, and the route reloads when its tab resumes.
            NewCustomerScreen(onDone = { navController.popBackStack() })
        }

        composable(Routes.WORK_DAY) {
            // Back to the shell either way. The punch went through the repository
            // the shell is collecting, so the bar and the drawer already know.
            WorkDayScreen(onDone = { navController.popBackStack() })
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
                onCheckedOut = { navController.popBackStack(Routes.SHELL, inclusive = false) },
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
            // The registry of what this build can render lives in SupportedSteps.
            when (entry.arguments?.getString("formId")) {
                // The last step still served by the generic note form. Feedback used
                // to share it and outgrew it: it needs a topic and a voice note.
                SupportedSteps.OUTSIDE_CHECKING ->
                    NoteStepScreen(onDone = { navController.popBackStack() })

                SupportedSteps.FEEDBACK ->
                    FeedbackScreen(onDone = { navController.popBackStack() })

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

/**
 * Where tapping a stop goes, wherever it was tapped.
 *
 * How far the visit has got decides it: already checked in means the rep wants
 * the work list, not the check-in form again. Shared by the route list and the
 * map so the two cannot come to different conclusions about the same stop.
 */
private fun NavHostController.navigateToStop(stop: RouteStop) {
    val visitId = stop.visitId
    if (stop.status == VisitStatus.IN_PROGRESS && visitId != null) {
        navigate(Routes.inCall(visitId, stop.customer.id))
    } else {
        navigate(Routes.checkIn(stop.customer.id))
    }
}
