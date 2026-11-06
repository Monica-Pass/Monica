package takagi.ru.monica.data.dedup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.credentialexchange.CxfPasskeyMaterial
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Isolated local source and real Rust target. No fake KDBX Room mirror or unrelated app records. */
@RunWith(AndroidJUnit4::class)
class DedupMdbxInstrumentedTest {
    private companion object { const val LOCAL_SOURCE_KEY = "monica" }

    @Test fun mergedPasswordsCustomFieldsAndNotesSurviveReopeningMdbx() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val oldMdk = mdkField.get(null); val testMdk = ByteArray(32) { (it + 48).toByte() }
        val wasUnlocked = SessionManager.isUnlocked.value
        val wasForeground = Mdbx2NativeReadSessions.isForeground
        mdkField.set(null, testMdk); SessionManager.markUnlocked(); Mdbx2NativeReadSessions.updateForeground(true)
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val security = SecurityManager(context)
        val mdbx = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security, passwordEntryDao = db.passwordEntryDao(),
            secureItemDao = db.secureItemDao(), customFieldDao = db.customFieldDao())
        val file = mdbx.createInitializedVaultFile(MdbxTigaMode.SKY, "Synthetic MDBX merge password")
        try {
            val targetId = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = "Synthetic target", filePath = file.absolutePath,
                storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                engineType = MdbxEngineType.RUST_MDBX2.name, encryptedPassword = security.encryptData("Synthetic MDBX merge password"),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue))
            val passwords = PasswordRepository(db.passwordEntryDao(), mdbxRepository = mdbx)
            val items = SecureItemRepository(db.secureItemDao(), mdbxRepository = mdbx)
            val passkeys = PasskeyRepository(db.passkeyDao(), mdbx, context)
            val sourceIds = (1..2).map { db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Synthetic login",
                website = "https://example.invalid", username = "alice", password = security.encryptData("synthetic password"))) }
            db.customFieldDao().insertAll(sourceIds.map { CustomField(entryId = it, title = "Recovery", value = "synthetic recovery", isProtected = true) })
            db.secureItemDao().insertItem(SecureItem(title = "Synthetic note", itemType = ItemType.NOTE,
                itemData = Json.encodeToString(NoteData(content = "Preserve this note"))))
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val material = checkNotNull(CxfPasskeyMaterial.decode(pair.private.encoded))
            val credentialId = UUID.randomUUID().toString()
            passkeys.savePasskey(PasskeyEntry(credentialId = credentialId, rpId = "example.invalid", rpName = "Synthetic credential",
                userId = "AQID", userName = "alice", userDisplayName = "Alice", publicKey = material.cosePublicKey,
                privateKeyAlias = Base64.getEncoder().encodeToString(pair.private.encoded)))
            val originalPasskeys = passkeys.getAllPasskeysSync()
            val service = DedupMergeService(passwords, items, passkeys, CustomFieldRepository(db.customFieldDao()), db.localKeePassDatabaseDao(),
                db.localMdbxDatabaseDao(), db.bitwardenVaultDao(), security, AppLocaleStringResolver(context), db)
            val target = DedupMergeTarget.MdbxDatabase(targetId, "Target")
            val plan = service.buildPlan(setOf(LOCAL_SOURCE_KEY), target)
            assertEquals(2, plan.writableItems)
            assertEquals(DedupPasskeySkipReason.PRESERVED, plan.previewPasskeys.single().skipReason)
            val result = service.executePlan(plan)
            assertEquals(result.failures.toString(), 2, result.insertedItems)
            assertEquals(0, result.insertedPasskeys)
            Mdbx2NativeReadSessions.invalidate(targetId)
            val stored = mdbx.readStoredEntries(targetId).filterNot { it.deleted }
            assertEquals(2, stored.size)
            val login = JSONObject(stored.single { it.entryType == "login" }.payloadJson)
            assertEquals("synthetic password", login.getString("password_plain"))
            val fields = login.getJSONArray("custom_fields")
            assertEquals(1, fields.length()); assertEquals("synthetic recovery", fields.getJSONObject(0).getString("value"))
            assertTrue(stored.any { it.payloadJson.contains("Preserve this note") })
            assertTrue(stored.none { it.entryType == "passkey" })
            assertEquals(originalPasskeys, passkeys.getAllPasskeysSync())
            val sourceKey = takagi.ru.monica.passkey.PasskeyPrivateKeyStore.resolve(security, originalPasskeys.single().privateKeyAlias)
            val privateKey = checkNotNull(PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(sourceKey)).privateKey
            val challenge = "synthetic MDBX round trip".toByteArray()
            val signature = PasskeyPrivateKeySupport.createSignature(privateKey, -7).apply { update(challenge) }.sign()
            assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(challenge) }.verify(signature))
            try { service.executePlan(plan); fail("Consumed plan must require fresh review") } catch (_: IllegalStateException) { }
            val next = service.buildPlan(setOf(LOCAL_SOURCE_KEY), target)
            assertTrue(next.mdbxTargetAddsIndependentCopies)
            assertEquals(0, next.targetExistingDuplicates); assertEquals(0, next.targetExistingSecureItems)
            assertEquals(0, next.targetExistingPasskeys)
            assertEquals(DedupPasskeySkipReason.PRESERVED, next.previewPasskeys.single().skipReason)
            assertEquals(2, next.writableItems); assertEquals(2, service.executePlan(next).insertedItems)
            Mdbx2NativeReadSessions.invalidate(targetId)
            val after = mdbx.readStoredEntries(targetId).filterNot { it.deleted }
            assertEquals(4, after.size)
            assertTrue(after.containsAll(stored))
            assertEquals(originalPasskeys, passkeys.getAllPasskeysSync())
            assertEquals(2, db.passwordEntryDao().getAllPasswordEntriesSync().count { it.id in sourceIds })
            assertEquals(1, passkeys.getAllPasskeysSync().count { it.isLocalOnlyPasskey() && it.credentialId == credentialId })
            Mdbx2NativeReadSessions.invalidate(targetId)
        } finally {
            db.close(); mdbx.deleteOwnedVaultFile(file)
            Mdbx2NativeReadSessions.updateForeground(wasForeground)
            if (!wasUnlocked) SessionManager.markLocked()
            mdkField.set(null, oldMdk); testMdk.fill(0)
        }
    }
}
