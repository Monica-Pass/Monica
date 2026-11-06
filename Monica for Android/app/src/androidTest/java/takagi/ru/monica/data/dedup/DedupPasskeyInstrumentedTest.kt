package takagi.ru.monica.data.dedup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.io.File
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.*
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.keepass.KeePassDxPasskeyCodec
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.KeePassKdbxService

@RunWith(AndroidJUnit4::class)
class DedupPasskeyInstrumentedTest {
    private companion object { const val LOCAL_SOURCE_KEY = "monica" }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var repository: PasskeyRepository
    private lateinit var passwords: PasswordRepository
    private lateinit var service: DedupMergeService
    private val nativeAliases = mutableListOf<String>()
    private val sourceFiles = mutableListOf<File>()
    private val sources = setOf(LOCAL_SOURCE_KEY)
    private val target = DedupMergeTarget.MdbxDatabase(7, "Synthetic target")
    private lateinit var mdbx: Mdbx2Repository
    private lateinit var targetFile: File
    private val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
    private var oldMdk: Any? = null
    private var wasUnlocked = false
    private var wasForeground = false
    private val testMdk = ByteArray(32) { (it + 31).toByte() }

    @Before fun setUp() = runBlocking {
        oldMdk = mdkField.get(null); mdkField.set(null, testMdk)
        wasUnlocked = SessionManager.isUnlocked.value; SessionManager.markUnlocked()
        wasForeground = Mdbx2NativeReadSessions.isForeground; Mdbx2NativeReadSessions.updateForeground(true)
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        mdbx = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security, passwordEntryDao = db.passwordEntryDao(),
            secureItemDao = db.secureItemDao(), customFieldDao = db.customFieldDao())
        targetFile = mdbx.createInitializedVaultFile(MdbxTigaMode.SKY, "Synthetic dedup target")
        db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(id = 7, name = "Synthetic target", filePath = targetFile.absolutePath,
            storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            engineType = MdbxEngineType.RUST_MDBX2.name, encryptedPassword = security.encryptData("Synthetic dedup target"),
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue))
        repository = PasskeyRepository(db.passkeyDao(), mdbx, context)
        passwords = PasswordRepository(db.passwordEntryDao(), mdbxRepository = mdbx)
        service = DedupMergeService(passwords, SecureItemRepository(db.secureItemDao(), mdbxRepository = mdbx),
            repository, CustomFieldRepository(db.customFieldDao()), db.localKeePassDatabaseDao(),
            db.localMdbxDatabaseDao(), db.bitwardenVaultDao(), security, AppLocaleStringResolver(context), db)
    }

    @After fun tearDown() = runBlocking {
        try {
            repository.deleteAllPasskeys()
            KeePassKdbxService.invalidateProcessCache(1)
            Mdbx2NativeReadSessions.invalidate(7)
            db.close()
            mdbx.deleteOwnedVaultFile(targetFile)
            sourceFiles.forEach { check(it.canonicalFile.parentFile == context.filesDir.canonicalFile); it.delete() }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { store -> nativeAliases.forEach(store::deleteEntry) }
        } finally {
            Mdbx2NativeReadSessions.updateForeground(wasForeground)
            if (!wasUnlocked) SessionManager.markLocked()
            mdkField.set(null, oldMdk); testMdk.fill(0)
        }
    }

    @Test fun identicalCredentialsMergeAndSourceStillSignsAfterDeletingTheCopy() = runBlocking {
        val pair = keyPair()
        val first = credential(pair)
        repository.savePasskey(first)
        repository.savePasskey(first.copy(useCount = 10, lastUsedAt = 99,
            credentialId = checkNotNull(takagi.ru.monica.passkey.PasskeyCredentialIdCodec.toWebAuthnId(first.credentialId))))
        val before = repository.getAllPasskeysSync()
        assertEquals(2, before.size)
        val plan = service.buildPlan(sources, target)
        assertEquals(0, plan.writableItems)
        assertEquals(0, plan.consolidatedCopies)
        assertEquals(2, plan.unsupportedSourcePasskeys)
        assertTrue(plan.previewPasskeys.all { it.skipReason == DedupPasskeySkipReason.PRESERVED })
        val result = service.executePlan(plan)
        assertEquals(result.failures.toString(), 0, result.insertedPasskeys)
        assertTrue(mdbx.readStoredEntries(7).isEmpty())
        before.forEach { assertSigns(it, pair) }
        assertEquals(before, repository.getAllPasskeysSync().filter { it.isLocalOnlyPasskey() })
        try { service.executePlan(plan); fail("Consumed review must be rejected") } catch (_: IllegalStateException) { }
        val next = service.buildPlan(sources, target)
        assertEquals(0, next.writableItems)
        assertEquals(0, next.targetExistingPasskeys)
        assertTrue(next.previewPasskeys.all { it.skipReason == DedupPasskeySkipReason.PRESERVED })
        val nativeBefore = mdbx.readStoredEntries(7)
        assertEquals(0, service.executePlan(next).insertedItems)
        Mdbx2NativeReadSessions.invalidate(7)
        assertEquals(nativeBefore, mdbx.readStoredEntries(7))
        before.forEach { assertSigns(it, pair) }
        repository.deletePasskey(before.first())
        assertSigns(repository.getAllPasskeysSync().single(), pair)
    }

    @Test fun differentCredentialsForTheSameAccountAreBothKept() = runBlocking {
        val first = keyPair(); val second = keyPair()
        val firstCredential = credential(first); val secondCredential = credential(second)
        repository.savePasskey(firstCredential)
        repository.savePasskey(secondCredential)
        val before = repository.getAllPasskeysSync()
        val plan = service.buildPlan(sources, target)
        assertEquals(0, plan.writableItems)
        assertEquals(0, plan.consolidatedCopies)
        assertEquals(2, plan.unsupportedSourcePasskeys)
        assertTrue(plan.previewPasskeys.all { it.skipReason == DedupPasskeySkipReason.PRESERVED })
        assertEquals(0, service.executePlan(plan).insertedPasskeys)
        assertEquals(before, repository.getAllPasskeysSync())
        assertSigns(before.single { it.credentialId == firstCredential.credentialId }, first)
        assertSigns(before.single { it.credentialId == secondCredential.credentialId }, second)
        assertTrue(mdbx.readStoredEntries(7).isEmpty())
    }

    @Test fun sameCredentialIdWithDifferentKeysOrMetadataIsNotGuessed() = runBlocking {
        val first = credential(keyPair())
        repository.savePasskey(first)
        repository.savePasskey(credential(keyPair()).copy(credentialId = first.credentialId))
        val before = repository.getAllPasskeysSync()
        val plan = service.buildPlan(sources, target)
        assertEquals(0, plan.writableItems)
        assertEquals(2, plan.unsupportedSourcePasskeys)
        assertTrue(plan.previewPasskeys.all { it.skipReason == DedupPasskeySkipReason.PRESERVED })
        assertEquals(0, service.executePlan(plan).insertedItems)
        assertEquals(before, repository.getAllPasskeysSync())
    }

    @Test fun nonzeroBoundAndReferenceCredentialsStayUntouched() = runBlocking {
        val first = credential(keyPair())
        repository.savePasskey(first.copy(signCount = 9))
        repository.savePasskey(credential(keyPair()).copy(boundPasswordId = 123))
        repository.savePasskey(credential(keyPair()).copy(syncStatus = "REFERENCE", privateKeyAlias = ""))
        val before = repository.getAllPasskeysSync()
        val plan = service.buildPlan(sources, target)
        assertEquals(setOf(DedupPasskeySkipReason.PRESERVED), plan.previewPasskeys.map { it.skipReason }.toSet())
        assertEquals(3, plan.unsupportedSourcePasskeys)
        assertEquals(0, service.executePlan(plan).insertedItems)
        assertEquals(before, repository.getAllPasskeysSync())
    }

    @Test fun targetCredentialCollisionNeverOverwritesItsKey() = runBlocking {
        val first = credential(keyPair())
        val targetPair = keyPair()
        repository.savePasskey(first)
        repository.savePasskey(credential(targetPair).copy(credentialId = first.credentialId, mdbxDatabaseId = 7))
        val before = repository.getAllPasskeysSync()
        val nativeBefore = mdbx.readStoredEntries(7).map { it.payloadJson }
        val plan = service.buildPlan(sources, target)
        assertEquals(DedupPasskeySkipReason.PRESERVED, plan.previewPasskeys.single().skipReason)
        assertEquals(0, service.executePlan(plan).insertedItems)
        assertEquals(before, repository.getAllPasskeysSync())
        Mdbx2NativeReadSessions.invalidate(7)
        assertEquals(nativeBefore, mdbx.readStoredEntries(7).map { it.payloadJson }); assertNativeSigns(targetPair)
    }

    @Test fun deviceKeyRemainsLocalAndIsNeverPretendedPortable() = runBlocking {
        val alias = "monica-dedup-test-${UUID.randomUUID()}".also(nativeAliases::add)
        val pair = KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        repository.savePasskey(credential(pair, alias))
        val before = repository.getAllPasskeysSync()
        val mdbxPlan = service.buildPlan(sources, target)
        assertEquals(DedupPasskeySkipReason.PRESERVED, mdbxPlan.previewPasskeys.single().skipReason)
        assertEquals(0, service.executePlan(mdbxPlan).insertedItems)
        assertEquals(before, repository.getAllPasskeysSync())
        assertTrue(mdbx.readStoredEntries(7).none { !it.deleted })
        val signer = PasskeyPrivateKeySupport.createSignature(pair.private, -7)
        val challenge = byteArrayOf(1, 2, 3)
        signer.update(challenge)
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(challenge) }.verify(signer.sign()))
    }

    @Test fun realNativeKeePassCredentialIsRetainedUntilCompleteProviderTransferIsProven() = runBlocking {
        val pair = keyPair(); val first = credential(pair)
        val password = "Synthetic native source"
        val created = KeePassDatabase.Ver4x.create("Synthetic", Meta(), Credentials.from(EncryptedValue.fromString(password)))
        val seed = when (val kdf = created.header.kdfParameters) { is KdfParameters.Aes -> kdf.seed; is KdfParameters.Argon2 -> kdf.salt }
        val entry = Entry(UUID.randomUUID(), fields = EntryFields.of("Title" to EntryValue.Plain("Synthetic passkey")) + KeePassDxPasskeyCodec.buildCustomFieldPairs(first))
        val file = File(context.filesDir, "dedup-passkey-${UUID.randomUUID()}.kdbx").also(sourceFiles::add)
        file.outputStream().use { created.copy(header = created.header.copy(kdfParameters = KdfParameters.Aes(10uL, seed)))
            .modifyParentGroup { copy(entries = listOf(entry)) }.encode(it) }
        db.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(id = 1, name = "Synthetic native", filePath = file.name,
            encryptedPassword = security.encryptData(password)))
        val nativeService = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
        val store = KeePassMonicaPasswordStore(nativeService, security)
        passwords.monicaKeePassStore = store
        val snapshot = store.refresh(1)
        assertEquals(1, snapshot.passkeys.size)
        assertTrue(db.passkeyDao().getAllPasskeysSync().isEmpty())
        val before = file.readBytes()
        val plan = service.buildPlan(setOf("keepass:1"), target)
        assertEquals(1, plan.totalSourcePasskeys); assertEquals(1, plan.unsupportedSourcePasskeys)
        assertEquals(DedupPasskeySkipReason.PRESERVED, plan.previewPasskeys.single().skipReason)
        assertEquals(0, plan.writableItems); assertEquals(0, service.executePlan(plan).insertedItems)
        assertArrayEquals(before, file.readBytes())
        assertTrue(mdbx.readStoredEntries(7).none { !it.deleted })
        assertSigns(snapshot.passkeys.single(), pair)
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun credential(pair: KeyPair, alias: String = Base64.getEncoder().encodeToString(pair.private.encoded)): PasskeyEntry {
        val public = pair.public as ECPublicKey
        fun coordinate(value: java.math.BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        val cose = byteArrayOf(0xa5.toByte(), 1, 2, 3, 0x26, 0x20, 1, 0x21, 0x58, 0x20) +
            coordinate(public.w.affineX) + byteArrayOf(0x22, 0x58, 0x20) + coordinate(public.w.affineY)
        return PasskeyEntry(credentialId = UUID.randomUUID().toString(), rpId = "example.invalid", rpName = "Example",
            userId = "AQID", userName = "alice@example.invalid", userDisplayName = "Alice",
            publicKey = Base64.getEncoder().encodeToString(cose), privateKeyAlias = alias,
            createdAt = 1, lastUsedAt = 1)
    }

    private fun assertSigns(entry: PasskeyEntry, pair: KeyPair) {
        val material = checkNotNull(PasskeyPrivateKeyStore.resolve(security, entry.privateKeyAlias))
        val key = checkNotNull(PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(material)).privateKey
        val challenge = UUID.randomUUID().toString().toByteArray()
        val signer = PasskeyPrivateKeySupport.createSignature(key, entry.publicKeyAlgorithm).apply { update(challenge) }
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(challenge) }.verify(signer.sign()))
    }

    private suspend fun assertNativeSigns(pair: KeyPair) {
        Mdbx2NativeReadSessions.invalidate(7)
        val challenge = "synthetic native dedup signature".toByteArray()
        val keys = mdbx.readStoredEntries(7).filter { !it.deleted && it.entryType == "passkey" }
        assertTrue("A cold native payload must sign with this source public key", keys.any { stored ->
            val raw = JSONObject(stored.payloadJson).getString("private_key_alias")
            val key = checkNotNull(PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(raw)).privateKey
            val signature = PasskeyPrivateKeySupport.createSignature(key, -7).apply { update(challenge) }.sign()
            Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(challenge) }.verify(signature)
        })
    }
}
