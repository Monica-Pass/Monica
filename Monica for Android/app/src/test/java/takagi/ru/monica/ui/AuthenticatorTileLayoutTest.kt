package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.AuthenticatorLayoutMode

class AuthenticatorTileLayoutTest {

    @Test
    fun standardLayoutRemainsTheDefaultAndUnknownValuesFallBackSafely() {
        assertEquals(AuthenticatorLayoutMode.STANDARD, AppSettings().authenticatorLayoutMode)
        assertEquals(AuthenticatorLayoutMode.TILE, AuthenticatorLayoutMode.fromStoredValue("TILE"))
        assertEquals(AuthenticatorLayoutMode.STANDARD, AuthenticatorLayoutMode.fromStoredValue("future-mode"))
        assertEquals(AuthenticatorLayoutMode.STANDARD, AuthenticatorLayoutMode.fromStoredValue(null))
    }

    @Test
    fun tileBranchUsesTheSharedTwoColumnGridWithoutSwipeActions() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt"
        ).readText().replace("\r\n", "\n")
        val gridSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/MonicaTileGrid.kt"
        ).readText().replace("\r\n", "\n")
        val tileBranch = source
            .substringAfter("if (appSettings.authenticatorLayoutMode == AuthenticatorLayoutMode.TILE)")
            .substringBefore("// Standard authenticator layout")

        assertTrue(tileBranch.contains("MonicaTileGrid("))
        assertTrue(gridSource.contains("LazyVerticalGrid("))
        assertTrue(gridSource.contains("GridCells.Fixed(2)"))
        assertTrue(gridSource.contains("Arrangement.spacedBy(8.dp)"))
        assertTrue(tileBranch.contains("rememberReorderableLazyGridState("))
        assertTrue(tileBranch.contains("enabled = isSelectionMode"))
        assertTrue(tileBranch.contains("onLongClick ="))
        assertFalse("Tile cards must not expose horizontal swipe actions.", tileBranch.contains("SwipeActions("))
    }

    @Test
    fun listUsesParentSettingsWithoutAStandardLayoutBootstrapFrame() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt"
        ).readText().replace("\r\n", "\n")

        assertTrue(source.contains("appSettings: AppSettings"))
        assertTrue(source.contains("onAuthenticatorLayoutModeChange: (AuthenticatorLayoutMode) -> Unit"))
        assertFalse(
            "A fresh settings subscription emits AppSettings.STANDARD before DataStore restores TILE.",
            source.contains("settingsFlow.collectAsState(initial = takagi.ru.monica.data.AppSettings())")
        )
    }

    @Test
    fun standardAuthenticatorAndSteamUseTheSharedCardSurface() {
        val cardSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/TotpCodeCard.kt"
        ).readText().replace("\r\n", "\n")
        val steamSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readText()

        assertEquals(2, Regex("MonicaItemCard\\(").findAll(cardSource).count())
        assertTrue(cardSource.contains("transparentContainer = hasImmersiveBackground"))
        assertTrue(steamSource.contains("TotpCodeCard("))
    }

    @Test
    fun layoutSettingParticipatesInPageAdjustmentBackupAndRestore() {
        val managerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/SettingsManager.kt"
        ).readText()
        val backupSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readText()

        assertTrue(managerSource.contains("AUTHENTICATOR_LAYOUT_MODE_KEY"))
        assertTrue(managerSource.contains("authenticatorLayoutMode = settings.authenticatorLayoutMode.name"))
        assertTrue(managerSource.contains("snapshot.authenticatorLayoutMode"))
        assertTrue(backupSource.contains("authenticatorLayoutMode"))
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!.canonicalFile
        }
        return File(dir, path)
    }
}
