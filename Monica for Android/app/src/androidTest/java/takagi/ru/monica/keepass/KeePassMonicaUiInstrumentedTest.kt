package takagi.ru.monica.keepass

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.PasswordViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import takagi.ru.monica.data.*
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import java.util.UUID
import takagi.ru.monica.ui.model.plainValueOrEmpty

/** Selected KDBX must feed the ordinary Monica list model without creating Room passwords. */
@RunWith(AndroidJUnit4::class)
class KeePassMonicaUiInstrumentedTest {
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private suspend fun select(model: PasswordViewModel, id: Long, count: Int = 300) {
        withContext(Dispatchers.Main) {
            val field = PasswordViewModel::class.java.getDeclaredField("_categoryFilter").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(model) as MutableStateFlow<CategoryFilter>).value = CategoryFilter.KeePassDatabase(id)
        }
        withTimeout(30000) { while (model.passwordEntries.value.size != count) delay(25) }
    }
    private suspend fun save(model: PasswordViewModel, row: PasswordEntry, values: List<String>, fields: List<CustomFieldDraft> = emptyList()): Long? {
        val done = CompletableDeferred<Long?>()
        withContext(Dispatchers.Main) { model.saveGroupedPasswords(listOf(row.id), row, values, fields) { done.complete(it) } }
        return withTimeout(30000) { done.await() }
    }
    private fun commits(uri: Uri) = resolver.query(uri, arrayOf("commits"), null, null, null)!!.use { it.moveToFirst(); it.getInt(0) }

    @Test fun selectedVaultUsesMonicaPasswordListWithoutRoomProjection() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { passwords, service, id, room, _ ->
            service.openNativeBrowser(id, true).getOrThrow()
            passwords.holdNativeKeePassRead(id).use {
                withContext(Dispatchers.Main) {
                    val field = PasswordViewModel::class.java.getDeclaredField("_categoryFilter").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    (field.get(passwords) as MutableStateFlow<CategoryFilter>).value = CategoryFilter.KeePassDatabase(id)
                }
                withTimeout(30000) { while (passwords.passwordEntries.value.size != 300) delay(25) }
                assertEquals("The standard Monica list must read the original vault", 300, passwords.passwordEntries.value.size)
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
            }
        }
    }

    @Test fun monicaFormCommitsOnceAndKeepsUuidFieldsHistoryAndOtherEntries() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, uri ->
            var browser = service.openNativeBrowser(id, true).getOrThrow()
            val target = browser.entries.first()
            service.replaceNativeEntryFields(id, target.identity.entryUuid,
                target.fields.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) } +
                    KeePassFieldChange("External field", "protected fixture", true) + KeePassFieldChange("MonicaLocalId", "123"),
                browser.sourceRevision.sha256).getOrThrow()
            browser = service.openNativeBrowser(id, true).getOrThrow()
            service.addNativeAttachment(id, target.identity.entryUuid, "fixture.txt", "keep this attachment".toByteArray(),
                browser.sourceRevision.sha256).getOrThrow()
            browser = service.openNativeBrowser(id, true).getOrThrow()
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.single { it.keepassEntryUuid == target.identity.entryUuid.toString() }
                assertNotNull(model.getRawPasswordEntryById(row.id, true))
                val before = commits(uri)
                assertEquals(row.id, save(model, row.copy(title = "Monica form saved"), listOf(row.password),
                    model.getCustomFieldsByEntryIdSync(row.id).map(CustomFieldDraft::fromCustomField)))
                assertEquals(1, commits(uri) - before)
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                val after = service.openNativeBrowser(id, true).getOrThrow()
                val saved = after.entry(target.identity)!!
                assertEquals("Monica form saved", saved.title)
                assertEquals("123", saved.field("MonicaLocalId")!!.rawValue)
                assertEquals("protected fixture", saved.field("External field")!!.rawValue)
                assertTrue(saved.field("External field")!!.isProtected)
                assertEquals(row.password, saved.field("Password")!!.rawValue)
                assertTrue(saved.history.size > browser.entry(target.identity)!!.history.size)
                assertEquals(browser.entry(target.identity)!!.attachments, saved.attachments)
                browser.entries.filter { it.identity != target.identity }.forEach { old ->
                    assertEquals(old.fields.map { it.name to it.rawValue }, after.entry(old.identity)!!.fields.map { it.name to it.rawValue })
                }
            }
        }
    }

    @Test fun staleMonicaDraftIsRejectedEvenAfterListRefresh() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, uri ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.first()
                model.getRawPasswordEntryById(row.id, true)
                val browser = service.openNativeBrowser(id, true).getOrThrow()
                val entry = browser.entry(KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid)))!!
                service.replaceNativeEntryFields(id, entry.identity.entryUuid,
                    entry.fields.filterNot { it.name == "Notes" }.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) } +
                        KeePassFieldChange("Notes", "Other writer"), browser.sourceRevision.sha256).getOrThrow()
                model.holdNativeKeePassRead(id).use { withTimeout(30000) { while (model.passwordEntries.value.none { it.notes == "Other writer" }) delay(25) } }
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                assertNull(save(model, row.copy(notes = "Stale overwrite"), listOf(row.password)))
                assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
            }
        }
    }

    @Test fun deniedMonicaSavePreservesFileAndVisibleEntry() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, _, id, room, uri ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.first()
                model.getRawPasswordEntryById(row.id, true)
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                resolver.call(uri, "deny-write", uri.toString(), null)
                assertNull(save(model, row.copy(title = "Denied"), listOf("replacement")))
                assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
                assertEquals(row, model.getRawPasswordEntryById(row.id))
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
            }
        }
    }

    @Test fun passwordWithMonicaCipherPrefixRemainsLiteralInOriginalVault() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, _ ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.first()
                model.getRawPasswordEntryById(row.id, true)
                val literal = "C2|this-is-a-real-keepass-password"
                assertEquals(row.id, save(model, row, listOf(literal)))
                assertEquals(literal, model.inspectSecretState(requireNotNull(model.getRawPasswordEntryById(row.id))).plainValueOrEmpty())
                assertEquals(literal, service.openNativeBrowser(id, true).getOrThrow().entry(
                    KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid)))!!.field("Password")!!.rawValue)
            }
        }
    }

    @Test fun lockingVaultClearsNativeRowsAndRejectsDraftWithoutReopening() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, uri ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.first()
                model.getRawPasswordEntryById(row.id, true)
                val before = commits(uri)
                service.lockDatabase(id)
                withTimeout(10000) { while (model.passwordEntries.value.isNotEmpty()) delay(25) }
                assertNull(model.getRawPasswordEntryById(row.id))
                assertNull(save(model, row, listOf("blocked")))
                assertEquals(before, commits(uri))
                assertFalse(service.isDatabaseUnlocked(id))
            }
        }
    }

    @Test fun groupedMonicaCredentialsShareOneCommitAndPreserveLocalSentinel() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, _, id, room, uri ->
            val local = PasswordEntry(title = "Local sentinel", website = "local.test", username = "local", password = "local fixture")
            val localId = room.passwordEntryDao().insertPasswordEntry(local)
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.first()
                model.getRawPasswordEntryById(row.id, true)
                val before = commits(uri)
                assertEquals(row.id, save(model, row, listOf("one", "two", "three")))
                assertEquals(1, commits(uri) - before)
                withTimeout(30000) { while (model.passwordEntries.value.size != 302) delay(25) }
                val saved = model.passwordEntries.value.single { it.id == row.id }
                assertEquals(3, model.passwordEntries.value.count { it.passwordGroupId == saved.passwordGroupId })
                assertEquals(local.copy(id = localId), room.passwordEntryDao().getPasswordEntryById(localId))
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
            }
        }
    }

    @Test fun originalVaultMoveToLocalCreatesAndVerifiesDestinationBeforeRemovingSource() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, _ ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.first()
                val result = model.moveKeePassPasswordsToMonicaCategoryAwait(listOf(row.id), null)
                assertEquals(1, result.getOrThrow())
                val local = room.passwordEntryDao().getLocalBackupEntries().single()
                assertFalse(KeePassMonicaPasswordStore.isUiId(local.id))
                assertEquals(row.title, local.title)
                assertEquals(row.password, model.inspectSecretState(local).plainValueOrEmpty())
                assertFalse(service.openNativeBrowser(id, true).getOrThrow().entries.any { it.identity.entryUuid.toString() == row.keepassEntryUuid })
            }
        }
    }

    @Test fun existingMonicaAttachmentControlsReadAndMutateOriginalVaultWithoutRoomParents() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, _ ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.first()
                model.getRawPasswordEntryById(row.id, true)
                val owner = takagi.ru.monica.attachments.model.AttachmentOwner.password(row.id)
                val repository = takagi.ru.monica.attachments.repository.AttachmentRepository(room.attachmentDao())
                val store = requireNotNull(model.monicaKeePassStore)
                repository.bindOriginalKeePassStore(store)
                store.addAttachment(owner, "native.txt", "native attachment".toByteArray())
                val asset = repository.list(owner).single()
                assertEquals("native.txt", asset.fileName)
                assertTrue(KeePassMonicaPasswordStore.isUiId(asset.id))
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                assertTrue(room.attachmentDao().getActiveByParent(row.id).isEmpty())
                assertEquals("native attachment", service.readAttachmentBytes(id, requireNotNull(row.keepassEntryUuid), requireNotNull(asset.keepassBinaryRef)).getOrThrow().decodeToString())
                // An attachment change made by this form advances its own baseline, so field save still succeeds.
                assertEquals(row.id, save(model, row.copy(title = "Own attachment edit"), listOf(row.password)))
                store.removeAttachment(owner, requireNotNull(asset.keepassBinaryRef))
                assertTrue(repository.list(owner).isEmpty())
                store.addAttachment(owner, "locked.txt", byteArrayOf(1))
                val lockedId = repository.list(owner).single().id
                service.lockDatabase(id)
                assertNull(repository.getById(lockedId))
            }
        }
    }

    @Test fun batchCopyEncryptsOriginalVaultPlaintextBeforeInsertingLocalRows() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, _, id, room, _ ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.first().copy(password = "C2|literal-original-secret")
                val copy = model.buildEncryptedPasswordCopy(row, takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget.Uncategorized)
                assertNotEquals(row.password, copy.password)
                val done = CompletableDeferred<Long?>()
                withContext(Dispatchers.Main) { model.addPasswordEntryWithResult(copy, false, true, true) { done.complete(it) } }
                val targetId = requireNotNull(withTimeout(30000) { done.await() })
                val saved = requireNotNull(room.passwordEntryDao().getPasswordEntryById(targetId))
                assertEquals(row.password, model.inspectSecretState(saved).plainValueOrEmpty())
                val security = takagi.ru.monica.security.SecurityManager(InstrumentationRegistry.getInstrumentation().targetContext)
                val nested = PasswordEntry(title = "Legacy nested fixture", website = "", username = "", password = security.encryptData(security.encryptData("nested secret")))
                val nestedId = room.passwordEntryDao().insertPasswordEntry(nested)
                assertEquals("nested secret", model.inspectSecretState(nested.copy(id = nestedId)).plainValueOrEmpty())
                val corrupt = PasswordEntry(title = "Corrupt outer fixture", website = "", username = "", password = "C2|invalid outer payload")
                val corruptId = room.passwordEntryDao().insertPasswordEntry(corrupt)
                assertTrue(model.inspectSecretState(corrupt.copy(id = corruptId)) is takagi.ru.monica.ui.model.SecretValueState.Unreadable)
                assertEquals(corrupt.password, room.passwordEntryDao().getPasswordEntryById(corruptId)!!.password)
                assertEquals(300, model.passwordEntries.value.size)
            }
        }
    }

    @Test fun originalAttachmentUploadsSupportDuplicateAndWhitespaceFileNames() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, _ ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.first()
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val storage = takagi.ru.monica.attachments.storage.AttachmentStorage(context)
                val executor = takagi.ru.monica.attachments.executor.KeePassAttachmentExecutor(context, service, storage,
                    takagi.ru.monica.attachments.storage.AttachmentKeyVault(takagi.ru.monica.security.SecurityManager(context)))
                val owner = takagi.ru.monica.attachments.model.AttachmentOwner.password(row.id)
                val repository = takagi.ru.monica.attachments.repository.AttachmentRepository(room.attachmentDao())
                repository.bindOriginalKeePassStore(requireNotNull(model.monicaKeePassStore))
                val uploads = mutableListOf<takagi.ru.monica.attachments.model.Attachment>()
                try {
                    listOf("first", "second", "second").forEach { text ->
                        val asset = executor.upload(owner, id, requireNotNull(row.keepassEntryUuid), " same.txt ", "text/plain", text.toByteArray())
                        uploads += asset
                        repository.insert(asset)
                    }
                    val assets = repository.list(owner)
                    assertEquals(2, assets.size)
                    assertTrue(assets.all { it.fileName == "same.txt" })
                    assertTrue(assets.all { repository.getById(it.id) != null })
                    assertEquals(setOf("first", "second"), assets.map { asset -> service.readAttachmentBytes(id,
                        requireNotNull(row.keepassEntryUuid), requireNotNull(asset.keepassBinaryRef)).getOrThrow().decodeToString() }.toSet())
                    assertTrue(room.attachmentDao().getActiveByParent(row.id).isEmpty())
                } finally { uploads.mapNotNull { it.localPath }.forEach { storage.delete(it) } }
            }
        }
    }

    @Test fun movingOriginalVaultLinksWithoutDestinationReferencesLeavesSourceIntact() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, uri ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val browser = service.openNativeBrowser(id, true).getOrThrow()
                val source = browser.entries.first()
                service.replaceNativeEntryFields(id, source.identity.entryUuid,
                    source.fields.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) } +
                        KeePassFieldChange("MonicaSsoRefEntryUuid", browser.entries[1].identity.entryUuid.toString()),
                    browser.sourceRevision.sha256).getOrThrow()
                val row = requireNotNull(model.monicaKeePassStore).refresh(id).rows.single { it.keepassEntryUuid == source.identity.entryUuid.toString() }
                assertTrue(KeePassMonicaPasswordStore.isUiId(requireNotNull(row.ssoRefEntryId)))
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                assertTrue(model.moveKeePassPasswordsToMonicaCategoryAwait(listOf(row.id), null).isFailure)
                assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
                assertTrue(room.passwordEntryDao().getLocalBackupEntries().isEmpty())
            }
        }
    }

    @Test fun movingOriginalHistoryToAnUnsupportedDestinationPreservesOriginalFile() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, uri ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val original = model.passwordEntries.value.first()
                model.getRawPasswordEntryById(original.id, true)
                assertEquals(original.id, save(model, original.copy(title = "History fixture"), listOf(original.password)))
                val row = requireNotNull(model.getRawPasswordEntryById(original.id))
                assertTrue(requireNotNull(model.monicaKeePassStore).hasOriginalHistory(row.id))
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                assertTrue(model.moveKeePassPasswordsToMonicaCategoryAwait(listOf(row.id), null).isFailure)
                assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
                assertTrue(room.passwordEntryDao().getLocalBackupEntries().isEmpty())
                assertTrue(service.openNativeBrowser(id, true).getOrThrow().entries.any { it.identity.entryUuid.toString() == row.keepassEntryUuid })
            }
        }
    }
}
