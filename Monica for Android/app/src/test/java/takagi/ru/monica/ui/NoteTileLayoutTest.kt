package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTileLayoutTest {

    @Test
    fun notesReuseTheAuthenticatorTileGridAndCardSurface() {
        val contentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteListContentSection.kt"
        ).readText()
        val cardSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteListCardComponents.kt"
        ).readText()
        val screenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteListScreen.kt"
        ).readText()

        assertTrue(contentSource.contains("MonicaTileGrid("))
        assertFalse(contentSource.contains("LazyVerticalStaggeredGrid("))
        assertTrue(cardSource.contains("if (isGridMode)"))
        assertTrue(cardSource.contains("MonicaItemCard("))
        assertFalse(cardSource.contains("private fun NoteCardSurface("))
        assertTrue(screenSource.contains("R.string.authenticator_layout_standard"))
        assertTrue(screenSource.contains("R.string.authenticator_layout_tile"))
        assertFalse(screenSource.contains("R.string.switch_to_list"))
        assertFalse(screenSource.contains("R.string.switch_to_grid"))
    }

    private fun projectFile(path: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, path)
    }
}
