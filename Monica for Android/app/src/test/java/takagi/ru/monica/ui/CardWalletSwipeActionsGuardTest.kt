package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CardWalletSwipeActionsGuardTest {

    @Test
    fun walletCardsReuseSwipeDeleteAndSelectionWithoutConflictingWithReorder() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt"
        ).readText().replace("\r\n", "\n")
        val swipeActionsCall = source
            .substringAfter("SwipeActions(")
            .substringBefore("\n                                    ) {")

        assertTrue(swipeActionsCall.contains("onSwipeLeft = { itemToDelete = item }"))
        assertTrue(swipeActionsCall.contains("onSwipeRight = toggleSelection"))
        assertTrue(swipeActionsCall.contains("enabled = !isDragging"))
        assertTrue(swipeActionsCall.contains("allowSwipeLeft = !isSelectionMode"))
        assertTrue(swipeActionsCall.contains("allowSwipeRight = true"))
        assertTrue(swipeActionsCall.contains("cardShape = MonicaItemCardShape"))
    }

    @Test
    fun swipeDeleteKeepsExistingConfirmationAndIdentityVerificationPath() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt"
        ).readText().replace("\r\n", "\n")

        assertTrue(source.contains("itemToDelete?.let { item ->\n        AlertDialog("))
        assertTrue(source.contains("requestDeleteVerification(setOf(item.id))"))
        assertTrue(source.contains("performDelete(verifyDeleteIds)"))
    }

    @Test
    fun swipeContainerUsesTheSameEightDpShapeAsVaultCards() {
        val cardSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/MonicaItemCard.kt"
        ).readText().replace("\r\n", "\n")
        val swipeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/gestures/SwipeActions.kt"
        ).readText().replace("\r\n", "\n")

        assertTrue(cardSource.contains("val MonicaItemCardShape = RoundedCornerShape(8.dp)"))
        assertTrue(swipeSource.contains("cardShape: Shape = RoundedCornerShape(16.dp)"))
        assertTrue(swipeSource.contains("val componentShape = cardShape"))
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!
        }
        return File(dir, path)
    }
}
