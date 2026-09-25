package takagi.ru.monica.repository

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.MdbxBlobTransferState
import takagi.ru.monica.data.MdbxPendingSegmentState
import takagi.ru.monica.data.MdbxRemoteStreamState
import takagi.ru.monica.data.MdbxSyncCheckpointState
import takagi.ru.monica.data.MdbxSyncStateSnapshot
import takagi.ru.monica.data.MdbxSyncStateStore
import takagi.ru.monica.utils.MdbxRemoteObject
import takagi.ru.monica.utils.MdbxRemoteSyncPaths
import takagi.ru.monica.utils.MdbxRemoteTransport
import takagi.ru.monica.utils.MdbxRemoteWriteMode
import takagi.ru.monica.utils.MdbxSyncSidecarBlob
import takagi.ru.monica.utils.MdbxSyncSidecarManifest
import takagi.ru.monica.utils.MdbxSyncSidecarStore

/**
 * Android transport coordinator for the Rust-owned MDBX2 incremental format.
 *
 * It never writes engine tables. Checkpoints advance only after immutable
 * remote publication or an atomic Rust apply has completed.
 */
internal class Mdbx2RemoteSyncCoordinator(
    private val rootDirectory: File,
    private val sessions: Mdbx2SyncSessionProvider,
    private val stateStore: MdbxSyncStateStore,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val pendingDirectory = File(rootDirectory, "pending")
    private val incomingDirectory = File(rootDirectory, "incoming")
    private val blobDirectory = File(rootDirectory, "blob-transfer")
    private val sidecarStore = MdbxSyncSidecarStore(rootDirectory)
    private val locks = ConcurrentHashMap<Long, Mutex>()

    /**
     * Downloads the immutable bootstrap into [destination] and publishes it
     * atomically. State is intentionally registered separately, after the
     * caller has created the local database row and verified its credentials.
     */
    suspend fun downloadBootstrapTo(
        remoteVaultPath: String,
        transport: MdbxRemoteTransport,
        destination: File
    ): Long = withContext(Dispatchers.IO) {
        // Per-vault operations are serialized by [lock] once a database id
        // exists. Bootstrap download happens before that row exists.
        ensureLocalDirectories()
        transport.testConnection()
        val normalizedPath = MdbxRemoteSyncPaths.normalizePath(remoteVaultPath)
        val parent = destination.parentFile
            ?: throw IOException("MDBX2 bootstrap destination has no parent")
        check(parent.exists() || parent.mkdirs()) {
            "Cannot create MDBX2 bootstrap destination directory"
        }
        val temporary = File.createTempFile("bootstrap-download-", ".mdbx", parent)
        try {
            transport.readTo(normalizedPath, temporary)
            require(temporary.isFile && temporary.length() > 0L) {
                "MDBX2 remote bootstrap is empty"
            }
            atomicReplace(temporary, destination)
            destination.length()
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    suspend fun publishBootstrap(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport
    ): Mdbx2RemoteBootstrapResult = lock(databaseId) {
        transport.testConnection()
        ensureLocalDirectories()
        sessions.withSession(databaseId) { engine ->
            val bootstrapFile = File(
                rootDirectory,
                "bootstrap-$databaseId-${UUID.randomUUID()}.mdbx"
            )
            check(!bootstrapFile.exists()) {
                "MDBX2 bootstrap destination already exists"
            }
            try {
                val bootstrap = engine.createBootstrap(bootstrapFile)
                require(bootstrap.vaultId == engine.vaultId) { "MDBX2 bootstrap vault identity mismatch" }
                ensureRemoteParent(transport, remoteVaultPath)
                transport.writeFrom(
                    path = MdbxRemoteSyncPaths.normalizePath(remoteVaultPath),
                    source = bootstrapFile,
                    mode = MdbxRemoteWriteMode.CREATE_ONLY
                )
                transport.ensureDirectory(MdbxRemoteSyncPaths.streamsRoot(remoteVaultPath))
                transport.ensureDirectory(MdbxRemoteSyncPaths.blobsRoot(remoteVaultPath))
                stateStore.write(
                    databaseId,
                    MdbxSyncStateSnapshot(
                        vaultId = engine.vaultId,
                        bootstrapCheckpoint = bootstrap.checkpoint,
                        exportCheckpoint = bootstrap.checkpoint
                    )
                )
                initializeSidecar(databaseId, engine, remoteVaultPath)
                Mdbx2RemoteBootstrapResult(
                    vaultId = engine.vaultId,
                    checkpoint = bootstrap.checkpoint,
                    fileSizeBytes = bootstrap.fileSizeBytes
                )
            } finally {
                bootstrapFile.delete()
            }
        }
    }

    suspend fun registerDownloadedBootstrap(
        databaseId: Long,
        remoteVaultPath: String
    ): MdbxSyncStateSnapshot =
        lock(databaseId) {
            sessions.withSession(databaseId) { engine ->
                val checkpoint = engine.checkpoint()
                MdbxSyncStateSnapshot(
                    vaultId = engine.vaultId,
                    bootstrapCheckpoint = checkpoint,
                    exportCheckpoint = checkpoint
                ).also {
                    stateStore.write(databaseId, it)
                    initializeSidecar(databaseId, engine, remoteVaultPath)
                }
            }
        }

    suspend fun clearLocalState(databaseId: Long) = lock(databaseId) {
        stateStore.delete(databaseId)
        val sidecar = sidecarFile(databaseId)
        if (sidecar.exists() && !sidecar.delete()) {
            throw IOException("Cannot delete MDBX2 sync sidecar")
        }
        listOf(pendingDirectory, incomingDirectory, blobDirectory).forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (file.name.startsWith("segment-$databaseId-") ||
                    file.name.startsWith("segment-receive-$databaseId-") ||
                    file.name.startsWith("blob-upload-$databaseId-") ||
                    file.name.startsWith("blob-download-$databaseId-") ||
                    file.name.startsWith("blob-verify-$databaseId-")) {
                    file.delete()
                }
            }
        }
    }

    suspend fun synchronize(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport
    ): Mdbx2RemoteSyncReport = lock(databaseId) {
        transport.testConnection()
        ensureLocalDirectories()
        sessions.withSession(databaseId) { engine ->
            var state = stateStore.read(databaseId)
            require(state.vaultId == engine.vaultId) {
                "MDBX2 remote sync state belongs to another vault"
            }
            require(state.bootstrapCheckpoint != null && state.exportCheckpoint != null) {
                "MDBX2 remote sync bootstrap state is missing"
            }
            var report = Mdbx2RemoteSyncReport()
            val publication = publishLocalSegments(
                databaseId = databaseId,
                remoteVaultPath = remoteVaultPath,
                transport = transport,
                engine = engine,
                initialState = state
            )
            state = publication.state
            report = report.copy(
                uploadedSegments = publication.uploadedSegments,
                uploadedBlobs = publication.uploadedBlobs
            )

            val receive = receiveRemoteSegments(
                databaseId = databaseId,
                remoteVaultPath = remoteVaultPath,
                transport = transport,
                engine = engine,
                initialState = state
            )
            // Network waits allow local reads/edits to append new commits. Only the
            // published segments and safe remote applies may advance this cursor.
            state = receive.state
            report.copy(
                vaultId = engine.vaultId,
                publishedCheckpoint = state.exportCheckpoint,
                syncedCommitInventory = receive.syncedCommitInventory,
                downloadedSegments = receive.downloadedSegments,
                downloadedBlobs = receive.downloadedBlobs,
                appliedCommits = receive.appliedCommits,
                skippedCommits = receive.skippedCommits,
                conflicts = receive.conflicts,
                blockedStreams = receive.blockedStreams
            )
        }
    }

    private suspend fun publishLocalSegments(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport,
        engine: Mdbx2SyncEngine,
        initialState: MdbxSyncStateSnapshot
    ): PublicationResult {
        var state = initialState
        var uploadedSegments = 0
        var uploadedBlobs = 0
        val verifiedBlobs = mutableMapOf<String, ULong>()
        repeat(MAX_SEGMENTS_PER_SYNC) {
            val current = engine.checkpoint()
            val base = state.exportCheckpoint
                ?: throw IllegalStateException("MDBX2 export checkpoint is missing")
            if (current == base && state.pendingSegment == null) {
                return PublicationResult(state, uploadedSegments, uploadedBlobs)
            }

            val (pendingFile, info) = restoreOrCreatePendingSegment(
                databaseId = databaseId,
                engine = engine,
                state = state,
                base = base
            )
            if (state.pendingSegment == null) {
                state = state.copy(pendingSegment = info.toPendingState(pendingFile))
                stateStore.write(databaseId, state)
            }

            uploadedBlobs += uploadReferencedBlobs(
                databaseId = databaseId,
                remoteVaultPath = remoteVaultPath,
                transport = transport,
                engine = engine,
                generationId = info.transferId,
                verifiedThisSync = verifiedBlobs
            )
            if (info.commitCount > 0u || info.deltaCount > 0u) {
                val remotePath = MdbxRemoteSyncPaths.segmentPath(
                    remoteVaultPath = remoteVaultPath,
                    deviceId = info.sourceDeviceId,
                    generationId = info.transferId,
                    sequence = info.segmentIndex,
                    digestHex = info.payloadSha256Hex
                )
                transport.ensureDirectory(parentPath(remotePath))
                transport.writeFrom(
                    path = remotePath,
                    source = pendingFile,
                    mode = MdbxRemoteWriteMode.CREATE_ONLY
                )
                uploadedSegments += 1
            }
            state = state.copy(
                generationId = info.transferId,
                exportCheckpoint = info.result,
                exportResume = info.nextResume,
                pendingSegment = null
            )
            if (info.commitCount > 0u || info.deltaCount > 0u) {
                // A successfully published local segment is already present
                // locally. Acknowledge its contiguous stream, but still receive
                // unknown historical streams after reopening a bootstrap.
                val streamId = "${info.sourceDeviceId}/${info.transferId}"
                val previous = state.remoteStreams.firstOrNull { it.streamId == streamId }
                if ((previous == null && info.segmentIndex == 0u) ||
                    (previous != null && previous.nextSequence == info.segmentIndex.toLong() &&
                        previous.checkpoint == info.base && previous.blockedReason == null)
                ) {
                    state = state.withRemoteStream(MdbxRemoteStreamState(
                        streamId = streamId,
                        generationId = info.transferId,
                        nextSequence = info.segmentIndex.toLong() + 1,
                        checkpoint = info.result,
                        resume = info.nextResume,
                        lastAppliedDigestHex = info.payloadSha256Hex
                    ))
                }
            }
            stateStore.write(databaseId, state)
            pendingFile.delete()
        }
        throw IOException("MDBX2 generated too many incremental segments in one synchronization")
    }

    private suspend fun restoreOrCreatePendingSegment(
        databaseId: Long,
        engine: Mdbx2SyncEngine,
        state: MdbxSyncStateSnapshot,
        base: MdbxSyncCheckpointState
    ): Pair<File, Mdbx2SegmentInfo> {
        val pending = state.pendingSegment
        if (pending != null) {
            val file = requirePendingFile(pending.path)
            if (!file.isFile || file.length() != pending.fileSizeBytes) {
                throw IOException("MDBX2 pending segment file is missing or truncated")
            }
            val info = engine.inspectSegment(file)
            require(info.vaultId == pending.vaultId &&
                info.sourceDeviceId == pending.sourceDeviceId &&
                info.transferId == pending.transferId &&
                info.segmentIndex.toLong() == pending.streamSequence &&
                info.payloadSha256Hex == pending.payloadSha256Hex &&
                info.base == pending.base && info.base == base && info.result == pending.result &&
                info.nextResume == pending.nextResume
            ) { "MDBX2 pending segment state does not match its authenticated file" }
            return file to info
        }
        val destination = File(
            pendingDirectory,
            "segment-$databaseId-${UUID.randomUUID()}.mdbxsync"
        )
        val info = engine.exportSegment(
            destination = destination,
            base = base,
            resume = state.exportResume,
            pageSize = SEGMENT_PAGE_SIZE
        )
        return destination to info
    }

    private suspend fun uploadReferencedBlobs(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport,
        engine: Mdbx2SyncEngine,
        generationId: String,
        verifiedThisSync: MutableMap<String, ULong>
    ): Int {
        val normalizedRemotePath = MdbxRemoteSyncPaths.normalizePath(remoteVaultPath)
        var sidecar = loadSidecar(databaseId, engine, normalizedRemotePath, generationId)
        var cursor: String? = null
        var uploaded = 0
        do {
            val page = engine.listBlobReferences(cursor, BLOB_PAGE_SIZE)
            page.items.forEach { reference ->
                val totalSize = reference.totalSize
                    ?: throw IOException("MDBX2 Blob ${reference.blobId} has no size")
                if (reference.availability != Mdbx2BlobAvailability.AVAILABLE) {
                    throw IOException("MDBX2 Blob ${reference.blobId} is unavailable locally")
                }
                if (verifiedThisSync[reference.blobId] == totalSize) return@forEach
                val remotePath = MdbxRemoteSyncPaths.blobPath(remoteVaultPath, reference.blobId)
                val existing = transport.stat(remotePath)
                if (existing != null) {
                    if (existing.isDirectory ||
                        existing.sizeBytes?.let { it != totalSize.toLong() } == true
                    ) {
                        throw IOException("MDBX2 remote Blob metadata mismatch: ${reference.blobId}")
                    }
                    val cached = sidecar.blobs.firstOrNull { blob ->
                        blob.blobId == reference.blobId &&
                            blob.totalSize == totalSize.toLong() &&
                            blob.uploaded
                    }
                    if (cached != null) {
                        verifiedThisSync[reference.blobId] = totalSize
                        return@forEach
                    }
                    val verification = File.createTempFile("blob-verify-$databaseId-", ".bin", blobDirectory)
                    try {
                        transport.readTo(remotePath, verification)
                        require(verification.length() == totalSize.toLong() &&
                            MdbxRemoteSyncPaths.sha256Hex(verification) == reference.blobId
                        ) { "MDBX2 remote Blob digest mismatch: ${reference.blobId}" }
                        sidecar = sidecar.withVerifiedBlob(
                            blobId = reference.blobId,
                            totalSize = totalSize.toLong(),
                            downloaded = false
                        )
                        sidecarStore.write(sidecarFile(databaseId), sidecar)
                        verifiedThisSync[reference.blobId] = totalSize
                        return@forEach
                    } finally {
                        verification.delete()
                    }
                }
                val temporary = File.createTempFile("blob-upload-$databaseId-", ".bin", blobDirectory)
                try {
                    writeLocalBlobToFile(engine, reference.blobId, totalSize, temporary)
                    transport.ensureDirectory(parentPath(remotePath))
                    transport.writeFrom(remotePath, temporary, MdbxRemoteWriteMode.CREATE_ONLY)
                    sidecar = sidecar.withVerifiedBlob(
                        blobId = reference.blobId,
                        totalSize = totalSize.toLong(),
                        downloaded = false
                    )
                    sidecarStore.write(sidecarFile(databaseId), sidecar)
                    verifiedThisSync[reference.blobId] = totalSize
                    uploaded += 1
                } finally {
                    temporary.delete()
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return uploaded
    }

    private suspend fun writeLocalBlobToFile(
        engine: Mdbx2SyncEngine,
        blobId: String,
        totalSize: ULong,
        destination: File
    ) {
        engine.withBlobTransfer { local ->
            destination.outputStream().buffered().use { output ->
                var offset = 0uL
                while (offset < totalSize) {
                    val chunk = local.readBlobChunk(
                        blobId = blobId,
                        totalSize = totalSize,
                        offset = offset,
                        maxBytes = BLOB_CHUNK_SIZE
                    )
                    require(chunk.blobId == blobId && chunk.totalSize == totalSize && chunk.offset == offset)
                    require(chunk.ciphertext.isNotEmpty()) { "MDBX2 returned an empty Blob chunk" }
                    output.write(chunk.ciphertext)
                    offset += chunk.ciphertext.size.toULong()
                    require(chunk.isLast == (offset == totalSize)) { "MDBX2 Blob chunk boundary mismatch" }
                }
            }
        }
        require(destination.length() == totalSize.toLong()) { "MDBX2 Blob export size mismatch" }
        require(MdbxRemoteSyncPaths.sha256Hex(destination) == blobId) {
            "MDBX2 Blob export digest mismatch"
        }
    }

    private suspend fun receiveRemoteSegments(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport,
        engine: Mdbx2SyncEngine,
        initialState: MdbxSyncStateSnapshot
    ): ReceiveResult {
        var remaining = listRemoteSegments(remoteVaultPath, transport)
            .groupBy { it.streamKey }.toSortedMap()
            .mapValues { (_, files) -> files.sortedBy(RemoteSegmentDescriptor::sequence) }
        var state = initialState
        var syncedCommitInventory = requireNotNull(initialState.exportCheckpoint).commitInventory
        var downloadedSegments = 0
        var downloadedBlobs = 0
        var appliedCommits = 0
        var skippedCommits = 0
        var conflicts = 0

        var appliedRevision = 0
        val downloaded = mutableMapOf<String, CachedRemoteSegment>()
        try {
            // Retry dependency-blocked streams only after another apply made
            // progress. Transfers from different devices/generations are not
            // ordered by ancestry, and a fixed pass count can strand valid data.
            while (remaining.isNotEmpty()) {
                var progressed = false
                val deferred = linkedMapOf<String, List<RemoteSegmentDescriptor>>()
                for ((streamKey, ordered) in remaining) {
                    var stream = state.remoteStreams.firstOrNull { it.streamId == streamKey }
                    for (descriptor in ordered) {
                        val expectedSequence = stream?.nextSequence ?: 0L
                        if (descriptor.sequence < expectedSequence) {
                            if (stream != null &&
                                descriptor.sequence == expectedSequence - 1L &&
                                stream.lastAppliedDigestHex != null &&
                                !stream.lastAppliedDigestHex.equals(descriptor.digestHex, ignoreCase = true)
                            ) {
                                stream = stream.copy(blockedReason = "conflicting digest for segment ${descriptor.sequence}")
                                state = state.withRemoteStream(stream)
                                stateStore.write(databaseId, state)
                                break
                            }
                            continue
                        }
                        if (descriptor.sequence > expectedSequence) {
                            stream = (stream ?: descriptor.initialStreamState(initialState.bootstrapCheckpoint)).copy(
                                blockedReason = "missing segment $expectedSequence"
                            )
                            state = state.withRemoteStream(stream)
                            stateStore.write(databaseId, state)
                            break
                        }
                        val cached = downloaded[descriptor.path] ?: run {
                            val temporary = File.createTempFile("segment-receive-$databaseId-", ".mdbxsync", incomingDirectory)
                            try {
                                transport.readTo(descriptor.path, temporary)
                                downloadedSegments += 1
                                val info = engine.inspectSegment(temporary)
                                require(info.vaultId == engine.vaultId) { "MDBX2 remote segment belongs to another vault" }
                                require(info.sourceDeviceId == descriptor.deviceId &&
                                    info.transferId == descriptor.generationId &&
                                    info.segmentIndex.toLong() == descriptor.sequence &&
                                    info.payloadSha256Hex.equals(descriptor.digestHex, ignoreCase = true)
                                ) { "MDBX2 remote segment path does not match its authenticated metadata" }
                                CachedRemoteSegment(temporary, info).also { downloaded[descriptor.path] = it }
                            } catch (error: Throwable) {
                                temporary.delete()
                                throw error
                            }
                        }
                        if (cached.attemptedAtRevision == appliedRevision) {
                            deferred[streamKey] = ordered
                            break
                        }
                        cached.attemptedAtRevision = appliedRevision
                        val info = cached.info
                        // A stream previously discovered with a sequence gap has no
                        // authenticated starting checkpoint until segment zero arrives.
                        val currentStream = stream?.takeIf { it.nextSequence > 0L }
                            ?: descriptor.initialStreamState(info.base)
                        val apply = engine.applySegment(
                            source = cached.file,
                            expectedBase = currentStream.checkpoint,
                            expectedResume = currentStream.resume
                        )
                        if (apply.missingParentCount > 0u) {
                            stream = currentStream.copy(
                                blockedReason = "waiting for ${apply.missingParentCount} parent commit(s)"
                            )
                            state = state.withRemoteStream(stream)
                            stateStore.write(databaseId, state)
                            deferred[streamKey] = ordered
                            break
                        }
                        appliedCommits += apply.appliedCommits.toInt()
                        skippedCommits += apply.skippedCommits.toInt()
                        conflicts += apply.conflictCount.toInt()
                        // Remote commits are already synchronized when only local audit
                        // deltas arrived during the download. A concurrent edit must still
                        // prevent advancing the user-visible synchronized commit head.
                        if (syncedCommitInventory == apply.localCheckpointBefore.commitInventory) {
                            syncedCommitInventory = apply.localCheckpointAfter.commitInventory
                        }
                        // Preserve the full transport cursor whenever any local delta,
                        // including an audit record, still needs uploading.
                        if (state.exportCheckpoint == apply.localCheckpointBefore) {
                            state = state.copy(exportCheckpoint = apply.localCheckpointAfter)
                            stateStore.write(databaseId, state)
                        }
                        val nextStream = currentStream.copy(
                            nextSequence = descriptor.sequence + 1,
                            checkpoint = apply.result,
                            resume = apply.nextResume,
                            lastAppliedDigestHex = descriptor.digestHex,
                            blockedReason = null
                        )
                        // Do not acknowledge the remote cursor until every
                        // Blob referenced by the applied segment is present.
                        // If Blob transfer is interrupted, the old cursor
                        // causes an idempotent re-apply and resumes the same
                        // durable Blob transfer on the next run.
                        val blobResult = downloadMissingBlobs(
                            databaseId,
                            remoteVaultPath,
                            transport,
                            engine,
                            state
                        )
                        state = blobResult.state.withRemoteStream(nextStream)
                        stateStore.write(databaseId, state)
                        downloadedBlobs += blobResult.downloadedBlobs
                        stream = nextStream
                        progressed = true
                        appliedRevision += 1
                        downloaded.remove(descriptor.path)
                        cached.file.delete()
                    }
                }
                if (!progressed) break
                remaining = deferred
            }
        } finally {
            downloaded.values.forEach { it.file.delete() }
        }
        return ReceiveResult(
            state = state,
            syncedCommitInventory = syncedCommitInventory,
            downloadedSegments = downloadedSegments,
            downloadedBlobs = downloadedBlobs,
            appliedCommits = appliedCommits,
            skippedCommits = skippedCommits,
            conflicts = conflicts,
            blockedStreams = state.remoteStreams.count { it.blockedReason != null }
        )
    }

    private suspend fun downloadMissingBlobs(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport,
        engine: Mdbx2SyncEngine,
        initialState: MdbxSyncStateSnapshot
    ): BlobDownloadResult {
        var state = initialState
        var cursor: String? = null
        var downloaded = 0
        do {
            val page = engine.listBlobReferences(cursor, BLOB_PAGE_SIZE)
            page.items.forEach { reference ->
                val localSize = reference.totalSize
                if (reference.availability == Mdbx2BlobAvailability.AVAILABLE &&
                    localSize != null &&
                    engine.hasBlob(reference.blobId, localSize)
                ) {
                    return@forEach
                }
                val remotePath = MdbxRemoteSyncPaths.blobPath(remoteVaultPath, reference.blobId)
                val remote = transport.stat(remotePath)
                    ?: throw IOException("MDBX2 remote Blob is missing: ${reference.blobId}")
                val remoteSize = remote.sizeBytes
                    ?: throw IOException("MDBX2 remote Blob has no bounded size: ${reference.blobId}")
                require(remoteSize in 1..MAX_REMOTE_BLOB_BYTES) {
                    "MDBX2 remote Blob exceeds the transfer limit"
                }
                require(localSize == null || remoteSize == localSize.toLong()) {
                    "MDBX2 remote Blob size mismatch"
                }
                val totalSize = remoteSize.toULong()
                val temporary = File.createTempFile("blob-download-$databaseId-", ".bin", blobDirectory)
                try {
                    transport.readTo(remotePath, temporary)
                    require(temporary.length() == totalSize.toLong()) { "MDBX2 downloaded Blob size mismatch" }
                    require(MdbxRemoteSyncPaths.sha256Hex(temporary) == reference.blobId) {
                        "MDBX2 downloaded Blob digest mismatch"
                    }
                    val existingTransfer = state.blobTransfers.firstOrNull {
                        it.blobId == reference.blobId && it.direction == DIRECTION_DOWNLOAD
                    }
                    val ownerId = existingTransfer?.ownerId ?: "android-$databaseId-${UUID.randomUUID()}"
                    var offset = existingTransfer?.nextOffset?.coerceIn(0L, totalSize.toLong()) ?: 0L
                    // The network download has finished. Keep one unlock for this
                    // local file batch, including its durable resume updates.
                    engine.withBlobTransfer { local ->
                        if (existingTransfer == null) {
                            runCatching { local.abortBlobTransfer(reference.blobId, ownerId) }
                        }
                        local.acquireBlobLease(
                            blobId = reference.blobId,
                            ownerId = ownerId,
                            nowUnixSecs = nowMillis() / 1000L,
                            ttlSecs = BLOB_LEASE_TTL_SECONDS
                        )
                        try {
                            RandomAccessFile(temporary, "r").use { input ->
                                input.seek(offset)
                                while (offset < totalSize.toLong()) {
                                    val count = minOf(BLOB_CHUNK_SIZE.toLong(), totalSize.toLong() - offset).toInt()
                                    val bytes = ByteArray(count)
                                    input.readFully(bytes)
                                    val nextOffset = offset + count
                                    local.writeBlobChunk(
                                        blobId = reference.blobId,
                                        totalSize = totalSize,
                                        offset = offset.toULong(),
                                        ciphertext = bytes,
                                        finalize = nextOffset == totalSize.toLong()
                                    )
                                    offset = nextOffset
                                    state = state.withBlobTransfer(
                                        MdbxBlobTransferState(
                                            blobId = reference.blobId,
                                            totalSize = totalSize.toLong(),
                                            ownerId = ownerId,
                                            nextOffset = offset,
                                            direction = DIRECTION_DOWNLOAD,
                                            updatedAt = nowMillis()
                                        )
                                    )
                                    stateStore.write(databaseId, state)
                                }
                            }
                        } finally {
                            runCatching { local.releaseBlobLease(reference.blobId, ownerId) }
                        }
                        require(local.hasBlob(reference.blobId, totalSize)) {
                            "MDBX2 Blob transfer did not finalize"
                        }
                    }
                    val sidecar = loadSidecar(
                        databaseId = databaseId,
                        engine = engine,
                        remoteVaultPath = remoteVaultPath,
                        generationId = state.generationId ?: INITIAL_GENERATION_ID
                    ).withVerifiedBlob(
                        blobId = reference.blobId,
                        totalSize = totalSize.toLong(),
                        downloaded = true
                    )
                    sidecarStore.write(sidecarFile(databaseId), sidecar)
                    state = state.removeBlobTransfer(reference.blobId, DIRECTION_DOWNLOAD)
                    stateStore.write(databaseId, state)
                    downloaded += 1
                } finally {
                    temporary.delete()
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return BlobDownloadResult(state, downloaded)
    }

    private suspend fun listRemoteSegments(
        remoteVaultPath: String,
        transport: MdbxRemoteTransport
    ): List<RemoteSegmentDescriptor> {
        val streamsRoot = MdbxRemoteSyncPaths.streamsRoot(remoteVaultPath)
        return buildList {
            transport.list(streamsRoot).filter(MdbxRemoteObject::isDirectory).forEach { device ->
                transport.list(device.path).filter(MdbxRemoteObject::isDirectory).forEach { generation ->
                    // Both transports return an empty list for a missing directory.
                    transport.list("${generation.path}/$SEGMENTS_DIRECTORY")
                        .filterNot(MdbxRemoteObject::isDirectory)
                        .mapNotNull { remote -> parseSegmentDescriptor(device, generation, remote) }
                        .forEach(::add)
                }
            }
        }
    }

    private fun parseSegmentDescriptor(
        device: MdbxRemoteObject,
        generation: MdbxRemoteObject,
        remote: MdbxRemoteObject
    ): RemoteSegmentDescriptor? {
        val name = remote.path.substringAfterLast('/')
        if (!name.endsWith(SEGMENT_SUFFIX)) return null
        val sequence = name.substringBefore('-').toLongOrNull() ?: return null
        if (sequence < 0L) return null
        val digest = name.substringAfter('-', "").removeSuffix(SEGMENT_SUFFIX)
        if (digest.length != 64 || digest.any { it.lowercase() !in "0123456789abcdef" }) return null
        return RemoteSegmentDescriptor(
            deviceId = device.path.substringAfterLast('/'),
            generationId = generation.path.substringAfterLast('/'),
            sequence = sequence,
            path = remote.path,
            digestHex = digest
        )
    }

    private fun Mdbx2SegmentInfo.toPendingState(file: File): MdbxPendingSegmentState =
        MdbxPendingSegmentState(
            path = file.absolutePath,
            streamId = "$sourceDeviceId/$transferId",
            streamSequence = segmentIndex.toLong(),
            vaultId = vaultId,
            sourceDeviceId = sourceDeviceId,
            transferId = transferId,
            segmentIndex = segmentIndex,
            isLast = isLast,
            base = base,
            result = result,
            nextResume = nextResume,
            payloadSha256Hex = payloadSha256Hex,
            fileSizeBytes = fileSizeBytes.toLong()
        )

    private fun MdbxSyncStateSnapshot.withRemoteStream(
        stream: MdbxRemoteStreamState
    ): MdbxSyncStateSnapshot = copy(
        remoteStreams = (remoteStreams.filterNot { it.streamId == stream.streamId } + stream)
            .sortedBy(MdbxRemoteStreamState::streamId)
    )

    private fun MdbxSyncStateSnapshot.withBlobTransfer(
        transfer: MdbxBlobTransferState
    ): MdbxSyncStateSnapshot = copy(
        blobTransfers = blobTransfers.filterNot {
            it.blobId == transfer.blobId && it.direction == transfer.direction
        } + transfer
    )

    private fun MdbxSyncStateSnapshot.removeBlobTransfer(
        blobId: String,
        direction: String
    ): MdbxSyncStateSnapshot = copy(
        blobTransfers = blobTransfers.filterNot { it.blobId == blobId && it.direction == direction }
    )

    private fun RemoteSegmentDescriptor.initialStreamState(
        checkpoint: MdbxSyncCheckpointState? = null
    ): MdbxRemoteStreamState = MdbxRemoteStreamState(
        streamId = streamKey,
        generationId = generationId,
        nextSequence = 0L,
        checkpoint = checkpoint
            ?: throw IllegalStateException("MDBX2 stream checkpoint is not initialized")
    )

    private fun requirePendingFile(path: String): File {
        val candidate = File(path).canonicalFile
        require(candidate.parentFile == pendingDirectory.canonicalFile) {
            "MDBX2 pending segment escaped its app-private directory"
        }
        return candidate
    }

    private fun ensureLocalDirectories() {
        listOf(rootDirectory, pendingDirectory, incomingDirectory, blobDirectory).forEach { directory ->
            check(directory.exists() || directory.mkdirs()) { "Cannot create MDBX2 sync directory" }
        }
    }

    private suspend fun initializeSidecar(
        databaseId: Long,
        engine: Mdbx2SyncEngine,
        remoteVaultPath: String
    ) {
        val normalizedRemotePath = MdbxRemoteSyncPaths.normalizePath(remoteVaultPath)
        sidecarStore.write(
            sidecarFile(databaseId),
            MdbxSyncSidecarManifest(
                vaultId = engine.vaultId,
                generationId = INITIAL_GENERATION_ID,
                streamId = engine.deviceId,
                remoteVaultPath = normalizedRemotePath
            )
        )
    }

    private suspend fun loadSidecar(
        databaseId: Long,
        engine: Mdbx2SyncEngine,
        remoteVaultPath: String,
        generationId: String
    ): MdbxSyncSidecarManifest {
        val current = sidecarStore.read(sidecarFile(databaseId))
        require(current == null || current.vaultId == engine.vaultId) {
            "MDBX2 sync sidecar belongs to another vault"
        }
        val normalizedRemotePath = MdbxRemoteSyncPaths.normalizePath(remoteVaultPath)
        val base = current?.takeIf { it.remoteVaultPath == normalizedRemotePath }
        return (base ?: MdbxSyncSidecarManifest(
            vaultId = engine.vaultId,
            generationId = generationId,
            streamId = engine.deviceId,
            remoteVaultPath = normalizedRemotePath
        )).copy(
            generationId = generationId,
            streamId = engine.deviceId,
            remoteVaultPath = normalizedRemotePath
        )
    }

    private fun MdbxSyncSidecarManifest.withVerifiedBlob(
        blobId: String,
        totalSize: Long,
        downloaded: Boolean
    ): MdbxSyncSidecarManifest {
        val previous = blobs.firstOrNull { it.blobId == blobId }
        val next = MdbxSyncSidecarBlob(
            blobId = blobId,
            totalSize = totalSize,
            uploaded = true,
            downloaded = downloaded || previous?.downloaded == true
        )
        return copy(
            blobs = (blobs.filterNot { it.blobId == blobId } + next)
                .sortedBy(MdbxSyncSidecarBlob::blobId)
        )
    }

    private fun sidecarFile(databaseId: Long): File =
        File(rootDirectory, "sync-state-$databaseId.json")

    private fun atomicReplace(source: File, destination: File) {
        try {
            java.nio.file.Files.move(
                source.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            if (!source.renameTo(destination)) {
                throw IOException("Cannot publish MDBX2 bootstrap")
            }
        }
    }

    private suspend fun ensureRemoteParent(
        transport: MdbxRemoteTransport,
        path: String
    ) {
        val parent = parentPath(MdbxRemoteSyncPaths.normalizePath(path))
        if (parent.isNotBlank()) transport.ensureDirectory(parent)
    }

    private fun parentPath(path: String): String = path.substringBeforeLast('/', "")

    private suspend fun <T> lock(databaseId: Long, block: suspend () -> T): T =
        locks.getOrPut(databaseId) { Mutex() }.withLock { block() }

    private data class PublicationResult(
        val state: MdbxSyncStateSnapshot,
        val uploadedSegments: Int,
        val uploadedBlobs: Int
    )

    private data class ReceiveResult(
        val state: MdbxSyncStateSnapshot,
        val syncedCommitInventory: String,
        val downloadedSegments: Int,
        val downloadedBlobs: Int,
        val appliedCommits: Int,
        val skippedCommits: Int,
        val conflicts: Int,
        val blockedStreams: Int
    )

    private data class BlobDownloadResult(
        val state: MdbxSyncStateSnapshot,
        val downloadedBlobs: Int
    )

    private data class RemoteSegmentDescriptor(
        val deviceId: String,
        val generationId: String,
        val sequence: Long,
        val path: String,
        val digestHex: String
    ) {
        val streamKey: String get() = "$deviceId/$generationId"
    }

    private data class CachedRemoteSegment(
        val file: File,
        val info: Mdbx2SegmentInfo,
        var attemptedAtRevision: Int = -1
    )

    companion object {
        private const val SEGMENT_SUFFIX = ".mdbxsync"
        private const val SEGMENTS_DIRECTORY = "segments"
        private const val DIRECTION_DOWNLOAD = "download"
        private const val INITIAL_GENERATION_ID = "bootstrap"
        private const val MAX_SEGMENTS_PER_SYNC = 10_000
        private const val BLOB_LEASE_TTL_SECONDS = 15 * 60L
        private const val MAX_REMOTE_BLOB_BYTES = 64L * 1024L * 1024L + 128L * 1024L
        private val SEGMENT_PAGE_SIZE = 128u
        private val BLOB_PAGE_SIZE = 256u
        private val BLOB_CHUNK_SIZE = 512u * 1024u
    }
}

internal data class Mdbx2RemoteBootstrapResult(
    val vaultId: String,
    val checkpoint: MdbxSyncCheckpointState,
    val fileSizeBytes: ULong
)

internal data class Mdbx2RemoteSyncReport(
    val vaultId: String? = null,
    val publishedCheckpoint: MdbxSyncCheckpointState? = null,
    // May include received commits while the transport cursor retains local audit deltas.
    val syncedCommitInventory: String? = null,
    val uploadedSegments: Int = 0,
    val downloadedSegments: Int = 0,
    val uploadedBlobs: Int = 0,
    val downloadedBlobs: Int = 0,
    val appliedCommits: Int = 0,
    val skippedCommits: Int = 0,
    val conflicts: Int = 0,
    val blockedStreams: Int = 0
)
