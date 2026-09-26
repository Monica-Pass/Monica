package takagi.ru.monica.repository

import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import uniffi.mdbx_ffi.createPortableBackup

@RunWith(AndroidJUnit4::class)
class Mdbx2ExternalPublicationInstrumentedTest {
    @Test
    fun divergentExternalCopiesPreserveIndependentEntries() = runBlocking {
        val fixture = createExternalFixture()
        try {
            fixture.selectDevice("external-test-device-a")
            fixture.repositoryA.upsertPasswords(
                listOf(
                    testEntry(
                        id = FIRST_ENTRY_ID,
                        databaseId = fixture.databaseAId,
                        title = "Entry from A"
                    )
                )
            )
            fixture.selectDevice("external-test-device-b")
            fixture.repositoryB.upsertPasswords(
                listOf(
                    testEntry(
                        id = SECOND_ENTRY_ID,
                        databaseId = fixture.databaseBId,
                        title = "Entry from B"
                    )
                )
            )

            val finalDatabaseId = fixture.importPublishedVault()
            val titles = fixture.repositoryA.readStoredEntries(finalDatabaseId)
                .filterNot { it.deleted }
                .mapTo(mutableSetOf()) { it.title }

            assertTrue("Published vault must retain A's entry", "Entry from A" in titles)
            assertTrue("Published vault must retain B's entry", "Entry from B" in titles)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun concurrentExternalCopiesPreserveIndependentEntries() = runBlocking {
        val fixture = createExternalFixture()
        try {
            fixture.selectDevice("concurrent-external-test-device-a")
            fixture.repositoryA.readStoredEntries(fixture.databaseAId)
            fixture.selectDevice("concurrent-external-test-device-b")
            fixture.repositoryB.readStoredEntries(fixture.databaseBId)
            val start = CompletableDeferred<Unit>()
            val writerA = async(Dispatchers.IO) {
                start.await()
                fixture.repositoryA.upsertPasswords(
                    listOf(
                        testEntry(
                            id = FIRST_ENTRY_ID,
                            databaseId = fixture.databaseAId,
                            title = "Concurrent entry from A"
                        )
                    )
                )
            }
            val writerB = async(Dispatchers.IO) {
                start.await()
                fixture.repositoryB.upsertPasswords(
                    listOf(
                        testEntry(
                            id = SECOND_ENTRY_ID,
                            databaseId = fixture.databaseBId,
                            title = "Concurrent entry from B"
                        )
                    )
                )
            }
            start.complete(Unit)
            writerA.await()
            writerB.await()

            val finalDatabaseId = fixture.importPublishedVault()
            val titles = fixture.repositoryA.readStoredEntries(finalDatabaseId)
                .filterNot { it.deleted }
                .mapTo(mutableSetOf()) { it.title }
            assertTrue("Concurrent entry from A" in titles)
            assertTrue("Concurrent entry from B" in titles)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun divergentExternalCopiesSurfaceSameEntryConflict() = runBlocking {
        val fixture = createExternalFixture(
            baseEntries = listOf(
                testEntry(
                    id = SHARED_ENTRY_ID,
                    databaseId = 0L,
                    title = "Shared base"
                )
            )
        )
        try {
            fixture.selectDevice("external-test-device-a")
            fixture.repositoryA.upsertPasswords(
                listOf(
                    testEntry(
                        id = SHARED_ENTRY_ID,
                        databaseId = fixture.databaseAId,
                        title = "Shared from A"
                    )
                )
            )
            fixture.selectDevice("external-test-device-b")
            fixture.repositoryB.upsertPasswords(
                listOf(
                    testEntry(
                        id = SHARED_ENTRY_ID,
                        databaseId = fixture.databaseBId,
                        title = "Shared from B"
                    )
                )
            )

            val finalDatabaseId = fixture.importPublishedVault()
            val conflicts = fixture.repositoryA.listConflicts(finalDatabaseId)

            assertFalse("Divergent updates must create an unresolved conflict", conflicts.isEmpty())
            assertTrue(conflicts.all { it.localCommitId.isNotBlank() })
            assertTrue(conflicts.all { it.incomingCommitId.isNotBlank() })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun externalChangeDuringMergeAbortsWithoutStaleOverwrite() = runBlocking {
        val fixture = createExternalFixture()
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database = requireNotNull(fixture.databaseA())
            var mergeCalls = 0
            val copyBBytes = fixture.copyB.readBytes().also { bytes ->
                bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
            }
            try {
                Mdbx2ExternalStorage(context).publishWithMerge(
                    database = database,
                    workingCopy = fixture.copyA
                ) {
                    mergeCalls += 1
                    if (mergeCalls == 1) {
                        context.contentResolver.openOutputStream(
                            StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false),
                            "rwt"
                        )?.use { output -> output.write(copyBBytes) }
                            ?: error("Unable to mutate test external document")
                    } else {
                        error("stop after detecting external revision")
                    }
                    0
                }
                fail("Publication must stop when the remote revision changes")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("stop after detecting"))
            }
            val publishedBytes = context.contentResolver.openInputStream(
                StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false)
            )?.use { it.readBytes() } ?: error("Published test document cannot be read")
            assertTrue(publishedBytes.contentEquals(copyBBytes))
            assertEquals(2, mergeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun externalPublicationLeaseSerializesConcurrentWriters(): Unit = runBlocking {
        val fixture = createExternalFixture()
        try {
            val databaseA = requireNotNull(fixture.databaseA())
            val databaseB = requireNotNull(fixture.databaseB())
            val storageA = Mdbx2ExternalStorage(
                InstrumentationRegistry.getInstrumentation().targetContext
            )
            val storageB = Mdbx2ExternalStorage(
                InstrumentationRegistry.getInstrumentation().targetContext
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async(Dispatchers.IO) {
                storageA.publishWithMerge(databaseA, fixture.copyA) {
                    entered.complete(Unit)
                    release.await()
                    0
                }
            }
            entered.await()
            val second = async(Dispatchers.IO) {
                storageB.publishWithMerge(databaseB, fixture.copyB) { 0 }
            }
            delay(250L)
            assertFalse("Second writer must wait for the document lease", second.isCompleted)
            release.complete(Unit)
            first.await()
            second.await()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun externalBlobSidecarMergeKeepsUnionAndRejectsDifferentBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = Mdbx2ExternalStorage(context)
        val root = File(context.cacheDir, "mdbx2-sidecar-test-${UUID.randomUUID()}")
        val source = File(root, "source.mdbx")
        val target = File(root, "target.mdbx")
        try {
            source.parentFile?.mkdirs()
            source.writeBytes(byteArrayOf(1))
            target.writeBytes(byteArrayOf(2))
            File("${source.absolutePath}.blobs/a").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            File("${target.absolutePath}.blobs/b").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(4, 5, 6))
            }
            storage.mergeSidecarIntoWorkingCopy(source, target)
            assertTrue(File("${target.absolutePath}.blobs/a").isFile)
            assertTrue(File("${target.absolutePath}.blobs/b").isFile)

            File("${target.absolutePath}.blobs/a").writeBytes(byteArrayOf(9))
            try {
                storage.mergeSidecarIntoWorkingCopy(source, target)
                fail("Different bytes at one Blob path must be rejected")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("conflicting Blob bytes"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publicationUsesActualTargetBytesWhenProviderReportsStaleSize() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val room = PasswordDatabase.getDatabase(context)
        val repository = Mdbx2Repository(
            context = context,
            databaseDao = room.localMdbxDatabaseDao(),
            securityManager = SecurityManager(context)
        )
        val workingCopy = repository.createInitializedVaultFile(
            tigaMode = MdbxTigaMode.MULTI,
            password = "stale-size-publication-test"
        )
        try {
            resolver.delete(StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false), null, null)
            invokePublishMainFile(
                storage = Mdbx2ExternalStorage(context),
                targetUri = StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false),
                workingCopy = workingCopy
            )

            val reportedSize = resolver.query(
                StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false),
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
            val actualSize = resolver.openInputStream(StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false))?.use { input ->
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                }
                total
            } ?: error("Published test document cannot be read")

            assertEquals(StaleSizeContentProvider.STALE_REPORTED_SIZE, reportedSize)
            assertTrue(actualSize > StaleSizeContentProvider.STALE_REPORTED_SIZE)
        } finally {
            resolver.delete(StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false), null, null)
            repository.deleteOwnedVaultFile(workingCopy)
        }
    }

    @Test
    fun publicationStillRejectsBytesThatDifferFromPortableBackup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val room = PasswordDatabase.getDatabase(context)
        val repository = Mdbx2Repository(
            context = context,
            databaseDao = room.localMdbxDatabaseDao(),
            securityManager = SecurityManager(context)
        )
        val workingCopy = repository.createInitializedVaultFile(
            tigaMode = MdbxTigaMode.MULTI,
            password = "corrupted-publication-test"
        )
        try {
            resolver.delete(StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, true), null, null)
            try {
                invokePublishMainFile(
                    storage = Mdbx2ExternalStorage(context),
                    targetUri = StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, true),
                    workingCopy = workingCopy
                )
                fail("Publication must reject bytes that differ from the portable backup")
            } catch (error: IllegalStateException) {
                assertTrue(
                    error.message.orEmpty().contains(
                        "External MDBX2 file digest verification failed"
                    )
                )
            }
        } finally {
            resolver.delete(StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, true), null, null)
            repository.deleteOwnedVaultFile(workingCopy)
        }
    }

    private fun invokePublishMainFile(
        storage: Mdbx2ExternalStorage,
        targetUri: Uri,
        workingCopy: File
    ) {
        val method = Mdbx2ExternalStorage::class.java.getDeclaredMethod(
            "publishMainFile",
            Uri::class.java,
            File::class.java
        ).apply { isAccessible = true }
        try {
            method.invoke(storage, targetUri, workingCopy)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private suspend fun createExternalFixture(
        baseEntries: List<PasswordEntry> = emptyList()
    ): ExternalFixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val baseRepository = Mdbx2Repository(context, databaseDao, securityManager)
        val repositoryA = Mdbx2Repository(context, databaseDao, securityManager)
        val repositoryB = Mdbx2Repository(context, databaseDao, securityManager)
        val password = "local-external-divergence-${UUID.randomUUID()}"
        val encryptedPassword = securityManager.encryptData(password)
        val baseFile = baseRepository.createInitializedVaultFile(MdbxTigaMode.MULTI, password)
        val ownedDirectory = requireNotNull(baseFile.parentFile)
        val copyA = File(ownedDirectory, "${UUID.randomUUID()}.mdbx")
        val copyB = File(ownedDirectory, "${UUID.randomUUID()}.mdbx")
        val databaseIds = mutableListOf<Long>()

        try {
            val baseDatabaseId = databaseDao.insertDatabase(
                localDatabase(
                    name = "External divergence base",
                    filePath = baseFile.absolutePath,
                    workingCopyPath = baseFile.absolutePath,
                    sourceType = MdbxSourceType.LOCAL_INTERNAL,
                    storageLocation = MdbxStorageLocation.INTERNAL,
                    encryptedPassword = encryptedPassword
                )
            )
            databaseIds += baseDatabaseId
            if (baseEntries.isNotEmpty()) {
                baseRepository.upsertPasswords(
                    baseEntries.map { entry -> entry.copy(mdbxDatabaseId = baseDatabaseId) }
                )
            }
            createPortableBackup(baseFile.absolutePath, copyA.absolutePath)
            createPortableBackup(baseFile.absolutePath, copyB.absolutePath)
            resolver.delete(StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false), null, null)
            invokePublishMainFile(
                storage = Mdbx2ExternalStorage(context),
                targetUri = StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false),
                workingCopy = baseFile
            )

            val databaseAId = databaseDao.insertDatabase(
                localDatabase(
                    name = "External divergence A",
                    filePath = StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false).toString(),
                    workingCopyPath = copyA.absolutePath,
                    sourceType = MdbxSourceType.LOCAL_EXTERNAL,
                    storageLocation = MdbxStorageLocation.EXTERNAL,
                    encryptedPassword = encryptedPassword
                )
            )
            val databaseBId = databaseDao.insertDatabase(
                localDatabase(
                    name = "External divergence B",
                    filePath = StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false).toString(),
                    workingCopyPath = copyB.absolutePath,
                    sourceType = MdbxSourceType.LOCAL_EXTERNAL,
                    storageLocation = MdbxStorageLocation.EXTERNAL,
                    encryptedPassword = encryptedPassword
                )
            )
            databaseIds += databaseAId
            databaseIds += databaseBId

            return ExternalFixture(
                repositoryA = repositoryA,
                repositoryB = repositoryB,
                databaseDao = databaseDao,
                databaseAId = databaseAId,
                databaseBId = databaseBId,
                encryptedPassword = encryptedPassword,
                files = mutableListOf(baseFile, copyA, copyB),
                databaseIds = databaseIds
            )
        } catch (error: Throwable) {
            databaseIds.asReversed().forEach { id -> runCatching { databaseDao.deleteDatabaseById(id) } }
            listOf(baseFile, copyA, copyB).forEach { file ->
                runCatching { baseRepository.deleteOwnedVaultFile(file) }
            }
            resolver.delete(StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false), null, null)
            throw error
        }
    }

    private fun localDatabase(
        name: String,
        filePath: String,
        workingCopyPath: String,
        sourceType: MdbxSourceType,
        storageLocation: MdbxStorageLocation,
        encryptedPassword: String
    ): LocalMdbxDatabase = LocalMdbxDatabase(
        name = name,
        filePath = filePath,
        storageLocation = storageLocation.name,
        sourceType = sourceType.name,
        engineType = MdbxEngineType.RUST_MDBX2.name,
        tigaMode = MdbxTigaMode.MULTI.name,
        encryptedPassword = encryptedPassword,
        unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
        kdfProfile = "argon2id-mdbx2",
        workingCopyPath = workingCopyPath,
        cacheCopyPath = workingCopyPath,
        isOfflineAvailable = true,
        lastSyncStatus = when (sourceType) {
            MdbxSourceType.LOCAL_INTERNAL -> MdbxSyncStatus.LOCAL_ONLY.name
            MdbxSourceType.LOCAL_EXTERNAL -> MdbxSyncStatus.IN_SYNC.name
            else -> MdbxSyncStatus.IN_SYNC.name
        }
    )

    private fun testEntry(
        id: Long,
        databaseId: Long,
        title: String
    ): PasswordEntry = PasswordEntry(
        id = id,
        title = title,
        website = "https://local-external.test/$id",
        username = "user-$id",
        password = "secret-$id",
        notes = "LOCAL_EXTERNAL divergence regression",
        mdbxDatabaseId = databaseId,
        mdbxFolderId = null
    )

    private inner class ExternalFixture(
        val repositoryA: Mdbx2Repository,
        val repositoryB: Mdbx2Repository,
        private val databaseDao: takagi.ru.monica.data.LocalMdbxDatabaseDao,
        val databaseAId: Long,
        val databaseBId: Long,
        private val encryptedPassword: String,
        private val files: MutableList<File>,
        private val databaseIds: MutableList<Long>
    ) {
        fun selectDevice(deviceId: String) {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("mdbx2_vault_sessions", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("device_id", deviceId)
                .commit()
        }

        suspend fun databaseA(): LocalMdbxDatabase? = databaseDao.getDatabaseById(databaseAId)

        suspend fun databaseB(): LocalMdbxDatabase? = databaseDao.getDatabaseById(databaseBId)

        val copyA: File get() = files[1]

        val copyB: File get() = files[2]

        suspend fun importPublishedVault(): Long {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val importedFile = File(
                requireNotNull(files.first().parentFile),
                "${UUID.randomUUID()}.mdbx"
            )
            Mdbx2ExternalStorage(context).copyDocumentToOwnedFile(
                sourceUri = StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false),
                targetFile = importedFile
            )
            files += importedFile
            val databaseId = databaseDao.insertDatabase(
                localDatabase(
                    name = "External divergence published result",
                    filePath = importedFile.absolutePath,
                    workingCopyPath = importedFile.absolutePath,
                    sourceType = MdbxSourceType.LOCAL_INTERNAL,
                    storageLocation = MdbxStorageLocation.INTERNAL,
                    encryptedPassword = encryptedPassword
                )
            )
            databaseIds += databaseId
            return databaseId
        }

        suspend fun close() {
            databaseIds.asReversed().forEach { id -> runCatching { databaseDao.deleteDatabaseById(id) } }
            files.distinctBy(File::getAbsolutePath).forEach { file ->
                runCatching { repositoryA.deleteOwnedVaultFile(file) }
            }
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.delete(
                StaleSizeContentProvider.uriFor(InstrumentationRegistry.getInstrumentation().context, false),
                null,
                null
            )
        }
    }

    private companion object {
        const val FIRST_ENTRY_ID = 8_810_001L
        const val SECOND_ENTRY_ID = 8_810_002L
        const val SHARED_ENTRY_ID = 8_810_003L
    }
}
