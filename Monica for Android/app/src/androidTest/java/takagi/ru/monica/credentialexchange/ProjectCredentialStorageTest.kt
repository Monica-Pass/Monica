package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import org.json.JSONObject
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.WebDavHelper

@RunWith(AndroidJUnit4::class)
class ProjectCredentialStorageTest {
    @Test fun localAccountsKeepEncryptedRowsAndStableIdentityAcrossEditDeleteAndReorder() = runBlocking {
        scenario { model -> exercise(model, StorageTarget.MonicaLocal(null)) }
    }
    @Test fun mdbxAccountsKeepAllPasswordsAndMetadataInNativeStorage() = runBlocking {
        scenario { model -> exercise(model, StorageTarget.Mdbx(mdbx().databaseId!!)) }
    }
    @Test fun keepassAccountsKeepAllPasswordsAndMetadataInNativeStorage() = runBlocking {
        scenario { model -> exercise(model, StorageTarget.KeePass(keepass().databaseId!!, null)) }
    }

    @Test fun bitwardenFreshDownloadPreservesEachAccountPasswordAndGroup() = runBlocking {
        withSyntheticMdk { scenario { viewModel ->
            val destination = bitwarden()
            val groups = listOf(ProjectCredentialGroup.Group(username = "personal", note = "Personal note", passwords = listOf(ProjectCredentialGroup.Password(value = "personal-secret"))),
                ProjectCredentialGroup.Group(username = "work", note = "Work note\nSecond line", passwords = listOf(ProjectCredentialGroup.Password(value = "work-secret-1"), ProjectCredentialGroup.Password(value = "work-secret-2"))))
            val done = CompletableDeferred<Long?>()
            viewModel.savePasswordsAcrossTargets(emptyList(), PasswordEntry(title = "$prefix-bw", username = "", password = "", website = website),
                emptyList(), listOf(StorageTarget.Bitwarden(destination.databaseId, null)), projectCredentials = groups, onComplete = { done.complete(it) })
            requireNotNull(withTimeout(30000) { done.await() })
            val vault = requireNotNull(db.bitwardenVaultDao().getVaultById(destination.databaseId))
            takagi.ru.monica.bitwarden.service.BitwardenSyncService(context).uploadLocalEntries(vault, accessToken, vaultKey)
            assertEquals(3, remote.created.size)
            assertFalse(remote.created.toString().contains("personal-secret"))
            assertFalse(remote.created.toString().contains("work-secret"))
            importedPasswords(destination).forEach { db.passwordEntryDao().deletePasswordEntryById(it.id) }
            val processor = takagi.ru.monica.bitwarden.service.CipherSyncProcessor(context)
            remote.created.forEach { cipher ->
                val response = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<takagi.ru.monica.bitwarden.api.CipherApiResponse>(cipher.toString())
                processor.syncCipherFromServer(vault, response, vaultKey)
            }
            val received = importedPasswords(destination)
            assertEquals(3, received.size)
            assertEquals(1, received.map { it.passwordProjectKey() }.distinct().size)
            val fields = received.associate { it.id to db.customFieldDao().getFieldsByEntryIdSync(it.id).map(CustomFieldDraft::fromCustomField) }
            val restored = ProjectCredentialGroup.restore(received.map { it.copy(password = security.decryptData(it.password)) }, fields)
            assertEquals(groups.map { it.id }, restored.map { it.id })
            assertEquals(groups.map { it.username }, restored.map { it.username })
            assertEquals(groups.map { it.note }, restored.map { it.note })
            assertEquals(groups.flatMap { it.passwords.map { pwd -> pwd.value } }, restored.flatMap { it.passwords.map { pwd -> pwd.value } })
        } }
    }

