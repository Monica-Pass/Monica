package takagi.ru.monica.keepass

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.KeePassNativeManagerScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import java.util.UUID

/** Real external files and UUID routes; the overview Room index starts empty. */
class KeePassPasswordNativeInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeSaveUsesOneCommitWithoutDependingOnOverviewRows() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { passwords, service, id, room, uri ->
            val scope = KeePassPasswordNativeScope.from(CategoryFilter.KeePassDatabase(id))!!
            val browser = service.openNativeBrowser(id, true).getOrThrow()
            assertEquals(300, scope.entries(browser, scope.initialGroup(browser)).size)
            assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
            val source = browser.entries.first()
            fun commits() = context.contentResolver.query(uri, arrayOf("commits"), null, null, null)!!.use {
                it.moveToFirst(); it.getInt(0)
            }
            val before = commits()
            val fields = source.fields.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) } +
                KeePassFieldChange("Notes", "Native saved") + KeePassFieldChange("Protected synthetic field", "retained", true)
            val start = SystemClock.elapsedRealtime()
            service.saveNativeEntryDraft(id, source.identity.entryUuid, null, fields, null, emptyList(), browser.sourceRevision.sha256).getOrThrow()
            val nativeList = service.openNativeBrowser(id, true).getOrThrow()
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "KDBX_NATIVE_PERF save_and_list_ms=${SystemClock.elapsedRealtime() - start}\n")
            })
            assertEquals("Native saved", nativeList.entry(source.identity)!!.field("Notes")!!.rawValue)
            assertEquals(1, commits() - before)
            assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
            KeePassKdbxService.invalidateProcessCache(id)
            val after = service.openNativeBrowser(id, true).getOrThrow()
            val saved = after.entry(source.identity)!!
            assertEquals("Native saved", saved.field("Notes")!!.rawValue)
            assertEquals("retained", saved.field("Protected synthetic field")!!.rawValue)
            assertTrue(saved.field("Protected synthetic field")!!.isProtected)
            assertEquals(source.field("Password")!!.rawValue, saved.field("Password")!!.rawValue)
            assertTrue(saved.history.isNotEmpty())
            browser.entries.filter { it.identity != source.identity }.forEach { original ->
                assertEquals(original.fields.map { it.name to it.rawValue }, after.entry(original.identity)!!.fields.map { it.name to it.rawValue })
            }
            assertTrue(passwords.applyKeePassWorkspaceSnapshot(id, service.loadWorkspace(id).getOrThrow(), true, false))
            assertEquals(300, room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).size)
        }
    }

    @Test fun staleNativeDraftCannotOverwriteAnExternalRevision() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, room, uri ->
            val browser = service.openNativeBrowser(id, true).getOrThrow()
            val entry = browser.entries.first()
            val fields = entry.fields.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) }
            service.saveNativeEntryDraft(id, entry.identity.entryUuid, null, fields + KeePassFieldChange("Notes", "Other writer"), null, emptyList(), browser.sourceRevision.sha256).getOrThrow()
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val result = service.saveNativeEntryDraft(id, entry.identity.entryUuid, null, fields + KeePassFieldChange("Notes", "Stale draft"), null, emptyList(), browser.sourceRevision.sha256)
            assertTrue(result.isFailure)
            assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
        }
    }

    @Test fun failedNativeWritePreservesFileAndOverview() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, room, uri ->
            val browser = service.openNativeBrowser(id, true).getOrThrow()
            val entry = browser.entries.first()
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            context.contentResolver.call(uri, "deny-write", uri.toString(), null)
            val result = service.saveNativeEntryDraft(id, entry.identity.entryUuid, null,
                entry.fields.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) } + KeePassFieldChange("Notes", "Denied"),
                null, emptyList(), browser.sourceRevision.sha256)
            assertTrue(result.isFailure)
            assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
        }
    }

    @Test fun folderUuidAndFiltersRemainNativeAndMissingGroupDoesNotFallBack() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, _ ->
            var browser = service.openNativeBrowser(id, true).getOrThrow()
            val folder = service.createNativeGroup(id, browser.rootGroup.identity.groupUuid, "Native folder", browser.sourceRevision.sha256).getOrThrow()
            browser = service.openNativeBrowser(id, true).getOrThrow()
            val entry = browser.entries.first()
            service.saveNativeEntryDraft(id, entry.identity.entryUuid, null,
                entry.fields.map { KeePassFieldChange(it.name, if (it.name == "UserName") "true" else it.rawValue, it.isProtected) } +
                    KeePassFieldChange("MonicaIsFavorite", "{REF:U@I:${entry.identity.entryUuid.toString().replace("-", "")}}"),
                null, emptyList(), browser.sourceRevision.sha256).getOrThrow()
            browser = service.openNativeBrowser(id, true).getOrThrow()
            service.moveNativeEntries(id, setOf(entry.identity.entryUuid), folder.identity.groupUuid, browser.sourceRevision.sha256).getOrThrow()
            browser = service.openNativeBrowser(id, true).getOrThrow()
            val all = KeePassPasswordNativeScope(id)
            assertEquals(300, all.entries(browser, all.initialGroup(browser)).size)
            val scoped = KeePassPasswordNativeScope(id, groupPath = "old path", groupUuid = folder.identity.groupUuid.toString())
            assertEquals(listOf(entry.identity), scoped.entries(browser, scoped.initialGroup(browser)).map { it.identity })
            assertEquals(299, KeePassPasswordNativeScope(id, uncategorized = true).entries(browser, browser.rootGroup.identity).size)
            assertEquals(listOf(entry.identity), KeePassPasswordNativeScope(id, starred = true).entries(browser, browser.rootGroup.identity).map { it.identity })
            assertTrue(runCatching { KeePassPasswordNativeScope(id, groupUuid = UUID.randomUUID().toString()).initialGroup(browser) }.isFailure)
        }
    }

    @Test fun customOnlyAndMixedEntriesKeepTheSamePasswordMembershipAsTheOverview() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, room, _ ->
            val additions = listOf(
                listOf(KeePassFieldChange("Title", "Custom only"), KeePassFieldChange("Synthetic field", "value", true)),
                listOf(KeePassFieldChange("Title", "Mixed login"), KeePassFieldChange("UserName", "native-user"),
                    KeePassFieldChange("Password", "native-secret", true), KeePassFieldChange("KPEX_PASSKEY_USERNAME", "native-user")),
                listOf(KeePassFieldChange("Title", "Unrecognized note payload"), KeePassFieldChange("MonicaItemType", "NOTE")),
            )
            val added = additions.map { fields ->
                val browser = service.openNativeBrowser(id).getOrThrow()
                service.createNativeEntry(id, browser.rootGroup.identity.groupUuid, fields, browser.sourceRevision.sha256).getOrThrow().identity
            }
            val browser = service.openNativeBrowser(id, true).getOrThrow()
            val native = KeePassPasswordNativeScope(id).entries(browser, browser.rootGroup.identity).map { it.identity.entryUuid }.toSet()
            val overview = service.readPasswordEntries(id).getOrThrow().filterNot { it.isInRecycleBin }.map { UUID.fromString(it.entryUuid) }.toSet()
            assertEquals(overview, native)
            assertTrue(added.take(2).all { it.entryUuid in native })
            assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
        }
    }

    @Test fun passwordBrowserDetailAndEditorDoNotNavigateToRoomAndLockDisposesDraft() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, room, _ ->
            val dao = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()
            val model = LocalKeePassViewModel(context.applicationContext as Application, dao, SecurityManager(context))
            val database = dao.getDatabaseById(id)!!
            val visible = mutableStateOf(true)
            try {
                compose.setContent {
                    MonicaTheme {
                        if (visible.value) KeePassNativeManagerScreen(database, model, {}, { error("Native password must not route to Room") },
                            passwordScope = KeePassPasswordNativeScope(id))
                    }
                }
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Synthetic 0").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Synthetic 0").performClick()
                compose.onNodeWithContentDescription(context.getString(R.string.edit)).performClick()
                compose.onNodeWithTag("native_entry_editor").assertIsDisplayed()
                compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Native UI saved")
                compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("native_entry_editor").fetchSemanticsNodes().isEmpty() }
                assertEquals("Native UI saved", service.openNativeBrowser(id).getOrThrow().entries.single {
                    it.field("UserName")?.rawValue == "user-0"
                }.title)
                compose.onNodeWithContentDescription(context.getString(R.string.edit)).performClick()
                compose.onNodeWithTag("native_entry_editor").assertIsDisplayed()
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                compose.runOnIdle { model.lockKeePassDatabase(id) }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("native_entry_editor").fetchSemanticsNodes().isEmpty() }
                assertFalse("Lock must not trigger an overview refresh that reopens the file", service.isDatabaseUnlocked(id))
                assertEquals(300, service.readPasswordEntries(id).getOrThrow().size)
            } finally {
                compose.runOnIdle { visible.value = false }
                model.viewModelScope.cancel()
            }
        }
    }
}
