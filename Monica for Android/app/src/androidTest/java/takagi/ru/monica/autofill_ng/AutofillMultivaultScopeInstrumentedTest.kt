package takagi.ru.monica.autofill_ng

import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService

/** Provider-backed real KDBX files and generated Room DAOs; synthetic BW mirrors, no cloud account. */
class AutofillMultivaultScopeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val active = AtomicBoolean(true)
    private lateinit var database: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var service: KeePassKdbxService
    private val fixtures = mutableListOf<Fixture>()
    private val sources = mutableListOf<AutofillPasswordSource>()
    private val scopedQueries = CopyOnWriteArrayList<List<Any?>>()
    private val masterPassword get() = InstrumentationRegistry.getArguments().getString("autofillBatchPassword")
        ?: "Batch328-synthetic-master!"
    private data class Fixture(val id: Long, val uri: Uri, val bytes: ByteArray, val objectId: UUID)

    @Before fun prepare() = runBlocking {
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && InstrumentationRegistry.getArguments().getString("autofillBatchUser") == user.toString()) {
            "Use an explicitly owned secondary test user"
        }
        security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(masterPassword))
        SessionManager.attachAppContext(context)
        SessionManager.markLocked()
        check(security.unlockVaultWithPassword(masterPassword)) { "Expected the synthetic master password" }
        assertFalse(SessionManager.isUnlocked.value)
        database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java)
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (sqlQuery.contains("SELECT * FROM password_entries", true) &&
                        sqlQuery.contains("NOT EXISTS", true) && sqlQuery.contains("BITWARDEN"))
                        scopedQueries += bindArgs.toList()
                }
            }, Executor { it.run() }).build()
        service = KeePassKdbxService(context, database.localKeePassDatabaseDao(), security)
    }

    @After fun cleanup() {
        active.set(false)
        sources.forEach { it.clear() }
        fixtures.forEach { fixture ->
            command(fixture, "release-read")
            KeePassKdbxService.invalidateProcessCache(fixture.id)
            context.contentResolver.delete(fixture.uri, null, null)
        }
        if (::database.isInitialized) database.close()
        SecurityManager.clearRuntimeUnlockCache()
    }

    @Test fun selectedKdbxNeverReadsAnotherRegisteredFileOrReturnsItsMirror() = runBlocking {
        val selected = fixture("Selected")
        val unrelated = fixture("Unrelated")
        val staleMirror = insertRoom("Stale KDBX mirror", keepassId = selected.id)
        insertRoom("Local")
        insertRoom("BW", bitwardenId = 41)
        val beforeSelected = counter(selected, "reads")
        val beforeUnrelated = counter(unrelated, "reads")
        command(unrelated, "block-read")
        try {
            val source = source()
            val rows = withTimeout(12_000) { measuredLoad("selected-kdbx", AutofillScope.KeePass(selected.id), source) }
            assertEquals(listOf(AutofillPasswordSource.nativeId(selected.id, selected.objectId.toString())), rows.map { it.id })
            assertFalse(rows.any { it.id == staleMirror })
            assertTrue(counter(selected, "reads") > beforeSelected)
            assertEquals(beforeUnrelated, counter(unrelated, "reads"))
            assertEquals(0, counter(unrelated, "read_blocked"))
            assertEquals(emptySet<Long>(), source.unavailableDatabaseIds)
            assertEquals(rows.single(), source.resolve(rows.single()))
            assertFalse(SessionManager.isUnlocked.value)
        } finally { command(unrelated, "release-read") }
        assertUnchanged(selected)
        assertUnchanged(unrelated)
    }

    @Test fun selectedBitwardenUsesScopedSqlAndPerformsZeroKdbxReads() = runBlocking {
        val first = fixture("First")
        val second = fixture("Second")
        val selected = insertRoom("BW41", bitwardenId = 41)
        insertRoom("BW42", bitwardenId = 42)
        insertRoom("Local")
        val counts = listOf(first, second).associateWith { counter(it, "reads") }
        scopedQueries.clear()
        listOf(first, second).forEach { command(it, "block-read") }
        try {
            val source = source()
            val rows = withTimeout(12_000) { measuredLoad("selected-bitwarden", AutofillScope.Bitwarden(41), source) }
            assertEquals(listOf(selected), rows.map { it.id })
            assertEquals("synthetic-secret", AutofillSecretResolver.resolvePasswordOrNull(security, rows.single()))
            assertTrue("The actual generated DAO must execute a bound scope query", scopedQueries.any { args ->
                args.contains("BITWARDEN") && args.any { it is Number && it.toLong() == 41L }
            })
            counts.forEach { (file, before) ->
                assertEquals(before, counter(file, "reads"))
                assertEquals(0, counter(file, "read_blocked"))
            }
            assertEquals(rows.single(), source.resolve(rows.single()))
        } finally { listOf(first, second).forEach { command(it, "release-read") } }
    }

    @Test fun allResultsEqualIndependentScopesWithoutDuplicatingMirrors() = runBlocking {
        val first = fixture("First")
        val second = fixture("Second")
        val local = insertRoom("Local")
        val bwFirst = insertRoom("BW41", bitwardenId = 41)
        val bwSecond = insertRoom("BW42", bitwardenId = 42)
        val mirrors = listOf(insertRoom("First mirror", keepassId = first.id), insertRoom("Second mirror", keepassId = second.id))
        val scopes = listOf("local" to AutofillScope.Local, "kdbx-1" to AutofillScope.KeePass(first.id),
            "kdbx-2" to AutofillScope.KeePass(second.id), "bitwarden-1" to AutofillScope.Bitwarden(41),
            "bitwarden-2" to AutofillScope.Bitwarden(42))
        val partitioned = scopes.flatMap { (label, scope) -> measuredLoad(label, scope) }
        val allSource = source()
        val all = measuredLoad("all-after-scoped", AutofillScope.All, allSource)
        assertEquals(5, all.size)
        assertEquals(partitioned.associateBy { it.id }, all.associateBy { it.id })
        assertEquals(setOf(local, bwFirst, bwSecond), all.filterNot(AutofillPasswordSource::isNative).map { it.id }.toSet())
        assertFalse(all.any { it.id in mirrors })
        assertTrue(allSource.areCurrent(all))
        assertUnchanged(first)
        assertUnchanged(second)
    }

    @Test fun clearingSourceDuringScopedNativeReadCannotPublishOrReviveIt() = runBlocking {
        val first = fixture("First")
        val second = fixture("Second")
        val source = source()
        command(first, "block-read")
        val pending = async(Dispatchers.IO) { source.load(AutofillScope.KeePass(first.id)) }
        try {
            withTimeout(10_000) { while (counter(first, "read_blocked") == 0) delay(10) }
            source.clear()
        } finally { command(first, "release-read") }
        assertTrue(withTimeout(10_000) { pending.await() }.isEmpty())
        val reads = counter(first, "reads")
        assertTrue(source.load(AutofillScope.KeePass(first.id)).isEmpty())
        assertEquals(reads, counter(first, "reads"))
        val otherRows = source().load(AutofillScope.KeePass(second.id))
        assertEquals(second.id, otherRows.single().keepassDatabaseId)
    }

    @Test fun explicitLibraryLockAndGlobalRelockNeverAuthorizeOldScope() = runBlocking {
        val first = fixture("First")
        val second = fixture("Second")
        val old = source()
        val prior = old.load(AutofillScope.KeePass(first.id)).single()
        service.lockDatabase(first.id)
        assertFalse(old.isCurrent(prior))
        assertNull(old.resolve(prior))
        val reads = counter(first, "reads")
        assertTrue(old.load(AutofillScope.KeePass(first.id)).isEmpty())
        assertEquals(reads, counter(first, "reads"))
        assertEquals(second.id, source().load(AutofillScope.KeePass(second.id)).single().keepassDatabaseId)
        val independentlyAuthenticated = source(allowLocked = true)
        val reopened = independentlyAuthenticated.load(AutofillScope.KeePass(first.id)).single()
        SecurityManager.clearRuntimeUnlockCache()
        check(security.unlockVaultWithPassword(masterPassword))
        assertFalse(independentlyAuthenticated.isCurrent(reopened))
        assertNull(independentlyAuthenticated.resolve(reopened))
        assertEquals(first.id, source(allowLocked = true).load(AutofillScope.KeePass(first.id)).single().keepassDatabaseId)
        assertFalse(SessionManager.isUnlocked.value)
    }

    @Test fun nativeLiteralPrefixesSurviveScopedLoadResolutionAndCapability() = runBlocking {
        val native = fixture("Literal", "MDK|literal-user", "C2|literal-password")
        val source = source()
        val row = source.load(AutofillScope.KeePass(native.id)).single()
        assertEquals("MDK|literal-user", AccountFillPolicy.resolveAccountIdentifier(row, security))
        assertEquals("C2|literal-password", AutofillSecretResolver.resolvePasswordOrNull(security, row))
        val selection = checkNotNull(source.selection(row))
        assertEquals(NativeAutofillStorage.KEEPASS, selection.storage)
        assertEquals(row, source().getSelected(selection))
        assertEquals(row, source.resolve(row))
        assertNull(source().getSelected(selection.copy(storage = NativeAutofillStorage.MDBX)))
        assertUnchanged(native)
    }

    private fun source(allowLocked: Boolean = false) = AutofillPasswordSource(context, security,
        SecurityManager.dataReadGeneration.value, active::get, database, allowLocked).also(sources::add)

    /** Same fixtures and real provider counts; sequential observations, not a controlled benchmark. */
    private suspend fun measuredLoad(label: String, scope: AutofillScope, currentSource: AutofillPasswordSource = source()): List<PasswordEntry> {
        val before = fixtures.associateWith { counter(it, "reads") }
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        val rows = currentSource.load(scope)
        val elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        val deltas = JSONArray()
        fixtures.forEachIndexed { index, fixture ->
            val after = counter(fixture, "reads")
            deltas.put(JSONObject().put("fixtureOrdinal", index + 1).put("readsBefore", before.getValue(fixture))
                .put("readsAfter", after).put("readsDelta", after - before.getValue(fixture)))
        }
        val result = JSONObject().put("label", label).put("elapsedMs", elapsedMs).put("resultCount", rows.size)
            .put("providerReads", deltas)
            .put("comparison", "Sequential same-fixture scope observations; synthetic BW Room mirrors; no cold/warm benchmark claim")
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "SCOPED328 $result\n") })
        return rows
    }

    private suspend fun insertRoom(title: String, keepassId: Long? = null, bitwardenId: Long? = null): Long =
        database.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = title, website = "https://scope.test",
            username = "synthetic-user", password = security.encryptData("synthetic-secret"), keepassDatabaseId = keepassId,
            bitwardenVaultId = bitwardenId, bitwardenCipherId = bitwardenId?.let { UUID.randomUUID().toString() }))

    private suspend fun fixture(title: String, username: String = "native-user", password: String = "native-password"): Fixture {
        val key = "Scoped328-native-fixture!"
        val objectId = UUID.randomUUID()
        val created = KeePassDatabase.Ver4x.create("Scoped328", Meta(generator = "Synthetic scope fixture"),
            Credentials.from(EncryptedValue.fromString(key)))
        val salt = when (val kdf = created.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        val vault = created.copy(header = created.header.copy(kdfParameters = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2d, salt = salt, parallelism = 1U,
            memory = 8uL * 1024uL * 1024uL, iterations = 2U, version = 0x13U,
            secretKey = null, associatedData = null))).modifyParentGroup {
            copy(entries = listOf(Entry(uuid = objectId, fields = EntryFields.of(
                "Title" to EntryValue.Plain(title), "UserName" to EntryValue.Plain(username),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString(password)),
                "URL" to EntryValue.Plain("https://scope.test")))))
        }
        val uri = Uri.parse("content://${instrumentation.context.packageName}.kdbx-delete/delete-${UUID.randomUUID()}.kdbx")
        val bytes = java.io.ByteArrayOutputStream().also { vault.encode(it) }.toByteArray()
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        val id = (UUID.randomUUID().mostSignificantBits and 0x0000ffffffffffffL) + 1_000_000_000L
        val fixture = Fixture(id, uri, bytes, objectId).also(fixtures::add)
        database.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(id = id, name = title,
            filePath = uri.toString(), storageLocation = KeePassStorageLocation.EXTERNAL,
            sourceType = KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI, encryptedPassword = security.encryptData(key)))
        return fixture
    }

    private fun command(fixture: Fixture, name: String) { context.contentResolver.call(fixture.uri, name, fixture.uri.toString(), null) }
    private fun counter(fixture: Fixture, name: String): Int = context.contentResolver.query(fixture.uri, arrayOf(name), null, null, null)!!
        .use { check(it.moveToFirst()); it.getInt(0) }
    private fun assertUnchanged(fixture: Fixture) = assertArrayEquals(fixture.bytes,
        context.contentResolver.openInputStream(fixture.uri)!!.use { it.readBytes() })
}