    private suspend fun withSyntheticMdk(block: suspend () -> Unit) {
        // Password history now requires MDK encryption, including on a fresh test user.
        // Keep this fixture independent of the user's persisted master-password setup.
        val field = takagi.ru.monica.security.SecurityManager::class.java
            .getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val previous = field.get(null)
        val synthetic = ByteArray(32) { (it + 27).toByte() }
        field.set(null, synthetic)
        try { block() } finally { field.set(null, previous); synthetic.fill(0) }
    }

    @Test fun failedSecondDestinationRollsBackEditsAndKeepsOriginalRows() = runBlocking {
        scenario { model ->
            val entry = PasswordEntry(title = "$prefix-failure", username = "original", password = "", website = website)
            val groups = listOf(ProjectCredentialGroup.Group(username = "original", passwords = listOf(ProjectCredentialGroup.Password(value = "old-secret"))))
            suspend fun attempt(groups: List<ProjectCredentialGroup.Group>, ids: List<Long>, targets: List<StorageTarget>): Long? {
                val done = CompletableDeferred<Long?>()
                model.savePasswordsAcrossTargets(ids, entry, emptyList(), targets, projectCredentials = groups, onComplete = { done.complete(it) })
                return withTimeout(30000) { done.await() }
            }
            val id = requireNotNull(attempt(groups, emptyList(), listOf(StorageTarget.MonicaLocal(null))))
            val original = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            val fields = db.customFieldDao().getFieldsByEntryIdSync(id).map(CustomFieldDraft::fromCustomField)
            val restored = ProjectCredentialGroup.restore(listOf(original.copy(password = security.decryptData(original.password))), mapOf(id to fields))
            val changed = restored.map { it.copy(username = "changed", passwords = it.passwords.map { pwd -> pwd.copy(value = "new-secret") }) }
            assertNull(attempt(changed, listOf(id), listOf(StorageTarget.MonicaLocal(null), StorageTarget.Mdbx(Long.MAX_VALUE))))
            val after = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            assertFalse(after.isDeleted)
            assertEquals("original", after.username)
            assertEquals("old-secret", security.decryptData(after.password))
            assertEquals(fields.map { it.value }, db.customFieldDao().getFieldsByEntryIdSync(id).map { it.value })
        }
    }

