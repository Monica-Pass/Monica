package takagi.ru.monica.steam

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.steam.profile.SteamMiniProfileBackgroundService
import takagi.ru.monica.steam.profile.pruneSteamMiniProfileCache
import takagi.ru.monica.steam.ui.calculateSteamMiniProfileCenterCrop

class SteamMiniProfileBackgroundTest {
    @Test
    fun centerCropUsesNormalizedTextureScaleWithoutStretching() {
        val wideCard = calculateSteamMiniProfileCenterCrop(
            viewWidth = 235,
            viewHeight = 100,
            mediaWidth = 160,
            mediaHeight = 90,
        )
        assertEquals(1f, wideCard.scaleX, 0.001f)
        assertEquals(2.35f / (16f / 9f), wideCard.scaleY, 0.001f)

        val narrowCard = calculateSteamMiniProfileCenterCrop(
            viewWidth = 100,
            viewHeight = 100,
            mediaWidth = 160,
            mediaHeight = 90,
        )
        assertEquals(16f / 9f, narrowCard.scaleX, 0.001f)
        assertEquals(1f, narrowCard.scaleY, 0.001f)
    }

    @Test
    fun parsesPublicMiniProfileBackgroundVideoUrls() {
        val payload = Json.parseToJsonElement(
            """
            {
              "persona_name": "Example",
              "profile_background": {
                "video/webm": "https://shared.fastly.steamstatic.com/items/bg.webm",
                "video/mp4": "https://shared.fastly.steamstatic.com/items/bg.mp4"
              }
            }
            """.trimIndent()
        ).jsonObject

        val background = SteamMiniProfileBackgroundService.parse(payload)

        assertEquals("https://shared.fastly.steamstatic.com/items/bg.mp4", background?.mp4Url)
        assertEquals("https://shared.fastly.steamstatic.com/items/bg.webm", background?.webmUrl)
        assertEquals(background?.mp4Url, background?.preferredUrl)
    }

    @Test
    fun rejectsMissingOrUntrustedBackgroundUrls() {
        val missing = Json.parseToJsonElement("{\"persona_name\":\"None\"}").jsonObject
        val untrusted = Json.parseToJsonElement(
            """
            {"profile_background":{"video/mp4":"https://example.com/tracker.mp4"}}
            """.trimIndent()
        ).jsonObject

        assertNull(SteamMiniProfileBackgroundService.parse(missing))
        assertNull(SteamMiniProfileBackgroundService.parse(untrusted))
        assertFalse(SteamMiniProfileBackgroundService.isAllowedSteamMediaUrl("http://steamstatic.com/a.mp4"))
    }

    @Test
    fun convertsSteamId64ToPublicMiniProfileAccountId() {
        assertEquals(
            1_910_742_929L,
            SteamMiniProfileBackgroundService.steamIdToAccountId("76561199871008657")
        )
        assertNull(SteamMiniProfileBackgroundService.steamIdToAccountId("WR428"))
    }

    @Test
    fun diskCachePrunesOldestFilesAndHonorsProtectedMedia() {
        val directory = kotlin.io.path.createTempDirectory(prefix = "steam-mini-profile-").toFile()
        try {
            val oldest = File(directory, "old.mp4").apply {
                writeBytes(ByteArray(8))
                setLastModified(1L)
            }
            val protected = File(directory, "current.mp4").apply {
                writeBytes(ByteArray(8))
                setLastModified(2L)
            }
            val newest = File(directory, "new.webp").apply {
                writeBytes(ByteArray(8))
                setLastModified(3L)
            }

            val deleted = pruneSteamMiniProfileCache(
                directory = directory,
                maxBytes = 16L,
                protectedPaths = setOf(protected.absolutePath)
            )

            assertEquals(listOf(oldest.name), deleted.map(File::getName))
            assertFalse(oldest.exists())
            assertTrue(protected.exists())
            assertTrue(newest.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
