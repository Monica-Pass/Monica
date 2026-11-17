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
import java.lang.reflect.InvocationTargetException
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.KeePassDatabaseSourceType
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.PasswordUrlRules
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService

/** Real Room/security/KDBX with synthetic rows, only in an explicitly owned secondary user. */
class AutofillBatchValidationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val authorized = AtomicBoolean(true)
    private val authorizationCalls = AtomicInteger()
    private val authorizationAction = AtomicReference<((Int) -> Unit)?>(null)
    private val queryAction = AtomicReference<(() -> Unit)?>(null)
    private val sources = mutableListOf<AutofillPasswordSource>()
    private val nativeFixtures = mutableListOf<NativeFixture>()
    private lateinit var database: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var service: KeePassKdbxService
    private val masterPassword get() = arguments.getString("autofillBatchPassword") ?: "Batch328-synthetic-master!"

    @Before fun prepare(): Unit = runBlocking {
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && arguments.getString("autofillBatchUser") == user.toString()) {
            "Use an owned secondary user and -e autofillBatchUser <id>"
        }
        security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(masterPassword))
        SessionManager.attachAppContext(context)
        SessionManager.markLocked()
        check(security.unlockVaultWithPassword(masterPassword)) { "Not the expected synthetic vault" }
        assertFalse(SessionManager.isUnlocked.value)
        // All fixture rows and registrations live in this independent real generated DAO/schema.
        database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java)
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (sqlQuery.startsWith("SELECT", ignoreCase = true) &&
                        sqlQuery.contains("FROM custom_fields", ignoreCase = true) &&
                        sqlQuery.contains(PasswordUrlRules.FIELD)) queryAction.get()?.invoke()
                }
            }, Executor { it.run() })
            .build()
        service = KeePassKdbxService(context, database.localKeePassDatabaseDao(), security)
    }

    @After fun cleanup() {
        authorizationAction.set(null)
        queryAction.set(null)
        sources.forEach { it.clear() }
        nativeFixtures.forEach {
            command(it, "release-read")
            KeePassKdbxService.invalidateProcessCache(it.id)
            context.contentResolver.delete(it.uri, null, null)
        }
        if (::database.isInitialized) database.close()
        if (::security.isInitialized) SecurityManager.clearRuntimeUnlockCache()
    }

    @Test fun stable550RowsMatchSingleChecksWithConstantAuthorizationCost() = runBlocking {
        insertRows(550)
        val source = source()
        val rows = load(source)
        assertEquals(550, rows.size)
        authorizationCalls.set(0)
        val original = rows.all(source::isCurrent)
        val individualCalls = authorizationCalls.get()
        authorizationCalls.set(0)
        assertEquals(original, areCurrent(source, rows))
        assertTrue(original)
        val batchCalls = authorizationCalls.get()
        authorizationCalls.set(0)
        assertTrue(areCurrent(source, rows.take(1)))
        val singleBatchCalls = authorizationCalls.get()
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "BATCH328 rows=550 individualAuthorizationCalls=$individualCalls batchAuthorizationCalls=$batchCalls singleBatchAuthorizationCalls=$singleBatchCalls\n")
        })
        assertEquals("Authorization cost must not scale with record count", singleBatchCalls, batchCalls)
        assertTrue("550 original checks must actually run", individualCalls >= 550)
        assertTrue("A nonempty batch needs fresh bounded authorization", batchCalls in 2..4)
        assertEquals("synthetic-password", security.decryptData(rows.first().password))
    }

    @Test fun emptyBatchIsTrueBeforeLoadAndAfterRevocationWithoutAuthorization() {
        authorized.set(false)
        val source = source()
        assertTrue(areCurrent(source, emptyList()))
        source.clear()
        assertTrue(areCurrent(source, emptyList()))
        assertEquals(0, authorizationCalls.get())
    }

    @Test fun changedDeletedArchivedAndForgedUiRowsMatchOriginalRejection() = runBlocking {
        insertRows(2)
        val mirrorSeed = row(998).copy(mdbxDatabaseId = 703)
        val mirror = mirrorSeed.copy(id = database.passwordEntryDao().insertPasswordEntry(mirrorSeed))
        val forged = row(999).copy(id = Long.MAX_VALUE)
        assertTrue(KeePassMonicaPasswordStore.isUiId(forged.id))
        database.passwordEntryDao().insertPasswordEntry(forged)
        val source = source()
        val rows = load(source)
        assertEquals("Only the two genuine local rows may be loaded", 2, rows.size)
        assertTrue(rows.all { it.id != forged.id && it.id != mirror.id && it.mdbxDatabaseId == null })
        // Reserved UI IDs and MDBX Room mirrors are rejected before becoming candidates.
        // Their absence must not prevent testing forged callback inputs at the final gate.
        val current = rows.first()
        val invalid = listOf(current.copy(title = "changed"), current.copy(username = "changed"),
            current.copy(password = "changed"), current.copy(isDeleted = true), current.copy(isArchived = true),
            current.copy(keepassDatabaseId = 42, keepassEntryUuid = "not-a-uuid"),
            forged, mirror)
        assertTrue(areCurrent(source, listOf(current)))
        invalid.forEach {
            assertFalse(source.isCurrent(it))
            assertEquals(source.isCurrent(it), areCurrent(source, listOf(current, it)))
        }
        for (excluded in listOf(forged, mirror)) {
            assertNull(source.get(excluded.id))
            assertNull(source.resolve(excluded))
            assertEquals("Autofill must not delete excluded Room rows", excluded,
                database.passwordEntryDao().getPasswordEntryById(excluded.id))
        }
        assertEquals(current, source.resolve(current))
    }

    @Test fun clearDuringTraversalCompletesImmediatelyAndCannotReviveRequest() = runBlocking {
        insertRows(3)
        val source = source()
        val rows = load(source)
        assertFalse(duringTraversal(source, rows) { source.clear() })
        assertTrue(load(source).isEmpty())
        assertNull(source.get(rows.first().id))
        assertNull(source.resolve(rows.first()))
        assertFalse(source.saveUriBinding(rows.first(), "org.example.revoked328", null))
        val fresh = source()
        assertTrue(areCurrent(fresh, load(fresh)))
    }

    @Test fun authorizationRevokedDuringTraversalRejectsResolutionAndWrite() = runBlocking {
        insertRows(3)
        val source = source()
        val rows = load(source)
        assertFalse(duringTraversal(source, rows) { authorized.set(false) })
        assertNull(source.resolve(rows.first()))
        assertFalse(source.saveUriBinding(rows.first(), "org.example.revoked328", null))
        assertEquals(rows.first(), database.passwordEntryDao().getPasswordEntryById(rows.first().id))
    }

    @Test fun generationRevocationThenPasswordUnlockDoesNotReviveOldBatch() = runBlocking {
        insertRows(3)
        val source = source()
        val rows = load(source)
        assertFalse(duringTraversal(source, rows) {
            SecurityManager.clearRuntimeUnlockCache()
            check(security.unlockVaultWithPassword(masterPassword))
        })
        assertFalse(areCurrent(source, rows))
        assertNull(source.resolve(rows.first()))
        val fresh = source()
        assertTrue(areCurrent(fresh, load(fresh)))
        assertFalse(SessionManager.isUnlocked.value)
    }

    @Test fun equalRoomMapReloadDuringTraversalRejectsCrossSnapshotBatch() = runBlocking {
        insertRows(3)
        val source = source()
        val rows = load(source)
        assertFalse(duringTraversal(source, rows) { assertEquals(rows, runBlocking { load(source) }) })
        assertTrue(areCurrent(source, rows))
        val changed = rows.first().copy(notes = "synthetic edit after reload")
        database.passwordEntryDao().updatePasswordEntry(changed)
        val latest = load(source)
        assertFalse(areCurrent(source, rows))
        assertNull(source.resolve(rows.first()))
        assertTrue(areCurrent(source, latest))
    }

    @Test fun clearInsideFinalAuthorizationIsCheckedBeforeAcceptingBatch() = runBlocking {
        insertRows(1)
        val source = source()
        val rows = load(source)
        authorizationCalls.set(0)
        authorizationAction.set { ordinal -> if (ordinal == 2) source.clear() }
        try { assertFalse(areCurrent(source, rows)) } finally { authorizationAction.set(null) }
        assertEquals(2, authorizationCalls.get())
    }

    @Test fun urlRulesPreserveAbsentVersusDeniedAndRejectDuplicateInvalidId() = runBlocking {
        insertRows(6)
        val source = source()
        val rows = load(source).sortedBy { it.id }
        val never = listOf(PasswordUrlRules.Rule(WEBSITE, PasswordUrlRules.Mode.NEVER))
        database.customFieldDao().insertAll(listOf(
            CustomField(entryId = rows[1].id, title = PasswordUrlRules.FIELD, value = "broken"),
            CustomField(entryId = rows[2].id, title = PasswordUrlRules.FIELD, value = "{\"version\":99,\"urls\":[]}"),
            CustomField(entryId = rows[3].id, title = PasswordUrlRules.FIELD, value = PasswordUrlRules.encode(never)),
            CustomField(entryId = rows[4].id, title = PasswordUrlRules.FIELD, value = PasswordUrlRules.encode(never)),
            CustomField(entryId = rows[4].id, title = PasswordUrlRules.FIELD, value = PasswordUrlRules.encode(never)),
        ))
        val rules = source.urlRules(rows)
        assertFalse(rules.containsKey(rows[0].id))
        assertFalse(rules.containsKey(rows[5].id))
        listOf(1, 2, 4).forEach { assertEquals(emptyList<PasswordUrlRules.Rule>(), rules[rows[it].id]) }
        assertEquals(never, rules[rows[3].id])
        assertEquals(setOf(rows[0].id, rows[5].id), matchedIds(rows, rules))
        val invalid = rows[0].copy(username = "stale")
        for (mixed in listOf(listOf(invalid, rows[0], rows[5]), listOf(rows[0], invalid, rows[5]))) {
            val mixedRules = source.urlRules(mixed)
            assertEquals(emptyList<PasswordUrlRules.Rule>(), mixedRules[rows[0].id])
            assertFalse(mixedRules.containsKey(rows[5].id))
            assertEquals(setOf(rows[5].id), matchedIds(mixed, mixedRules))
        }
    }

    @Test fun revocationInsideSecondUrlSqlChunkExplicitlyDeniesAll801Rows() = runBlocking {
        insertRows(801)
        val source = source()
        val rows = load(source)
        assertFalse(matchedIds(rows, emptyMap()).isEmpty())
        val rules = duringUrlQuery(source, rows, ordinal = 2) { authorized.set(false) }
        assertDenied(rows, rules)
    }

    @Test fun roomMapReloadDuringUrlSqlExplicitlyDeniesFormerSnapshot() = runBlocking {
        insertRows(3)
        val source = source()
        val rows = load(source)
        val rules = duringUrlQuery(source, rows) { assertEquals(rows, runBlocking { load(source) }) }
        assertDenied(rows, rules)
        assertTrue(source.urlRules(rows).isEmpty())
    }

    @Test fun generationChangeDuringUrlSqlCannotBroadenDefaultWebsiteMatching() = runBlocking {
        insertRows(3)
        val source = source()
        val rows = load(source)
        val rules = duringUrlQuery(source, rows) {
            SecurityManager.clearRuntimeUnlockCache()
            check(security.unlockVaultWithPassword(masterPassword))
        }
        assertDenied(rows, rules)
    }

    @Test fun nativeAndMixedBatchPreserveClassificationAndOnlyRelevantLocks() = runBlocking {
        insertRows(2)
        val first = registerNative()
        val second = registerNative()
        val source = source()
        val loaded = load(source)
        val native = loaded.single { it.keepassDatabaseId == first.id }
        assertTrue(AutofillPasswordSource.isNative(native))
        val mixed = loaded.filter { it.keepassDatabaseId != second.id }
        assertTrue(mixed.all(source::isCurrent))
        assertTrue(areCurrent(source, mixed))
        assertTrue(duringTraversal(source, mixed) { service.lockDatabase(second.id) })
        val malformed = native.copy(keepassEntryUuid = "not-a-uuid")
        assertFalse(source.isCurrent(malformed))
        assertFalse(areCurrent(source, listOf(malformed)))
        assertFalse(duringTraversal(source, mixed) { service.lockDatabase(first.id) })
        assertNull(source.resolve(native))
        assertTrue(load(source()).none { it.keepassDatabaseId == first.id })
        val verified = source(allowLocked = true)
        val verifiedRows = load(verified)
        assertTrue(verifiedRows.any { it.keepassDatabaseId == first.id })
        assertTrue(areCurrent(verified, verifiedRows))
        assertNativeUnchanged(first)
        assertNativeUnchanged(second)
    }

    @Test fun nativeMapRefreshDuringTraversalRejectsCrossSnapshotBatch() = runBlocking {
        val fixture = registerNative()
        val source = source()
        val rows = load(source)
        val native = rows.single()
        val selection = requireNotNull(source.selection(native))
        assertFalse(duringTraversal(source, rows) {
            assertEquals(native, runBlocking { source.getSelected(selection) })
        })
        assertTrue(areCurrent(source, rows))
        assertNativeUnchanged(fixture)
    }

    @Test fun nativeLockDuringUrlSqlDeniesMixedResultsAndKeepsFileUnchanged() = runBlocking {
        insertRows(2)
        val fixture = registerNative()
        val source = source()
        val rows = load(source)
        assertEquals(3, rows.size)
        assertFalse(matchedIds(rows, emptyMap()).isEmpty())
        val rules = duringUrlQuery(source, rows) { service.lockDatabase(fixture.id) }
        assertDenied(rows, rules)
        assertNativeUnchanged(fixture)
    }

    @Test fun localOnlyAvoidsSlowAndUnreadableRegisteredKdbxWhileDefaultStillReads() = runBlocking {
        insertRows(2)
        val fixture = registerNative()
        val baselineReads = counter(fixture, "reads")
        command(fixture, "block-read")
        val localSource = source()
        val localRead = worker { runBlocking { load(localSource, localOnly = true) } }
        try {
            val localRows = try { localRead.get(5, TimeUnit.SECONDS) } catch (_: TimeoutException) {
                fail("Local-only load reached the deliberately blocked native provider")
                emptyList()
            }
            assertEquals(2, localRows.size)
            assertTrue(localRows.all { it.isLocalOnlyEntry() })
            assertEquals("Local-only must perform zero native opens", baselineReads, counter(fixture, "reads"))
            assertEquals(0, counter(fixture, "read_blocked"))
            val defaultSource = source()
            val defaultRead = worker { runBlocking { load(defaultSource) } }
            try {
                withTimeout(10_000) { while (counter(fixture, "read_blocked") == 0) delay(10) }
                assertFalse("Provider barrier must synchronously hold the actual default load", defaultRead.isDone)
            } finally {
                command(fixture, "release-read")
                defaultRead.get(20, TimeUnit.SECONDS)
            }
            val all = defaultRead.get(20, TimeUnit.SECONDS)
            assertEquals(3, all.size)
            assertTrue(all.any { it.keepassDatabaseId == fixture.id })
            assertTrue(counter(fixture, "reads") > baselineReads)
        } finally {
            command(fixture, "release-read")
            localRead.get(20, TimeUnit.SECONDS)
        }
        context.contentResolver.openOutputStream(fixture.uri, "wt")!!.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        KeePassKdbxService.invalidateProcessCache(fixture.id)
        val unreadableBaseline = counter(fixture, "reads")
        assertEquals(2, load(source(), localOnly = true).size)
        assertEquals(unreadableBaseline, counter(fixture, "reads"))
        val defaultSource = source()
        assertTrue(load(defaultSource).none { it.keepassDatabaseId == fixture.id })
        assertTrue(fixture.id in defaultSource.unavailableDatabaseIds)
        assertTrue(counter(fixture, "reads") > unreadableBaseline)
    }

    @Test fun localOnlyExcludesOtherOwnersAndRetainsLegacyBlankBitwardenBinding() = runBlocking {
        val seeds = listOf(row(0), row(1).copy(bitwardenVaultId = 701, bitwardenCipherId = "native-cipher"),
            row(2).copy(keepassDatabaseId = 702), row(3).copy(mdbxDatabaseId = 703),
            row(4).copy(bitwardenVaultId = 704, bitwardenCipherId = ""),
            row(5).copy(isArchived = true), row(6).copy(isDeleted = true),
            row(7).copy(loginType = " steam_mafile "),
            row(8).copy(keepassDatabaseId = 705, bitwardenVaultId = 706),
            row(9).copy(bitwardenVaultId = 707, bitwardenCipherId = " \t\n"))
        val ids = database.passwordEntryDao().insertPasswordEntries(seeds)
        val source = source()
        val rows = load(source, localOnly = true)
        assertEquals(setOf(ids[0], ids[4], ids[8], ids[9]), rows.map { it.id }.toSet())
        assertTrue(rows.all { it.isLocalOnlyEntry() })
        assertTrue(areCurrent(source, rows))
        assertEquals(emptySet<Long>(), source.unavailableDatabaseIds)
    }

    private fun source(allowLocked: Boolean = false) = AutofillPasswordSource(context, security,
        SecurityManager.dataReadGeneration.value, {
            val ordinal = authorizationCalls.incrementAndGet()
            authorizationAction.get()?.invoke(ordinal)
            authorized.get()
        }, database, allowLocked).also { sources += it }

    private fun row(index: Int) = PasswordEntry(title = "Batch328 synthetic $index", website = WEBSITE,
        username = "synthetic-$index", password = security.encryptData("synthetic-password"),
        createdAt = Date(1_000), updatedAt = Date(1_000))

    private suspend fun insertRows(count: Int) {
        val seed = row(0)
        database.passwordEntryDao().insertPasswordEntries((0 until count).map {
            seed.copy(title = "Batch328 synthetic $it", username = "synthetic-$it")
        })
    }

    // Reflection allows this same test APK to exercise old source: its original all(isCurrent)
    // is the negative control for cost/reference changes, and its original load exposes native I/O.
    private fun areCurrent(source: AutofillPasswordSource, rows: List<PasswordEntry>): Boolean {
        val method = source.javaClass.methods.firstOrNull { it.name == "areCurrent" && it.parameterTypes.size == 1 }
        return if (method == null) rows.all(source::isCurrent) else invoke(method, source, rows) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun load(source: AutofillPasswordSource, localOnly: Boolean = false): List<PasswordEntry> =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = source.javaClass.methods.firstOrNull { it.name == "load" &&
                it.parameterTypes.size == 2 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType }
            if (method != null) invoke(method, source, localOnly, continuation)
            else invoke(source.javaClass.methods.single { it.name == "load" && it.parameterTypes.size == 1 },
                source, continuation)
        }

    private fun invoke(method: java.lang.reflect.Method, target: Any, vararg args: Any?): Any? =
        try { method.invoke(target, *args) } catch (error: InvocationTargetException) { throw error.targetException }

    private fun duringTraversal(source: AutofillPasswordSource, rows: List<PasswordEntry>, action: () -> Unit): Boolean {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = AtomicBoolean()
        val paused = object : AbstractList<PasswordEntry>() {
            override val size get() = rows.size
            override fun get(index: Int): PasswordEntry {
                if (index == rows.lastIndex && once.compareAndSet(false, true)) {
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "Traversal barrier was not released" }
                }
                return rows[index]
            }
        }
        val validation = worker { areCurrent(source, paused) }
        var mutation: FutureTask<Unit>? = null
        try {
            assertTrue("Batch must reach the deterministic traversal barrier", entered.await(10, TimeUnit.SECONDS))
            mutation = worker(action)
            mutation.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            mutation?.get(20, TimeUnit.SECONDS)
            validation.get(20, TimeUnit.SECONDS)
        }
        return validation.get(20, TimeUnit.SECONDS)
    }

    private fun duringUrlQuery(source: AutofillPasswordSource, rows: List<PasswordEntry>, ordinal: Int = 1,
        action: () -> Unit): Map<Long, List<PasswordUrlRules.Rule>> {
        val queries = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        queryAction.set {
            if (queries.incrementAndGet() == ordinal) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "SQL barrier was not released" }
            }
        }
        val read = worker { runBlocking { source.urlRules(rows) } }
        var mutation: FutureTask<Unit>? = null
        try {
            assertTrue("Actual URL SQL chunk $ordinal must be reached", entered.await(10, TimeUnit.SECONDS))
            assertFalse("Direct query callback must hold the actual SQL path", read.isDone)
            mutation = worker(action)
            mutation.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            queryAction.set(null)
            mutation?.get(20, TimeUnit.SECONDS)
            read.get(20, TimeUnit.SECONDS)
        }
        return read.get(20, TimeUnit.SECONDS)
    }

    private fun <T> worker(action: () -> T): FutureTask<T> = FutureTask(action).also {
        Thread(it, "batch328-test-worker").apply { isDaemon = true }.start()
    }

    private fun matchedIds(rows: List<PasswordEntry>, rules: Map<Long, List<PasswordUrlRules.Rule>>): Set<Long> {
        val matcher = BitwardenLikeAutofillMatcherNg()
        return try {
            matcher.match(rows, "org.example.batch328", "batch328.example.test",
                config = BitwardenLikeAutofillMatcherNg.Config(maxSuggestions = 1000), urlRules = rules)
                .map { it.id }.toSet()
        } finally { matcher.clear() }
    }

    private fun assertDenied(rows: List<PasswordEntry>, rules: Map<Long, List<PasswordUrlRules.Rule>>) {
        assertEquals(rows.map { it.id }.toSet(), rules.keys)
        assertTrue(rules.values.all { it.isEmpty() })
        assertTrue("Explicitly denied rows must not regain default website matches", matchedIds(rows, rules).isEmpty())
    }

    private data class NativeFixture(val id: Long, val uri: Uri, val bytes: ByteArray)

    private suspend fun registerNative(): NativeFixture {
        val credentials = Credentials.from(EncryptedValue.fromString("Batch328-native-password!"))
        val created = KeePassDatabase.Ver4x.create("Batch328", Meta(generator = "Synthetic batch fixture"), credentials)
        val seed = when (val kdf = created.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        val fixture = created.copy(header = created.header.copy(kdfParameters = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2d, salt = seed, parallelism = 2U,
            memory = 32uL * 1024uL * 1024uL, iterations = 8U, version = 0x13U,
            secretKey = null, associatedData = null))).modifyParentGroup {
            copy(entries = listOf(Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(
                "Title" to EntryValue.Plain("Batch328 native"), "UserName" to EntryValue.Plain("synthetic-native"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("native-password")),
                "URL" to EntryValue.Plain(WEBSITE)))))
        }
        val uri = Uri.parse("content://${instrumentation.context.packageName}.kdbx-delete/delete-${UUID.randomUUID()}.kdbx")
        val bytes = java.io.ByteArrayOutputStream().also { fixture.encode(it) }.toByteArray()
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        // Avoid collisions with process-wide native caches belonging to other test classes.
        val id = (UUID.randomUUID().mostSignificantBits and 0x0000ffffffffffffL) + 1_000_000_000L
        val owned = NativeFixture(id, uri, bytes)
        nativeFixtures += owned
        database.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(id = id, name = "Batch328 synthetic",
            filePath = uri.toString(), storageLocation = KeePassStorageLocation.EXTERNAL,
            sourceType = KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI,
            encryptedPassword = security.encryptData("Batch328-native-password!")))
        return owned
    }

    private fun assertNativeUnchanged(fixture: NativeFixture) {
        assertArrayEquals(fixture.bytes, context.contentResolver.openInputStream(fixture.uri)!!.use { it.readBytes() })
    }

    private fun command(fixture: NativeFixture, name: String) {
        context.contentResolver.call(fixture.uri, name, fixture.uri.toString(), null)
    }

    private fun counter(fixture: NativeFixture, name: String): Int = context.contentResolver
        .query(fixture.uri, arrayOf(name), null, null, null)!!.use { check(it.moveToFirst()); it.getInt(0) }

    companion object { private const val WEBSITE = "https://batch328.example.test" }
}
