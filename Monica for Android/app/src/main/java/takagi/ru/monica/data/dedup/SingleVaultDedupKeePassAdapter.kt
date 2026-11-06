package takagi.ru.monica.data.dedup

import android.content.Context
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Group
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import takagi.ru.monica.data.KeePassDatabaseSourceType
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.resolvedActiveFilePath
import takagi.ru.monica.data.resolvedActiveStorageLocation
import takagi.ru.monica.keepass.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.*

internal class SingleVaultDedupKeePassAdapter(
    context: Context,
    private val db: PasswordDatabase,
    private val security: SecurityManager,
    private val databaseId: Long,
    private val label: String,
) : SingleVaultDedupSourceAdapter {
    private val service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
    private val payloadStore = SingleVaultDedupPayloadStore(context, security)
    private var writableAfterRead: Boolean? = null
    override val source: SingleVaultDedupUiSource
        get() {
            val registration = db.localKeePassDatabaseDao().getAllDatabasesSync().singleOrNull { it.id == databaseId }
            val unlocked = service.isDatabaseUnlocked(databaseId)
            val canClean = registration?.sourceType == KeePassDatabaseSourceType.LOCAL_INTERNAL &&
                registration.resolvedActiveStorageLocation() == KeePassStorageLocation.INTERNAL && unlocked &&
                !service.isDatabaseReadOnly(databaseId) && writableAfterRead != false
            return SingleVaultDedupUiSource("keepass:$databaseId", label, canClean,
                if (!unlocked) SingleVaultDedupUiIssue.LOCKED else if (!canClean) SingleVaultDedupUiIssue.ANALYSIS_ONLY else null)
        }

    private class Capture(val read: SingleVaultDedupKeePassRead, val includeArchived: Boolean, projects: List<SingleVaultDedupProject>) : SingleVaultDedupCapture(projects)

    override suspend fun capture(includeArchived: Boolean, checkActive: () -> Unit): SingleVaultDedupCapture {
        checkActive()
        val read = service.readNativeForDedup(databaseId)
        checkActive()
        writableAfterRead = read.canClean && read.xmlComplete
        return Capture(read, includeArchived, SingleVaultDedupKeePassContent.projects(read.session, includeArchived, read.xmlComplete, checkActive))
    }

    override suspend fun clean(captured: SingleVaultDedupCapture, selected: List<SingleVaultDedupCandidate>, checkActive: () -> Unit): SingleVaultDedupUiOutcome {
        val capturedNative = captured as? Capture ?: return outcome(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED)
        if (selected.isEmpty()) return outcome(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED)
        if (!source.canClean || !capturedNative.read.canClean || !capturedNative.read.xmlComplete) return outcome(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED)
        checkActive()
        val current = service.readNativeForDedup(databaseId)
        if (!current.canClean || !current.xmlComplete || current.session.sourceRevision != capturedNative.read.session.sourceRevision || current.lockVersion != capturedNative.read.lockVersion)
            return outcome(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED)
        val analysis = SingleVaultDedupComparator.analyze(capturedNative.projects, checkActive)
        if (selected.distinct().size != selected.size || selected.any { it !in analysis.candidates }) return outcome(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED)
        val byKey = capturedNative.projects.associateBy { it.summary.key }
        val selectedIds = selected.flatMap { byKey.getValue(it.candidateKey).summary.memberIds }.toSet()
        val keeperIds = selected.flatMap { byKey.getValue(it.keeperKey).summary.memberIds }.toSet()
        if (selectedIds.intersect(keeperIds).isNotEmpty()) return outcome(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED)
        fun uuid(id: Long) = current.session.entryNodes[(id - 1).toInt()].entry.uuid
        val removed = selectedIds.map(::uuid).toSet()
        val keepers = keeperIds.map(::uuid).toSet()
        val batchId = UUID.randomUUID().toString()
        var prepared = false
        try {
            service.changeNativeForDedup(databaseId, current.session.sourceRevision, current.lockVersion, transform = { original ->
                require(original.content.meta.recycleBinEnabled)
                KeePassNativeManagement.deleteEntries(original, removed, KeePassNativeDeleteMode.RECYCLE_BIN)
            }, beforeCommit = { original, written ->
                checkActive()
                val originalSession = session(original)
                val writtenSession = session(written)
                removed.forEach { id ->
                    val after = unique(writtenSession, id)
                    require(after.isInRecycleBin)
                    val before = unique(originalSession, id)
                    require(!before.isInRecycleBin)
                    require(SingleVaultDedupKeePassContent.entry(before.entry) == SingleVaultDedupKeePassContent.entry(after.entry.copy(times = before.entry.times, previousParentGroup = before.entry.previousParentGroup)))
                }
                keepers.forEach { id -> require(SingleVaultDedupKeePassContent.payload(original, id) == SingleVaultDedupKeePassContent.payload(written, id)) }
                val registration = requireNotNull(db.localKeePassDatabaseDao().getDatabaseById(databaseId))
                val payload = buildJsonObject {
                    put("version", 2); put("batchId", batchId); put("source", source.key); put("databaseFile", registration.resolvedActiveFilePath())
                    put("rootUuid", original.content.group.uuid.toString())
                    put("original", JsonArray(removed.map { SingleVaultDedupKeePassContent.payload(original, it) }))
                    put("retired", JsonArray(removed.map { SingleVaultDedupKeePassContent.payload(written, it) }))
                    put("recoveryCopies", JsonObject(removed.associate { it.toString() to JsonPrimitive(recoveryCopyId(batchId, it).toString()) }))
                }
                val text = payload.toString()
                require(text.toByteArray(Charsets.UTF_8).size <= SingleVaultDedupPayloadStore.MAX_PLAIN)
                payload.getValue("original").jsonArray.forEach { raw ->
                    val decoded = SingleVaultDedupKeePassContent.decodePayload(raw.jsonObject)
                    require(SingleVaultDedupKeePassContent.equivalentPayload(decoded, KeePassLosslessTransfer.captureEntry(original, decoded.entry.uuid)))
                }
                payload.getValue("retired").jsonArray.forEach { raw ->
                    val decoded = SingleVaultDedupKeePassContent.decodePayload(raw.jsonObject)
                    require(SingleVaultDedupKeePassContent.equivalentPayload(decoded, KeePassLosslessTransfer.captureEntry(written, decoded.entry.uuid)))
                }
                val sealed = payloadStore.write(batchId, source.key, text, checkActive)
                db.singleVaultDedupRecoveryDao().insert(SingleVaultDedupRecoveryEntity(batchId, source.key, System.currentTimeMillis(), selected.size, sealed, "PREPARED"))
                val persisted = requireNotNull(db.singleVaultDedupRecoveryDao().get(batchId))
                require(payloadStore.read(batchId, source.key, persisted.sealedPayload) == text)
                prepared = true
            }, checkActive = checkActive)
            withContext(NonCancellable) { db.singleVaultDedupRecoveryDao().transition(batchId, "PREPARED", "COMMITTED") }
            return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.SUCCESS, selected.size, batchId)
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            return SingleVaultDedupUiOutcome(if (prepared) SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED else SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED,
                recoveryBatchId = batchId.takeIf { prepared })
        }
    }

    override suspend fun restore(batch: SingleVaultDedupRecoveryEntity, checkActive: () -> Unit): SingleVaultDedupUiOutcome {
        if (batch.sourceKey != source.key || batch.state !in setOf("PREPARED", "COMMITTED")) return outcome(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED)
        try {
            checkActive()
            val stored = requireNotNull(db.singleVaultDedupRecoveryDao().get(batch.id))
            if (stored.state == "RESTORED") return outcome(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED)
            require(stored == batch)
            val inline = Json.parseToJsonElement(security.decryptData(batch.sealedPayload)).jsonObject
            val payload = if (inline["kind"] == JsonPrimitive(SingleVaultDedupPayloadStore.KIND)) {
                Json.parseToJsonElement(payloadStore.read(batch.id, source.key, batch.sealedPayload)).jsonObject
            } else inline
            val version = payload.getValue("version").jsonPrimitive.int
            require(version in 1..2 && payload.getValue("source").jsonPrimitive.content == source.key)
            if (version == 2) require(payload["batchId"] == JsonPrimitive(batch.id))
            val current = service.readNativeForDedup(databaseId)
            require(current.canClean && current.xmlComplete)
            val registration = requireNotNull(db.localKeePassDatabaseDao().getDatabaseById(databaseId))
            require(payload.getValue("databaseFile").jsonPrimitive.content == registration.resolvedActiveFilePath())
            require(payload.getValue("rootUuid").jsonPrimitive.content == current.session.database.content.group.uuid.toString())
            val originals = payload.getValue("original").jsonArray.map { SingleVaultDedupKeePassContent.decodePayload(it.jsonObject) }
            val retired = payload.getValue("retired").jsonArray.map { SingleVaultDedupKeePassContent.decodePayload(it.jsonObject) }
            val copyIds = originals.associate { it.entry.uuid to recoveryCopyId(batch.id, it.entry.uuid) }
            if (version == 2) require(payload.getValue("recoveryCopies").jsonObject == JsonObject(copyIds.mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value.toString()) }))
            val plan = restorePlan(current.session.database, originals, retired, copyIds)
            if (plan.database !== current.session.database) {
                service.changeNativeForDedup(databaseId, current.session.sourceRevision, current.lockVersion, transform = { original -> restorePlan(original, originals, retired, copyIds).database },
                    beforeCommit = { _, written ->
                        plan.targets.forEach { original ->
                            val verified = KeePassLosslessTransfer.captureEntry(written, original.entry.uuid)
                            require(SingleVaultDedupKeePassContent.equivalentPayload(original, verified))
                        }
                    }, checkActive = checkActive)
            }
            withContext(NonCancellable) { require(db.singleVaultDedupRecoveryDao().transition(batch.id, batch.state, "RESTORED") == 1) }
            return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.SUCCESS, batch.projectCount, batch.id, restoredCopyCount = plan.copyCount)
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) { return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED, recoveryBatchId = batch.id) }
    }

    private fun session(database: KeePassDatabase) = KeePassNativeSessionBuilder.build(databaseId, KeePassSourceRevision("", 0), database) { parent, name ->
        if (parent.isNullOrEmpty()) name else "$parent/$name"
    }
    private fun unique(session: KeePassNativeSession, id: UUID) = requireNotNull(session.entriesByIdentity[KeePassNativeEntryIdentity(databaseId, id)]?.singleOrNull())
    private fun outcome(status: SingleVaultDedupUiOutcomeStatus) = SingleVaultDedupUiOutcome(status)

    companion object {
        internal fun recoveryCopyId(batchId: String, originalId: UUID): UUID {
            require(UUID.fromString(batchId).toString() == batchId)
            return UUID.nameUUIDFromBytes("monica-dedup-recovery-copy-v1:$batchId:$originalId".toByteArray(Charsets.UTF_8))
        }

        internal class RestorePlan(val database: KeePassDatabase, val targets: List<KeePassNativeEntryTransferPayload>, val copyCount: Int)

        /** Missing originals use a stable new identity; tombstones and changed objects are never overwritten. */
        internal fun restoreSelected(database: KeePassDatabase, originals: List<KeePassNativeEntryTransferPayload>, retired: List<KeePassNativeEntryTransferPayload>,
            copyIds: Map<UUID, UUID> = emptyMap()): KeePassDatabase = restorePlan(database, originals, retired, copyIds).database

        internal fun restorePlan(database: KeePassDatabase, originals: List<KeePassNativeEntryTransferPayload>, retired: List<KeePassNativeEntryTransferPayload>,
            copyIds: Map<UUID, UUID>): RestorePlan {
            require(originals.isNotEmpty() && originals.map { it.entry.uuid }.distinct().size == originals.size)
            require(originals.map { it.entry.uuid }.toSet() == retired.map { it.entry.uuid }.toSet())
            require(copyIds.values.distinct().size == copyIds.size && copyIds.values.none { id -> originals.any { it.entry.uuid == id } })
            val session = KeePassNativeSessionBuilder.build(1, KeePassSourceRevision("", 0), database) { parent, name -> if (parent == null) name else "$parent/$name" }
            require(session.duplicateEntryIdentities.isEmpty() && session.duplicateGroupIdentities.isEmpty())
            val restore = mutableListOf<KeePassNativeEntryTransferPayload>()
            var copyCount = 0
            val targets = originals.map { original ->
                val current = session.entriesByIdentity[KeePassNativeEntryIdentity(1, original.entry.uuid)]?.singleOrNull()
                val copyId = copyIds[original.entry.uuid]
                val existingCopy = copyId?.let { session.entriesByIdentity[KeePassNativeEntryIdentity(1, it)]?.singleOrNull() }
                require(session.groupsByIdentity[KeePassNativeGroupIdentity(1, original.sourceParentGroupUuid)]?.singleOrNull()?.isInRecycleBin == false)
                if (current == null) {
                    requireNotNull(copyId)
                    require(session.groupsByIdentity[KeePassNativeGroupIdentity(1, copyId)].isNullOrEmpty())
                    require(database.content.deletedObjects.none { it.id == copyId })
                    fun copyHistory(entry: app.keemobile.kotpass.models.Entry): app.keemobile.kotpass.models.Entry = entry.copy(
                        uuid = if (entry.uuid == original.entry.uuid) copyId else entry.uuid, history = entry.history.map(::copyHistory))
                    val recovered = original.copy(entry = copyHistory(original.entry))
                    if (existingCopy == null) restore += recovered else {
                        require(!existingCopy.isInRecycleBin)
                        require(SingleVaultDedupKeePassContent.equivalentPayload(recovered, KeePassLosslessTransfer.captureEntry(database, copyId)))
                    }
                    copyCount++
                    return@map recovered
                }
                require(existingCopy == null)
                val old = retired.single { it.entry.uuid == original.entry.uuid }
                if (current.parentGroup.groupUuid == original.sourceParentGroupUuid && SingleVaultDedupKeePassContent.entry(original.entry) == SingleVaultDedupKeePassContent.entry(current.entry)) {
                    require(SingleVaultDedupKeePassContent.equivalentPayload(original, KeePassLosslessTransfer.captureEntry(database, original.entry.uuid)))
                }
                else {
                    require(current.isInRecycleBin && SingleVaultDedupKeePassContent.equivalentPayload(old, KeePassLosslessTransfer.captureEntry(database, current.entry.uuid)))
                    restore += original
                }
                original
            }
            if (restore.isEmpty()) return RestorePlan(database, targets, copyCount)
            val ids = restore.map { it.entry.uuid }.toSet()
            fun detach(group: Group): Group = group.copy(entries = group.entries.filterNot { it.uuid in ids }, groups = group.groups.map(::detach))
            val restored = restore.fold(database.modifyParentGroup { detach(this) }) { current, original ->
                KeePassLosslessTransfer.insertEntry(current, original.sourceParentGroupUuid, original)
            }
            require(restored.content.deletedObjects == database.content.deletedObjects)
            targets.forEach { require(SingleVaultDedupKeePassContent.equivalentPayload(it, KeePassLosslessTransfer.captureEntry(restored, it.entry.uuid))) }
            return RestorePlan(restored, targets, copyCount)
        }
    }
}
