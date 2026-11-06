package takagi.ru.monica.data.dedup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.*
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.executor.LocalAttachmentExecutor
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.credentialexchange.TransferFixture
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.*
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.*

/** Real encrypted Room/blob and native KDBX/MDBX paths. All values and vaults are synthetic. */
@RunWith(AndroidJUnit4::class)
class SingleVaultDedupExtendedContentInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var reader: LocalAttachmentExecutor
    private lateinit var local: SingleVaultDedupService
    private val batches = mutableSetOf<String>()
    private val blobs = mutableSetOf<String>()
    private var wasUnlocked = false

    @Before fun setup() {
        wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        reader = LocalAttachmentExecutor(context, AttachmentStorage(context), AttachmentKeyVault(security))
        local = SingleVaultDedupService(db) { listOf(SingleVaultDedupLocalAdapter(context, db, security, "Synthetic extended local")) }
    }

    @After fun cleanup() = runBlocking {
        local.invalidate()
        val assets = SingleVaultDedupLocalAssets(context)
        batches.forEach { id -> db.singleVaultDedupRecoveryDao().get(id)?.let { receipt ->
            val raw = Json.parseToJsonElement(security.decryptTimelineSnapshot(receipt.sealedPayload)).jsonObject
            assets.discardBackup(id, raw.getValue("blobs").jsonArray.map { it.jsonObject.let { value ->
                SingleVaultDedupLocalAssets.Backup(value.getValue("id").jsonPrimitive.long,
                    value.getValue("file").jsonPrimitive.content, value.getValue("hash").jsonPrimitive.content)
            } })
        } }
        blobs += db.singleVaultDedupLocalDao().attachments().mapNotNull { it.localPath }
        assets.discardPrepared(blobs)
        db.close()
        if (!wasUnlocked) SessionManager.markLocked()
    }

    @Test fun completeMultiAccountAbcWithEveryExtensionCleansOnlyBAndRestoresExactOriginals() = runBlocking {
        val a = addProject(1, listOf("1", "2", "3"), "ABC extended")
        val b = addProject(10, listOf("1", "2"), "ABC extended")
        val c = addProject(20, listOf("2", "3", "4"), "ABC extended")
        val originals = db.singleVaultDedupLocalDao().passwords()
        val fields = db.singleVaultDedupLocalDao().fields()
        val history = db.singleVaultDedupLocalDao().history()
        val plan = local.analyze("local", false)
        assertTrue(plan.projects.all { it.issues.isEmpty() })
        assertEquals(3, plan.projects.size)
        val candidate = plan.candidates.single()
        assertEquals(projectKey(b), candidate.candidateKey)
        assertEquals(projectKey(a), candidate.keeperKey)
        val outcome = local.clean(plan.planId, setOf(candidate.candidateKey))
        outcome.recoveryBatchId?.let(batches::add)
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, outcome.status)
        val after = db.singleVaultDedupLocalDao().passwords()
        assertEquals(b.toSet(), after.filter { it.isDeleted }.map { it.id }.toSet())
        assertEquals(originals.filter { it.id in a + c }, after.filter { it.id in a + c })
        assertEquals(fields, db.singleVaultDedupLocalDao().fields())
        assertEquals(history, db.singleVaultDedupLocalDao().history())
        val restored = local.restore(requireNotNull(outcome.recoveryBatchId))
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, restored.status)
        assertTrue(restored.isRestoration)
        assertEquals(originals, db.singleVaultDedupLocalDao().passwords())
        assertEquals(fields, db.singleVaultDedupLocalDao().fields())
        assertEquals(history, db.singleVaultDedupLocalDao().history())
        db.singleVaultDedupLocalDao().attachments().forEach { attachment ->
            assertArrayEquals(BLOB, reader.openDecrypted(attachment).use { it.readBytes() })
        }
        assertProjectContents(b)
    }

    @Test fun uniqueContentInEveryBlockWalletAndSupplementalFieldPreventsContainment() = runBlocking {
        val changes = PasswordContentBlocks.Kind.entries.map { "block:${it.name}" } +
            EmbeddedWalletContent.Kind.entries.map { "wallet:${it.name}" } + listOf("supplemental", "hidden", "duplicate-count")
        var next = 100L
        changes.forEach { change ->
            val a = base(next++, "Distinct $change")
            val b = base(next++, "Distinct $change")
            db.passwordEntryDao().insertPasswordEntry(a)
            db.passwordEntryDao().insertPasswordEntry(b)
            storeFields(a.id, richFields())
            storeFields(b.id, richFields(change))
        }
        val plan = local.analyze("local", false)
        assertEquals(changes.size * 2, plan.projects.size)
        assertTrue(plan.projects.all { it.issues.isEmpty() })
        // A changed hidden bit or repeated count can yield containment in one direction.
        // For count we replace one duplicate with a new unique value, so neither side covers the other.
        assertTrue(plan.candidates.isEmpty())
        assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
        assertTrue(db.singleVaultDedupRecoveryDao().pending().isEmpty())
    }

    @Test fun uniqueLegacyColumnsHistoryAndAttachmentBytesRemainSeparate() = runBlocking {
        val changes: List<Pair<String, (PasswordEntry) -> PasswordEntry>> = listOf(
            "notes" to { it.copy(notes = "unique notes") },
            "card" to { it.copy(creditCardCVV = security.encryptData("987")) },
            "ssh" to { it.copy(sshKeyData = security.encryptData(ssh("unique ssh comment"))) },
            "wifi" to { it.copy(wifiMetadata = security.encryptData(WifiData(ssid = "Unique SSID", hiddenNetwork = true).toJson())) },
            "sso" to { it.copy(loginType = "SSO", ssoProvider = "unique provider") },
            "username" to { it.copy(username = "another account") },
            "otp" to { it.copy(authenticatorKey = security.encryptData("unique OTP secret")) },
        )
        var next = 500L
        changes.forEach { (name, patch) ->
            val a = base(next++, "Legacy $name"); val b = patch(base(next++, "Legacy $name"))
            db.singleVaultDedupLocalDao().insertPasswords(listOf(a, b))
            storeFields(a.id, richFields()); storeFields(b.id, richFields())
        }
        val historyA = base(next++, "History values"); val historyB = base(next++, "History values")
        db.singleVaultDedupLocalDao().insertPasswords(listOf(historyA, historyB))
        listOf(historyA, historyB).forEach { row -> db.singleVaultDedupLocalDao().insertHistory(listOf(
            PasswordHistoryEntry(entryId = row.id, password = security.encryptData("old-${row.id}"), lastUsedAt = Date(1)))) }
        val attachmentA = base(next++, "Attachment bytes"); val attachmentB = base(next, "Attachment bytes")
        db.singleVaultDedupLocalDao().insertPasswords(listOf(attachmentA, attachmentB))
        attach(attachmentA.id, byteArrayOf(1, 2, 3)); attach(attachmentB.id, byteArrayOf(1, 2, 4))
        val plan = local.analyze("local", false)
        assertTrue(plan.projects.all { it.issues.isEmpty() })
        assertTrue(plan.candidates.isEmpty())
        assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
    }

    @Test fun bitwardenUnsyncedFullProjectCopiesCoherentAccountsAndEverySupportedExtension() = runBlocking {
        val ids = addProject(1, listOf("1", "2", "3"), "Synthetic BW draft", dependencies = false)
        db.bitwardenVaultDao().insert(BitwardenVault(id = 1, email = "extended@example.invalid", userId = "synthetic-user"))
        db.singleVaultDedupLocalDao().updatePasswords(db.singleVaultDedupLocalDao().passwords().map {
            it.copy(bitwardenVaultId = 1, bitwardenLocalModified = true)
        })
        val before = db.singleVaultDedupLocalDao().passwords()
        val fields = db.singleVaultDedupLocalDao().fields()
        val merger = DedupMergeService(PasswordRepository(db.passwordEntryDao()), SecureItemRepository(db.secureItemDao()),
            PasskeyRepository(db.passkeyDao()), CustomFieldRepository(db.customFieldDao()), db.localKeePassDatabaseDao(),
            db.localMdbxDatabaseDao(), db.bitwardenVaultDao(), security, AppLocaleStringResolver(context), db)
        val plan = merger.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal)
        assertTrue(plan.unsupportedItems.isEmpty())
        assertEquals(ids.size, plan.writablePasswords)
        val result = merger.executePlan(plan)
        assertEquals(result.failures.toString(), ids.size, result.insertedPasswords)
        assertEquals(before, db.singleVaultDedupLocalDao().passwords().filter { it.bitwardenVaultId == 1L })
        assertEquals(fields, db.singleVaultDedupLocalDao().fields().filter { it.entryId in ids })
        val copied = db.singleVaultDedupLocalDao().passwords().filter { it.isLocalOnlyEntry() }
        assertEquals(1, copied.map { it.passwordGroupId }.distinct().size)
        assertNotEquals(before.first().passwordGroupId, copied.first().passwordGroupId)
        assertProjectContents(copied.map { it.id })
        for (copy in copied) {
            val original = before.single { security.decryptData(it.password) == security.decryptData(copy.password) }
            assertEquals(security.decryptData(original.creditCardNumber), security.decryptData(copy.creditCardNumber))
            assertEquals(security.decryptData(original.sshKeyData), security.decryptData(copy.sshKeyData))
            assertEquals(security.decryptData(original.wifiMetadata), security.decryptData(copy.wifiMetadata))
            assertExtendedFieldsEqual(fields.filter { it.entryId == original.id }, db.customFieldDao().getFieldsByEntryIdSync(copy.id))
        }
    }

    @Test fun localProtocolExtensionInvalidatesMergeAndIsExplicitlyRetained() = runBlocking {
        val fixture = TransferFixture()
        var ownedAddress: String? = null
        try { with(fixture) {
            val target = mdbx()
            val id = passwords.insertPasswordEntry(PasswordEntry(title = "$prefix-protocol", website = "https://example.invalid",
                username = "synthetic", password = security.encryptData("synthetic password")))
            val merger = DedupMergeService(passwords, secureItems, passkeys, CustomFieldRepository(db.customFieldDao()),
                db.localKeePassDatabaseDao(), db.localMdbxDatabaseDao(), db.bitwardenVaultDao(), security, AppLocaleStringResolver(context), db)
            val destination = DedupMergeTarget.MdbxDatabase(target.databaseId, "$prefix-MDBX")
            val reviewed = merger.buildPlan(setOf("monica"), destination)
            assertTrue(reviewed.previewPasswords.any { it.entry.title == "$prefix-protocol" })
            db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS device_sync_content_extensions(local_id TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL)")
            ownedAddress = "local:$id"
            db.openHelper.writableDatabase.execSQL("INSERT INTO device_sync_content_extensions(local_id,payload) VALUES(?,?)",
                arrayOf(ownedAddress, security.encryptData("{\"extensions\":{\"future_nested_content\":\"preserve\"}}")))
            assertTrue(runCatching { merger.executePlan(reviewed) }.isFailure)
            assertTrue(mdbx.readStoredEntries(target.databaseId).isEmpty())
            val fresh = merger.buildPlan(setOf("monica"), destination)
            assertEquals(0, fresh.writableItems)
            assertTrue(fresh.unsupportedItems.any { it.title == "$prefix-protocol" })
            assertEquals(0, merger.executePlan(fresh).insertedItems)
            assertFalse(db.passwordEntryDao().getPasswordEntryById(id)!!.isDeleted)
            assertTrue(mdbx.readStoredEntries(target.databaseId).isEmpty())
        } } finally {
            ownedAddress?.let { fixture.db.openHelper.writableDatabase.execSQL("DELETE FROM device_sync_content_extensions WHERE local_id=?", arrayOf(it)) }
            fixture.close()
        }
    }

    @Test fun standaloneWalletItemsKeepNestedVariantsAndRestoreExactPayloads() = runBlocking {
        val drafts = richFields()
        val expectedCandidates = mutableSetOf<String>()
        var id = 1000L
        for (kind in EmbeddedWalletContent.Kind.entries) {
            val raw = drafts.single { it.title == EmbeddedWalletContent.fieldName(kind) }.value
            val item = (EmbeddedWalletContent.read(raw) as EmbeddedWalletContent.ReadResult.Available).snapshot.displayItem()
            val first = item.copy(id = id++, title = "Standalone ${kind.name}", itemData = security.encryptData(item.itemData))
            val duplicate = first.copy(id = id++)
            expectedCandidates += "secure:${duplicate.id}"
            val variantData = JsonObject(Json.parseToJsonElement(item.itemData).jsonObject + ("customFields" to buildJsonArray {
                add(buildJsonObject { put("label", "Nested"); put("value", "standalone unique value"); put("type", "HIDDEN") })
            }))
            val variant = first.copy(id = id++, itemData = security.encryptData(variantData.toString()))
            db.singleVaultDedupLocalDao().insertItems(listOf(first, duplicate, variant))
        }
        val original = db.singleVaultDedupLocalDao().items()
        val plan = local.analyze("local", false)
        assertTrue(plan.projects.all { it.issues.isEmpty() })
        assertEquals(expectedCandidates, plan.candidates.map { it.candidateKey }.toSet())
        val outcome = local.clean(plan.planId, expectedCandidates)
        outcome.recoveryBatchId?.let(batches::add)
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, outcome.status)
        assertEquals(expectedCandidates, db.singleVaultDedupLocalDao().items().filter { it.isDeleted }.map { "secure:${it.id}" }.toSet())
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, local.restore(requireNotNull(outcome.recoveryBatchId)).status)
        assertEquals(original, db.singleVaultDedupLocalDao().items())
    }

    @Test fun realKdbxExtendedFieldsAndAttachmentsCopyToMonicaAndUnsupportedHistoryStaysListed() = runBlocking {
        nativeCopy(toMdbx = false)
    }

    @Test fun realKdbxCompleteExtensionsStayAtSourceWhenMdbxCannotPreserveIndependentMetadata() = runBlocking {
        nativeCopy(toMdbx = true)
    }

    @Test fun realKdbxPortableFieldsAndAttachmentsCopyToMdbxAndSurviveNativeReopen() = runBlocking {
        nativeCopy(toMdbx = true, portableFields = true)
    }

    private suspend fun nativeCopy(toMdbx: Boolean, portableFields: Boolean = false) {
        val fixture = TransferFixture()
        val oldStore = AttachmentContainer.originalKeePassStore
        try { with(fixture) {
            val source = keepass()
            val target = if (toMdbx) mdbx() else null
            // Keep the complete fixture for Monica and the MDBX retention case. The
            // independent positive control excludes only metadata requiring new IDs.
            val drafts = if (portableFields) richFields().filterNot {
                PasswordContentBlocks.owns(it.title) || EmbeddedWalletContent.isMetadata(it.title)
            } + CustomFieldDraft(title = "Visible", value = "portable ordinary value") else richFields()
            val nativeFields = linkedMapOf<String, EntryValue>(
                "Title" to EntryValue.Plain("$prefix-extended"), "UserName" to EntryValue.Plain("primary@example.invalid"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("native secret")),
                "URL" to EntryValue.Plain("https://example.invalid/extended"), "Notes" to EntryValue.Plain("native notes\n精确保留"),
                "Card Number" to EntryValue.Encrypted(EncryptedValue.fromString("4111111111111111")),
                "Card CVV" to EntryValue.Encrypted(EncryptedValue.fromString("123")),
            )
            // KDBX uses unique string keys; ordinary duplicate fields are covered by Room/BW tests.
            drafts.filterNot { it.title == "Duplicate" || it.title.startsWith("Control") }.forEach {
                nativeFields[it.title] = if (it.isProtected) EntryValue.Encrypted(EncryptedValue.fromString(it.value)) else EntryValue.Plain(it.value)
            }
            val binary = BinaryData.Uncompressed(false, BLOB)
            val entry = Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(*nativeFields.map { it.key to it.value }.toTypedArray()),
                binaries = listOf(BinaryReference(binary.hash, "extended.bin")))
            val incompatible = entry.copy(uuid = UUID.randomUUID(), fields = entry.fields + ("Title" to EntryValue.Plain("$prefix-retained-history")), history = listOf(entry))
            val credentials = Credentials.from(EncryptedValue.fromString("Synthetic transfer fixture password"))
            val original = keepassFile(source.databaseId).inputStream().use { KeePassDatabase.decode(it, credentials) }
            val prepared = original.modifyParentGroup { copy(entries = listOf(entry, incompatible)) }.modifyBinaries { mapOf(binary.hash to binary) }
            keepassFile(source.databaseId).outputStream().use { prepared.encode(it) }
            val before = keepassFile(source.databaseId).readBytes()
            val fileService = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
            val store = KeePassMonicaPasswordStore(fileService, security)
            passwords.monicaKeePassStore = store
            AttachmentContainer.bindOriginalKeePassStore(store); AttachmentContainer.registerKeePassService(fileService)
            val snapshot = store.refresh(source.databaseId)
            assertTrue(db.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(source.databaseId).isEmpty())
            val expected = snapshot.rows.single { it.title == "$prefix-extended" }
            val originalCustom = snapshot.customFields.getValue(expected.id)
            val sourceDrafts = originalCustom.map { CustomFieldDraft(title = it.title, value = it.value, isProtected = it.isProtected) }
            assertEquals(if (portableFields) emptySet<PasswordContentBlocks.Kind>() else PasswordContentBlocks.Kind.entries.toSet(),
                PasswordContentBlocks.read(sourceDrafts).map { requireNotNull(it.block).kind }.toSet())
            assertEquals(if (portableFields) 0 else EmbeddedWalletContent.Kind.entries.size,
                originalCustom.count { EmbeddedWalletContent.isMetadata(it.title) })
            if (portableFields) {
                assertEquals("portable ordinary value", originalCustom.single { it.title == "Visible" && !it.isProtected }.value)
                assertEquals("hidden secret", originalCustom.single { it.title == "Hidden" && it.isProtected }.value)
                listOf("payment", "contact", "address").forEach { section ->
                    assertTrue(originalCustom.any { it.title.startsWith("monica.content.$section.") && it.isProtected })
                }
                assertTrue(originalCustom.none { security.looksLikeMonicaCiphertext(it.value) })
            }
            val merger = DedupMergeService(passwords, secureItems, passkeys, CustomFieldRepository(db.customFieldDao()),
                db.localKeePassDatabaseDao(), db.localMdbxDatabaseDao(), db.bitwardenVaultDao(), security,
                AppLocaleStringResolver(context), db, DedupAttachmentSupport(context, db))
            val destination = target?.let { DedupMergeTarget.MdbxDatabase(it.databaseId, "$prefix-MDBX") } ?: DedupMergeTarget.MonicaLocal
            val retainedTarget = if (toMdbx && !portableFields) requireNotNull(target) else null
            val targetFile = retainedTarget?.let {
                assertTrue(mdbx.readStoredEntries(it.databaseId).isEmpty())
                assertTrue(mdbx.readStoredAttachments(it.databaseId).isEmpty())
                java.io.File(requireNotNull(db.localMdbxDatabaseDao().getDatabaseById(it.databaseId)).resolvedActiveFilePath())
            }
            val targetBytesBefore = targetFile?.readBytes()
            val roomPasswordsBefore = db.singleVaultDedupLocalDao().passwords()
            val roomItemsBefore = db.singleVaultDedupLocalDao().items()
            val roomFieldsBefore = db.singleVaultDedupLocalDao().fields()
            val roomAttachmentsBefore = db.singleVaultDedupLocalDao().attachments()
            val roomPasskeysBefore = passkeys.getAllPasskeysSync()
            val plan = merger.buildPlan(setOf("keepass:${source.databaseId}"), destination)
            assertTrue(plan.passkeyRetainedProjects.isEmpty())
            assertTrue(plan.previewPasskeys.isEmpty())
            if (retainedTarget != null) {
                assertEquals(listOf("$prefix-extended", "$prefix-retained-history"), plan.unsupportedItems.map { it.title })
                assertEquals(0, plan.writableItems)
                assertTrue(plan.previewPasswords.isEmpty())
                assertTrue(plan.previewSecureItems.isEmpty())
                assertNativeSourceUnchanged(keepassFile(source.databaseId), credentials, before)
                assertArrayEquals(requireNotNull(targetBytesBefore), requireNotNull(targetFile).readBytes())
                val result = merger.executePlan(plan)
                assertEquals(0, result.insertedItems)
                assertTrue(result.failures.isEmpty())
                assertNativeSourceUnchanged(keepassFile(source.databaseId), credentials, before)
                Mdbx2NativeReadSessions.clear()
                assertTrue(mdbx.readStoredEntries(retainedTarget.databaseId).isEmpty())
                assertTrue(mdbx.readStoredAttachments(retainedTarget.databaseId).isEmpty())
                assertArrayEquals(targetBytesBefore, targetFile.readBytes())
                assertEquals(roomPasswordsBefore, db.singleVaultDedupLocalDao().passwords())
                assertEquals(roomItemsBefore, db.singleVaultDedupLocalDao().items())
                assertEquals(roomFieldsBefore, db.singleVaultDedupLocalDao().fields())
                assertEquals(roomAttachmentsBefore, db.singleVaultDedupLocalDao().attachments())
                assertEquals(roomPasskeysBefore, passkeys.getAllPasskeysSync())
                assertEquals(snapshot.customFields, store.refresh(source.databaseId).customFields)
                return@with
            }
            assertEquals(listOf("$prefix-retained-history"), plan.unsupportedItems.map { it.title })
            assertEquals(1, plan.writablePasswords)
            val result = merger.executePlan(plan)
            assertEquals(result.failures.toString(), 1, result.insertedPasswords)
            val copy = db.passwordEntryDao().getAllPasswordEntriesSync().single { it.title == "$prefix-extended" && it.keepassDatabaseId == null }
            assertEquals("native secret", security.decryptDataIfMonicaCiphertext(copy.password))
            assertEquals("4111111111111111", security.decryptDataIfMonicaCiphertext(copy.creditCardNumber))
            assertEquals("123", security.decryptDataIfMonicaCiphertext(copy.creditCardCVV))
            assertEquals("native notes\n精确保留", copy.notes)
            assertExtendedFieldsEqual(originalCustom, db.customFieldDao().getFieldsByEntryIdSync(copy.id))
            val facade = AttachmentContainer.facade(context)
            val copiedAsset = facade.list(AttachmentOwner.password(copy.id)).single()
            assertArrayEquals(BLOB, facade.readAttachmentBytes(copiedAsset.id, BLOB.size + 1))
            assertArrayEquals(before, keepassFile(source.databaseId).readBytes())
            assertNativeSourceUnchanged(keepassFile(source.databaseId), credentials, before)
            if (target != null) {
                Mdbx2NativeReadSessions.clear()
                val nativeRows = mdbx.readStoredEntries(target.databaseId).filter { !it.deleted }
                assertEquals(1, nativeRows.size)
                val payload = Json.parseToJsonElement(nativeRows.single().payloadJson).jsonObject
                assertEquals("native secret", payload.getValue("password_plain").jsonPrimitive.content)
                assertEquals("native notes\n精确保留", payload.getValue("notes").jsonPrimitive.content)
                assertEquals("4111111111111111", payload.getValue("credit_card_number_plain").jsonPrimitive.content)
                assertEquals("123", payload.getValue("credit_card_cvv_plain").jsonPrimitive.content)
                val storedFields = payload.getValue("custom_fields").jsonArray.mapIndexed { index, value -> value.jsonObject.let {
                    CustomField(id = index + 1L, entryId = copy.id, title = it.getValue("title").jsonPrimitive.content,
                        value = it.getValue("value").jsonPrimitive.content, isProtected = it.getValue("is_protected").jsonPrimitive.boolean,
                        sortOrder = it.getValue("sort_order").jsonPrimitive.int)
                } }
                // Inspect cold native values directly: this assertion must not decrypt
                // field values using Monica's source key to make the copy look portable.
                assertEquals(originalCustom.sortedBy { it.sortOrder }.map { Triple(it.title, it.value, it.isProtected) },
                    storedFields.sortedBy { it.sortOrder }.map { Triple(it.title, it.value, it.isProtected) })
                assertTrue(storedFields.none { security.looksLikeMonicaCiphertext(it.value) })
                assertExtendedFieldsEqual(originalCustom, storedFields)
                val persistedAsset = mdbx.readStoredAttachments(target.databaseId).single()
                assertEquals("extended.bin", persistedAsset.fileName)
                assertEquals(BLOB.size.toLong(), persistedAsset.originalSize)
                val storage = AttachmentStorage(context)
                val temporary = "${UUID.randomUUID()}.enc"
                blobs += temporary
                storage.absolutePathOf(temporary).writeBytes(persistedAsset.blob)
                val wrapped = MdbxAttachmentCekPayload.toLocalWrappedCek(requireNotNull(persistedAsset.wrappedCek), security::encryptData)
                val cek = AttachmentKeyVault(security).unwrap(wrapped)
                try { assertArrayEquals(BLOB, storage.openDecryptedStream(temporary, cek).use { it.readBytes() }) }
                finally { cek.fill(0) }
            }
        } } finally {
            oldStore?.let(AttachmentContainer::bindOriginalKeePassStore)
            fixture.close()
        }
    }

    private fun assertNativeSourceUnchanged(file: java.io.File, credentials: Credentials, before: ByteArray) {
        assertArrayEquals(before, file.readBytes())
        val original = before.inputStream().use { KeePassDatabase.decode(it, credentials) }
        val reopened = file.inputStream().use { KeePassDatabase.decode(it, credentials) }
        fun fields(entry: Entry) = entry.fields.entries.associate { (name, value) ->
            name to (value.content to (value is EntryValue.Encrypted))
        }
        fun compareEntry(expected: Entry, actual: Entry) {
            assertEquals(expected.uuid, actual.uuid)
            assertEquals(fields(expected), fields(actual))
            assertEquals(expected.binaries, actual.binaries)
            assertEquals(expected.history.size, actual.history.size)
            expected.history.zip(actual.history).forEach { (old, current) -> compareEntry(old, current) }
        }
        val expectedEntries = original.content.group.entries
        val actualEntries = reopened.content.group.entries
        assertEquals(2, expectedEntries.size)
        assertEquals(expectedEntries.map { it.uuid }, actualEntries.map { it.uuid })
        assertEquals(listOf(0, 1), actualEntries.map { it.history.size })
        expectedEntries.zip(actualEntries).forEach { (old, current) -> compareEntry(old, current) }
        assertEquals(original.binaries.keys, reopened.binaries.keys)
        assertEquals(1, reopened.binaries.size)
        original.binaries.forEach { (hash, binary) ->
            val current = reopened.binaries.getValue(hash)
            assertEquals(binary.memoryProtection, current.memoryProtection)
            assertArrayEquals(binary.getContent(), current.getContent())
            assertArrayEquals(BLOB, current.getContent())
        }
    }

    private fun base(id: Long, title: String) = PasswordEntry(id = id, title = title, website = "https://example.invalid/extended",
        username = "primary@example.invalid", password = security.encryptData("shared-password"), notes = "Notes\n中文 exact",
        createdAt = Date(1_700_000_000_123), updatedAt = Date(1_700_000_000_456),
        email = "mail@example.invalid", phone = "+10000000000", appPackageName = "invalid.synthetic", appName = "Synthetic",
        addressLine = "1 Synthetic Lane", city = "Example", state = "State", zipCode = "00000", country = "XX",
        creditCardNumber = security.encryptData("4111111111111111"), creditCardHolder = "SYNTHETIC HOLDER",
        creditCardExpiry = "12/2035", creditCardCVV = security.encryptData("123"),
        authenticatorKey = security.encryptData("otpauth://totp/Synthetic?secret=JBSWY3DPEHPK3PXP"),
        sshKeyData = security.encryptData(ssh()), wifiMetadata = security.encryptData(WifiData(ssid = "Synthetic WiFi", hiddenNetwork = true).toJson()))

    private fun ssh(comment: String = "synthetic ssh") = Json.encodeToString(SshKeyData.serializer(),
        SshKeyData(algorithm = "ED25519", keySize = 256, publicKeyOpenSsh = "ssh-ed25519 synthetic-public-key",
            privateKeyOpenSsh = "-----SYNTHETIC PRIVATE KEY-----", fingerprintSha256 = "SHA256:synthetic", comment = comment))

    private suspend fun addProject(first: Long, values: List<String>, title: String, dependencies: Boolean = true): List<Long> {
        val project = UUID.randomUUID().toString()
        val groups = listOf(ProjectCredentialGroup.Group(label = "Primary", username = "primary@example.invalid", note = "Primary note",
            passwords = values.map { ProjectCredentialGroup.Password(value = it) }),
            ProjectCredentialGroup.Group(label = "Secondary", username = "secondary@example.invalid", note = "Secondary note",
                passwords = listOf(ProjectCredentialGroup.Password(value = "secondary-password"))))
        return ProjectCredentialGroup.rows(groups).mapIndexed { index, row ->
            val id = first + index
            db.passwordEntryDao().insertPasswordEntry(base(id, title).copy(username = row.username,
                password = security.encryptData(row.password.value), passwordGroupId = project))
            val fields = ProjectCredentialGroup.put(richFields(), row.metadata.forProject(project))
            storeFields(id, EntryContentFields.withOrder(fields, groups.map { ProjectCredentialGroup.token(it.id) } +
                PasswordContentBlocks.read(fields).map { it.token } + listOf("NOTES", "AUTHENTICATOR", "PAYMENT", "CONTACT", "ADDRESS", "CUSTOM_FIELDS", "ATTACHMENTS")))
            if (dependencies) {
                db.singleVaultDedupLocalDao().insertHistory(listOf(PasswordHistoryEntry(entryId = id, password = security.encryptData("older-password"), lastUsedAt = Date(123))))
                attach(id, BLOB)
            }
            id
        }
    }

    private suspend fun projectKey(ids: List<Long>): String = "project:" + db.passwordEntryDao().getPasswordEntryById(ids.first())!!.passwordGroupId

    private suspend fun storeFields(id: Long, fields: List<CustomFieldDraft>) {
        db.customFieldDao().insertAll(fields.mapIndexed { index, field -> CustomField(entryId = id, title = field.title,
            value = security.encryptData(field.value), isProtected = field.isProtected, sortOrder = index) })
    }

    private suspend fun attach(id: Long, bytes: ByteArray) {
        val row = reader.writeFromBytes(AttachmentOwner.password(id), "proof.bin", "application/octet-stream", bytes)
        blobs += requireNotNull(row.localPath)
        db.attachmentDao().insert(row)
    }

    private fun richFields(change: String? = null): List<CustomFieldDraft> {
        var result = listOf(CustomFieldDraft(title = "Duplicate", value = "same"),
            CustomFieldDraft(title = "Duplicate", value = if (change == "duplicate-count") "unique duplicate" else "same"),
            CustomFieldDraft(title = "Hidden", value = "hidden secret", isProtected = change != "hidden"),
            CustomFieldDraft(title = "Control\u001fName", value = "value\u001e\u001f\n中文"))
        for (kind in PasswordContentBlocks.Kind.entries) {
            val data = PasswordContentBlocks.editableKeys(kind).associateWith { key -> "$kind $key synthetic" }.toMutableMap()
            if (change == "block:${kind.name}") data[PasswordContentBlocks.editableKeys(kind).first()] = "UNIQUE BLOCK CONTENT"
            result = PasswordContentBlocks.put(result, PasswordContentBlocks.create(kind).edited("Synthetic ${kind.name}", data))
        }
        for (kind in EmbeddedWalletContent.Kind.entries) {
            val extra = if (change == "wallet:${kind.name}") "unique nested value" else "nested secret"
            val custom = buildJsonArray { repeat(2) { add(buildJsonObject { put("label", "Nested"); put("value", extra); put("type", "HIDDEN") }) } }
            val data = when (kind) {
                EmbeddedWalletContent.Kind.NOTE -> buildJsonObject { put("content", "Embedded note"); put("tags", buildJsonArray { add("tag"); add("tag") }); put("isMarkdown", true); put("customFields", custom) }
                EmbeddedWalletContent.Kind.BANK_CARD -> buildJsonObject { put("cardNumber", "4111111111111111"); put("cardholderName", "SYNTHETIC"); put("expiryMonth", "12"); put("expiryYear", "2035"); put("cvv", "123"); put("bankBranch", "Synthetic branch"); put("iban", "SYNTHETIC-IBAN"); put("customFields", custom) }
                EmbeddedWalletContent.Kind.DOCUMENT -> buildJsonObject { put("documentType", "PASSPORT"); put("documentNumber", "SYNTHETIC-ID"); put("fullName", "Synthetic Person"); put("workEmail", "work@example.invalid"); put("walletFields", buildJsonObject { put("custom", "preserved") }); put("customFields", custom) }
                EmbeddedWalletContent.Kind.ADDRESS -> buildJsonObject { put("fullName", "Synthetic Person"); put("streetAddress", "1 Synthetic Lane"); put("district", "District"); put("homeEmail", "home@example.invalid"); put("customFields", custom) }
            }
            result = EmbeddedWalletContent.put(result, EmbeddedWalletContent.create(SecureItem(itemType = kind.itemType,
                title = "Synthetic ${kind.name}", notes = "Wallet notes", itemData = data.toString())))
        }
        val supplemental = mapOf("payment" to "bankName bankBranch cardType brand nickname validFromMonth validFromYear pin iban swiftBic routingNumber accountNumber branchCode currency customerServicePhone billingAddress",
            "contact" to "fullName title firstName middleName lastName company documentType documentNumber issuedDate expiryDate issuedBy nationality ssn passportNumber licenseNumber additionalInfo",
            "address" to "fullName company apartment address3 phone email")
        supplemental.forEach { (section, names) -> names.split(' ').forEach { name -> result = result + CustomFieldDraft(
            title = "monica.content.$section.$name", value = if (change == "supplemental" && name == "iban") "UNIQUE IBAN" else "$section $name value", isProtected = true) } }
        return result
    }

    private suspend fun assertProjectContents(ids: List<Long>) {
        val rows = db.singleVaultDedupLocalDao().passwords().filter { it.id in ids }.map { it.copy(password = security.decryptData(it.password), authenticatorKey = security.decryptData(it.authenticatorKey)) }
        val fields = ids.associateWith { id -> db.customFieldDao().getFieldsByEntryIdSync(id).map { CustomFieldDraft(title = it.title, value = security.decryptDataIfMonicaCiphertext(it.value), isProtected = it.isProtected) } }
        val groups = ProjectCredentialGroup.restore(rows, fields)
        assertEquals(listOf("primary@example.invalid", "secondary@example.invalid"), groups.map { it.username })
        assertEquals("secondary-password", groups.last().passwords.single().value)
        fields.values.forEach { row ->
            assertEquals(PasswordContentBlocks.Kind.entries.toSet(), PasswordContentBlocks.read(row).map { requireNotNull(it.block).kind }.toSet())
            assertEquals(4, row.count { EmbeddedWalletContent.isMetadata(it.title) })
            assertEquals(2, row.count { it.title == "Duplicate" && it.value == "same" })
            assertTrue(row.single { it.title == "Hidden" }.isProtected)
            assertEquals("payment iban value", row.single { it.title == "monica.content.payment.iban" }.value)
        }
    }

    private fun assertExtendedFieldsEqual(original: List<CustomField>, copied: List<CustomField>) {
        fun plain(fields: List<CustomField>) = fields.sortedWith(compareBy<CustomField> { it.sortOrder }.thenBy { it.id }).map {
            CustomFieldDraft(title = it.title, value = security.decryptDataIfMonicaCiphertext(it.value), isProtected = it.isProtected)
        }
        val before = plain(original); val after = plain(copied)
        fun ordinary(fields: List<CustomFieldDraft>) = fields.filterNot { it.title == ProjectCredentialGroup.FIELD || it.title == EntryContentFields.ORDER || PasswordContentBlocks.owns(it.title) || EmbeddedWalletContent.isMetadata(it.title) }.map { Triple(it.title, it.value, it.isProtected) }
        assertEquals(ordinary(before), ordinary(after))
        fun blocks(fields: List<CustomFieldDraft>) = PasswordContentBlocks.read(fields).map { JsonObject(requireNotNull(it.block).raw - "id") }
        assertEquals(blocks(before), blocks(after))
        fun wallets(fields: List<CustomFieldDraft>) = fields.filter { EmbeddedWalletContent.isMetadata(it.title) }.map { JsonObject(Json.parseToJsonElement(it.value).jsonObject - "id") }
        assertEquals(wallets(before), wallets(after))
        assertEquals(before.count { it.isProtected }, after.count { it.isProtected })
    }

    companion object { private val BLOB = ByteArray(4099) { (it % 251).toByte() } }
}
