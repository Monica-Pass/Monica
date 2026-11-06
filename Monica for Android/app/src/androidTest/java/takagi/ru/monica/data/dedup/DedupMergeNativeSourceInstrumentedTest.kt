package takagi.ru.monica.data.dedup

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.credentialexchange.TransferFixture
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.KeePassKdbxService
import java.util.UUID

class DedupMergeNativeSourceInstrumentedTest {
    @Test fun nativeUiIdsReadTheirOwnFieldsAndAttachmentWithoutRoomMirrors() = runBlocking {
        nativeScenario(false)
    }

    @Test fun nativeHistoryIsRetainedAndDoesNotBlockUnrelatedCompatibleRecord() = runBlocking {
        nativeScenario(true)
    }

    @Test fun inheritedNativeAutoTypeBehaviorIsRetained() = runBlocking {
        nativeScenario(false, inheritedBehavior = true)
    }

    @Test fun disabledRootAndExplicitNestedGroupBehaviorAreRetained() = runBlocking {
        nativeScenario(false, rootAutoType = GroupOverride.Disabled)
        nativeScenario(false, rootSearching = GroupOverride.Disabled)
        nativeScenario(false, nestedAutoType = GroupOverride.Enabled)
    }

    private suspend fun nativeScenario(
        includeHistory: Boolean,
        inheritedBehavior: Boolean = false,
        rootAutoType: GroupOverride? = null,
        rootSearching: GroupOverride? = null,
        nestedAutoType: GroupOverride? = null,
    ) {
        val fixture = TransferFixture()
        val oldStore = AttachmentContainer.originalKeePassStore
        try { with(fixture) {
            val source = keepass()
            val bytes = byteArrayOf(1, 7, 9, 0, -1)
            val binary = BinaryData.Uncompressed(false, bytes)
            val entry = Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(
                "Title" to EntryValue.Plain("$prefix-native"), "UserName" to EntryValue.Plain("User"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("native-password")),
                "URL" to EntryValue.Plain("https://native.example.invalid"),
                "Recovery" to EntryValue.Encrypted(EncryptedValue.fromString("native-only-extra"))),
                binaries = listOf(BinaryReference(binary.hash, "native.bin")))
            val credential = Credentials.from(EncryptedValue.fromString("Synthetic transfer fixture password"))
            val original = keepassFile(source.databaseId).inputStream().use { KeePassDatabase.decode(it, credential) }
            assertEquals(GroupOverride.Enabled, original.content.group.enableAutoType)
            assertEquals(GroupOverride.Enabled, original.content.group.enableSearching)
            val sourceEntries = listOf(entry) + if (includeHistory) listOf(entry.copy(uuid = UUID.randomUUID(),
                fields = entry.fields + ("Title" to EntryValue.Plain("$prefix-history")), history = listOf(entry))) else emptyList()
            val updated = original.modifyParentGroup {
                copy(defaultAutoTypeSequence = if (inheritedBehavior) "{USERNAME}{TAB}{PASSWORD}{ENTER}" else defaultAutoTypeSequence,
                    enableAutoType = rootAutoType ?: enableAutoType,
                    enableSearching = rootSearching ?: enableSearching,
                    entries = if (nestedAutoType == null) sourceEntries else emptyList(),
                    groups = if (nestedAutoType == null) emptyList() else listOf(Group(UUID.randomUUID(), "Nested",
                        enableAutoType = nestedAutoType, entries = sourceEntries)))
            }.modifyBinaries { mapOf(binary.hash to binary) }
            keepassFile(source.databaseId).outputStream().use { updated.encode(it) }
            val fileBefore = keepassFile(source.databaseId).readBytes()
            val fileService = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
            val store = KeePassMonicaPasswordStore(fileService, security)
            passwords.monicaKeePassStore = store
            AttachmentContainer.bindOriginalKeePassStore(store)
            AttachmentContainer.registerKeePassService(fileService)
            val snapshot = store.refresh(source.databaseId)
            assertTrue(snapshot.rows.all { KeePassMonicaPasswordStore.isUiId(it.id) })
            assertTrue(db.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(source.databaseId).isEmpty())
            val merger = DedupMergeService(passwords, secureItems, passkeys, CustomFieldRepository(db.customFieldDao()),
                db.localKeePassDatabaseDao(), db.localMdbxDatabaseDao(), db.bitwardenVaultDao(), security,
                AppLocaleStringResolver(context), db, DedupAttachmentSupport(context, db))
            val planned = merger.buildPlan(setOf("keepass:${source.databaseId}"), DedupMergeTarget.MonicaLocal)
            if (inheritedBehavior || rootAutoType != null || rootSearching != null || nestedAutoType != null) {
                assertEquals(0, planned.writableItems)
                assertEquals(1, planned.unsupportedItems.size)
                assertArrayEquals(fileBefore, keepassFile(source.databaseId).readBytes())
                return@with
            }
            assertEquals(planned.unsupportedItems.toString(), 1, planned.writablePasswords)
            assertEquals(if (includeHistory) 1 else 0, planned.unsupportedItems.size)
            val row = planned.previewPasswords.single()
            assertEquals("native-only-extra", security.decryptDataIfMonicaCiphertext(row.customFields.single { it.title == "Recovery" }.value))
            assertEquals(1, row.attachments.size)
            val result = merger.executePlan(planned)
            assertEquals(result.failures.toString(), 1, result.insertedPasswords)
            val copy = db.passwordEntryDao().getAllPasswordEntriesSync().single { it.isLocalOnlyEntry() && it.title == "$prefix-native" }
            assertEquals("native-only-extra", security.decryptDataIfMonicaCiphertext(db.customFieldDao().getFieldsByEntryIdSync(copy.id).single { it.title == "Recovery" }.value))
            val facade = AttachmentContainer.facade(context)
            val asset = facade.list(AttachmentOwner.password(copy.id)).single()
            assertArrayEquals(bytes, facade.readAttachmentBytes(asset.id, 1024))
            assertArrayEquals(fileBefore, keepassFile(source.databaseId).readBytes())
        } } finally {
            oldStore?.let(AttachmentContainer::bindOriginalKeePassStore)
            fixture.close()
        }
    }
}
