package takagi.ru.monica.autofill_ng

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassKdbxService
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.openVault

/** Real native files and guarded reads; only the inherited, explicitly owned test user. */
class NativeAutofillLoadPerformanceTest : MdbxAutofillFixture() {
    @Test fun nativeDatabaseSwitchStages() = runBlocking {
        val count = 300
        val native = fixture()
        openVault(native.file.path, vaultPassword, "synthetic-load-fixture").let { vault ->
            try { (1 until count).chunked(200).forEach { chunk ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "load-fixture", chunk.map {
                    MdbxWriteCommand.CreateEntry(UUID.randomUUID().toString(), native.folderId, "login", "Synthetic $it", LOGIN)
                })
            } } finally { vault.close() }
        }
        val file = File(context.filesDir, "load-${UUID.randomUUID()}.kdbx")
        val credentials = Credentials.from(EncryptedValue.fromString(vaultPassword))
        val created = KeePassDatabase.Ver4x.create("Synthetic performance", Meta(generator = "Autofill test"), credentials)
        val seed = when(val kdf = created.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        val kdbx = created.copy(header = created.header.copy(kdfParameters = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2d, salt = seed, parallelism = 2U,
            memory = 32uL * 1024uL * 1024uL, iterations = 8U, version = 0x13U, secretKey = null, associatedData = null)))
            .modifyParentGroup { copy(entries = (0 until count).map { Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(
                "Title" to EntryValue.Plain("Synthetic $it"), "UserName" to EntryValue.Plain("native-user"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("native-secret")),
                "URL" to EntryValue.Plain("https://native.example"))) }) }
        file.outputStream().use { kdbx.encode(it) }
        val id = (UUID.randomUUID().mostSignificantBits and 0x0000ffffffffffffL) + 1_000_000_000L
        database.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(id=id, name="Synthetic KDBX",
            filePath=file.name, storageLocation=KeePassStorageLocation.INTERNAL,
            encryptedPassword=security.encryptData(vaultPassword)))
        val calls = AtomicInteger()
        val nanos = AtomicLong()
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val authorize = {
            val start = System.nanoTime()
            calls.incrementAndGet()
            val result = active.get() && power.isInteractive && !keyguard.isKeyguardLocked
            nanos.addAndGet(System.nanoTime()-start)
            result
        }
        try {
            val repo = Mdbx2Repository(context, database.localMdbxDatabaseDao(), security)
            val service = KeePassKdbxService(context, database.localKeePassDatabaseDao(), security)
            for ((kind, scope) in listOf("kdbx" to AutofillScope.KeePass(id), "mdbx" to AutofillScope.Mdbx(native.databaseId))) {
                repeat(3) { round ->
                    calls.set(0); nanos.set(0)
                    val source = AutofillPasswordSource(context, security, SecurityManager.dataReadGeneration.value, authorize, database)
                    val start = SystemClock.elapsedRealtimeNanos()
                    val rows = source.load(scope)
                    val loaded = SystemClock.elapsedRealtimeNanos()
                    val rules = source.urlRules(rows)
                    val matched = AutofillPickerSuggestions.load(rows, AutofillPickerActivityV2.Args(applicationId="org.example.synthetic", webDomain="native.example"), AutofillPreferences(context), "Synthetic", rules, activeScope=scope)
                    val prepared = SystemClock.elapsedRealtimeNanos()
                    assertEquals(count, rows.size)
                    assertTrue(source.areCurrent(rows))
                    rows.forEach { assertEquals("native-user", AccountFillPolicy.resolveAccountIdentifier(it, security)) }
                    report("kind=$kind round=$round rows=${rows.size} loadMs=${(loaded-start)/1e6} prepareMs=${(prepared-loaded)/1e6} checks=${calls.get()} systemCheckMs=${nanos.get()/1e6} suggested=${matched.size}")
                    assertTrue("Full external authorization must be bounded per native batch", calls.get() in 4..32)
                    source.clear()
                }
                val start = SystemClock.elapsedRealtimeNanos()
                val resultCount = if (kind=="kdbx") service.readAutofillSnapshot(id, checkActive={}).getOrThrow().entries.size
                    else repo.readAutofillSnapshot(native.databaseId, checkActive={}).credentials.size
                assertEquals(count, resultCount)
                report("kind=$kind diagnosticDirectReadMs=${(SystemClock.elapsedRealtimeNanos()-start)/1e6}")
            }
        } finally {
            KeePassKdbxService.invalidateProcessCache(id)
            file.delete()
        }
    }
    private fun report(value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
        putString("stream", "NATIVE_LOAD328 $value\n")
    })
}
