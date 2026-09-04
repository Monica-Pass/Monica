package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportFileDisplayNameTest {

    @Test
    fun pathFileNameIsUsedWhenProviderDoesNotExposeDisplayName() {
        assertEquals(
            "passwords.csv",
            fallbackImportFileDisplayName(
                uriPath = "/storage/emulated/0/Download/passwords.csv",
                lastPathSegment = "passwords.csv",
                fallback = "Selected file"
            )
        )
    }

    @Test
    fun opaqueDocumentIdFallsBackToUserFacingPlaceholder() {
        assertEquals(
            "Selected file",
            fallbackImportFileDisplayName(
                uriPath = "document:1000166228",
                lastPathSegment = "document:1000166228",
                fallback = "Selected file"
            )
        )
    }
}
