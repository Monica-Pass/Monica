package takagi.ru.monica.keepass

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.KeePassEntryData
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.PasswordViewModel

/** Diagnostic harness: the real file service and the real password-page batch command. */
class KeePassDeleteInvestigationTest {
    @Test fun fortySelectedPasswordsUseOneFileCommit() = runBlocking {
        scenario { model, service, id, room, uri ->
            project(model, id, service.readPasswordEntries(id).getOrThrow())
            val targets = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).take(40)
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            fun writes(): Int = resolver.query(uri, arrayOf("commits"), null, null, null)!!.use {
                it.moveToFirst(); it.getInt(0)
            }
            val before = writes()
            val started = SystemClock.elapsedRealtime()
            // Shared command now used by both password-page and VaultV2 multi-selection.
            assertEquals(40, withContext(Dispatchers.Main) { model.deletePasswordEntriesBatch(targets) })
            assertEquals(260, room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).size)
            KeePassKdbxService.invalidateProcessCache(id)
            assertEquals(260, service.readPasswordEntries(id).getOrThrow().count { !it.isInRecycleBin })
            Log.i(TAG, "selection nativeDoneMs=${SystemClock.elapsedRealtime()-started} writes=${writes()-before}")
            assertEquals("One multi-selection should commit this KDBX once", 1, writes()-before)
        }
    }

    @Test fun localExternal300DeleteThenColdReload() = runBlocking {
        scenario { model, service, id, room, uri ->
            val snapshot = service.readPasswordEntries(id).getOrThrow()
            project(model, id, snapshot)
            val entries = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
            assertEquals(300, entries.size)
            val counts = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val observer = launch(Dispatchers.Default) {
                room.passwordEntryDao().getPasswordEntriesByKeePassDatabase(id).collect { counts += it.size }
            }
            while (counts.lastOrNull() != 300) delay(10)
            val start = SystemClock.elapsedRealtime()
            val deleted = withContext(Dispatchers.Main) { model.deletePasswordEntriesBatch(entries) }
            Log.i(TAG, "delete deleted=$deleted total=300 ms=${SystemClock.elapsedRealtime()-start}")
            assertEquals(300, deleted)
            while (counts.lastOrNull() != 0) delay(10)
            observer.cancelAndJoin()
            assertEquals("No intermediate partially deleted list", listOf(300, 0), counts.distinct())
            KeePassKdbxService.invalidateProcessCache(id)
            val after = service.readPasswordEntries(id).getOrThrow()
            assertEquals(0, after.count { !it.isInRecycleBin })
            project(model, id, after)
            assertEquals(0, room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).size)
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.query(uri, arrayOf("reads", "commits"), null, null, null)!!.use {
                it.moveToFirst(); Log.i(TAG, "file opens reads=${it.getInt(0)} commits=${it.getInt(1)} listCounts=$counts")
                assertEquals("One fixture creation and one delete save", 2, it.getInt(1))
            }
        }
    }

    @Test fun snapshotReadBeforeDeleteMustNotResurrectDeletedRows() = runBlocking {
        scenario { model, service, id, room, _ ->
            val oldSnapshot = service.loadWorkspace(id).getOrThrow()
            assertTrue(model.applyKeePassWorkspaceSnapshot(id, oldSnapshot, true, false))
            val entries = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
            assertEquals(300, withContext(Dispatchers.Main) { model.deletePasswordEntriesBatch(entries) })
            // Pause a real projection at its load->upsert boundary, then finish it after deletion.
            assertFalse(model.applyKeePassWorkspaceSnapshot(id, oldSnapshot, true, false))
            val visible = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).size
            val fileActive = service.readPasswordEntries(id).getOrThrow().count { !it.isInRecycleBin }
            Log.i(TAG, "stale projection visible=$visible fileActive=$fileActive")
            assertEquals("A delayed projection must not undo successful local deletion", 0, visible)
        }
    }

    @Test fun blockedSingleSaveDoesNotHideRowBeforeFileCommit() = runBlocking {
        scenario { model, service, id, room, uri ->
            project(model, id, service.readPasswordEntries(id).getOrThrow())
            val target = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).first()
            command(uri, "block-next-write")
            try {
                withContext(Dispatchers.Main) { model.deletePasswordEntry(target) }
                while (counter(uri, "blocked") != 1) delay(10)
                assertEquals(300, room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).size)
            } finally { command(uri, "release-write") }
            while (room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).size != 299) delay(10)
            KeePassKdbxService.invalidateProcessCache(id)
            assertEquals(299, service.readPasswordEntries(id).getOrThrow().count { !it.isInRecycleBin })
        }
    }

    @Test fun deniedWritePreservesFileAndEveryVisibleRow() = runBlocking {
        scenario { model, service, id, room, uri ->
            project(model, id, service.readPasswordEntries(id).getOrThrow())
            val targets = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val original = resolver.openInputStream(uri)!!.use { it.readBytes() }
            command(uri, "deny-write")
            assertEquals(0, withContext(Dispatchers.Main) { model.deletePasswordEntriesBatch(targets) })
            assertEquals(300, room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).size)
            assertArrayEquals(original, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue("No per-entry retries after a failed file save", counter(uri, "commits") <= 5)
        }
    }

    @Test fun staleSelectionCannotPartiallyDeleteDatabase() = runBlocking {
        scenario { model, service, id, room, uri ->
            project(model, id, service.readPasswordEntries(id).getOrThrow())
            val targets = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
            val stale = targets.dropLast(1) + targets.last().copy(keepassEntryUuid=UUID.randomUUID().toString())
            val before = counter(uri, "commits")
            assertEquals(0, withContext(Dispatchers.Main) { model.deletePasswordEntriesBatch(stale) })
            assertEquals(before, counter(uri, "commits"))
            assertEquals(300, room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).size)
            assertEquals(300, service.readPasswordEntries(id).getOrThrow().count { !it.isInRecycleBin })
        }
    }

    @Test fun coldProjectionPublishesWholeListOnly() = runBlocking {
        scenario { model, service, id, room, _ ->
            val counts = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val observer = launch(Dispatchers.Default) {
                room.passwordEntryDao().getPasswordEntriesByKeePassDatabase(id).collect { counts += it.size }
            }
            while (counts.lastOrNull() != 0) delay(10)
            project(model, id, service.readPasswordEntries(id).getOrThrow())
            while (counts.lastOrNull() != 300) delay(10)
            observer.cancelAndJoin()
            assertEquals(listOf(0, 300), counts.distinct())
            Log.i(TAG, "cold projection listCounts=$counts")
        }
    }

    private fun command(uri: Uri, name: String) {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(uri, name, uri.toString(), null)
    }

    private fun counter(uri: Uri, name: String): Int =
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.query(uri, arrayOf(name), null, null, null)!!.use {
            it.moveToFirst(); it.getInt(0)
        }

    @Test fun failedVerificationMustNotRollBackAnotherSessionsSuccessfulWrite() = runBlocking {
        scenario { _, service, id, _, uri ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val credentials = Credentials.from(EncryptedValue.fromString("Synthetic KDBX deletion test"))
            val original = context.contentResolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
            val otherSession = original.modifyParentGroup { copy(entries=entries.dropLast(10)) }
            val output = java.io.ByteArrayOutputStream().also { otherSession.encode(it) }.toByteArray()
            val targets = service.readPasswordEntries(id).getOrThrow().take(10).map {
                PasswordEntry(title=it.title, username=it.username, password=it.password, website=it.url,
                    keepassDatabaseId=id, keepassEntryUuid=it.entryUuid)
            }
            context.contentResolver.call(uri,"arm-other-writer",uri.toString(),android.os.Bundle().apply { putByteArray("bytes",output) })
            val ownResult = service.deletePasswordEntries(id, targets)
            assertTrue("Injected intervening write should fail verification",ownResult.isFailure)
            val actual = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val count = java.io.ByteArrayInputStream(actual).use { KeePassDatabase.decode(it,credentials).content.group.entries.size }
            Log.i(TAG,"intervening writer expected=290 actual=$count ownWriteSuccess=${ownResult.isSuccess}")
            assertArrayEquals("Rollback must not overwrite the other session's verified content", output, actual)
        }
    }

    private suspend fun project(model: PasswordViewModel, id: Long, entries: List<KeePassEntryData>) {
        val started = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Main) {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val method = PasswordViewModel::class.java.declaredMethods.single { it.name == "upsertKeePassEntries" }
                method.isAccessible = true
                try { method.invoke(model, id, entries, continuation) }
                catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
            }
        }
        Log.i(TAG, "projection rows=${entries.size} ms=${SystemClock.elapsedRealtime()-started}")
    }

    internal suspend fun scenario(
        securityOverride: SecurityManager? = null,
        databaseTransform: (KeePassDatabase) -> KeePassDatabase = { it },
        block: suspend (PasswordViewModel, KeePassKdbxService, Long, PasswordDatabase, Uri) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val uri = Uri.parse("content://${instrumentation.context.packageName}.kdbx-delete/delete-${UUID.randomUUID()}.kdbx")
        val security = securityOverride ?: SecurityManager(context)
        val registrationDb = PasswordDatabase.getDatabase(context)
        val local = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        val credentials = Credentials.from(EncryptedValue.fromString("Synthetic KDBX deletion test"))
        val created = KeePassDatabase.Ver4x.create("Root", Meta(generator="Deletion fixture"), credentials)
        val seed = when(val kdf=created.header.kdfParameters) { is KdfParameters.Aes -> kdf.seed; is KdfParameters.Argon2 -> kdf.salt }
        val database = created.copy(header=created.header.copy(kdfParameters=KdfParameters.Argon2(
            variant=KdfParameters.Argon2.Variant.Argon2d, salt=seed,
            parallelism=2U, memory=32uL * 1024uL * 1024uL, iterations=8U,
            version=0x13U, secretKey=null, associatedData=null
        )))
            .modifyParentGroup {
                copy(entries=(0 until 300).map { i ->
                    Entry(uuid=UUID.randomUUID(), fields=EntryFields.of(
                        "Title" to EntryValue.Plain("Synthetic $i"),
                        "UserName" to EntryValue.Plain("user-$i"),
                        "Password" to EntryValue.Encrypted(EncryptedValue.fromString("secret-$i"))
                    ))
                })
            }
        context.contentResolver.openOutputStream(uri, "wt")!!.use { databaseTransform(database).encode(it) }
        val id = registrationDb.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(
            name="Delete fixture", filePath=uri.toString(), storageLocation=KeePassStorageLocation.EXTERNAL,
            sourceType=KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI,
            encryptedPassword=security.encryptData("Synthetic KDBX deletion test")))
        val model = withContext(Dispatchers.Main) {
            PasswordViewModel(PasswordRepository(local.passwordEntryDao()), security, context=context,
                localKeePassDatabaseDao=registrationDb.localKeePassDatabaseDao(), strings=AppLocaleStringResolver(context)).also {
                // The repository is isolated, but context-backed settings belong to the
                // shared test installation. Do not inherit its last MDBX/KeePass filter,
                // which would bind an unrelated owner during compatibility mutations.
                // A neutral, non-All filter also prevents the async restore from winning.
                // Set only this model's memory state; never overwrite persisted settings.
                val field = PasswordViewModel::class.java.getDeclaredField("_categoryFilter")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val filter = field.get(it) as MutableStateFlow<CategoryFilter>
                filter.value = CategoryFilter.Local
            }
        }
        val service = KeePassKdbxService(context, registrationDb.localKeePassDatabaseDao(), security)
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var emissions = 0
        val observer = scope.launch { model.passwordEntries.collect { emissions++ } }
        val handler = Handler(Looper.getMainLooper())
        var last = SystemClock.uptimeMillis(); var maxGap = 0L
        val beat = object: Runnable { override fun run() { val now=SystemClock.uptimeMillis(); maxGap=maxOf(maxGap,now-last); last=now; handler.postDelayed(this,16) } }
        handler.post(beat)
        try { withTimeout(180000) { block(model,service,id,local,uri) } }
        finally {
            handler.removeCallbacks(beat)
            Log.i(TAG,"mainLooperMaxGapMs=$maxGap listEmissions=$emissions")
            observer.cancelAndJoin(); scope.cancel(); model.viewModelScope.cancel()
            KeePassKdbxService.invalidateProcessCache(id)
            registrationDb.localKeePassDatabaseDao().deleteDatabaseById(id)
            context.contentResolver.delete(uri,null,null)
            local.close()
            if (!wasUnlocked) SessionManager.markLocked()
        }
    }
    companion object { const val TAG="KdbxDeleteInvestigation" }
}