    @Test fun multipleFoldersKeepCompleteIndependentCopies() = runBlocking {
        scenario { model ->
            val cats = listOf(Category(name = "$prefix-A"), Category(name = "$prefix-B")).map { it.copy(id = db.categoryDao().insert(it)) }
            try {
                val groups = listOf(ProjectCredentialGroup.Group(username = "one", passwords = listOf(ProjectCredentialGroup.Password(value = "first"))),
                    ProjectCredentialGroup.Group(username = "two", passwords = listOf(ProjectCredentialGroup.Password(value = "second"))))
                val done = CompletableDeferred<Long?>()
                model.savePasswordsAcrossTargets(emptyList(), PasswordEntry(title = "$prefix-folders", website = website, username = "", password = ""),
                    emptyList(), cats.map { StorageTarget.MonicaLocal(it.id) }, projectCredentials = groups, onComplete = { done.complete(it) })
                requireNotNull(withTimeout(30000) { done.await() })
                val rows = db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.title == "$prefix-folders" && !it.isDeleted }
                assertEquals(4, rows.size)
                assertEquals(2, rows.map { it.passwordProjectKey() }.distinct().size)
                cats.forEach { cat ->
                    val copy = rows.filter { it.categoryId == cat.id }
                    assertEquals(2, copy.size)
                    val fields = copy.associate { it.id to db.customFieldDao().getFieldsByEntryIdSync(it.id).map(CustomFieldDraft::fromCustomField) }
                    assertEquals(listOf("one", "two"), ProjectCredentialGroup.restore(copy.map { it.copy(password = security.decryptData(it.password)) }, fields).map { it.username })
                }
            } finally { cats.forEach { db.categoryDao().delete(it) } }
        }
    }

    @Test fun replacingPrimaryPasswordAndMovingProjectPreservesSharedAttachmentBytes() = runBlocking {
        scenario { model ->
            val groups = listOf(ProjectCredentialGroup.Group(username = "personal", passwords = listOf(
                ProjectCredentialGroup.Password(value = "first"), ProjectCredentialGroup.Password(value = "second"))),
                ProjectCredentialGroup.Group(username = "work", passwords = listOf(ProjectCredentialGroup.Password(value = "third"))))
            val title = "$prefix-attachments"
            val common = PasswordEntry(title = title, website = website, username = "", password = "")
            suspend fun save(draft: List<ProjectCredentialGroup.Group>, ids: List<Long>, target: StorageTarget): Long {
                val done = CompletableDeferred<Long?>()
                model.savePasswordsAcrossTargets(ids, common, emptyList(), listOf(target), projectCredentials = draft, onComplete = { done.complete(it) })
                return requireNotNull(withTimeout(30000) { done.await() })
            }
            suspend fun rows() = db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.title == title && !it.isDeleted }
            suspend fun restore(): List<ProjectCredentialGroup.Group> {
                val current = rows()
                val fields = current.associate { it.id to db.customFieldDao().getFieldsByEntryIdSync(it.id).map(CustomFieldDraft::fromCustomField) }
                return ProjectCredentialGroup.restore(current.map { it.copy(password = security.decryptData(it.password)) }, fields)
            }
            val id = save(groups, emptyList(), StorageTarget.MonicaLocal(null))
            val facade = takagi.ru.monica.attachments.AttachmentContainer.facade(context)
            val bytes = ByteArray(65537) { (it % 251).toByte() }
            facade.addInlineAttachment(takagi.ru.monica.attachments.facade.AttachmentFacade.InlineUploadRequest(
                takagi.ru.monica.attachments.model.AttachmentOwner.password(id), takagi.ru.monica.attachments.model.AttachmentSource.LOCAL,
                "group-file.bin", "application/octet-stream", bytes, true))
            suspend fun verify(owner: Long) {
                val attachment = facade.listByPassword(owner).single { it.fileName == "group-file.bin" }
                val output = java.io.ByteArrayOutputStream()
                facade.copyAttachmentTo(attachment.id, output)
                assertArrayEquals(bytes, output.toByteArray())
            }
            val loaded = restore()
            val owner = save(listOf(loaded[0].copy(passwords = loaded[0].passwords.drop(1)), loaded[1]), rows().map { it.id }, StorageTarget.MonicaLocal(null))
            assertNotEquals(id, owner)
            verify(owner)
            val mdbxTarget = mdbx().databaseId!!
            val newOwner = save(restore(), rows().map { it.id }, StorageTarget.Mdbx(mdbxTarget))
            assertEquals(2, rows().size)
            verify(newOwner)
            assertEquals(setOf("second", "third"), rows().map { security.decryptData(it.password) }.toSet())
        }
    }

    private fun TransferFixture.verifyAutofill(rows: List<PasswordEntry>) {
        val usernameId = android.widget.EditText(context).autofillId
        val passwordId = android.widget.EditText(context).autofillId
        fun data(id: android.view.autofill.AutofillId, hint: takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint) =
            takagi.ru.monica.autofill_ng.model.AutofillView.Data(id, 1, true, null, null, hint)
        val views = listOf(
            takagi.ru.monica.autofill_ng.model.AutofillView.Login.Username(data(usernameId, takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint.USERNAME)),
            takagi.ru.monica.autofill_ng.model.AutofillView.Login.Password(data(passwordId, takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint.PASSWORD)))
        val request = takagi.ru.monica.autofill_ng.model.AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(), inlinePresentationSpecs = null, maxInlineSuggestionsCount = 0,
            isCompatMode = false, packageName = "test.project.credentials", partition = takagi.ru.monica.autofill_ng.model.AutofillPartition.Login(views), uri = website)
        val built = takagi.ru.monica.autofill_ng.builder.FilledDataBuilderNg(context, security).build(request, rows, requireAuthentication = false)
        assertEquals(rows.size, built.filledPartitions.size)
        rows.forEach { row ->
            val partition = built.filledPartitions.single { it.autofillCipher.cipherId == row.id.toString() }
            assertEquals(row.username, partition.filledItems.single { it.autofillId == usernameId }.value?.textValue?.toString())
            assertEquals(security.decryptData(row.password), partition.filledItems.single { it.autofillId == passwordId }.value?.textValue?.toString())
        }
    }

    private suspend fun scenario(block: suspend TransferFixture.(PasswordViewModel) -> Unit) {
        val fixture = TransferFixture()
        val model = PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = CustomFieldRepository(fixture.db.customFieldDao()), context = fixture.context,
            localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(fixture.context))
        try { fixture.block(model) } finally { model.viewModelScope.cancel(); fixture.close() }
    }

    private suspend fun TransferFixture.exercise(model: PasswordViewModel, target: StorageTarget) {
        val title = "$prefix-project-accounts"
        val common = PasswordEntry(title = title, website = website, username = "", password = "", notes = "Shared notes")
        val groups = listOf(
            ProjectCredentialGroup.Group(username = "personal@example.invalid", otp = "JBSWY3DPEHPK3PXP", note = "Personal note",
                passwords = listOf(ProjectCredentialGroup.Password(value = "  primary 1  "), ProjectCredentialGroup.Password(value = "primary 2"))),
            ProjectCredentialGroup.Group(label = "Work", username = "work@example.invalid", note = "Work note\nSecond line",
                passwords = listOf(ProjectCredentialGroup.Password(value = "work 1"), ProjectCredentialGroup.Password(value = "work 2"))))
        suspend fun save(groups: List<ProjectCredentialGroup.Group>, ids: List<Long> = emptyList()): Long {
            val result = CompletableDeferred<Long?>()
            model.savePasswordsAcrossTargets(ids, common, emptyList(), listOf(target),
                customFields = listOf(CustomFieldDraft(title = "Shared custom", value = "Shared")),
                projectCredentials = groups, onComplete = { result.complete(it) })
            return requireNotNull(withTimeout(60_000) { result.await() }) { "Project save failed for $target" }
        }
        suspend fun current() = db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.title == title && !it.isDeleted }
        suspend fun restore(): List<ProjectCredentialGroup.Group> {
            val entries = current()
            val fields = entries.associate { it.id to db.customFieldDao().getFieldsByEntryIdSync(it.id).map(CustomFieldDraft::fromCustomField) }
            return ProjectCredentialGroup.restore(entries.map { it.copy(password = security.decryptData(it.password),
                authenticatorKey = security.decryptDataIfMonicaCiphertext(it.authenticatorKey)) }, fields)
        }
        suspend fun verifyNativeAndBackup(expected: List<ProjectCredentialGroup.Group>) {
            val destination = when (target) {
                is StorageTarget.Mdbx -> ImportDestination(ImportDestinationKind.MDBX, target.databaseId)
                is StorageTarget.KeePass -> ImportDestination(ImportDestinationKind.KEEPASS, target.databaseId)
                is StorageTarget.Bitwarden -> ImportDestination(ImportDestinationKind.BITWARDEN, target.vaultId)
                is StorageTarget.MonicaLocal -> ImportDestination(ImportDestinationKind.LOCAL)
            }
            val expectedRows = ProjectCredentialGroup.rows(expected)
            if (target is StorageTarget.Mdbx) {
                Mdbx2NativeReadSessions.clear()
                val payloads = mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }.map { JSONObject(it.payloadJson) }
                assertEquals(expectedRows.size, payloads.size)
                expectedRows.forEach { row ->
                    val native = payloads.single { it.getString("password_plain") == row.password.value }
                    assertEquals(row.username, native.getString("username"))
                    val fields = native.getJSONArray("custom_fields")
                    val meta = (0 until fields.length()).map { fields.getJSONObject(it) }.single { it.getString("title") == ProjectCredentialGroup.FIELD }
                    assertEquals(row.password.id, ProjectCredentialGroup.parse(meta.getString("value"))?.passwordId)
                }
            }
            if (target is StorageTarget.KeePass) {
                KeePassKdbxService.invalidateProcessCache(target.databaseId)
                val native = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security).loadWorkspace(target.databaseId).getOrThrow().passwords
                assertEquals(expectedRows.size, native.size)
                expectedRows.forEach { row ->
                    val found = native.single { it.password == row.password.value }
                    assertEquals(row.username, found.username)
                    assertEquals(row.password.id, ProjectCredentialGroup.parse(found.customFields.single { it.title == ProjectCredentialGroup.FIELD }.value)?.passwordId)
                }
            }
            val archive = this.model.prepareZipBackup(backupEncryptionPassword = "synthetic group backup",
                source = destination, preferences = BackupPreferences(includeImages = false)).getOrThrow().first
            try {
                val content = WebDavHelper(context).restoreFromBackupFile(archive, "synthetic group backup",
                    restoreMonicaConfig = false, importDataOnly = true).getOrThrow().content
                val restoredRows = content.passwords.filter { it.title == title }
                assertEquals(expectedRows.size, restoredRows.size)
                assertEquals(expectedRows.map { it.username to it.password.value }.toSet(), restoredRows.map { it.username to security.decryptDataIfMonicaCiphertext(it.password) }.toSet())
                assertEquals(1, restoredRows.map { it.passwordGroupId }.distinct().size)
                // Metadata must be in the portable backup, not only the local projection.
                expectedRows.forEach { row ->
                    assertTrue(content.customFieldsMap.values.flatten().any { it.title == ProjectCredentialGroup.FIELD && ProjectCredentialGroup.parse(it.value)?.passwordId == row.password.id })
                }
            } finally { archive.delete() }
        }
        save(groups)
        verifyNativeAndBackup(groups)
        val initial = current()
        assertEquals(4, initial.size)
        assertEquals(1, initial.map { it.passwordProjectKey() }.distinct().size)
        assertTrue(initial.all { it.password !in groups.flatMap { group -> group.passwords.map { password -> password.value } } })
        verifyAutofill(initial)
        var restored = restore()
        assertEquals(groups.map { it.username }, restored.map { it.username })
        assertEquals(groups.map { it.note }, restored.map { it.note })
        assertTrue(initial.all { it.notes == "Shared notes" })
        assertEquals(groups.flatMap { it.passwords.map { pwd -> pwd.value } }, restored.flatMap { it.passwords.map { pwd -> pwd.value } })
        assertEquals("", restored[1].otp)
        val workTwoId = restored[1].passwords[1].originalEntryId
        val changed = restored[1].copy(note = "Edited work note", passwords = restored[1].passwords.reversed().mapIndexed { index, password ->
            if (index == 0) password.copy(value = "edited work 2") else password
        })
        save(listOf(restored[0], changed), initial.map { it.id })
        restored = restore()
        assertEquals(workTwoId, restored[1].passwords[0].originalEntryId)
        assertEquals("Edited work note", restored[1].note)
        assertEquals("edited work 2", restored[1].passwords[0].value)
        assertEquals("  primary 1  ", restored[0].passwords[0].value)
        verifyNativeAndBackup(restored)
        save(listOf(restored[0], restored[1].copy(passwords = restored[1].passwords.take(1))), current().map { it.id })
        assertEquals(3, current().size)
        restored = restore()
        assertEquals("Edited work note", restored[1].note)
        save(listOf(restored[0]), current().map { it.id })
        assertEquals(2, current().size)
    }
}
