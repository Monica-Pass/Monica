package takagi.ru.monica.repository

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.MdbxSyncCheckpointState
import takagi.ru.monica.data.MdbxSyncResumeState
import takagi.ru.monica.data.MdbxSyncStateDao
import takagi.ru.monica.data.MdbxSyncStateEntity
import takagi.ru.monica.data.MdbxSyncStateStore
import takagi.ru.monica.utils.MdbxRemoteObject
import takagi.ru.monica.utils.MdbxRemoteSyncPaths
import takagi.ru.monica.utils.MdbxRemoteTransport
import takagi.ru.monica.utils.MdbxRemoteWriteMode

class Mdbx2RemoteSyncCoordinatorTest {

    @Test
    fun localChangesDuringDirectoryListingRemainPendingForNextSync() = runBlocking {
        val root = tempDirectory("mdbx2-coordinator-edit-during-list")
        try {
            val transport = MemoryTransport()
            val engine = FakeEngine("vault-a", "device-a")
            val dao = FakeStateDao()
            val sync = coordinator(root, engine, dao)
            val remotePath = "vaults/main.mdbx"
            sync.publishBootstrap(1L, remotePath, transport)
            val published = engine.checkpoint()
            val editingTransport = object : MdbxRemoteTransport by transport {
                override suspend fun list(path: String?): List<MdbxRemoteObject> {
                    if (path == MdbxRemoteSyncPaths.streamsRoot(remotePath)) {
                        engine.advance("local-during-list")
                    }
                    return transport.list(path)
                }
            }

            val report = sync.synchronize(1L, remotePath, editingTransport)
            assertEquals(published, report.publishedCheckpoint)
            assertEquals(published.commitInventory, report.syncedCommitInventory)
            assertEquals(published, MdbxSyncStateStore(dao).read(1L).exportCheckpoint)
            assertTrue(engine.checkpoint() != published)
            assertEquals(1, sync.synchronize(1L, remotePath, transport).uploadedSegments)
            assertEquals(engine.checkpoint(), MdbxSyncStateStore(dao).read(1L).exportCheckpoint)
            assertEquals(0, sync.synchronize(1L, remotePath, transport).uploadedSegments)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localChangesDuringRemoteDownloadAreNotSkippedByApply() = runBlocking {
        val root = tempDirectory("mdbx2-coordinator-edit-during-download")
        try {
            val transport = MemoryTransport()
            val source = FakeEngine("vault-a", "device-a")
            val target = FakeEngine("vault-a", "device-b")
            val targetDao = FakeStateDao()
            val sourceSync = coordinator(root, source, FakeStateDao())
            val targetSync = coordinator(root, target, targetDao)
            val remotePath = "vaults/main.mdbx"
            sourceSync.publishBootstrap(1L, remotePath, transport)
            targetSync.registerDownloadedBootstrap(2L, remotePath)
            val published = target.checkpoint()
            source.advance("remote-change")
            sourceSync.synchronize(1L, remotePath, transport)
            val editingTransport = object : MdbxRemoteTransport by transport {
                override suspend fun readTo(path: String, destination: File) {
                    if (path.endsWith(".mdbxsync")) target.advance("local-during-download")
                    transport.readTo(path, destination)
                }
            }

            val report = targetSync.synchronize(2L, remotePath, editingTransport)
            assertEquals(1, report.appliedCommits)
            assertEquals(published, report.publishedCheckpoint)
            assertEquals(published.commitInventory, report.syncedCommitInventory)
            assertEquals(published, MdbxSyncStateStore(targetDao).read(2L).exportCheckpoint)
            assertEquals(1, targetSync.synchronize(2L, remotePath, transport).uploadedSegments)
            assertEquals(0, targetSync.synchronize(2L, remotePath, transport).uploadedSegments)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readOnlyAuditDuringDownloadDoesNotMakeReceivedCommitsLookUnsynced() = runBlocking {
        val root = tempDirectory("mdbx2-coordinator-audit-during-download")
        try {
            val transport = MemoryTransport()
            val source = FakeEngine("vault-a", "device-a")
            val target = FakeEngine("vault-a", "device-b")
            val targetDao = FakeStateDao()
            val sourceSync = coordinator(root, source, FakeStateDao())
            val targetSync = coordinator(root, target, targetDao)
            val remotePath = "vaults/main.mdbx"
            sourceSync.publishBootstrap(1L, remotePath, transport)
            targetSync.registerDownloadedBootstrap(2L, remotePath)
            val published = target.checkpoint()
            source.advance("remote-change")
            sourceSync.synchronize(1L, remotePath, transport)
            val readingTransport = object : MdbxRemoteTransport by transport {
                override suspend fun readTo(path: String, destination: File) {
                    if (path.endsWith(".mdbxsync")) target.recordReadAudit()
                    transport.readTo(path, destination)
                }
            }

            val report = targetSync.synchronize(2L, remotePath, readingTransport)
            assertEquals(1, report.appliedCommits)
            assertEquals(target.checkpoint().commitInventory, report.syncedCommitInventory)
            assertEquals(published, report.publishedCheckpoint)
            assertEquals(published, MdbxSyncStateStore(targetDao).read(2L).exportCheckpoint)
            assertEquals(1, targetSync.synchronize(2L, remotePath, transport).uploadedSegments)
            assertEquals(0, targetSync.synchronize(2L, remotePath, transport).uploadedSegments)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bootstrapFastForwardAndDuplicatePublicationAreIdempotent() = runBlocking {
        val root = tempDirectory("mdbx2-coordinator-fast-forward")
        try {
            val transport = MemoryTransport()
            val source = FakeEngine("vault-a", "device-a")
            val target = FakeEngine("vault-a", "device-b")
            val sourceDao = FakeStateDao()
            val targetDao = FakeStateDao()
            val sourceCoordinator = coordinator(root, source, sourceDao)
            val targetCoordinator = coordinator(root, target, targetDao)
            val remotePath = "vaults/main.mdbx"

            sourceCoordinator.publishBootstrap(1L, remotePath, transport)
            val downloaded = File(root, "target.mdbx")
            targetCoordinator.downloadBootstrapTo(remotePath, transport, downloaded)
            assertTrue(downloaded.isFile)
            targetCoordinator.registerDownloadedBootstrap(2L, remotePath)

            source.advance("c1")
            assertEquals(1, sourceCoordinator.synchronize(1L, remotePath, transport).uploadedSegments)
            val received = targetCoordinator.synchronize(2L, remotePath, transport)
            assertEquals(1, received.downloadedSegments)
            assertEquals("c1", target.checkpoint().commitInventory)

            val duplicate = sourceCoordinator.synchronize(1L, remotePath, transport)
            assertEquals(0, duplicate.uploadedSegments)
            assertEquals(0, duplicate.downloadedSegments)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun divergentStreamsRemainVisibleAndMissingSequenceBlocksOnlyThatStream() = runBlocking {
        val root = tempDirectory("mdbx2-coordinator-divergence")
        try {
            val transport = MemoryTransport()
            val a = FakeEngine("vault-a", "device-a")
            val b = FakeEngine("vault-a", "device-b")
            val ca = coordinator(root, a, FakeStateDao())
            val cb = coordinator(root, b, FakeStateDao())
            val remotePath = "vaults/main.mdbx"
            ca.publishBootstrap(1L, remotePath, transport)
            cb.registerDownloadedBootstrap(2L, remotePath)

            a.advance("a1")
            b.advance("b1")
            ca.synchronize(1L, remotePath, transport)
            cb.synchronize(2L, remotePath, transport)

            val streamRoot = MdbxRemoteSyncPaths.streamsRoot(remotePath)
            assertEquals(2, transport.list(streamRoot).count { it.isDirectory })

            // Publish only sequence 1 for a third stream; the receiver must
            // retain it remotely but refuse to apply it until sequence 0 exists.
            val gapEngine = FakeEngine("vault-a", "device-gap")
            gapEngine.advance("g1")
            val first = File(root, "gap-0.mdbxsync")
            gapEngine.exportSegment(first, gapEngine.checkpoint().copy(commitInventory = "c0", deltaInventory = "d0"), null, 128u)
            val secondBase = gapEngine.checkpoint()
            gapEngine.advance("g2")
            val second = File(root, "gap-1.mdbxsync")
            val secondInfo = gapEngine.exportSegment(second, secondBase, null, 128u)
            transport.writeFrom(
                MdbxRemoteSyncPaths.segmentPath(remotePath, "device-gap", "transfer-device-gap", secondInfo.segmentIndex, secondInfo.payloadSha256Hex),
                second,
                MdbxRemoteWriteMode.CREATE_ONLY
            )
            val blocked = cb.synchronize(2L, remotePath, transport)
            assertTrue(blocked.blockedStreams >= 1)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pendingSegmentSurvivesFailureAndCoordinatorRestart() = runBlocking {
        val root = tempDirectory("mdbx2-coordinator-restart")
        try {
            val transport = MemoryTransport().apply { failNextSegmentWrite = true }
            val engine = FakeEngine("vault-a", "device-a")
            val dao = FakeStateDao()
            val first = coordinator(root, engine, dao)
            val remotePath = "vaults/main.mdbx"
            first.publishBootstrap(1L, remotePath, transport)
            engine.advance("c1")

            assertTrue(runCatching { first.synchronize(1L, remotePath, transport) }.isFailure)
            assertNotNull(MdbxSyncStateStore(dao).read(1L).pendingSegment)

            val restarted = coordinator(root, engine, dao)
            val report = restarted.synchronize(1L, remotePath, transport)
            assertEquals(1, report.uploadedSegments)
            assertTrue(MdbxSyncStateStore(dao).read(1L).pendingSegment == null)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun immutableCollisionLeavesPendingStateForManualRecovery() = runBlocking {
        val root = tempDirectory("mdbx2-coordinator-collision")
        try {
            val transport = MemoryTransport().apply { rejectSegmentWrites = true }
            val engine = FakeEngine("vault-a", "device-a")
            val dao = FakeStateDao()
            val coordinator = coordinator(root, engine, dao)
            val remotePath = "vaults/main.mdbx"
            coordinator.publishBootstrap(1L, remotePath, transport)
            engine.advance("c1")

            assertTrue(runCatching { coordinator.synchronize(1L, remotePath, transport) }.isFailure)
            val pending = MdbxSyncStateStore(dao).read(1L).pendingSegment
            assertNotNull(pending)
            assertTrue(File(pending!!.path).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun conflictingDigestForAppliedSequenceBlocksTheStream() = runBlocking {
        val root = tempDirectory("mdbx2-coordinator-conflicting-digest")
        try {
            val transport = MemoryTransport()
            val source = FakeEngine("vault-a", "device-a")
            val target = FakeEngine("vault-a", "device-b")
            val sourceCoordinator = coordinator(root, source, FakeStateDao())
            val targetCoordinator = coordinator(root, target, FakeStateDao())
            val remotePath = "vaults/main.mdbx"
            sourceCoordinator.publishBootstrap(1L, remotePath, transport)
            targetCoordinator.registerDownloadedBootstrap(2L, remotePath)
            source.advance("c1")
            sourceCoordinator.synchronize(1L, remotePath, transport)
            targetCoordinator.synchronize(2L, remotePath, transport)

            val conflicting = File(root, "conflicting.mdbxsync").apply { writeText("conflicting") }
            val digest = MdbxRemoteSyncPaths.sha256Hex(conflicting)
            transport.writeFrom(
                MdbxRemoteSyncPaths.segmentPath(
                    remotePath,
                    "device-a",
                    "transfer-device-a",
                    0u,
                    digest
                ),
                conflicting,
                MdbxRemoteWriteMode.CREATE_ONLY
            )
            assertTrue(targetCoordinator.synchronize(2L, remotePath, transport).blockedStreams >= 1)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun blobPublishesBeforeSegmentAndInterruptedDownloadDoesNotEchoRemoteChange() = runBlocking {
        val root = tempDirectory("mdbx2-coordinator-blob-resume")
        try {
            val transport = MemoryTransport()
            val source = FakeEngine("vault-a", "device-a")
            val target = FakeEngine("vault-a", "device-b")
            val sourceDao = FakeStateDao()
            val targetDao = FakeStateDao()
            val sourceCoordinator = coordinator(root, source, sourceDao)
            val targetCoordinator = coordinator(root, target, targetDao)
            val remotePath = "vaults/main.mdbx"
            sourceCoordinator.publishBootstrap(1L, remotePath, transport)
            targetCoordinator.registerDownloadedBootstrap(2L, remotePath)

            val ciphertext = ByteArray(700_000) { index -> (index % 251).toByte() }
            val blobId = source.addAvailableBlob(ciphertext)
            target.expectBlob(blobId, ciphertext.size.toULong())
            source.advance("c1")
            sourceCoordinator.synchronize(1L, remotePath, transport)

            val blobWrite = transport.writeOrder.indexOfFirst { "/blobs/" in it }
            val segmentWrite = transport.writeOrder.indexOfFirst { it.endsWith(".mdbxsync") }
            assertTrue(blobWrite >= 0 && segmentWrite > blobWrite)

            transport.failNextBlobRead = true
            assertTrue(runCatching { targetCoordinator.synchronize(2L, remotePath, transport) }.isFailure)
            val interruptedState = MdbxSyncStateStore(targetDao).read(2L)
            assertTrue(interruptedState.remoteStreams.isEmpty())
            assertEquals("c1", interruptedState.exportCheckpoint?.commitInventory)

            targetCoordinator.synchronize(2L, remotePath, transport)
            assertTrue(target.hasBlob(blobId, ciphertext.size.toULong()))
            assertTrue(transport.writeOrder.none { "/device-b/" in it && it.endsWith(".mdbxsync") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedBlobSidecarAvoidsRepeatedDownloadsAcrossCoordinatorRestart() = runBlocking {
        val root = tempDirectory("mdbx2-coordinator-blob-sidecar")
        try {
            val transport = MemoryTransport()
            val source = FakeEngine("vault-a", "device-a")
            val sourceDao = FakeStateDao()
            val first = coordinator(root, source, sourceDao)
            val remotePath = "vaults/main.mdbx"
            first.publishBootstrap(1L, remotePath, transport)

            source.addAvailableBlob(ByteArray(700_000) { index -> (index % 241).toByte() })
            source.advance("c1")
            assertEquals(1, first.synchronize(1L, remotePath, transport).uploadedBlobs)
            assertEquals(0, transport.blobReadCount)

            source.advance("c2")
            val restarted = coordinator(root, source, sourceDao)
            val second = restarted.synchronize(1L, remotePath, transport)
            assertEquals(1, second.uploadedSegments)
            assertEquals(0, second.uploadedBlobs)
            assertEquals(0, transport.blobReadCount)
            assertTrue(File(File(root, source.deviceId), "sync-state-1.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reversedParentDependenciesResolveBeyondFourPassesWithoutRedownloading() = runBlocking {
        val root = tempDirectory("mdbx2-reversed-parents")
        try {
            val transport = MemoryTransport()
            val engine = FakeEngine("vault-a", "receiver").apply { requireParents = true }
            val sync = coordinator(root, engine, FakeStateDao())
            val remotePath = "vaults/main.mdbx"
            sync.registerDownloadedBootstrap(1L, remotePath)
            // Directory names are deliberately the reverse of commit ancestry.
            for (index in 1..6) {
                publishFixtureSegment(root, transport, remotePath,
                    deviceId = "device-${7 - index}", base = "c${index - 1}", result = "c$index")
            }

            val report = sync.synchronize(1L, remotePath, transport)
            assertEquals(0, report.blockedStreams)
            assertEquals(6, report.appliedCommits)
            assertEquals("c6", engine.checkpoint().commitInventory)
            assertEquals(6, report.downloadedSegments)
            assertTrue(transport.segmentReads.values.all { it == 1 })
            assertEquals(0, sync.synchronize(1L, remotePath, transport).downloadedSegments)
            assertTrue(File(File(root, engine.deviceId), "incoming").listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stalledParentIsDownloadedOnceAndCanResumeAfterParentArrives() = runBlocking {
        val root = tempDirectory("mdbx2-stalled-parent")
        try {
            val transport = MemoryTransport()
            val engine = FakeEngine("vault-a", "receiver").apply { requireParents = true }
            val dao = FakeStateDao()
            val sync = coordinator(root, engine, dao)
            val remotePath = "vaults/main.mdbx"
            sync.registerDownloadedBootstrap(1L, remotePath)
            val baseline = engine.checkpoint()
            publishFixtureSegment(root, transport, remotePath, "a-child", "c1", "c2")

            val stalled = sync.synchronize(1L, remotePath, transport)
            assertEquals(1, stalled.blockedStreams)
            assertEquals(1, stalled.downloadedSegments)
            assertEquals(0, stalled.appliedCommits)
            assertEquals(baseline, engine.checkpoint())
            val state = MdbxSyncStateStore(dao).read(1L)
            assertEquals(baseline, state.exportCheckpoint)
            assertEquals(0L, state.remoteStreams.single().nextSequence)

            publishFixtureSegment(root, transport, remotePath, "z-parent", "c0", "c1")
            val resumed = coordinator(root, engine, dao).synchronize(1L, remotePath, transport)
            assertEquals(0, resumed.blockedStreams)
            assertEquals(2, resumed.downloadedSegments)
            assertEquals("c2", engine.checkpoint().commitInventory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reopeningBootstrapOnSameDeviceRecoversItsPreviouslyPublishedSegments() = runBlocking {
        val root = tempDirectory("mdbx2-reopen-same-device")
        try {
            val transport = MemoryTransport()
            val source = FakeEngine("vault-a", "device-a")
            val publisher = coordinator(File(root, "publisher"), source, FakeStateDao())
            val remotePath = "vaults/main.mdbx"
            publisher.publishBootstrap(1L, remotePath, transport)
            source.advance("c1")
            assertEquals(0, publisher.synchronize(1L, remotePath, transport).downloadedSegments)

            val restored = FakeEngine("vault-a", "device-a")
            val receiver = coordinator(File(root, "restored"), restored, FakeStateDao())
            receiver.registerDownloadedBootstrap(2L, remotePath)
            val report = receiver.synchronize(2L, remotePath, transport)
            assertEquals(1, report.downloadedSegments)
            assertEquals("c1", restored.checkpoint().commitInventory)
            assertEquals(0, receiver.synchronize(2L, remotePath, transport).downloadedSegments)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun listingUsesKnownSegmentsDirectoryWithoutRedundantMetadataProbes() = runBlocking {
        val root = tempDirectory("mdbx2-list-requests")
        try {
            val transport = MemoryTransport()
            val engine = FakeEngine("vault-a", "receiver")
            val sync = coordinator(root, engine, FakeStateDao())
            val remotePath = "vaults/main.mdbx"
            sync.registerDownloadedBootstrap(1L, remotePath)
            publishFixtureSegment(root, transport, remotePath, "source", "c0", "c1")

            sync.synchronize(1L, remotePath, transport)
            assertEquals(emptyList<String>(), transport.statPaths)
            assertEquals(listOf(
                MdbxRemoteSyncPaths.streamsRoot(remotePath),
                "${MdbxRemoteSyncPaths.streamsRoot(remotePath)}/source",
                "${MdbxRemoteSyncPaths.streamsRoot(remotePath)}/source/transfer-source/segments"
            ), transport.listPaths)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pagedPublicationKeepsOneGenerationAndVerifiesEachBlobOncePerSync() = runBlocking {
        val root = tempDirectory("mdbx2-paged-publication")
        try {
            val transport = MemoryTransport()
            val source = FakeEngine("vault-a", "source")
            val dao = FakeStateDao()
            val sync = coordinator(root, source, dao)
            val remotePath = "vaults/main.mdbx"
            sync.publishBootstrap(1L, remotePath, transport)
            val blobId = source.addAvailableBlob(byteArrayOf(1, 2, 3, 4))
            source.queuePages("c1", "c2", "c3")

            val report = sync.synchronize(1L, remotePath, transport)
            assertEquals(3, report.uploadedSegments)
            assertEquals(1, report.uploadedBlobs)
            assertEquals(0, report.downloadedSegments)
            assertEquals(1, transport.statPaths.count { it == MdbxRemoteSyncPaths.blobPath(remotePath, blobId) })
            val paths = transport.writeOrder.filter { it.endsWith(".mdbxsync") }
            assertEquals(1, paths.map { it.substringBeforeLast('/') }.distinct().size)
            val state = MdbxSyncStateStore(dao).read(1L)
            assertEquals(null, state.exportResume)
            assertEquals(3L, state.remoteStreams.single().nextSequence)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedPagedPublicationResumesSameGenerationAfterRestart() = runBlocking {
        val root = tempDirectory("mdbx2-paged-restart")
        try {
            val transport = MemoryTransport().apply { failOnSegmentWriteNumber = 2 }
            val source = FakeEngine("vault-a", "source")
            val dao = FakeStateDao()
            val sync = coordinator(root, source, dao)
            val remotePath = "vaults/main.mdbx"
            sync.publishBootstrap(1L, remotePath, transport)
            source.queuePages("c1", "c2", "c3")
            assertTrue(runCatching { sync.synchronize(1L, remotePath, transport) }.isFailure)
            val interrupted = MdbxSyncStateStore(dao).read(1L)
            assertEquals(1u, interrupted.exportResume?.nextSegmentIndex)
            assertEquals(1u, interrupted.pendingSegment?.segmentIndex)

            val report = coordinator(root, source, dao).synchronize(1L, remotePath, transport)
            assertEquals(2, report.uploadedSegments)
            val paths = transport.writeOrder.filter { it.endsWith(".mdbxsync") }
            assertEquals(3, paths.size)
            assertEquals(1, paths.map { it.substringBeforeLast('/') }.distinct().size)
            assertEquals("c3", MdbxSyncStateStore(dao).read(1L).exportCheckpoint?.commitInventory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun arrivingSegmentZeroInitializesPreviouslyGappedStreamFromAuthenticatedBase() = runBlocking {
        val root = tempDirectory("mdbx2-gap-recovery")
        try {
            val transport = MemoryTransport()
            val engine = FakeEngine("vault-a", "receiver").apply { requireParents = true }
            val sync = coordinator(root, engine, FakeStateDao())
            val remotePath = "vaults/main.mdbx"
            sync.registerDownloadedBootstrap(1L, remotePath)
            publishFixtureSegment(root, transport, remotePath, "a-child", "c2", "c3", sequence = 1)
            assertEquals(1, sync.synchronize(1L, remotePath, transport).blockedStreams)
            publishFixtureSegment(root, transport, remotePath, "a-child", "c1", "c2")
            publishFixtureSegment(root, transport, remotePath, "z-parent", "c0", "c1")

            val report = sync.synchronize(1L, remotePath, transport)
            assertEquals(0, report.blockedStreams)
            assertEquals("c3", engine.checkpoint().commitInventory)
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun publishFixtureSegment(
        root: File,
        transport: MemoryTransport,
        remotePath: String,
        deviceId: String,
        base: String,
        result: String,
        sequence: Int = 0
    ) {
        val file = File(root, "$deviceId-$sequence.mdbxsync")
        file.writeText(listOf("vault-a", deviceId, "transfer-$deviceId", sequence, base, "d$base", result, "d$result").joinToString("|"))
        transport.writeFrom(MdbxRemoteSyncPaths.segmentPath(remotePath, deviceId, "transfer-$deviceId", sequence.toUInt(),
            MdbxRemoteSyncPaths.sha256Hex(file)), file, MdbxRemoteWriteMode.CREATE_ONLY)
    }

    private fun coordinator(
        root: File,
        engine: Mdbx2SyncEngine,
        dao: FakeStateDao
    ): Mdbx2RemoteSyncCoordinator = Mdbx2RemoteSyncCoordinator(
        rootDirectory = File(root, engine.deviceId),
        sessions = object : Mdbx2SyncSessionProvider {
            override suspend fun <T> withSession(
                databaseId: Long,
                block: suspend (Mdbx2SyncEngine) -> T
            ): T = block(engine)
        },
        stateStore = MdbxSyncStateStore(dao)
    )

    private fun tempDirectory(prefix: String): File =
        File.createTempFile(prefix, "").also { it.delete(); it.mkdirs() }

    private class FakeStateDao : MdbxSyncStateDao {
        private val values = ConcurrentHashMap<Long, MdbxSyncStateEntity>()

        override suspend fun get(databaseId: Long): MdbxSyncStateEntity? = values[databaseId]
        override suspend fun upsert(state: MdbxSyncStateEntity) { values[state.databaseId] = state }
        override suspend fun delete(databaseId: Long) { values.remove(databaseId) }
    }

    private class MemoryTransport : MdbxRemoteTransport {
        private val files = linkedMapOf<String, ByteArray>()
        private val directories = linkedSetOf<String>()
        val writeOrder = mutableListOf<String>()
        val statPaths = mutableListOf<String>()
        val listPaths = mutableListOf<String>()
        val segmentReads = mutableMapOf<String, Int>()
        var failNextSegmentWrite: Boolean = false
        var failNextBlobRead: Boolean = false
        var rejectSegmentWrites: Boolean = false
        var blobReadCount: Int = 0
        var failOnSegmentWriteNumber: Int? = null
        private var segmentWriteAttempts = 0

        override suspend fun testConnection() = Unit

        override suspend fun stat(path: String): MdbxRemoteObject? {
            val normalized = MdbxRemoteSyncPaths.normalizePath(path)
            statPaths += normalized
            files[normalized]?.let { return MdbxRemoteObject(normalized, false, it.size.toLong(), normalized) }
            if (normalized in directories) return MdbxRemoteObject(normalized, true)
            return null
        }

        override suspend fun list(path: String?): List<MdbxRemoteObject> {
            val normalized = path?.let(MdbxRemoteSyncPaths::normalizePath).orEmpty()
            listPaths += normalized
            val prefix = if (normalized.isBlank()) "" else "$normalized/"
            val children = linkedSetOf<String>()
            files.keys.filter { it.startsWith(prefix) }.forEach { child ->
                val suffix = child.removePrefix(prefix)
                children += suffix.substringBefore('/')
            }
            directories.filter { it.startsWith(prefix) }.forEach { child ->
                val suffix = child.removePrefix(prefix)
                if (suffix.isNotBlank()) children += suffix.substringBefore('/')
            }
            return children.map { name ->
                val childPath = if (normalized.isBlank()) name else "$normalized/$name"
                val directory = childPath in directories || files.keys.any { it.startsWith("$childPath/") }
                val bytes = files[childPath]
                MdbxRemoteObject(childPath, directory, bytes?.size?.toLong(), childPath)
            }.sortedBy(MdbxRemoteObject::path)
        }

        override suspend fun ensureDirectory(path: String) {
            val normalized = MdbxRemoteSyncPaths.normalizePath(path)
            var current = ""
            normalized.split('/').forEach { part ->
                current = if (current.isBlank()) part else "$current/$part"
                directories += current
            }
        }

        override suspend fun readTo(path: String, destination: File) {
            val normalized = MdbxRemoteSyncPaths.normalizePath(path)
            if (normalized.endsWith(".mdbxsync")) segmentReads[normalized] = (segmentReads[normalized] ?: 0) + 1
            if ("/blobs/" in normalized) blobReadCount += 1
            if (failNextBlobRead && "/blobs/" in normalized) {
                failNextBlobRead = false
                throw IOException("simulated Blob download interruption")
            }
            val bytes = files[normalized]
                ?: throw IOException("missing remote object")
            destination.parentFile?.mkdirs()
            destination.writeBytes(bytes)
        }

        override suspend fun writeFrom(
            path: String,
            source: File,
            mode: MdbxRemoteWriteMode,
            expectedVersion: String?
        ): MdbxRemoteObject {
            val normalized = MdbxRemoteSyncPaths.normalizePath(path)
            if (normalized.endsWith(".mdbxsync")) {
                segmentWriteAttempts++
                if (segmentWriteAttempts == failOnSegmentWriteNumber) {
                    throw IOException("simulated paged publication interruption")
                }
            }
            if (rejectSegmentWrites && normalized.endsWith(".mdbxsync")) {
                throw IOException("immutable collision")
            }
            if (failNextSegmentWrite && normalized.endsWith(".mdbxsync")) {
                failNextSegmentWrite = false
                throw IOException("simulated interruption")
            }
            val bytes = source.readBytes()
            val existing = files[normalized]
            if (mode == MdbxRemoteWriteMode.CREATE_ONLY && existing != null) {
                if (existing.contentEquals(bytes)) {
                    return MdbxRemoteObject(normalized, false, bytes.size.toLong(), normalized)
                }
                throw IOException("immutable collision")
            }
            files[normalized] = bytes
            writeOrder += normalized
            ensureDirectory(normalized.substringBeforeLast('/', "_root_"))
            return MdbxRemoteObject(normalized, false, bytes.size.toLong(), normalized)
        }
    }

    private class FakeEngine(
        override val vaultId: String,
        override val deviceId: String
    ) : Mdbx2SyncEngine {
        private var token = "c0"
        private var auditRevision = 0
        private var nextIndex = 0u
        private val transferId = "transfer-$deviceId"
        private val blobSizes = linkedMapOf<String, ULong>()
        private val blobContents = linkedMapOf<String, ByteArray>()
        private val blobWrites = linkedMapOf<String, ByteArrayOutputStream>()
        private val knownCommits = mutableSetOf("c0")
        var requireParents = false
        private val queuedPages = ArrayDeque<String>()
        private var paginating = false
        private var pageGeneration = 0

        fun advance(nextToken: String) { token = nextToken; knownCommits += nextToken }

        fun recordReadAudit() { auditRevision++ }

        fun queuePages(vararg tokens: String) {
            paginating = true
            queuedPages.addAll(tokens)
            advance(tokens.last())
        }

        fun addAvailableBlob(bytes: ByteArray): String {
            val blobId = sha256Hex(bytes)
            blobSizes[blobId] = bytes.size.toULong()
            blobContents[blobId] = bytes.copyOf()
            return blobId
        }

        fun expectBlob(blobId: String, totalSize: ULong) {
            blobSizes[blobId] = totalSize
            blobContents.remove(blobId)
        }

        override suspend fun checkpoint(): MdbxSyncCheckpointState =
            MdbxSyncCheckpointState(token, if (auditRevision == 0) "d$token" else "d$token-audit-$auditRevision")

        override suspend fun createBootstrap(destination: File): Mdbx2BootstrapInfo {
            check(!destination.exists()) { "Bootstrap destination must be unpublished" }
            destination.writeText("bootstrap:$vaultId:$token")
            return Mdbx2BootstrapInfo(vaultId, checkpoint(), destination.length().toULong())
        }

        override suspend fun exportSegment(
            destination: File,
            base: MdbxSyncCheckpointState,
            resume: takagi.ru.monica.data.MdbxSyncResumeState?,
            pageSize: UInt
        ): Mdbx2SegmentInfo {
            check(!destination.exists()) { "Segment destination must be unpublished" }
            val index = if (paginating) resume?.nextSegmentIndex ?: 0u else nextIndex++
            val generation = if (paginating) resume?.transferId ?: "pages-$deviceId-${pageGeneration++}" else transferId
            val result = if (paginating) {
                val nextToken = queuedPages.removeFirst()
                MdbxSyncCheckpointState(nextToken, "d$nextToken")
            } else checkpoint()
            val payload = listOf(
                vaultId, deviceId, generation, index,
                base.commitInventory, base.deltaInventory,
                result.commitInventory, result.deltaInventory,
                !paginating || queuedPages.isEmpty()
            ).joinToString("|")
            destination.writeText(payload)
            return segmentInfo(destination, payload)
        }

        override suspend fun inspectSegment(source: File): Mdbx2SegmentInfo =
            segmentInfo(source, source.readText())

        override suspend fun applySegment(
            source: File,
            expectedBase: MdbxSyncCheckpointState,
            expectedResume: takagi.ru.monica.data.MdbxSyncResumeState?
        ): Mdbx2SegmentApplyResult {
            val before = checkpoint()
            val info = inspectSegment(source)
            require(info.base == expectedBase)
            if (requireParents && info.base.commitInventory !in knownCommits) {
                return Mdbx2SegmentApplyResult(expectedBase, expectedResume, 0u, 0u, 0u, 2u, before, before)
            }
            token = info.result.commitInventory
            knownCommits += token
            return Mdbx2SegmentApplyResult(info.result, info.nextResume, 1u, 0u, 0u, 0u, before, checkpoint())
        }

        override suspend fun listBlobReferences(cursor: String?, pageSize: UInt) =
            Mdbx2BlobReferencePage(
                items = blobSizes.entries.sortedBy { it.key }.map { (blobId, _) ->
                    Mdbx2BlobReference(
                        blobId = blobId,
                        totalSize = blobContents[blobId]?.size?.toULong(),
                        availability = if (blobContents.containsKey(blobId)) {
                            Mdbx2BlobAvailability.AVAILABLE
                        } else {
                            Mdbx2BlobAvailability.MISSING
                        }
                    )
                },
                nextCursor = null
            )
        override suspend fun hasBlob(blobId: String, totalSize: ULong) = blobSession.hasBlob(blobId, totalSize)

        override suspend fun <T> withBlobTransfer(block: suspend (Mdbx2BlobTransferSession) -> T): T = block(blobSession)

        private val blobSession = object : Mdbx2BlobTransferSession {
            override fun hasBlob(blobId: String, totalSize: ULong) =
                blobContents[blobId]?.size?.toULong() == totalSize
            override fun readBlobChunk(
                blobId: String,
                totalSize: ULong,
                offset: ULong,
                maxBytes: UInt
            ): Mdbx2BlobChunk {
                val bytes = blobContents[blobId] ?: error("missing fake Blob")
                require(bytes.size.toULong() == totalSize)
                val start = offset.toInt()
                val end = minOf(bytes.size, start + maxBytes.toInt())
                return Mdbx2BlobChunk(
                    blobId = blobId,
                    totalSize = totalSize,
                    offset = offset,
                    ciphertext = bytes.copyOfRange(start, end),
                    isLast = end == bytes.size
                )
            }
            override fun writeBlobChunk(
                blobId: String,
                totalSize: ULong,
                offset: ULong,
                ciphertext: ByteArray,
                finalize: Boolean
            ) {
                val output = blobWrites.getOrPut(blobId) { ByteArrayOutputStream() }
                require(output.size().toULong() == offset)
                output.write(ciphertext)
                if (finalize) {
                    val bytes = output.toByteArray()
                    require(bytes.size.toULong() == totalSize)
                    require(sha256Hex(bytes) == blobId)
                    blobContents[blobId] = bytes
                    blobWrites.remove(blobId)
                }
            }
            override fun acquireBlobLease(blobId: String, ownerId: String, nowUnixSecs: Long, ttlSecs: Long) = Unit
            override fun releaseBlobLease(blobId: String, ownerId: String) = Unit
            override fun abortBlobTransfer(blobId: String, ownerId: String) {
                blobWrites.remove(blobId)
            }
        }

        private fun segmentInfo(file: File, payload: String): Mdbx2SegmentInfo {
            val parts = payload.split('|')
            require(parts.size in 8..9)
            val base = MdbxSyncCheckpointState(parts[4], parts[5])
            val result = MdbxSyncCheckpointState(parts[6], parts[7])
            val digest = MdbxRemoteSyncPaths.sha256Hex(file)
            val isLast = parts.getOrNull(8)?.toBoolean() ?: true
            return Mdbx2SegmentInfo(
                vaultId = parts[0],
                sourceDeviceId = parts[1],
                transferId = parts[2],
                segmentIndex = parts[3].toUInt(),
                isLast = isLast,
                base = base,
                result = result,
                nextResume = if (isLast) null else MdbxSyncResumeState(parts[2], parts[3].toUInt() + 1u, digest),
                commitCount = 1u,
                deltaCount = 1u,
                payloadSha256Hex = digest,
                fileSizeBytes = file.length().toULong()
            )
        }

        private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
