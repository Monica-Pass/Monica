package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.ui.common.state.InitialListRenderState
import takagi.ru.monica.ui.common.state.resolveInitialListRenderState

class DockAndListFirstFramePerformanceGuardTest {

    @Test
    fun emptyStateRequiresTheFinalListStreamToCompleteItsFirstEmission() {
        assertEquals(
            InitialListRenderState.Loading,
            resolveInitialListRenderState(isReady = false, itemCount = 0),
        )
        assertEquals(
            InitialListRenderState.Empty,
            resolveInitialListRenderState(isReady = true, itemCount = 0),
        )
        assertEquals(
            InitialListRenderState.Content,
            resolveInitialListRenderState(isReady = false, itemCount = 2),
        )
        assertEquals(
            InitialListRenderState.Content,
            resolveInitialListRenderState(isReady = true, itemCount = 2),
        )
    }

    @Test
    fun cardWalletWaitsForEveryParsedStreamRatherThanRawRoomStreams() {
        val screenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt"
        ).readText()
        val cardViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/BankCardViewModel.kt"
        ).readText()
        val documentViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/DocumentViewModel.kt"
        ).readText()
        val addressViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/BillingAddressViewModel.kt"
        ).readText()

        assertTrue(cardViewModelSource.contains("val parsedCardsReady"))
        assertTrue(documentViewModelSource.contains("val parsedDocumentsReady"))
        assertTrue(addressViewModelSource.contains("val parsedBillingAddressesReady"))
        assertTrue(screenSource.contains("val walletItemsReady ="))
        assertTrue(screenSource.contains("isReady = walletItemsReady"))
        assertFalse(screenSource.contains("bankLoading || documentLoading"))
    }

    @Test
    fun cardWalletDefersBackgroundRefreshUntilTheFirstListIsReady() {
        val screenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt"
        ).readText().replace("\r\n", "\n")

        assertTrue(
            "KeePass compatibility refresh must wait for parsed card data so it cannot compete with the first frame.",
            screenSource.contains("LaunchedEffect(walletItemsReady)") &&
                screenSource.contains("if (!walletItemsReady) return@LaunchedEffect")
        )
        assertTrue(
            "Bitwarden auto-sync must not start until the card-wallet first frame is ready.",
            screenSource.contains("enabled = hasRestoredCategoryFilter && walletItemsReady")
        )
    }

    @Test
    fun notesUseOneBackgroundDecodedStreamAndNeverRenderEmptyBeforeItIsReady() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/NoteViewModel.kt"
        ).readText()
        val screenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteListScreen.kt"
        ).readText()
        val contentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteListContentSection.kt"
        ).readText()

        assertTrue(viewModelSource.contains("data class ParsedNoteItem"))
        assertTrue(viewModelSource.contains("val parsedNotesReady"))
        assertTrue(viewModelSource.contains("flowOn(Dispatchers.Default)"))
        assertTrue(screenSource.contains("val parsedNotesState by viewModel.parsedNotesState.collectAsState()"))
        assertTrue(screenSource.contains("isInitialLoading = !parsedNotesState.isReady"))
        assertFalse(screenSource.contains("filteredNotes.map { it.toNoteListItemUiModel() }"))
        assertTrue(contentSource.contains("isInitialLoading: Boolean"))
        assertTrue(contentSource.contains("InitialListRenderState.Loading"))
    }

    @Test
    fun dockKeepsSaveablePageStateAndCompactSwitchesDoNotResetEveryPane() {
        val animatedHostSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/AuthenticatorPasskeyAnimatedContent.kt"
        ).readText()
        val mainScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()
        val noteContentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteListContentSection.kt"
        ).readText()

        assertTrue(animatedHostSource.contains("rememberSaveableStateHolder()"))
        assertTrue(animatedHostSource.contains("SaveableStateProvider(targetTab.key)"))
        assertTrue(mainScreenSource.contains("if (!isCompactWidth)"))
        assertFalse(mainScreenSource.contains("if (isCompactWidth || currentTab != BottomNavItem.Notes)"))
        assertTrue(noteContentSource.contains("rememberSaveableLazyListState()"))
    }

    @Test
    fun ordinaryDockSwitchesDoNotKeepTheOutgoingPageInASizeTransition() {
        val animatedHostSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/AuthenticatorPasskeyAnimatedContent.kt"
        ).readText()

        assertTrue(
            animatedHostSource.contains(
                "(slideInFromRight() togetherWith parallaxExitToLeft()).using("
            )
        )
        assertTrue(
            animatedHostSource.contains(
                "(parallaxEnterFromLeft() togetherWith slideOutToRight()).using("
            )
        )
        assertFalse(
            animatedHostSource.contains("transform.using(SizeTransform(clip = false))")
        )
        assertTrue(
            animatedHostSource.contains("if (currentTab.isAuthenticatorPasskeyTab())")
        )
        assertTrue(animatedHostSource.contains("key(currentTab.key)"))
        assertTrue(animatedHostSource.contains("SaveableStateProvider(currentTab.key)"))
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(directory, relativePath)
    }
}
