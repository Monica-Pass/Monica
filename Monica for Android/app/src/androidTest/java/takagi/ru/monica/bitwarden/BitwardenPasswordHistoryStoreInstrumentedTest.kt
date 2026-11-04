package takagi.ru.monica.bitwarden

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.launch
import org.junit.Rule
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.PasswordHistorySection
import takagi.ru.monica.ui.screens.HistoryPasswordConfirmDialog
import takagi.ru.monica.ui.theme.MonicaTheme
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.BitwardenPasswordHistory
import takagi.ru.monica.bitwarden.service.BitwardenPasswordHistoryStore
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordHistoryEntry
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.model.PasswordUrlRules
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Isolated Room and MDK fixtures. Does not reset an installed app/account or contact a server. */
@RunWith(AndroidJUnit4::class)
class BitwardenPasswordHistoryStoreInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun nativeCreateTransmitsOfflineHistoryAndLateClearRemainsPending(): Unit = runBlocking {
        fixture {
            val unbound = entry().copy(bitwardenCipherId = null, bitwardenRevisionDate = null, bitwardenLocalModified = true)
            db.passwordEntryDao().update(unbound)
            db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id, password = security.encryptData("offline-old"), lastUsedAt = Date(1)))
            val create = CipherCreateRequest(type = 1, name = BitwardenCrypto.encryptString("Synthetic", key), login = request("A").login)
            val plan = store.prepareCreate(unbound, create, key)
            assertEquals(listOf("offline-old"), BitwardenPasswordHistory.decode(plan.request.passwordHistory, key).map { it.password })
            assertTrue(store.isCurrent(plan))
            db.passwordHistoryDao().deleteByEntryId(id)
            assertFalse(store.isCurrent(plan))
            val created = source.copy(id = "new-synthetic-history", login = plan.request.login,
                passwordHistory = plan.request.passwordHistory, revisionDate = "2026-11-04T02:00:00Z")
            db.passwordEntryDao().update(entry().copy(bitwardenCipherId = created.id, bitwardenRevisionDate = created.revisionDate))
            store.acknowledgeCreate(plan, created, key, unchanged = false)
            assertTrue(histories().isEmpty())
            val retry = store.prepare(entry(), created, request("A"), key)
            assertEquals(JsonArray(emptyList()), retry.prepared.request.passwordHistory)
        }
    }

    @Test fun delayedOldCleanRefreshCannotReplaceHistoryOfNewRevision(): Unit = runBlocking {
        fixture {
            val old = source.copy(passwordHistory = JsonArray(listOf(wire("old-history"))))
            val latest = source.copy(passwordHistory = JsonArray(listOf(wire("new-history"))), revisionDate = "2026-11-04T02:00:00Z")
            db.passwordEntryDao().update(entry().copy(bitwardenRevisionDate = latest.revisionDate))
            assertTrue(store.refresh(id, latest, key))
            assertFalse(store.refresh(id, old, key))
            assertEquals(listOf("new-history"), histories().map { security.decryptData(it.password) })
        }
    }

    @Test fun importsAllRemoteOccurrencesAsMdkAndRepeatRefreshDoesNotDuplicateRows(): Unit = runBlocking {
        fixture {
            val repeated = wire("  \t")
            source = source.copy(passwordHistory = JsonArray(listOf(repeated, repeated, wire("older"),
                buildJsonObject { put("future", "preserve") })))
            assertTrue(store.refresh(id, source, key))
            val rows = histories()
            assertEquals(3, rows.size)
            assertTrue(rows.all { it.password.startsWith("MDK|") })
            assertEquals(listOf("  \t", "  \t", "older"), rows.map { security.decryptData(it.password) }.sortedBy { if (it == "older") 1 else 0 })
            val state = requireNotNull(db.bitwardenPasswordHistoryStateDao().get(id))
            assertTrue(state.payloadMdk.startsWith("MDK|"))
            assertFalse(state.payloadMdk.contains("older"))
            repeat(3) { assertTrue(store.refresh(id, source, key)) }
            assertEquals(rows.map { it.id }, histories().map { it.id })
            val plan = store.prepare(entry(), source, request("A"), key)
            assertEquals(source.passwordHistory, plan.prepared.request.passwordHistory)
        }
    }

    @Test fun exactDuplicateDeleteAndClearRemainDirtyUntilAcknowledged(): Unit = runBlocking {
        fixture {
            val repeated = wire("same")
            source = source.copy(passwordHistory = JsonArray(listOf(repeated, repeated, wire("other"))))
            store.refresh(id, source, key)
            val deleteId = histories().first { security.decryptData(it.password) == "same" }.id
            assertTrue(store.deleteHistory(deleteId))
            val deletePlan = store.prepare(entry(), source, request("A"), key)
            assertEquals(listOf("same", "other"), BitwardenPasswordHistory.decode(deletePlan.prepared.request.passwordHistory, key).map { it.password })
            assertTrue(entry().bitwardenLocalModified)
            assertTrue(store.clear(id))
            val plan = store.prepare(entry(), source, request("A"), key)
            assertEquals(JsonArray(emptyList()), plan.prepared.request.passwordHistory)
            assertFalse(store.isCurrent(deletePlan))
            assertTrue(store.isCurrent(plan))
            store.acknowledge(plan, response(plan.prepared.request, "2026-11-04T02:00:00Z"), key)
            val state = requireNotNull(db.bitwardenPasswordHistoryStateDao().get(id))
            assertEquals(state.localVersion, state.ackVersion)
            assertTrue(histories().isEmpty())
            // Parent entry dirty is intentionally cleared only by its enclosing successful transaction.
            assertTrue(entry().bitwardenLocalModified)
        }
    }

    @Test fun organizationHistoryUsesCipherVisibilityDefaultsAndStillEnforcesReadOnly(): Unit = runBlocking {
        for (visibility in listOf<Boolean?>(null, true)) fixture {
            source = source.copy(organizationId = "synthetic-team", edit = true, viewPassword = visibility,
                passwordHistory = JsonArray(listOf(wire("team-old"))))
            assertTrue(store.refresh(id, source, key))
            assertEquals(listOf("team-old"), histories().map { security.decryptData(it.password) })
            val plan = store.prepare(entry(), source, request("B"), key)
            assertEquals(listOf("team-old", "A"), BitwardenPasswordHistory.decode(plan.prepared.request.passwordHistory, key).map { it.password })
        }
        fixture {
            source = source.copy(organizationId = "synthetic-team", edit = false,
                passwordHistory = JsonArray(listOf(wire("read-only-old"))))
            assertTrue(store.refresh(id, source, key))
            val originalRows = histories()
            assertTrue(runCatching { store.clear(id) }.isFailure)
            assertTrue(runCatching { store.prepare(entry(), source, request("B"), key) }.isFailure)
            assertEquals(originalRows, histories())
            assertFalse(entry().bitwardenLocalModified)
        }
    }

    @Test fun explicitlyHiddenOrganizationHistoryCannotBeImportedOrPrepared(): Unit = runBlocking {
        fixture {
            source = source.copy(organizationId = "synthetic-team", edit = true, viewPassword = false,
                passwordHistory = JsonArray(listOf(wire("restricted-old"))))
            val originalEntry = entry()
            assertTrue(runCatching { store.refresh(id, source, key) }.isFailure)
            assertTrue(runCatching { store.prepare(entry(), source, request("B"), key) }.isFailure)
            assertTrue(histories().isEmpty())
            assertNull(db.bitwardenPasswordHistoryStateDao().get(id))
            assertEquals(originalEntry, entry())
        }
    }

    @Test fun currentFieldsRetainRawValuesAndRulesWhileHiddenHistoryRequiresMdk(): Unit = runBlocking {
        fixture {
            assertTrue(store.refresh(id, source, key))
            val original = listOf(
                CustomFieldDraft(title = "Hidden", value = "old-hidden", isProtected = true),
                CustomFieldDraft(title = "Hidden", value = "unchanged-hidden", isProtected = true),
                CustomFieldDraft(title = "Visible", value = "old-visible")
            )
            db.customFieldDao().replaceFieldsForEntry(id, original.mapIndexed { index, field -> field.toCustomField(id, index) })
            val rules = listOf(PasswordUrlRules.Rule("http://192.168.1.8:8080/login", PasswordUrlRules.Mode.EXACT))
            val drafts = PasswordUrlRules.put(listOf(original[0].copy(value = "new-hidden"), original[1],
                original[2].copy(value = "searchable-visible")), rules, native = true)
            assertTrue(store.persistHiddenFieldTransition(entry(), drafts))

            val repository = takagi.ru.monica.repository.CustomFieldRepository(db.customFieldDao())
            val reopened = repository.getFieldsByEntryIdSync(id)
            assertEquals(drafts.map { Triple(it.title, it.value, it.isProtected) },
                reopened.map { Triple(it.title, it.value, it.isProtected) })
            assertEquals(listOf(id), repository.searchEntryIdsByFieldContent("searchable-visible"))
            assertEquals(rules, takagi.ru.monica.autofill_ng.PasswordUrlRuleStore.load(db.customFieldDao(), listOf(id))[id])
            val snapshots = histories()
            assertEquals(listOf("Hidden: old-hidden"), snapshots.map { security.decryptData(it.password) })
            assertTrue(snapshots.all { it.password.startsWith("MDK|") && !it.password.contains("old-hidden") })
            assertTrue(requireNotNull(db.bitwardenPasswordHistoryStateDao().get(id)).payloadMdk.startsWith("MDK|"))
            assertTrue(entry().bitwardenLocalModified)
        }
    }

    @Test fun atomicOfflinePasswordTransitionsKeepAtoBtoAtoBAndDedupeOnlySameTransition(): Unit = runBlocking {
        fixture {
            store.refresh(id, source, key)
            var expected = entry()
            assertTrue(store.persistPasswordTransition(expected, expected.copy(password = security.encryptData("B"), updatedAt = Date(1)), "A"))
            assertTrue(store.captureSnapshot(id, "A"))
            assertEquals(1, histories().size)
            expected = entry()
            store.persistPasswordTransition(expected, expected.copy(password = security.encryptData("A"), updatedAt = Date(2)), "B")
            expected = entry()
            store.persistPasswordTransition(expected, expected.copy(password = security.encryptData("B"), updatedAt = Date(3)), "A")
            val plan = store.prepare(entry(), source, request("B"), key)
            assertEquals(listOf("A", "B", "A"), BitwardenPasswordHistory.decode(plan.prepared.request.passwordHistory, key).map { it.password })
            assertEquals(3, histories().map { it.id }.distinct().size)
        }
    }

    @Test fun clearAfterPasswordChangeCannotResurrectOldPasswordOnNextUpload(): Unit = runBlocking {
        fixture {
            store.refresh(id, source, key)
            val old = entry()
            store.persistPasswordTransition(old, old.copy(password = security.encryptData("B"), updatedAt = Date(1)), "A")
            store.clear(id)
            val plan = store.prepare(entry(), source, request("B"), key)
            assertEquals(JsonArray(emptyList()), plan.prepared.request.passwordHistory)
            assertEquals("B", security.decryptData(entry().password))
        }
    }

    @Test fun lateUploadAcknowledgementPreservesNewerClearAndAdvancesOnlyBaseline(): Unit = runBlocking {
        fixture {
            source = source.copy(passwordHistory = JsonArray(listOf(wire("older"))))
            store.refresh(id, source, key)
            val old = entry()
            store.persistPasswordTransition(old, old.copy(password = security.encryptData("B"), updatedAt = Date(1)), "A")
            val plan = store.prepare(entry(), source, request("B"), key)
            store.clear(id)
            val response = response(plan.prepared.request, "2026-11-04T02:00:00Z")
            assertFalse(store.isCurrent(plan))
            store.acknowledge(plan, response, key)
            assertTrue(histories().isEmpty())
            val state = requireNotNull(db.bitwardenPasswordHistoryStateDao().get(id))
            assertTrue(state.localVersion > state.ackVersion)
            assertEquals(response.revisionDate, state.serverRevision)
            db.passwordEntryDao().update(entry().copy(bitwardenRevisionDate = response.revisionDate))
            val retry = store.prepare(entry(), response, request("B"), key)
            assertEquals(JsonArray(emptyList()), retry.prepared.request.passwordHistory)
        }
    }

    @Test fun dirtyRefreshAndStaleEditorNeverOverwriteHistoryOrPassword(): Unit = runBlocking {
        fixture {
            store.refresh(id, source, key)
            val stale = entry()
            store.persistPasswordTransition(stale, stale.copy(password = security.encryptData("B"), updatedAt = Date(1)), "A")
            val current = entry()
            val ids = histories().map { it.id }
            assertFalse(store.refresh(id, source.copy(passwordHistory = JsonArray(listOf(wire("remote"))), revisionDate = "2026-11-04T02:00:00Z"), key))
            assertTrue(runCatching { store.persistPasswordTransition(stale, stale.copy(password = security.encryptData("C")), "A") }.isFailure)
            assertEquals(current, entry())
            assertEquals(ids, histories().map { it.id })
        }
    }

    @Test fun accountAndGlobalLockRejectMutationAndCorruptResponseCannotClearPendingState(): Unit = runBlocking {
        fixture {
            store.refresh(id, source, key)
            store.clear(id)
            val plan = store.prepare(entry(), source, request("A"), key)
            val before = db.bitwardenPasswordHistoryStateDao().get(id)
            accountUnlocked = false
            assertFalse(store.isCurrent(plan))
            assertTrue(runCatching { store.captureSnapshot(id, "A") }.isFailure)
            accountUnlocked = true
            SessionManager.markLocked()
            assertFalse(store.isCurrent(plan))
            assertTrue(runCatching { store.clear(id) }.isFailure)
            SessionManager.markUnlocked()
            val malformed = source.copy(passwordHistory = JsonArray(listOf(buildJsonObject {
                put("password", "2.corrupt"); put("lastUsedDate", revision)
            })))
            assertTrue(runCatching { store.acknowledge(plan, malformed, key) }.isFailure)
            assertEquals(before, db.bitwardenPasswordHistoryStateDao().get(id))
        }
    }

    @Test fun vaultLockThenUnlockCannotAuthorizeOldUploadOrDelete(): Unit = runBlocking {
        fixture {
            store.refresh(id, source, key)
            val old = entry()
            store.persistPasswordTransition(old, old.copy(password = security.encryptData("B"), updatedAt = Date(1)), "A")
            val session = store.captureSession(1234L)
            val plan = store.prepare(entry(), source, request("B"), key)
            val row = histories().single()
            accountUnlocked = false; accountGeneration++
            accountUnlocked = true; accountGeneration++
            assertFalse(store.isCurrent(plan))
            assertTrue(runCatching { store.deleteHistory(row.id, session) }.isFailure)
            assertTrue(runCatching { store.acknowledge(plan, response(plan.prepared.request, "2026-11-04T02:00:00Z"), key) }.isFailure)
            assertEquals(row, histories().single())
            assertTrue(entry().bitwardenLocalModified)
        }
    }

    @Test fun offlineTransitionsRetainOpaqueWireAndRepeatedHiddenOccurrences(): Unit = runBlocking {
        fixture {
            val unknown = buildJsonObject { put("next-format", "keep") }
            source = source.copy(passwordHistory = JsonArray(listOf(unknown) + (1..5).map { wire("old-$it") }))
            store.refresh(id, source, key)
            val old = entry()
            store.persistPasswordTransition(old, old.copy(password = security.encryptData("B"), updatedAt = Date(1)), "A")
            val plan = store.prepare(entry(), source, request("B"), key)
            assertEquals(unknown, plan.prepared.request.passwordHistory!!.jsonArray.first())
            assertEquals(listOf(null, "old-2", "old-3", "old-4", "old-5", "A"),
                BitwardenPasswordHistory.decode(plan.prepared.request.passwordHistory, key).map { it.password })
            assertEquals(5, histories().size)
        }
    }

    @Test fun migration81To82RetainsOldSecureItemsAndAddsHistoryWithCascade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "synthetic-history-migration-${UUID.randomUUID()}.db"
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(81) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE password_entries (id INTEGER NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE secure_items (id INTEGER NOT NULL PRIMARY KEY, title TEXT NOT NULL, itemData TEXT NOT NULL)")
                        db.execSQL("INSERT INTO secure_items VALUES (7, 'Synthetic retained', 'synthetic-ciphertext')")
                        db.execSQL("INSERT INTO password_entries VALUES (1)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        try {
            val db = helper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=ON")
            PasswordDatabase.MIGRATION_81_82.migrate(db)
            db.query("SELECT title,itemData,isArchived,archivedAt,bitwarden_archive_intent FROM secure_items WHERE id=7").use {
                assertTrue(it.moveToFirst()); assertEquals("Synthetic retained", it.getString(0)); assertEquals("synthetic-ciphertext", it.getString(1))
                assertEquals(0, it.getInt(2)); assertTrue(it.isNull(3)); assertTrue(it.isNull(4))
            }
            db.execSQL("INSERT INTO bitwarden_password_history_states VALUES (1,1234,'synthetic','r1','sealed',0,0)")
            db.execSQL("DELETE FROM password_entries WHERE id=1")
            db.query("SELECT COUNT(*) FROM bitwarden_password_history_states").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally { helper.close(); context.deleteDatabase(name) }
    }

    /** Actual detail component and confirmation dialog; callbacks mutate the real encrypted Store. */
    @Test fun nativeHistoryComponentShowsHidesCancelsDeletesAndClearsStoredHistory(): Unit = runBlocking {
        fixture {
            source = source.copy(passwordHistory = JsonArray(listOf(wire("synthetic-history-A"), wire("synthetic-history-B"))))
            store.refresh(id, source, key)
            val displayed = mutableStateOf(histories().map { it.copy(password = security.decryptData(it.password)) })
            val localized = compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {
                setLocale(java.util.Locale.ENGLISH)
            })
            fun label(res: Int) = localized.getString(res)
            compose.setContent {
                CompositionLocalProvider(LocalContext provides localized) {
                    MonicaTheme {
                        val visibility = remember { mutableStateMapOf<Long, Boolean>() }
                        var deleting by remember { mutableStateOf<PasswordHistoryEntry?>(null) }
                        var clearing by remember { mutableStateOf(false) }
                        val scope = rememberCoroutineScope()
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (displayed.value.isEmpty()) Text("Synthetic empty history")
                                else PasswordHistorySection(displayed.value, visibility, localized,
                                    onDeleteHistoryItem = { deleting = it }, onClearHistory = { clearing = true })
                            }
                        }
                        if (deleting != null || clearing) HistoryPasswordConfirmDialog(
                            title = label(if (clearing) R.string.clear_password_history_title else R.string.delete_password_history_entry_title),
                            message = label(if (clearing) R.string.clear_password_history_message else R.string.delete_password_history_entry_message),
                            confirmText = label(if (clearing) R.string.clear_password_history else R.string.delete),
                            onDismiss = { deleting = null; clearing = false },
                            onConfirm = {
                                val row = deleting
                                val clear = clearing
                                deleting = null; clearing = false
                                scope.launch {
                                    if (clear) store.clear(id) else store.deleteHistory(requireNotNull(row).id)
                                    displayed.value = histories().map { it.copy(password = security.decryptData(it.password)) }
                                }
                            })
                    }
                }
            }
            compose.onNodeWithText("synthetic-history-B").assertDoesNotExist()
            compose.onAllNodesWithContentDescription(label(R.string.show))[0].performScrollTo().performClick()
            compose.onNodeWithText("synthetic-history-B").assertExists()
            compose.onNodeWithContentDescription(label(R.string.hide)).performClick()
            compose.onNodeWithText("synthetic-history-B").assertDoesNotExist()
            compose.onAllNodesWithContentDescription(label(R.string.delete))[0].performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.cancel)).performClick()
            assertEquals(2, histories().size)
            compose.onAllNodesWithContentDescription(label(R.string.delete))[0].performScrollTo().performClick()
            compose.onNode(hasText(label(R.string.delete)) and hasClickAction()).performClick()
            compose.waitUntil(10_000) { displayed.value.size == 1 }
            assertEquals(1, histories().size)
            compose.onNodeWithContentDescription(label(R.string.more_options)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.clear_password_history)).performClick()
            compose.onNodeWithText(label(R.string.cancel)).performClick()
            assertEquals(1, histories().size)
            compose.onNodeWithContentDescription(label(R.string.more_options)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.clear_password_history)).performClick()
            compose.onNode(hasText(label(R.string.clear_password_history)) and hasClickAction()).performClick()
            compose.waitUntil(10_000) { displayed.value.isEmpty() }
            assertTrue(histories().isEmpty())
            val pending = store.prepare(entry(), source, request("A"), key)
            assertEquals(JsonArray(emptyList()), pending.prepared.request.passwordHistory)
            assertTrue(entry().bitwardenLocalModified)
            compose.onNodeWithText("Synthetic empty history").assertExists()
        }
    }

    @Test fun uncertainCreateRebindMergesEveryLocalHistoryOccurrence(): Unit = runBlocking {
        fixture {
            source = source.copy(passwordHistory = JsonArray(listOf(wire("server-old"))))
            db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id, password = security.encryptData("offline-old"), lastUsedAt = Date(1)))
            val pending = store.reconcileRebound(id, source, key, store.captureSession(1234L))
            assertTrue(pending)
            val plan = store.prepare(entry(), source, request("A"), key)
            assertEquals(listOf("server-old", "offline-old"), BitwardenPasswordHistory.decode(plan.prepared.request.passwordHistory, key).map { it.password })
            assertEquals(2, histories().size)
        }
    }

    @Test fun missingCreateHistoryEchoCannotResurrectClearMadeWhilePosting(): Unit = runBlocking {
        fixture {
            val unbound = entry().copy(bitwardenCipherId = null, bitwardenRevisionDate = null, bitwardenLocalModified = true)
            db.passwordEntryDao().update(unbound)
            db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id, password = security.encryptData("H1"), lastUsedAt = Date(1)))
            db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id, password = security.encryptData("H2"), lastUsedAt = Date(2)))
            val create = CipherCreateRequest(type = 1, name = BitwardenCrypto.encryptString("Synthetic", key), login = request("A").login)
            val plan = store.prepareCreate(unbound, create, key)
            store.clear(id)
            val missingEcho = source.copy(id = "created-without-history-echo", revisionDate = "2026-11-04T02:00:00Z", passwordHistory = null)
            db.passwordEntryDao().update(entry().copy(bitwardenCipherId = missingEcho.id, bitwardenRevisionDate = missingEcho.revisionDate))
            store.retainUnconfirmedCreate(plan, missingEcho)
            val actualGet = missingEcho.copy(passwordHistory = plan.request.passwordHistory)
            val retry = store.prepare(entry(), actualGet, request("A"), key)
            assertEquals(JsonArray(emptyList()), retry.prepared.request.passwordHistory)
            assertTrue(histories().isEmpty())
            assertTrue(entry().bitwardenLocalModified)
        }
    }

    @Test fun clearAfterLostPostSurvivesStoreRecreationAndRebinding(): Unit = runBlocking {
        fixture {
            db.passwordEntryDao().update(entry().copy(bitwardenCipherId = null, bitwardenRevisionDate = null, bitwardenLocalModified = true))
            db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id, password = security.encryptData("posted-H1"), lastUsedAt = Date(1)))
            // The POST completed at the server but its response and in-memory plan were lost.
            val remote = source.copy(passwordHistory = JsonArray(listOf(wire("posted-H1"))))
            store.clear(id)
            val local = requireNotNull(db.bitwardenPasswordHistoryStateDao().get(id))
            assertEquals("local:$id", local.cipherId)
            val reopened = BitwardenPasswordHistoryStore(context, db, security,
                vaultSessionGeneration = { accountGeneration }, vaultUnlocked = { accountUnlocked })
            db.passwordEntryDao().update(entry().copy(bitwardenCipherId = remote.id, bitwardenRevisionDate = remote.revisionDate))
            assertTrue(reopened.reconcileRebound(id, remote, key, reopened.captureSession(1234L)))
            val bound = requireNotNull(db.bitwardenPasswordHistoryStateDao().get(id))
            assertEquals(remote.id, bound.cipherId)
            assertTrue(histories().isEmpty())
            val retry = reopened.prepare(entry(), remote, request("A"), key)
            assertEquals(JsonArray(emptyList()), retry.prepared.request.passwordHistory)
        }
    }

    @Test fun unboundHistoryDeletionRetainsOtherOccurrenceAcrossLostPostAndRebind(): Unit = runBlocking {
        fixture {
            db.passwordEntryDao().update(entry().copy(bitwardenCipherId = null, bitwardenRevisionDate = null, bitwardenLocalModified = true))
            val first = db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id, password = security.encryptData("same"), lastUsedAt = Date(1)))
            db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id, password = security.encryptData("same"), lastUsedAt = Date(1)))
            val posted = store.prepareCreate(entry(), CipherCreateRequest(type = 1,
                name = BitwardenCrypto.encryptString("Synthetic", key), login = request("A").login), key).request.passwordHistory
            store.deleteHistory(first)
            val remote = source.copy(passwordHistory = posted)
            val reopened = BitwardenPasswordHistoryStore(context, db, security,
                vaultSessionGeneration = { accountGeneration }, vaultUnlocked = { accountUnlocked })
            db.passwordEntryDao().update(entry().copy(bitwardenCipherId = remote.id, bitwardenRevisionDate = remote.revisionDate))
            assertTrue(reopened.reconcileRebound(id, remote, key, reopened.captureSession(1234L)))
            val retry = reopened.prepare(entry(), remote, request("A"), key)
            assertEquals(listOf("same"), BitwardenPasswordHistory.decode(retry.prepared.request.passwordHistory, key).map { it.password })
            assertEquals(1, histories().size)
        }
    }

    @Test fun swappingSealedHistoryPayloadBetweenEntriesIsRejected(): Unit = runBlocking {
        fixture {
            source = source.copy(passwordHistory = JsonArray(listOf(wire("first-history"))))
            store.refresh(id, source, key)
            val second = source.copy(id = "second-history-source", passwordHistory = JsonArray(listOf(wire("second-history"))))
            val secondId = db.passwordEntryDao().insert(entry().copy(id = 0, bitwardenCipherId = second.id))
            store.refresh(secondId, second, key)
            val firstState = requireNotNull(db.bitwardenPasswordHistoryStateDao().get(id))
            val secondState = requireNotNull(db.bitwardenPasswordHistoryStateDao().get(secondId))
            db.bitwardenPasswordHistoryStateDao().put(firstState.copy(payloadMdk = secondState.payloadMdk))
            val originalRows = histories()
            assertTrue(runCatching { store.clear(id) }.isFailure)
            assertTrue(runCatching { store.prepare(entry(), source, request("B"), key) }.isFailure)
            assertEquals(originalRows, histories())
            assertEquals(secondState, db.bitwardenPasswordHistoryStateDao().get(secondId))
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        Fixture().use { it.initialize(); it.block() }
    }

    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "bw-history-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply { dataDir = root.absolutePath }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val cacheField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val originalMdk = (cacheField.get(null) as? ByteArray)?.copyOf()
        val originallyUnlocked = SessionManager.isUnlocked.value
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        lateinit var security: SecurityManager
        lateinit var store: BitwardenPasswordHistoryStore
        val key = SymmetricCryptoKey(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 33).toByte() })
        val revision = "2026-11-04T00:00:00Z"
        var accountUnlocked = true
        var accountGeneration = 0L
        var id = 0L
        lateinit var source: CipherApiResponse
        fun wire(value: String) = buildJsonObject { put("password", BitwardenCrypto.encryptString(value, key)); put("lastUsedDate", revision) }
        fun request(value: String) = CipherUpdateRequest(type = 1, name = BitwardenCrypto.encryptString("Synthetic", key),
            login = CipherLoginApiData(password = BitwardenCrypto.encryptString(value, key)))
        fun response(request: CipherUpdateRequest, revision: String) = source.copy(login = request.login,
            passwordHistory = request.passwordHistory, revisionDate = revision)
        suspend fun entry() = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
        suspend fun histories() = db.passwordHistoryDao().getHistoryByEntryIdSync(id)
        suspend fun initialize() {
            SecurityManager.clearRuntimeUnlockCache()
            security = SecurityManager(context)
            check(security.setMasterPassword("Synthetic BW history MDK 324!"))
            SessionManager.markUnlocked()
            store = BitwardenPasswordHistoryStore(context, db, security, vaultSessionGeneration = { accountGeneration }, vaultUnlocked = { accountUnlocked })
            source = CipherApiResponse(id = "synthetic-history", type = 1, name = BitwardenCrypto.encryptString("Synthetic", key),
                login = CipherLoginApiData(password = BitwardenCrypto.encryptString("A", key), passwordRevisionDate = revision), revisionDate = revision)
            id = db.passwordEntryDao().insert(PasswordEntry(title = "Synthetic", website = "", username = "", password = security.encryptData("A"),
                bitwardenVaultId = 1234L, bitwardenCipherId = source.id, bitwardenCipherType = 1, bitwardenRevisionDate = revision))
        }
        override fun close() {
            db.close()
            key.clear()
            SecurityManager.clearRuntimeUnlockCache()
            cacheField.set(null, originalMdk)
            if (originallyUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.parentFile == base.cacheDir && root.name.startsWith("bw-history-"))
            root.deleteRecursively()
        }
    }
}
