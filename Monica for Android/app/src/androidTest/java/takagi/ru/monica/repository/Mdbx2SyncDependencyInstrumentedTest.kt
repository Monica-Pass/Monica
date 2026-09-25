package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStateStore
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.MdbxRemoteObject
import takagi.ru.monica.utils.MdbxRemoteSyncPaths
import takagi.ru.monica.utils.MdbxRemoteTransport
import takagi.ru.monica.utils.MdbxRemoteWriteMode
import uniffi.mdbx_ffi.MdbxFfiException
import uniffi.mdbx_ffi.MdbxIncrementalSyncSegmentInfo
import uniffi.mdbx_ffi.MdbxTigaMode
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.createVaultWithTigaMode
import uniffi.mdbx_ffi.openVault

@RunWith(AndroidJUnit4::class)
class Mdbx2SyncDependencyInstrumentedTest {
    @Test
    fun nativeMissingParentRollbackIsDeferredAndResolvedFromAnotherStream() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, room.localMdbxDatabaseDao(), security)
        val vaultDirectory = File(context.filesDir, "mdbx2").apply { mkdirs() }
        val sourceFile = File(vaultDirectory, "${UUID.randomUUID()}.mdbx")
        val targetFile = File(vaultDirectory, "${UUID.randomUUID()}.mdbx")
        val scratch = File(context.cacheDir, "sync-dependencies-${UUID.randomUUID()}").apply { mkdirs() }
        val parentFile = File(scratch, "parent.mdbxsync")
        val childFile = File(scratch, "child.mdbxsync")
        val password = "incremental-dependency-fixture"
        val remotePath = "vaults/dependencies.mdbx"
        var databaseId = 0L
        Mdbx2NativeRuntime.ensureLoaded()
        try {
            val parentInfo: MdbxIncrementalSyncSegmentInfo
            val parent = createVaultWithTigaMode(sourceFile.absolutePath, password, "z-parent", MdbxTigaMode.SKY)
            try {
                val bootstrap = parent.createIncrementalSyncBootstrap(targetFile.absolutePath)
                parent.createProject("Parent collection")
                parentInfo = parent.exportIncrementalSyncSegment(parentFile.absolutePath, bootstrap.checkpoint, null, 128u)
            } finally {
                parent.close()
            }
            val childInfo: MdbxIncrementalSyncSegmentInfo
            val child = openVault(sourceFile.absolutePath, password, "a-child")
            try {
                child.createProject("Child collection")
                childInfo = child.exportIncrementalSyncSegment(childFile.absolutePath, parentInfo.result, null, 128u)
            } finally {
                child.close()
            }
            databaseId = room.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "Sync dependency fixture", filePath = targetFile.absolutePath,
                workingCopyPath = targetFile.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                storageLocation = MdbxStorageLocation.INTERNAL.name,
                encryptedPassword = security.encryptData(password)
            ))

            // Exercise the actual FFI exception and rollback contract, not a simulated result.
            repository.withVaultForSync(databaseId) { _, vault ->
                val before = vault.incrementalSyncCheckpoint()
                val failure = runCatching {
                    vault.applyIncrementalSyncSegment(childFile.absolutePath, childInfo.base, null)
                }.exceptionOrNull()
                assertTrue("Expected native missing-parent exception, got $failure", failure is MdbxFfiException.Storage)
                assertTrue((failure as MdbxFfiException.Storage).detail.matches(
                    Regex("validation error: incremental segment is missing [1-9][0-9]* commit parent\\(s\\)")
                ))
                assertEquals(before, vault.incrementalSyncCheckpoint())
                assertTrue(vault.listCollectionSummaries(100u, null).items.isEmpty())
            }

            val transport = FixtureTransport()
            transport.add(remotePath, parentInfo, parentFile)
            transport.add(remotePath, childInfo, childFile)
            val coordinator = Mdbx2RemoteSyncCoordinator(
                File(scratch, "coordinator"), Mdbx2RepositorySyncSessionProvider(repository),
                MdbxSyncStateStore(room.mdbxSyncStateDao())
            )
            coordinator.registerDownloadedBootstrap(databaseId, remotePath)
            // The child directory sorts before its parent, reproducing the reported abort.
            val result = coordinator.synchronize(databaseId, remotePath, transport)
            assertEquals(0, result.blockedStreams)
            assertEquals(2, result.downloadedSegments)
            assertTrue(transport.segmentReads.values.all { it == 1 })
            repository.withReadVaultForSync(databaseId) { _, vault ->
                assertEquals(setOf("Parent collection", "Child collection"),
                    vault.listCollectionSummaries(100u, null).items.map { it.title }.toSet())
            }
            assertEquals(0, coordinator.synchronize(databaseId, remotePath, transport).downloadedSegments)
        } finally {
            if (databaseId != 0L) {
                room.mdbxSyncStateDao().delete(databaseId)
                room.localMdbxDatabaseDao().deleteDatabaseById(databaseId)
            }
            repository.deleteOwnedVaultFile(sourceFile)
            repository.deleteOwnedVaultFile(targetFile)
            check(scratch.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            scratch.deleteRecursively()
        }
    }

    @Test
    fun nativeMultiPageHistorySynchronizesWithoutStrandingParents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, room.localMdbxDatabaseDao(), security)
        val vaultDirectory = File(context.filesDir, "mdbx2").apply { mkdirs() }
        val sourceFile = File(vaultDirectory, "${UUID.randomUUID()}.mdbx")
        val targetFile = File(vaultDirectory, "${UUID.randomUUID()}.mdbx")
        val scratch = File(context.cacheDir, "sync-pages-${UUID.randomUUID()}").apply { mkdirs() }
        val password = "incremental-paging-fixture"
        val remotePath = "vaults/paging.mdbx"
        var databaseId = 0L
        Mdbx2NativeRuntime.ensureLoaded()
        try {
            val transport = FixtureTransport()
            val source = createVaultWithTigaMode(sourceFile.absolutePath, password, "paged-source", MdbxTigaMode.SKY)
            var pageCount = 0
            try {
                val bootstrap = source.createIncrementalSyncBootstrap(targetFile.absolutePath)
                // Separate operations create enough history to cross a page;
                // a multi-command operation is intentionally one native commit.
                (1..130).forEach {
                    source.executeWriteOperation(UUID.randomUUID().toString(), "import-collection", listOf(
                        MdbxWriteCommand.CreateProject(UUID.randomUUID().toString(), "Imported collection $it")
                    ))
                }
                var base = bootstrap.checkpoint
                var resume: uniffi.mdbx_ffi.MdbxIncrementalSyncResume? = null
                do {
                    val file = File(scratch, "page-${pageCount++}.mdbxsync")
                    val info = source.exportIncrementalSyncSegment(file.absolutePath, base, resume, 128u)
                    transport.add(remotePath, info, file)
                    base = info.result
                    resume = info.nextResume
                } while (resume != null)
                assertTrue("Fixture must span native pages", pageCount > 1)
            } finally {
                source.close()
            }
            databaseId = room.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "Paged sync fixture", filePath = targetFile.absolutePath,
                workingCopyPath = targetFile.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                storageLocation = MdbxStorageLocation.INTERNAL.name,
                encryptedPassword = security.encryptData(password)
            ))
            val coordinator = Mdbx2RemoteSyncCoordinator(
                File(scratch, "coordinator"), Mdbx2RepositorySyncSessionProvider(repository),
                MdbxSyncStateStore(room.mdbxSyncStateDao())
            )
            coordinator.registerDownloadedBootstrap(databaseId, remotePath)
            val result = coordinator.synchronize(databaseId, remotePath, transport)
            assertEquals(0, result.blockedStreams)
            assertEquals(pageCount, result.downloadedSegments)
            repository.withReadVaultForSync(databaseId) { _, vault ->
                assertEquals(130, vault.listCollectionSummaries(200u, null).items.size)
            }
        } finally {
            if (databaseId != 0L) {
                room.mdbxSyncStateDao().delete(databaseId)
                room.localMdbxDatabaseDao().deleteDatabaseById(databaseId)
            }
            repository.deleteOwnedVaultFile(sourceFile)
            repository.deleteOwnedVaultFile(targetFile)
            check(scratch.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            scratch.deleteRecursively()
        }
    }

    private class FixtureTransport : MdbxRemoteTransport {
        private val files = linkedMapOf<String, ByteArray>()
        val segmentReads = mutableMapOf<String, Int>()

        fun add(remotePath: String, info: MdbxIncrementalSyncSegmentInfo, source: File) {
            val digest = info.payloadSha256.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            files[MdbxRemoteSyncPaths.segmentPath(remotePath, info.sourceDeviceId,
                info.transferId, info.segmentIndex, digest)] = source.readBytes()
        }

        override suspend fun testConnection() = Unit
        override suspend fun ensureDirectory(path: String) = Unit
        override suspend fun stat(path: String): MdbxRemoteObject? = when {
            files.containsKey(path) -> MdbxRemoteObject(path, false, files.getValue(path).size.toLong())
            files.keys.any { it.startsWith("$path/") } -> MdbxRemoteObject(path, true)
            else -> null
        }

        override suspend fun list(path: String?): List<MdbxRemoteObject> {
            val prefix = if (path.isNullOrBlank()) "" else "$path/"
            return files.keys.filter { it.startsWith(prefix) }
                .map { prefix + it.removePrefix(prefix).substringBefore('/') }.distinct().sorted()
                .map { child -> requireNotNull(stat(child)) }
        }

        override suspend fun readTo(path: String, destination: File) {
            segmentReads[path] = (segmentReads[path] ?: 0) + 1
            destination.writeBytes(files.getValue(path))
        }

        override suspend fun writeFrom(path: String, source: File, mode: MdbxRemoteWriteMode,
            expectedVersion: String?): MdbxRemoteObject {
            val bytes = source.readBytes()
            val previous = files[path]
            check(previous == null || previous.contentEquals(bytes))
            files[path] = bytes
            return MdbxRemoteObject(path, false, bytes.size.toLong())
        }
    }
}
