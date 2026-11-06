package takagi.ru.monica.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/** Restore old saved navigation without keeping the removed feature or its state alive. */
internal fun NavGraphBuilder.addRetiredFeatureDestinations(navController: NavHostController) {
    composable("dedup_engine") {
        LaunchedEffect(navController) {
            navController.navigate(Screen.SyncBackup.route) {
                popUpTo("dedup_engine") { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}
