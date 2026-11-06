package takagi.ru.monica.data.dedup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.credentialexchange.CxfPasskeyMaterial
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Real Rust MDBX writes, isolated Room, and synthetic keys; no user records or remote endpoint. */
@RunWith(AndroidJUnit4::class)
class DedupMdbxCreateOnlyInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
    private val testMdk = ByteArray(32) { (it + 71).toByte() }
    private var oldMdk: Any? = null
    private var wasUnlocked = false
    private var wasForeground = false
    private lateinit var db: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var native: Mdbx2Repository
    private lateinit var passwords: PasswordRepository
    private lateinit var items: SecureItemRepository
    private lateinit var passkeys: PasskeyRepository
    private lateinit var service: DedupMergeService
    private lateinit var file: File
    private val target = DedupMergeTarget.MdbxDatabase(1, "Synthetic target")

    @Before fun setup() = runBlocking {
        oldMdk = mdkField.get(null); mdkField.set(null, testMdk)
        wasUnlocked = SessionManager.isUnlocked.value; SessionManager.markUnlocked()
        wasForeground = Mdbx2NativeReadSessions.isForeground; Mdbx2NativeReadSessions.updateForeground(true)
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        native = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security,
            passwordEntryDao = db.passwordEntryDao(), secureItemDao = db.secureItemDao(), customFieldDao = db.customFieldDao())
        file = native.createInitializedVaultFile(MdbxTigaMode.SKY, "Synthetic create-only test")
        db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(id = 1, name = "Synthetic target", filePath = file.absolutePath,
            storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            engineType = MdbxEngineType.RUST_MDBX2.name, encryptedPassword = security.encryptData("Synthetic create-only test"),
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue))
        passwords = PasswordRepository(db.passwordEntryDao(), mdbxRepository = native)
        items = SecureItemRepository(db.secureItemDao(), mdbxRepository = native)
        passkeys = PasskeyRepository(db.passkeyDao(), native, context)
        service = DedupMergeService(passwords, items, passkeys, CustomFieldRepository(db.customFieldDao()),
            db.localKeePassDatabaseDao(), db.localMdbxDatabaseDao(), db.bitwardenVaultDao(), security,
            AppLocaleStringResolver(context), db)
    }

    @After fun cleanup() = runBlocking<Unit> {
        try { Mdbx2NativeReadSessions.invalidate(1); db.close(); native.deleteOwnedVaultFile(file) }
        finally {
            Mdbx2NativeReadSessions.updateForeground(wasForeground)
            if (!wasUnlocked) SessionManager.markLocked()
            mdkField.set(null, oldMdk); testMdk.fill(0)
        }
    }

    @Test fun nativeOnlyPasswordCollisionAfterReviewNeverUpdatesOrDeletesOriginal() = runBlocking {
        passwords.insertPasswordEntry(login())
        val plan = reviewed()
        val original = plan.previewPasswords.single().entry.copy(id = 900001, title = "Native original",
            password = security.encryptData("Native-only secret"))
        native.upsertPassword(original)
        assertNoTargetMirrors()
        assertRejectedWithoutNativeChanges(plan)
        assertEquals("Native-only secret", JSONObject(coldRows().single().payloadJson).getString("password_plain"))
    }

    @Test fun nativeOnlySecureItemTombstoneCollisionIsNotRestoredOrDeleted() = runBlocking {
        items.insertItem(note())
        val plan = reviewed()
        val original = plan.previewSecureItems.single().item.copy(id = 900002, title = "Native recycled note",
            itemData = Json.encodeToString(NoteData(content = "Only in recycled native object")), isDeleted = true)
        native.upsertSecureItem(original)
        assertTrue(coldRows().single().deleted)
        assertNoTargetMirrors()
        assertRejectedWithoutNativeChanges(plan)
        assertTrue(coldRows().single().deleted)
        assertTrue(coldRows().single().payloadJson.contains("Only in recycled native object"))
    }

    @Test fun nativeOnlyPasskeyCollisionKeepsOriginalKeyAndCounter() = runBlocking {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val material = checkNotNull(CxfPasskeyMaterial.decode(pair.private.encoded))
        val source = PasskeyEntry(credentialId = UUID.randomUUID().toString(), rpId = "example.invalid", rpName = "Source",
            userId = "AQID", userName = "alice", userDisplayName = "Alice", publicKey = material.cosePublicKey,
            privateKeyAlias = Base64.getEncoder().encodeToString(pair.private.encoded))
        passkeys.savePasskey(source)
        val plan = reviewed()
        native.upsertPasskey(source.copy(id = 900003, mdbxDatabaseId = 1, rpName = "Native original", signCount = 19))
        assertNoTargetMirrors()
        val originalRows = coldRows()
        val originalBytes = file.readBytes()
        val originalKeys = passkeys.getAllPasskeysSync()
        assertEquals(0, plan.writableItems)
        assertEquals(DedupPasskeySkipReason.PRESERVED, plan.previewPasskeys.single().skipReason)
        assertEquals(0, service.executePlan(plan).insertedItems)
        // The dedup engine no longer calls this API; retain its repository-level
        // collision/compensation defense for other authorized copy workflows.
        assertTrue(runCatching { passkeys.saveDedupCopy(source.copy(mdbxDatabaseId = 1)) }.isFailure)
        assertEquals(originalRows, coldRows())
        assertArrayEquals(originalBytes, file.readBytes())
        assertEquals(originalKeys, passkeys.getAllPasskeysSync())
        assertNoTargetMirrors()
        val payload = JSONObject(coldRows().single().payloadJson)
        assertEquals(19L, payload.getLong("sign_count"))
        val key = checkNotNull(PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(payload.getString("private_key_alias"))).privateKey
        val challenge = "Native collision must retain the original signing key".toByteArray()
        val signature = PasskeyPrivateKeySupport.createSignature(key, -7).apply { update(challenge) }.sign()
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(challenge) }.verify(signature))
    }

    @Test fun objectInDeletedNativeCollectionCannotBeRecreatedByCopy() = runBlocking {
        passwords.insertPasswordEntry(login())
        val plan = reviewed()
        val folder = native.createFolder(1, "Recycled collection")
        val original = plan.previewPasswords.single().entry.copy(id = 900004, mdbxFolderId = folder.folderId,
            password = security.encryptData("Secret in deleted collection"))
        native.upsertPassword(original)
        // Native collections may only be recycled after their active objects.
        native.deletePassword(original)
        val recycledOriginal = coldRows().single()
        assertTrue(recycledOriginal.deleted)
        native.deleteFolder(1, folder.folderId)
        assertRejectedWithoutNativeChanges(plan)
        native.restoreFolder(1, folder.folderId, null)
        assertEquals(recycledOriginal, coldRows().single())
    }

    @Test fun staleEqualRoomMirrorDoesNotSuppressIndependentPasswordAndNoteCopies() = runBlocking {
        passwords.insertPasswordEntry(login())
        items.insertItem(note())
        val first = service.executePlan(reviewed())
        assertEquals(first.failures.toString(), 2, first.insertedItems)
        val oldPassword = db.passwordEntryDao().getAllPasswordEntriesSync().single { it.mdbxDatabaseId == 1L }
        val oldNote = db.secureItemDao().getAllItems().first().single { it.mdbxDatabaseId == 1L }
        native.upsertPassword(oldPassword.copy(password = security.encryptData("Native changed password")))
        native.upsertSecureItem(oldNote.copy(itemData = Json.encodeToString(NoteData(content = "Native changed note"))))
        val originals = coldRows()
        assertEquals(oldPassword, db.passwordEntryDao().getAllPasswordEntriesSync().single { it.mdbxDatabaseId == 1L })
        assertEquals(oldNote, db.secureItemDao().getAllItems().first().single { it.mdbxDatabaseId == 1L })
        val next = reviewed()
        assertTrue(next.mdbxTargetAddsIndependentCopies)
        assertEquals(0, next.targetExistingDuplicates); assertEquals(0, next.targetExistingSecureItems)
        assertEquals(2, next.writableItems)
        assertNotEquals(oldPassword.replicaGroupId, next.previewPasswords.single().entry.replicaGroupId)
        assertNotEquals(oldNote.replicaGroupId, next.previewSecureItems.single().item.replicaGroupId)
        val result = service.executePlan(next)
        assertEquals(result.failures.toString(), 2, result.insertedItems)
        val after = coldRows()
        assertEquals(4, after.size); assertTrue(after.containsAll(originals))
        assertEquals(setOf("Source secret", "Native changed password"), after.filter { it.entryType == "login" }
            .map { JSONObject(it.payloadJson).getString("password_plain") }.toSet())
        assertTrue(after.any { it.payloadJson.contains("Source note") })
        assertTrue(after.any { it.payloadJson.contains("Native changed note") })
    }

    @Test fun encryptedCustomFieldsAreRetainedWhileIndependentPlainFieldsCopy() = runBlocking {
        val retainedId = passwords.insertPasswordEntry(login().copy(title = "Source-dependent field"))
        val copiedId = passwords.insertPasswordEntry(login().copy(title = "Portable field"))
        db.customFieldDao().insertAll(listOf(
            CustomField(entryId = retainedId, title = "Recovery", value = security.encryptData("Source-dependent secret"), isProtected = true),
            CustomField(entryId = copiedId, title = "Recovery", value = "Portable secret", isProtected = true)))
        val originals = db.passwordEntryDao().getAllPasswordEntriesSync()
        val fields = db.customFieldDao().getFieldsByEntryIdSync(retainedId)
        val plan = reviewed()
        assertEquals(1, plan.writablePasswords)
        assertEquals(listOf("Source-dependent field"), plan.unsupportedItems.map { it.title })
        val result = service.executePlan(plan)
        assertEquals(result.failures.toString(), 1, result.insertedItems)
        assertEquals(originals, db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.mdbxDatabaseId == null })
        assertEquals(fields, db.customFieldDao().getFieldsByEntryIdSync(retainedId))
        val nativeFields = JSONObject(coldRows().single().payloadJson).getJSONArray("custom_fields")
        assertEquals("Portable secret", nativeFields.getJSONObject(0).getString("value"))
        assertTrue(nativeFields.getJSONObject(0).getBoolean("is_protected"))
    }

    private fun login() = PasswordEntry(title = "Synthetic source login", username = "alice",
        password = security.encryptData("Source secret"), website = "https://example.invalid")
    private fun note() = SecureItem(title = "Synthetic source note", itemType = ItemType.NOTE,
        itemData = Json.encodeToString(NoteData(content = "Source note")))
    private suspend fun reviewed() = service.buildPlan(setOf("monica"), target)
    private suspend fun coldRows(): List<MdbxStoredVaultEntry> {
        Mdbx2NativeReadSessions.invalidate(1)
        return native.readStoredEntries(1)
    }
    private suspend fun assertNoTargetMirrors() {
        assertTrue(db.passwordEntryDao().getAllPasswordEntriesSync().none { it.mdbxDatabaseId == 1L })
        assertTrue(db.secureItemDao().getAllItems().first().none { it.mdbxDatabaseId == 1L })
        assertTrue(passkeys.getAllPasskeysSync().none { it.mdbxDatabaseId == 1L })
    }
    private suspend fun assertRejectedWithoutNativeChanges(plan: DedupMergePlan) {
        assertEquals(1, plan.writableItems)
        val passwordsBefore = db.passwordEntryDao().getAllPasswordEntriesSync()
        val itemsBefore = db.secureItemDao().getAllItems().first()
        val passkeysBefore = passkeys.getAllPasskeysSync()
        val before = coldRows()
        val bytes = file.readBytes()
        val result = service.executePlan(plan)
        assertEquals(0, result.insertedItems); assertEquals(1, result.failures.size)
        assertEquals(before, coldRows())
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(passwordsBefore, db.passwordEntryDao().getAllPasswordEntriesSync())
        assertEquals(itemsBefore, db.secureItemDao().getAllItems().first())
        assertEquals(passkeysBefore, passkeys.getAllPasskeysSync())
        assertNoTargetMirrors()
    }
}
