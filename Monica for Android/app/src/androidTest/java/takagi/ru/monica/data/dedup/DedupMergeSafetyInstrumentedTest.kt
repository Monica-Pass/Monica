package takagi.ru.monica.data.dedup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.BitwardenVaultIdentity
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.AppLocaleStringResolver
import java.util.Date
import java.util.UUID

/** In-memory Room and unsynced synthetic drafts: no network or user database operations. */
@RunWith(AndroidJUnit4::class)
class DedupMergeSafetyInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var service: DedupMergeService
    private var wasUnlocked = false

    @Before fun setup() = runBlocking {
        wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        db.bitwardenVaultDao().insert(BitwardenVault(id = 1, email = "dedup@example.invalid", canonicalEmail = "dedup@example.invalid", userId = "synthetic-user"))
        service = DedupMergeService(PasswordRepository(db.passwordEntryDao()), SecureItemRepository(db.secureItemDao()),
            PasskeyRepository(db.passkeyDao()), CustomFieldRepository(db.customFieldDao()), db.localKeePassDatabaseDao(),
            db.localMdbxDatabaseDao(), db.bitwardenVaultDao(), security, AppLocaleStringResolver(context), db)
    }

    @After fun cleanup() { db.close(); if (!wasUnlocked) SessionManager.markLocked() }
    private fun draft(id: Long, password: String = "synthetic-password") = PasswordEntry(id = id, title = "Example", website = "https://example.invalid",
        username = "User", password = password, createdAt = Date(1), updatedAt = Date(1), bitwardenVaultId = 1, bitwardenLocalModified = true)
    private suspend fun plan() = service.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal)
    private suspend fun local() = db.passwordEntryDao().getActiveEntries().first().filter { it.isLocalOnlyEntry() }

    @Test fun differentPasswordsAndAdditionalFieldsRemainSeparateCompleteCopies() = runBlocking {
        db.passwordEntryDao().insertPasswordEntry(draft(1, "first"))
        db.passwordEntryDao().insertPasswordEntry(draft(2, "second").copy(email = "extra@example.invalid"))
        db.customFieldDao().insertAll(listOf(CustomField(entryId = 1, title = "Recovery", value = "one"),
            CustomField(entryId = 2, title = "Recovery", value = "two"), CustomField(entryId = 2, title = "Recovery", value = "two", sortOrder = 1)))
        val before = db.passwordEntryDao().getAllPasswordEntriesSync()
        val planned = plan()
        assertEquals(2, planned.writablePasswords)
        assertEquals(2, service.executePlan(planned).insertedPasswords)
        assertEquals(setOf("first", "second"), local().map { it.password }.toSet())
        val second = local().single { it.password == "second" }
        assertEquals("extra@example.invalid", second.email)
        assertEquals(2, db.customFieldDao().getFieldsByEntryIdSync(second.id).size)
        assertEquals(before, db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.bitwardenVaultId == 1L })
    }

    @Test fun equalDecryptedVariantsCollapseAndConsumedReviewCannotReplay() = runBlocking {
        db.passwordEntryDao().insertPasswordEntry(draft(1, security.encryptData("same")))
        db.passwordEntryDao().insertPasswordEntry(draft(2, security.encryptData("same")))
        val planned = plan()
        assertEquals(1, planned.writableItems)
        assertEquals(1, service.executePlan(planned).insertedItems)
        assertTrue(runCatching { service.executePlan(planned) }.isFailure)
        assertEquals(1, local().size)
        assertEquals(0, plan().writableItems)
    }

    @Test fun sourceChangeRequiresNewReviewAndWritesNothing() = runBlocking {
        db.passwordEntryDao().insertPasswordEntry(draft(1))
        val planned = plan()
        db.passwordEntryDao().updatePasswordEntry(draft(1, "changed"))
        assertTrue(runCatching { service.executePlan(planned) }.isFailure)
        assertTrue(local().isEmpty())
    }

    @Test fun newTargetContentRequiresNewReviewAndWritesNothing() = runBlocking {
        db.passwordEntryDao().insertPasswordEntry(draft(1))
        val planned = plan()
        db.passwordEntryDao().insertPasswordEntry(draft(2).copy(bitwardenVaultId = null, bitwardenLocalModified = false))
        assertTrue(runCatching { service.executePlan(planned) }.isFailure)
        assertEquals(1, local().size)
    }

    @Test fun relationshipIdOnlyChangeWithoutTimestampRequiresFreshReview() = runBlocking {
        val project = UUID.randomUUID().toString(); val group = UUID.randomUUID().toString()
        db.passwordEntryDao().insertPasswordEntry(draft(1).copy(passwordGroupId = project))
        val metadata = buildJsonObject {
            put("version", 1); put("projectId", project); put("groupId", group); put("passwordId", UUID.randomUUID().toString())
            put("label", ""); put("primary", true); put("groupOrder", 0); put("passwordOrder", 0)
        }
        db.customFieldDao().insertAll(listOf(CustomField(entryId = 1, title = ProjectCredentialGroup.FIELD, value = metadata.toString())))
        val planned = plan()
        assertEquals(1, planned.writableItems)
        val field = db.customFieldDao().getFieldsByEntryIdSync(1).single()
        db.customFieldDao().insertAll(listOf(field.copy(value = JsonObject(metadata + ("passwordId" to JsonPrimitive(UUID.randomUUID().toString()))).toString())))
        assertTrue(runCatching { service.executePlan(planned) }.isFailure)
        assertTrue(local().isEmpty())
    }

    @Test fun conflictingProjectOwnershipRetainsBothConnectedGroups() = runBlocking {
        val first = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString()
        for (id in 1L..2L) {
            db.passwordEntryDao().insertPasswordEntry(draft(id).copy(passwordGroupId = if (id == 1L) first else second))
            val metadata = buildJsonObject {
                put("version", 1); put("projectId", second); put("groupId", UUID.randomUUID().toString()); put("passwordId", UUID.randomUUID().toString())
                put("label", ""); put("primary", true); put("groupOrder", 0); put("passwordOrder", 0)
            }
            db.customFieldDao().insertAll(listOf(CustomField(entryId = id, title = ProjectCredentialGroup.FIELD, value = metadata.toString())))
        }
        val planned = plan()
        assertEquals(0, planned.writableItems)
        assertEquals(2, planned.unsupportedItems.size)
    }

    @Test fun lockGenerationRevokesReviewedCopy() = runBlocking {
        db.passwordEntryDao().insertPasswordEntry(draft(1))
        val planned = plan()
        SessionManager.markLocked()
        SessionManager.markUnlocked()
        assertTrue(runCatching { service.executePlan(planned) }.isFailure)
        assertTrue(local().isEmpty())
    }

    @Test fun explicitTwoPasswordProjectHasNewCoherentIdsAndReopens() = runBlocking {
        val project = UUID.randomUUID().toString(); val group = UUID.randomUUID().toString()
        for (id in 1L..2L) {
            db.passwordEntryDao().insertPasswordEntry(draft(id, "password-$id").copy(passwordGroupId = project))
            val metadata = buildJsonObject {
                put("version", 1); put("projectId", project); put("groupId", group); put("passwordId", UUID.randomUUID().toString())
                put("label", ""); put("primary", true); put("groupOrder", 0); put("passwordOrder", (id - 1).toInt())
            }
            db.customFieldDao().insertAll(listOf(CustomField(entryId = id, title = ProjectCredentialGroup.FIELD, value = metadata.toString()),
                CustomField(entryId = id, title = EntryContentFields.ORDER, value = "CREDENTIAL:$group", sortOrder = 1)))
        }
        val planned = plan()
        assertEquals(2, planned.writablePasswords)
        assertEquals(1, planned.previewPasswords.map { it.projectCopyKey }.distinct().size)
        val result = service.executePlan(planned)
        assertEquals(result.failures.toString(), 2, result.insertedPasswords)
        val copies = local()
        assertEquals(1, copies.map { it.passwordGroupId }.distinct().size)
        assertNotEquals(project, copies.first().passwordGroupId)
        val fields = copies.associate { it.id to DedupMergeContent.drafts(db.customFieldDao().getFieldsByEntryIdSync(it.id), security::decryptDataIfMonicaCiphertext) }
        val restored = ProjectCredentialGroup.restore(copies, fields)
        assertEquals(setOf("password-1", "password-2"), restored.single().passwords.map { it.value }.toSet())
        assertNotEquals(group, restored.single().id)
    }

    @Test fun bindingOneMemberRetainsWholePreviouslyCopyableProjectWhileIndependentControlCopies() = runBlocking {
        // Same supported path as explicitTwoPasswordProjectHasNewCoherentIdsAndReopens:
        // unsynced Bitwarden drafts -> Monica local, with no native/MDBX incompatibility gate.
        val project = UUID.randomUUID().toString()
        val group = UUID.randomUUID().toString()
        for (id in 1L..2L) {
            db.passwordEntryDao().insertPasswordEntry(draft(id, "password-$id").copy(
                title = "Synthetic complete project", passwordGroupId = project))
            val metadata = buildJsonObject {
                put("version", 1); put("projectId", project); put("groupId", group)
                put("passwordId", UUID.randomUUID().toString()); put("label", "")
                put("primary", true); put("groupOrder", 0); put("passwordOrder", (id - 1).toInt())
            }
            db.customFieldDao().insertAll(listOf(
                CustomField(entryId = id, title = ProjectCredentialGroup.FIELD, value = metadata.toString()),
                CustomField(entryId = id, title = EntryContentFields.ORDER, value = "CREDENTIAL:$group", sortOrder = 1),
                CustomField(entryId = id, title = "Recovery", value = security.encryptData("recovery-$id"),
                    isProtected = true, sortOrder = 2)))
        }
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val public = pair.public as java.security.interfaces.ECPublicKey
        fun coordinate(value: java.math.BigInteger): ByteArray {
            val bytes = value.toByteArray().takeLast(32).toByteArray()
            return ByteArray(32 - bytes.size) + bytes
        }
        val cose = byteArrayOf(0xa5.toByte(), 1, 2, 3, 0x26, 0x20, 1, 0x21, 0x58, 0x20) +
            coordinate(public.w.affineX) + byteArrayOf(0x22, 0x58, 0x20) + coordinate(public.w.affineY)
        // Legacy inline PKCS#8, generated for this in-memory fixture only. No protected
        // preference or device-keystore entry is created or removed by this test.
        val unboundKey = PasskeyEntry(id = 77, credentialId = UUID.randomUUID().toString(),
            rpId = "example.invalid", rpName = "Synthetic source key", userId = "dXNlcg",
            userName = "User", userDisplayName = "User", publicKey = java.util.Base64.getEncoder().encodeToString(cose),
            privateKeyAlias = java.util.Base64.getEncoder().encodeToString(pair.private.encoded),
            bitwardenVaultId = 1, syncStatus = "PENDING", boundPasswordId = null, createdAt = 1, lastUsedAt = 1)
        assertTrue(unboundKey.resolveOwnership() is PasskeyOwnership.Bitwarden)
        db.passkeyDao().insert(unboundKey)
        fun assertSigns(row: PasskeyEntry) {
            val material = checkNotNull(takagi.ru.monica.passkey.PasskeyPrivateKeyStore.resolve(security, row.privateKeyAlias))
            val privateKey = checkNotNull(takagi.ru.monica.passkey.PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(material)).privateKey
            val challenge = UUID.randomUUID().toString().toByteArray()
            val signature = takagi.ru.monica.passkey.PasskeyPrivateKeySupport.createSignature(privateKey, row.publicKeyAlgorithm)
                .apply { update(challenge) }.sign()
            assertTrue(java.security.Signature.getInstance("SHA256withECDSA").apply {
                initVerify(pair.public); update(challenge)
            }.verify(signature))
        }

        // Positive baseline actually writes both project members while its standalone key stays.
        val unbound = plan()
        assertEquals(2, unbound.writablePasswords)
        assertEquals(setOf(1L, 2L), unbound.previewPasswords.flatMap { it.sourceEntryIds }.toSet())
        assertTrue(unbound.previewPasswords.all { it.projectCopyKey != null })
        assertEquals(1, unbound.previewPasswords.map { it.projectCopyKey }.distinct().size)
        assertTrue(unbound.passkeyRetainedProjects.isEmpty())
        assertEquals(1, unbound.totalSourcePasskeys)
        assertEquals(DedupPasskeySkipReason.PRESERVED, unbound.previewPasskeys.single().skipReason)
        val baselineResult = service.executePlan(unbound)
        assertEquals(baselineResult.failures.toString(), 2, baselineResult.insertedPasswords)
        assertEquals(0, baselineResult.insertedPasskeys)
        assertEquals(0, baselineResult.failedItems)
        val baselineCopies = local().sortedBy { it.id }
        assertEquals(2, baselineCopies.size)
        val baselineFields = baselineCopies.associate { it.id to db.customFieldDao().getFieldsByEntryIdSync(it.id) }
        val restored = ProjectCredentialGroup.restore(baselineCopies, baselineFields.mapValues {
            DedupMergeContent.drafts(it.value, security::decryptDataIfMonicaCiphertext)
        })
        assertEquals(setOf("password-1", "password-2"), restored.single().passwords.map { it.value }.toSet())
        assertEquals(listOf(unboundKey), db.passkeyDao().getAllPasskeysSync())
        assertSigns(unboundKey)

        db.passwordEntryDao().insertPasswordEntry(draft(100, "independent-control").copy(title = "Independent control"))
        val beforeBinding = plan()
        // Existing local project copies cannot make this negative vacuous: all three source
        // rows are still eligible immediately before changing only the key's binding.
        assertEquals(3, beforeBinding.writablePasswords)
        assertEquals(setOf(1L, 2L, 100L), beforeBinding.previewPasswords.flatMap { it.sourceEntryIds }.toSet())
        val boundKey = unboundKey.copy(boundPasswordId = 2)
        db.passkeyDao().update(boundKey)
        val originalRows = db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.bitwardenVaultId == 1L }.sortedBy { it.id }
        val originalFields = originalRows.associate { it.id to db.customFieldDao().getFieldsByEntryIdSync(it.id) }
        val originalKeys = db.passkeyDao().getAllPasskeysSync()
        assertEquals(listOf(boundKey), originalKeys)

        // A plan approved before the binding change must not publish either member.
        assertTrue(runCatching { service.executePlan(beforeBinding) }.isFailure)
        assertEquals(baselineCopies, local().sortedBy { it.id })
        assertEquals(originalKeys, db.passkeyDao().getAllPasskeysSync())
        val bound = plan()
        assertEquals(1, bound.writablePasswords)
        assertEquals(listOf(100L), bound.previewPasswords.single().sourceEntryIds)
        assertEquals(1, bound.passkeyRetainedProjects.size)
        assertEquals("bitwarden:1", bound.passkeyRetainedProjects.single().sourceKey)
        assertEquals(listOf(1L, 2L), bound.passkeyRetainedProjects.single().passwordIds)
        assertEquals(1, bound.unsupportedSourcePasskeys)
        assertTrue(bound.previewPasskeys.all { !it.writable && it.skipReason == DedupPasskeySkipReason.PRESERVED })
        val result = service.executePlan(bound)
        assertEquals(result.failures.toString(), 1, result.insertedPasswords)
        assertEquals(0, result.insertedSecureItems)
        assertEquals(0, result.insertedPasskeys)
        assertEquals(0, result.failedItems)
        val finalCopies = local()
        assertEquals(3, finalCopies.size)
        val control = finalCopies.single { it.title == "Independent control" }
        assertEquals("independent-control", control.password)
        assertNull(control.passwordGroupId)
        assertEquals(baselineCopies, finalCopies.filter { it.id in baselineFields.keys }.sortedBy { it.id })
        baselineFields.forEach { (id, fields) -> assertEquals(fields, db.customFieldDao().getFieldsByEntryIdSync(id)) }
        assertEquals(originalRows, db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.bitwardenVaultId == 1L }.sortedBy { it.id })
        originalFields.forEach { (id, fields) -> assertEquals(fields, db.customFieldDao().getFieldsByEntryIdSync(id)) }
        assertEquals(originalKeys, db.passkeyDao().getAllPasskeysSync())
        val retainedKey = requireNotNull(db.passkeyDao().getPasskeyByRecordId(77))
        assertEquals(2L, retainedKey.boundPasswordId)
        assertEquals(boundKey.privateKeyAlias, retainedKey.privateKeyAlias)
        assertSigns(retainedKey)
    }

    @Test fun missingBoundSourceAndExternalReferencesAreRetained() = runBlocking {
        db.passwordEntryDao().insertPasswordEntry(draft(1).copy(bitwardenCipherId = "remote", bitwardenRevisionDate = "revision", bitwardenLocalModified = false))
        db.passwordEntryDao().insertPasswordEntry(draft(2).copy(ssoRefEntryId = 1))
        val planned = plan()
        assertEquals(0, planned.writableItems)
        assertEquals(2, planned.unsupportedItems.size)
        assertEquals(0, service.executePlan(planned).insertedItems)
        assertTrue(local().isEmpty())
    }

    @Test fun boundBitwardenNativeRepromptLinkedFieldsAndHistoryCannotBeSilentlyLost() = runBlocking {
        val vault = requireNotNull(db.bitwardenVaultDao().getVaultById(1))
        val account = BitwardenVaultIdentity.buildAccountKey(vault.serverUrl, vault.userId, BitwardenVaultIdentity.resolveCanonicalEmail(vault))
        val patches = listOf(buildJsonObject { put("reprompt", 1) }, buildJsonObject { put("passwordHistory", buildJsonArray { add(buildJsonObject { put("password", "old") }) }) },
            buildJsonObject { put("fields", buildJsonArray { add(buildJsonObject { put("name", "Linked"); put("value", "x"); put("type", 3); put("linkedId", 100) }) }) },
            buildJsonObject { put("futureBehavior", "unsupported") },
            buildJsonObject { put("type", 2); put("secureNote", buildJsonObject { put("type", 0) });
                put("fields", buildJsonArray { add(buildJsonObject { put("name", "Extra note field"); put("value", "keep"); put("type", 0) }) }) },
            buildJsonObject { put("login", buildJsonObject { put("fido2Credentials", buildJsonArray {
                add(buildJsonObject { put("credentialId", "synthetic-preserved-native-credential") })
            }) }) })
        patches.forEachIndexed { index, patch ->
            val id = index + 1L; val cipher = "synthetic-$id"
            val raw = JsonObject(buildJsonObject { put("id", cipher); put("type", 1); put("name", "Example"); put("revisionDate", "r1"); put("login", buildJsonObject { }) } + patch)
            val envelope = buildJsonObject { put("format", 1); put("vaultId", 1); put("accountKey", account); put("cipherId", cipher); put("revisionDate", "r1"); put("document", raw) }
            db.bitwardenCipherSourceDao().put(BitwardenCipherSource(1, cipher, "r1", account, security.encryptData(envelope.toString())))
            db.passwordEntryDao().insertPasswordEntry(draft(id).copy(bitwardenCipherId = cipher, bitwardenRevisionDate = "r1", bitwardenLocalModified = false))
        }
        val planned = plan()
        assertEquals(6, planned.unsupportedItems.size)
        assertEquals(0, planned.writableItems)
        assertTrue(db.passkeyDao().getAllPasskeysSync().isEmpty())
        assertEquals(listOf(6L), planned.passkeyRetainedProjects.single().passwordIds)
        assertEquals("bitwarden:1", planned.passkeyRetainedProjects.single().sourceKey)
    }

    @Test fun standaloneBindingsUnknownFieldsAndExternalAssetsStayLocalWhilePlainOtpCopiesToNativeMdbx() = runBlocking {
        val fixture = takagi.ru.monica.credentialexchange.TransferFixture()
        try { with(fixture) {
            val boundId = passwords.insertPasswordEntry(PasswordEntry(title = "$prefix-bound", username = "user", password = security.encryptData("original"), website = ""))
            val otp = TotpData(secret = security.encryptData("JBSWY3DPEHPK3PXP"), issuer = "Synthetic", accountName = "alice")
            fun payload(data: TotpData) = Json.encodeToString(TotpData.serializer(), data)
            val originalItems = listOf(
                "plain" to payload(otp),
                "bound" to payload(otp.copy(boundPasswordId = boundId)),
                "folder-reference" to payload(otp.copy(categoryId = 81)),
                "external-icon" to payload(otp.copy(customIconType = "UPLOADED", customIconValue = "source-only.png")),
                "unknown" to JsonObject(Json.parseToJsonElement(payload(otp)).jsonObject + ("futureReference" to JsonPrimitive(91))).toString()
            ).map { (suffix, data) ->
                val item = SecureItem(itemType = ItemType.TOTP, title = "$prefix-$suffix", itemData = data)
                item.copy(id = secureItems.insertItem(item))
            }
            val target = mdbx()
            val merger = DedupMergeService(passwords, secureItems, passkeys, CustomFieldRepository(db.customFieldDao()),
                db.localKeePassDatabaseDao(), db.localMdbxDatabaseDao(), db.bitwardenVaultDao(), security,
                AppLocaleStringResolver(context), db, DedupAttachmentSupport(context, db))
            val planned = merger.buildPlan(setOf("monica"), DedupMergeTarget.MdbxDatabase(target.databaseId, "Synthetic target"))
            assertEquals(1, planned.writableSecureItems)
            assertEquals(originalItems.drop(1).map { it.title }.toSet(), planned.unsupportedItems.map { it.title }.toSet())
            val result = merger.executePlan(planned)
            assertEquals(result.failures.toString(), 1, result.insertedSecureItems)
            assertEquals(1, result.insertedPasswords)
            originalItems.forEach { assertEquals(it, db.secureItemDao().getItemById(it.id)) }
            val copy = db.secureItemDao().getAllItems().first().single { it.mdbxDatabaseId == target.databaseId }
            assertTrue(security.looksLikeMonicaCiphertext(copy.itemData))
            val copiedData = Json.decodeFromString(TotpData.serializer(), security.decryptData(copy.itemData))
            assertEquals(otp.copy(secret = "JBSWY3DPEHPK3PXP"), copiedData)
            assertNull(copiedData.boundPasswordId)
            Mdbx2NativeReadSessions.clear()
            val nativeRows = mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }
            assertEquals(2, nativeRows.size)
            val nativeData = org.json.JSONObject(nativeRows.single { it.entryType == "totp" }.payloadJson).getString("item_data")
            // Native JSON must work without access to Monica's source encryption key.
            assertEquals(copiedData, Json.decodeFromString(TotpData.serializer(), nativeData))
            assertEquals("JBSWY3DPEHPK3PXP", takagi.ru.monica.util.TotpDataResolver.parseStoredItemData(nativeData)?.secret)
            assertEquals(boundId, Json.decodeFromString(TotpData.serializer(), requireNotNull(db.secureItemDao().getItemById(originalItems[1].id)).itemData).boundPasswordId)
        } } finally { fixture.close() }
    }

    @Test fun historicalPasswordAndSecureItemValuesRemainAtTheirSource() = runBlocking {
        db.passwordEntryDao().insertPasswordEntry(draft(1))
        db.passwordEntryDao().insertPasswordEntry(draft(2).copy(title = "Plain control"))
        val history = PasswordHistoryEntry(entryId = 1, password = security.encryptData("old-password"), lastUsedAt = Date(1))
        db.singleVaultDedupLocalDao().insertHistory(listOf(history))
        val item = SecureItem(id = 1, itemType = ItemType.TOTP, title = "Historical OTP",
            itemData = Json.encodeToString(TotpData.serializer(), TotpData("JBSWY3DPEHPK3PXP")), bitwardenVaultId = 1, bitwardenLocalModified = true)
        db.secureItemDao().insertItem(item)
        db.singleVaultDedupLocalDao().insertTimeline(listOf(TimelineVersionSnapshot(operationLogId = 1, itemType = "TOTP", itemId = 1,
            operationType = "UPDATE", encryptedChangesJson = security.encryptTimelineSnapshot("{\"old\":\"value\"}"))))
        val beforeHistory = db.singleVaultDedupLocalDao().history()
        val beforeTimeline = db.singleVaultDedupLocalDao().timeline()
        val planned = plan()
        assertEquals(1, planned.writablePasswords)
        assertEquals(0, planned.writableSecureItems)
        assertEquals(setOf("Example", "Historical OTP"), planned.unsupportedItems.map { it.title }.toSet())
        assertEquals(1, service.executePlan(planned).insertedItems)
        assertEquals(beforeHistory, db.singleVaultDedupLocalDao().history())
        assertEquals(beforeTimeline, db.singleVaultDedupLocalDao().timeline())
        assertEquals(draft(1), db.passwordEntryDao().getPasswordEntryById(1))
        assertEquals(item, db.secureItemDao().getItemById(1))
    }

    @Test fun historicalValueAddedAfterReviewRequiresAnotherReview() = runBlocking {
        db.passwordEntryDao().insertPasswordEntry(draft(1))
        val planned = plan()
        assertEquals(1, planned.writablePasswords)
        db.singleVaultDedupLocalDao().insertHistory(listOf(PasswordHistoryEntry(entryId = 1,
            password = security.encryptData("late-history"), lastUsedAt = Date(2))))
        assertTrue(runCatching { service.executePlan(planned) }.isFailure)
        assertTrue(local().isEmpty())
        assertEquals(1, plan().unsupportedItems.size)
    }

    @Test fun embeddedPasskeyFieldsAndLoginTypeRemainWhileUnrelatedPasswordCopies() = runBlocking {
        db.passwordEntryDao().insertPasswordEntry(draft(1).copy(title = "Embedded Passkey"))
        db.passwordEntryDao().insertPasswordEntry(draft(2).copy(title = "Passkey login", loginType = "PASSKEY"))
        db.passwordEntryDao().insertPasswordEntry(draft(3).copy(title = "Ordinary login"))
        db.customFieldDao().insertAll(listOf(CustomField(entryId = 1, title = "KPEX_PASSKEY_PRIVATE_KEY_PEM",
            value = "synthetic opaque credential material", isProtected = true)))
        val markedCard = SecureItem(id = 1, title = "Structured Passkey field", itemType = ItemType.BANK_CARD,
            itemData = Json.encodeToString(BankCardData.serializer(), BankCardData("123", "Holder", "01", "2030",
                customFields = listOf(SecureCustomField(security.encryptData("Passkey"), security.encryptData("synthetic opaque material"))))),
            bitwardenVaultId = 1, bitwardenLocalModified = true)
        val ordinaryCard = markedCard.copy(id = 2, title = "Ordinary structured field",
            itemData = Json.encodeToString(BankCardData.serializer(), BankCardData("123", "Holder", "01", "2030",
                customFields = listOf(SecureCustomField("Name", "Fido")))))
        db.secureItemDao().insertItem(markedCard)
        db.secureItemDao().insertItem(ordinaryCard)
        val originals = db.passwordEntryDao().getAllPasswordEntriesSync()
        val fields = db.customFieldDao().getFieldsByEntryIdSync(1)
        val planned = plan()
        assertEquals(setOf(1L, 2L), planned.passkeyRetainedProjects.flatMap { it.passwordIds }.toSet())
        assertEquals(listOf(1L), planned.passkeyRetainedProjects.flatMap { it.secureItemIds })
        assertEquals(2, planned.writableItems)
        assertEquals(2, service.executePlan(planned).insertedItems)
        assertEquals("Ordinary login", local().single().title)
        assertEquals(originals, db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.bitwardenVaultId == 1L })
        assertEquals(fields, db.customFieldDao().getFieldsByEntryIdSync(1))
        assertEquals(markedCard, db.secureItemDao().getItemById(1))
        assertEquals(ordinaryCard, db.secureItemDao().getItemById(2))
        assertTrue(db.passkeyDao().getAllPasskeysSync().isEmpty())
    }
}
