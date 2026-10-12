package app.cuentapepas.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.cuentapepas.ui.about.AboutScreen
import app.cuentapepas.ui.bench.BenchmarkScreen
import app.cuentapepas.ui.calibration.CalibrationScreen
import app.cuentapepas.ui.estimate.LotEstimateScreen
import app.cuentapepas.ui.home.HomeScreen
import app.cuentapepas.ui.home.LotScreen
import app.cuentapepas.ui.live.LiveScreen
import app.cuentapepas.ui.newsession.NewSessionScreen
import app.cuentapepas.ui.settings.SettingsScreen
import app.cuentapepas.ui.summary.SessionSummaryScreen

/** Route patterns and builders. Ids are UUIDs, so they are safe path segments. */
object Routes {
    const val HOME = "home"
    const val LOT = "lot/{lotId}"
    const val NEW = "new/{lotId}"
    const val LIVE = "live/{sessionId}"
    const val SUMMARY = "summary/{sessionId}"
    const val CALIBRATION = "calibration/{lotId}"
    const val ESTIMATE = "estimate/{lotId}"
    const val BENCH = "bench"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun lot(lotId: String) = "lot/$lotId"
    fun new(lotId: String) = "new/$lotId"
    fun live(sessionId: String) = "live/$sessionId"
    fun summary(sessionId: String) = "summary/$sessionId"
    fun calibration(lotId: String) = "calibration/$lotId"
    fun estimate(lotId: String) = "estimate/$lotId"
}

private fun NavBackStackEntry.arg(name: String): String =
    requireNotNull(arguments?.getString(name)) { "missing navigation argument $name" }

private val lotArgs = listOf(navArgument("lotId") { type = NavType.StringType })
private val sessionArgs = listOf(navArgument("sessionId") { type = NavType.StringType })

@Composable
fun AppNav(nav: NavHostController = rememberNavController()) {
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenLot = { nav.navigate(Routes.lot(it)) },
                onBench = { nav.navigate(Routes.BENCH) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.LOT, lotArgs) { e ->
            val lotId = e.arg("lotId")
            LotScreen(
                lotId = lotId,
                onBack = { nav.popBackStack() },
                onNewSession = { nav.navigate(Routes.new(lotId)) },
                onCalibration = { nav.navigate(Routes.calibration(lotId)) },
                onEstimate = { nav.navigate(Routes.estimate(lotId)) },
                onOpenSession = { nav.navigate(Routes.summary(it)) },
            )
        }
        composable(Routes.NEW, lotArgs) { e ->
            NewSessionScreen(
                lotId = e.arg("lotId"),
                onBack = { nav.popBackStack() },
                onStarted = { sessionId ->
                    // The form is not part of the back stack once recording starts.
                    nav.navigate(Routes.live(sessionId)) { popUpTo(Routes.NEW) { inclusive = true } }
                },
            )
        }
        composable(Routes.LIVE, sessionArgs) { e ->
            LiveScreen(sessionId = e.arg("sessionId"), onFinished = { sessionId ->
                nav.navigate(Routes.summary(sessionId)) { popUpTo(Routes.LIVE) { inclusive = true } }
            })
        }
        composable(Routes.SUMMARY, sessionArgs) { e ->
            SessionSummaryScreen(
                sessionId = e.arg("sessionId"),
                onBack = { nav.popBackStack() },
                onDone = { lotId -> goToLot(nav, lotId) },
            )
        }
        composable(Routes.CALIBRATION, lotArgs) { e -> CalibrationScreen(e.arg("lotId")) { nav.popBackStack() } }
        composable(Routes.ESTIMATE, lotArgs) { e -> LotEstimateScreen(e.arg("lotId")) { nav.popBackStack() } }
        composable(Routes.BENCH) { BenchmarkScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen { nav.popBackStack() } }
        composable(Routes.ABOUT) { AboutScreen { nav.popBackStack() } }
    }
}

/** Back to the lot screen: reuse it if it is on the stack, otherwise open it above Home. */
private fun goToLot(nav: NavHostController, lotId: String) {
    if (!nav.popBackStack(Routes.lot(lotId), inclusive = false)) {
        nav.navigate(Routes.lot(lotId)) { popUpTo(Routes.HOME) }
    }
}
