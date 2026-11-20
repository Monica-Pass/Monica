package takagi.ru.monica.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressiveTopBarKeyboardRegressionGuardTest {

    @Test
    fun searchFieldRestoresKeyboardWhenPressedAfterSystemDismissal() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/ExpressiveTopBar.kt"
        ).readText()

        assertTrue(
            "The search field must expose a shared interaction source so taps are observed even when it still owns focus.",
            source.contains("val searchInteractionSource = remember { MutableInteractionSource() }") &&
                source.contains("interactionSource = searchInteractionSource")
        )
        assertTrue(
            "A press on the already-focused search field must request focus and show the software keyboard again.",
            source.contains("PressInteraction.Press") &&
                source.contains("focusRequester.requestFocus()") &&
                source.contains("keyboardController?.show()")
        )
        assertTrue(
            "The initial expanded search state must continue to focus the field automatically.",
            Regex("""LaunchedEffect\(isSearchExpanded\)\s*\{\s*if \(isSearchExpanded\)\s*\{\s*focusRequester\.requestFocus\(\)\s*keyboardController\?\.show\(\)""")
                .containsMatchIn(source)
        )
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, relativePath)
    }
}
