package takagi.ru.monica.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun compareVersionTags_detectsNewerRelease() {
        assertTrue(UpdateChecker.compareVersionTags("v1.0.296", "1.0.294") > 0)
    }

    @Test
    fun compareVersionTags_treatsMatchingPrefixAsSameVersion() {
        assertEquals(0, UpdateChecker.compareVersionTags("v1.0.294", "1.0.294-preview"))
    }

    @Test
    fun compareVersionTags_ignoresReleaseLetterSuffix() {
        assertEquals(0, UpdateChecker.compareVersionTags("V1.0.294c", "1.0.294"))
        assertEquals(0, UpdateChecker.compareVersionTags("V1.0.294c", "V1.0.294b"))
    }

    @Test
    fun compareVersionTags_detectsOlderRelease() {
        assertTrue(UpdateChecker.compareVersionTags("1.0.287", "1.0.294") < 0)
    }

    @Test
    fun compareVersionTagsRejectsUnknownOrPartialVersions() {
        for (unknown in listOf("nightly", "1.2", "other1.2.3", "1.2.3/next", "999999999999999999999.2.3")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                UpdateChecker.compareVersionTags(unknown, "1.0.327")
            }
        }
    }

    @Test
    fun updateDownloadProgressCalculatesFractionOnlyWhenTotalIsKnown() {
        val known = UpdateDownloadProgress(bytesRead = 25L, totalBytes = 100L)
        val unknown = UpdateDownloadProgress(bytesRead = 25L, totalBytes = -1L)

        assertTrue(known.hasTotal)
        assertEquals(0.25f, known.fraction, 0.0001f)
        assertFalse(unknown.hasTotal)
        assertEquals(0f, unknown.fraction, 0.0001f)
    }
}
