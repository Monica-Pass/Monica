package takagi.ru.monica.ui.vaultv2

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the embedded trash route in the vault page.
 *
 * The route is local to [VaultV2Pane], so the system back gesture must be
 * consumed there before the app-level exit handler gets a chance to run.
 */
class VaultV2EmbeddedHistoryBackNavigationGuardTest {

    @Test
    fun `embedded history route owns system back and clears its local mode`() {
        val pane = source("ui/vaultv2/VaultV2Pane.kt")
        val routeStart = pane.indexOf("if (useEmbeddedHistoryPages && vaultHistoryPageMode == 2)")
        val backHandler = pane.indexOf("BackHandler(enabled = vaultHistoryPageMode == 2)")

        assertTrue("embedded history route must be present", routeStart >= 0)
        assertTrue(
            "embedded history back handler must be declared before the route is rendered",
            backHandler in 0 until routeStart,
        )
        assertTrue(
            "system back must close the embedded route instead of exiting the app",
            pane.substring(backHandler, routeStart).contains("vaultHistoryPageMode = 0"),
        )
    }

    private fun source(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(
            directory,
            "app/src/main/java/takagi/ru/monica/$relativePath",
        ).readText()
    }
}
