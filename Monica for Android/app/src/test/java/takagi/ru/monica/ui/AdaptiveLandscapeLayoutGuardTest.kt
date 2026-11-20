package takagi.ru.monica.ui

import takagi.ru.monica.testing.readSourceText

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLandscapeLayoutGuardTest {

    @Test
    fun vaultAndSteamReceiveTheExistingWindowWidthContract() {
        val mainScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readSourceText()
        val steamSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readSourceText()

        assertTrue(mainScreenSource.contains("VaultV2TabPane("))
        assertTrue(mainScreenSource.contains("isCompactWidth = isCompactWidth"))
        assertTrue(mainScreenSource.contains("wideListPaneWidth = wideListPaneWidth"))
        assertTrue(steamSource.contains("isCompactWidth: Boolean"))
        assertTrue(steamSource.contains("wideListPaneWidth: Dp"))
    }

    @Test
    fun vaultWideLayoutOwnsBothTheListAndTheDetailPane() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2TabPane.kt"
        ).readSourceText()

        assertTrue(source.contains("AdaptiveListDetailLayout(compact = isCompactWidth"))
        assertTrue(source.contains("listWidth = wideListPaneWidth"))
        assertTrue(source.contains("VaultV2DetailPaneContent("))
        assertTrue(source.contains("hasDetail = hasWideDetail"))
    }

    @Test
    fun steamKeepsCompactNavigationAndUsesListDetailForWideTokenPages() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readSourceText()

        assertTrue(source.contains("SteamAdaptiveContent("))
        assertTrue(source.contains("SteamWideCodeContent("))
        assertTrue(source.contains(".width(wideListPaneWidth)"))
        assertTrue(source.contains("if (isCompactWidth)"))
    }

    @Test
    fun vaultWideDetailOccupancyParticipatesInFabVisibility() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/MainScreenFab.kt"
        ).readSourceText()

        assertTrue(source.contains("BottomNavItem.VaultV2 -> vaultV2HasWideDetail"))
    }

    @Test
    fun vaultWideTrashUsesTheFullTabSurfaceInsteadOfTheListColumn() {
        val mainScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readSourceText()
        val vaultSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        ).readSourceText()

        assertTrue(mainScreenSource.contains("if (passwordHistoryPageMode.isVisible)"))
        assertTrue(mainScreenSource.contains("useEmbeddedHistoryPages = false"))
        assertTrue(vaultSource.contains("useEmbeddedHistoryPages: Boolean = true"))
        assertTrue(vaultSource.contains("onOpenTrashPage"))
        assertTrue(vaultSource.contains("TrashScreen("))
    }

    @Test
    fun vaultRoutesEveryAggregatedEntryTypeToTheWideDetailHost() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readSourceText()

        assertTrue(source.contains("onOpenPassword = handlePasswordDetailOpen"))
        assertTrue(source.contains("onOpenTotp = handleTotpOpen"))
        assertTrue(source.contains("onOpenBankCard = handleBankCardOpen"))
        assertTrue(source.contains("onOpenDocument = handleDocumentOpen"))
        assertTrue(source.contains("onOpenNote = { handleNoteOpen(it) }"))
        assertTrue(source.contains("onOpenPasskey = handleVaultV2PasskeyOpen"))
        assertTrue(source.contains("VaultV2DetailKind.PASSWORD"))
        assertTrue(source.contains("VaultV2DetailKind.AUTHENTICATOR"))
        assertTrue(source.contains("VaultV2DetailKind.BANK_CARD"))
        assertTrue(source.contains("VaultV2DetailKind.DOCUMENT"))
        assertTrue(source.contains("VaultV2DetailKind.NOTE"))
        assertTrue(source.contains("VaultV2DetailKind.PASSKEY"))
    }

    @Test
    fun steamWideModeIsLimitedToTokenMasterDetailAndKeepsOtherSectionsReadable() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readSourceText()

        assertTrue(source.contains("selectedSection == SteamSection.CODE"))
        assertTrue(source.contains("SteamReadableSectionFrame(isCompactWidth)"))
        assertTrue(source.contains("SteamSection.INVENTORY -> SteamInventoryContent"))
        assertTrue(source.contains("SteamSection.MARKET -> SteamReadableSectionFrame"))
    }

    private fun projectFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
