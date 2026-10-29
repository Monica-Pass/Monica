package takagi.ru.monica.keepass

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.CustomIcon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.ui.icons.*
import takagi.ru.monica.ui.password.PasswordEntryCard
import takagi.ru.monica.ui.password.MultiPasswordEntryCard
import takagi.ru.monica.ui.screens.PasswordDetailIcon
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.ui.vaultv2.*
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.*
import java.io.ByteArrayOutputStream
import java.util.UUID

class KeePassCustomIconUiTest {
    @get:Rule val compose = createComposeRule()
    private val iconUuid = UUID.randomUUID()
    private val bytes = png(Color.MAGENTA)
    private fun png(color: Int, width: Int = 32) = ByteArrayOutputStream().use { out ->
        val bitmap = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }
    private fun fixture(database: KeePassDatabase, data: ByteArray = bytes): KeePassDatabase =
        database.modifyCustomIcons { it + (iconUuid to CustomIcon(data = data, name = "KeePass test icon", lastModified = null)) }
            .modifyParentGroup { copy(customIconUuid = iconUuid,
                entries = entries.map { it.copy(customIconUuid = iconUuid) }) }
    private suspend fun select(model: PasswordViewModel, id: Long): PasswordEntry {
        withContext(Dispatchers.Main) {
            val field = PasswordViewModel::class.java.getDeclaredField("_categoryFilter").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(model) as MutableStateFlow<CategoryFilter>).value = CategoryFilter.KeePassDatabase(id)
        }
        withTimeout(30000) { while (model.passwordEntries.value.size != 300) delay(25) }
        return model.passwordEntries.value.first()
    }
    private fun counters(uri: android.net.Uri): Pair<Int, Int> =
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.query(uri,
            arrayOf("reads", "commits"), null, null, null)!!.use { it.moveToFirst(); it.getInt(0) to it.getInt(1) }
    private fun iconCount() = compose.onAllNodesWithTag("keepass_custom_icon", useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun standardMonicaCardsDetailVaultAndGroupShowOriginalIconsWithoutReopeningAndHideOnLock() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { fixture(it) }) { model, service, id, room, uri ->
            model.holdNativeKeePassRead(id).use {
                val row = select(model, id).copy(website = "https://github.com", title = "GitHub")
                val group = service.openNativeBrowser(id).getOrThrow().rootGroup
                val before = counters(uri)
                compose.setContent { MonicaTheme { Column {
                    PasswordEntryCard(row, onClick = {}, iconCardsEnabled = true)
                    MultiPasswordEntryCard(listOf(row), onClick = {}, iconCardsEnabled = true)
                    PasswordDetailIcon(row, UnmatchedIconHandlingStrategy.DEFAULT_ICON)
                    VaultItemIcon(row.website, row.title, "", row.customIconType, row.customIconValue, Icons.Default.Key,
                        keepassDatabaseId = id, keepassEntryUuid = row.keepassEntryUuid)
                    OverviewItemRow(buildVaultV2PasswordItems(listOf(row)).single(), androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        selected = false, selectionMode = false, onClick = {})
                    VaultV2FolderRow(VaultV2FolderRowModel("test-group", "Test group", "Synthetic", 300,
                        CategoryFilter.KeePassGroupFilter(id, group.legacyPath.orEmpty(), group.identity.groupUuid.toString()),
                        VaultV2FolderRowKind.FOLDER), onClick = {})
                } } }
                compose.waitUntil(15000) { iconCount() == 6 }
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                java.io.File(context.getExternalFilesDir(null), "keepass-custom-icons-list.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                assertEquals("Images must use the warm session", before, counters(uri))
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                assertNull(KeePassKdbxService.cachedNativeCustomIcon(id + 100000, row.keepassEntryUuid))
                compose.runOnIdle { takagi.ru.monica.security.SessionManager.markLocked() }
                compose.waitUntil(10000) { iconCount() == 0 }
                compose.runOnIdle { takagi.ru.monica.security.SessionManager.markUnlocked() }
                compose.waitUntil(10000) { iconCount() == 6 }
                compose.runOnIdle { service.lockDatabase(id) }
                compose.waitUntil(10000) { iconCount() == 0 }
                assertNull(KeePassKdbxService.cachedNativeCustomIcon(id, row.keepassEntryUuid))
                assertEquals("Reading icons after locking cannot unlock or write the file", before, counters(uri))
            }
        }
    }

    @Test fun sameUuidPoolReplacementRefreshesVisibleImageAndDoesNotCrossDatabase() = runBlocking {
        var fixtureDatabase: KeePassDatabase? = null
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { fixture(it).also { fixtureDatabase = it } }) { _, service, id, _, uri ->
            var snapshot = service.openNativeBrowser(id).getOrThrow()
            val uuid = snapshot.entries.first().identity.entryUuid.toString()
            val pixel = mutableStateOf(0)
            val iconType = mutableStateOf("NONE")
            compose.setContent { MonicaTheme {
                val bitmap = rememberKeePassCustomIcon(id, uuid, enabled = iconType.value == "NONE")
                if (bitmap != null) {
                    pixel.value = bitmap.toPixelMap()[0, 0].hashCode()
                }
                VaultItemIcon("", "Icon override", "", iconType.value, "🔐", Icons.Default.Key,
                    keepassDatabaseId = id, keepassEntryUuid = uuid)
            } }
            compose.waitUntil(15000) { iconCount() == 1 }
            val oldPixel = pixel.value
            val before = counters(uri).second
            val replacement = png(Color.CYAN)
            // Simulate an external KeePass edit. Monica's UUID collision guard intentionally
            // rejects overwriting different image data in its own icon-pool mutation API.
            val changed = fixtureDatabase!!.modifyCustomIcons { pool ->
                pool + (iconUuid to pool.getValue(iconUuid).copy(data = replacement))
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.contentResolver.openOutputStream(uri, "wt")!!.use { changed.encode(it) }
            KeePassKdbxService.invalidateProcessCache(id)
            service.openNativeBrowser(id).getOrThrow()
            compose.waitUntil(15000) { iconCount() == 1 && pixel.value != oldPixel }
            snapshot = service.openNativeBrowser(id).getOrThrow()
            assertArrayEquals(replacement, snapshot.customIcons[iconUuid]!!.data)
            assertEquals(before + 1, counters(uri).second)
            assertNull(KeePassKdbxService.cachedNativeCustomIcon(id, UUID.randomUUID().toString()))
            compose.runOnIdle { iconType.value = "EMOJI" }
            compose.waitUntil(10000) { iconCount() == 0 }
            compose.runOnIdle { iconType.value = "NONE" }
            compose.waitUntil(10000) { iconCount() == 1 }
        }
    }

    @Test fun ordinaryMonicaSavePreservesIconUuidPoolBytesAndHistoryWithoutFakeUploadedMetadata() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { fixture(it) }) { model, service, id, room, uri ->
            model.holdNativeKeePassRead(id).use {
                val row = select(model, id)
                val before = counters(uri).second
                val done = CompletableDeferred<Long?>()
                withContext(Dispatchers.Main) { model.saveGroupedPasswords(listOf(row.id), row.copy(title = "Edited in Monica"),
                    listOf("secret-0"), emptyList()) { done.complete(it) } }
                assertNotNull(withTimeout(30000) { done.await() })
                assertEquals(before + 1, counters(uri).second)
                KeePassKdbxService.invalidateProcessCache(id)
                val snapshot = service.openNativeBrowser(id).getOrThrow()
                val record = snapshot.entry(KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid)))!!
                assertEquals("Edited in Monica", record.title)
                assertEquals(iconUuid, record.customIconUuid)
                assertArrayEquals(bytes, record.customIcon!!.data)
                assertArrayEquals(bytes, snapshot.customIcons[iconUuid]!!.data)
                assertTrue(record.history.any { it.customIconUuid == iconUuid && it.customIcon!!.data.contentEquals(bytes) })
                assertEquals("NONE", row.customIconType)
                assertNull(row.customIconValue)
                assertFalse(record.fields.any { it.name == "MonicaCustomIconValue" && it.rawValue.isNotBlank() })
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
            }
        }
    }

    @Test fun damagedMissingAndOversizedImagesFallBackWithoutChangingTheVault() = runBlocking {
        val invalid = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        assertNull(decodeKeePassCustomIcon(invalid))
        assertNull(decodeKeePassCustomIcon(ByteArray(KeePassCustomIconEditor.MAX_ICON_BYTES + 1)))
        val large = decodeKeePassCustomIcon(png(Color.MAGENTA, 512))!!
        assertTrue(large.width <= 192 && large.height <= 192)
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { fixture(it, invalid) }) { _, service, id, _, uri ->
            val snapshot = service.openNativeBrowser(id).getOrThrow()
            val uuid = snapshot.entries.first().identity.entryUuid.toString()
            val before = counters(uri)
            compose.setContent { MonicaTheme { Column {
                VaultItemIcon("", "Damaged", "", "NONE", null, Icons.Default.Key, keepassDatabaseId = id, keepassEntryUuid = uuid)
                VaultItemIcon("", "Missing", "", "NONE", null, Icons.Default.Key, keepassDatabaseId = id, keepassEntryUuid = UUID.randomUUID().toString())
            } } }
            compose.waitForIdle()
            assertEquals(0, iconCount())
            assertEquals(before, counters(uri))
            assertArrayEquals(invalid, service.openNativeBrowser(id).getOrThrow().customIcons[iconUuid]!!.data)
        }
    }

    @Test fun missingPoolReferencesAndAmbiguousGroupPathsDoNotChooseAnUnrelatedIcon() = runBlocking {
        val missingUuid = UUID.randomUUID()
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { source ->
            fixture(source).modifyParentGroup {
                copy(entries = entries.map { it.copy(customIconUuid = missingUuid) }, groups = listOf(
                    app.keemobile.kotpass.models.Group(uuid = UUID.randomUUID(), name = "Same", customIconUuid = iconUuid),
                    app.keemobile.kotpass.models.Group(uuid = UUID.randomUUID(), name = "Same", customIconUuid = iconUuid)))
            }
        }) { _, service, id, _, uri ->
            val snapshot = service.openNativeBrowser(id).getOrThrow()
            val before = counters(uri)
            val entry = snapshot.entries.first()
            assertEquals(missingUuid, entry.customIconUuid)
            assertNull(KeePassKdbxService.cachedNativeCustomIcon(id, entry.identity.entryUuid.toString()))
            val ambiguous = snapshot.groupsByLegacyPath.entries.single { it.value.size == 2 }
            assertNull(KeePassKdbxService.cachedNativeCustomIcon(id, groupPath = ambiguous.key))
            assertArrayEquals(bytes, KeePassKdbxService.cachedNativeCustomIcon(id,
                groupUuid = ambiguous.value.first().identity.groupUuid.toString())!!.data)
            assertEquals(before, counters(uri))
        }
    }
}
