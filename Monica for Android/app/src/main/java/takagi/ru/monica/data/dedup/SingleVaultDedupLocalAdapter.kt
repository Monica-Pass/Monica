package takagi.ru.monica.data.dedup

import android.content.Context
import androidx.room.withTransaction
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.*

/** Room originals, recovery receipt and tombstones commit together. Never writes an external projection. */
internal class SingleVaultDedupLocalAdapter(
    context: Context,
    private val db: PasswordDatabase,
    private val security: SecurityManager,
    label: String,
) : SingleVaultDedupSourceAdapter {
    override val source = SingleVaultDedupUiSource("local", label, true)
    private val dao get() = db.singleVaultDedupLocalDao()
    private val assets = SingleVaultDedupLocalAssets(context)
    private val codec = SingleVaultDedupRowCodec

    private data class Rows(
        val passwords: List<PasswordEntry>, val items: List<SecureItem>, val fields: List<CustomField>,
        val attachments: List<Attachment>, val history: List<PasswordHistoryEntry>, val passkeys: List<PasskeyEntry>,
        val timeline: List<TimelineVersionSnapshot>, val archive: List<PasswordArchiveSyncMeta>,
        val stacks: List<PasswordPageAggregateStackEntry>, val logs: List<OperationLog>,
        val deviceSync: SingleVaultDedupDeviceSyncAudit.Snapshot,
    ) { override fun toString() = "Rows(<redacted>)" }
    private class Capture(
        val rows: Rows, projects: List<SingleVaultDedupProject>,
        val assetEvidence: List<SingleVaultDedupLocalAssets.Evidence>,
    ) : SingleVaultDedupCapture(projects)

    private suspend fun readRows(): Rows = Rows(dao.passwords(), dao.items(), dao.fields(), dao.attachments(),
        dao.history(), dao.passkeys(), dao.timeline(), dao.archive(), dao.stacks(), dao.logs(), SingleVaultDedupDeviceSyncAudit.read(db))

    override suspend fun capture(includeArchived: Boolean, checkActive: () -> Unit): SingleVaultDedupCapture {
        checkActive()
        val rows = db.withTransaction { readRows() }
        val passwords = rows.passwords.filter { it.isLocalOnlyEntry() }
        val items = rows.items.filter { it.isLocalOnlyItem() }
        val reasons = mutableMapOf<Long, MutableSet<SingleVaultDedupBlockReason>>()
        fun block(id: Long, reason: SingleVaultDedupBlockReason) { reasons.getOrPut(id) { mutableSetOf() }.add(reason) }
        rows.deviceSync.passwordIds.forEach { block(it, SingleVaultDedupBlockReason.EXTERNAL_REFERENCE) }
        val syncItemIds = rows.deviceSync.itemIds.toMutableSet()
        rows.attachments.filter { it.id in rows.deviceSync.attachmentIds }.forEach { attachment ->
            attachment.parentPasswordId?.let { block(it, SingleVaultDedupBlockReason.EXTERNAL_REFERENCE) }
            attachment.parentSecureItemId?.let(syncItemIds::add)
        }
        val parsedItems = mutableMapOf<Long, JsonElement>()
        var unreadableReference = false
        // Look at all owners, including hidden/deleted rows. Removing a local target must not break a foreign reference.
        rows.items.forEach { item ->
            checkActive()
            try {
                val json = SingleVaultDedupLocalContent.decodeJson(SingleVaultDedupProjectBuilder.strictJson(decode(item.itemData)), ::decode)
                parsedItems[item.id] = json
                referencedPasswordIds(json).forEach { block(it, SingleVaultDedupBlockReason.EXTERNAL_REFERENCE) }
                if (!SingleVaultDedupLocalContent.knownSchema(item.itemType, json)) unreadableReference = true
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { parsedItems.remove(item.id); unreadableReference = true }
        }
        rows.passwords.mapNotNull { it.ssoRefEntryId }.forEach { block(it, SingleVaultDedupBlockReason.EXTERNAL_REFERENCE) }
        rows.passkeys.mapNotNull { it.boundPasswordId }.forEach {
            block(it, SingleVaultDedupBlockReason.PASSKEY_REFERENCE)
            block(it, SingleVaultDedupBlockReason.EXTERNAL_REFERENCE)
        }
        rows.stacks.forEach { stack -> stack.itemKey.removePrefix("password:").toLongOrNull()?.let {
            block(it, SingleVaultDedupBlockReason.EXTERNAL_REFERENCE)
        } }
        if (unreadableReference) passwords.forEach { block(it.id, SingleVaultDedupBlockReason.UNREADABLE_CONTENT) }
        val evidence = mutableListOf<SingleVaultDedupLocalAssets.Evidence>()
        val assetProblems = mutableSetOf<Long>()
        val macKey = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            rows.attachments.filter { row -> passwords.any { it.id == row.parentPasswordId } || items.any { it.id == row.parentSecureItemId } }.forEach { row ->
                try { evidence += assets.inspect(row, macKey, checkActive)
                } catch (cancel: CancellationException) { throw cancel
                } catch (_: Exception) {
                    row.parentPasswordId?.let { block(it, SingleVaultDedupBlockReason.UNREADABLE_CONTENT) }
                    row.parentSecureItemId?.let(assetProblems::add)
                }
            }
        } finally { macKey.fill(0) }
        fun assetNodes(passwordId: Long? = null, itemId: Long? = null) = evidence.filter {
            if (passwordId != null) it.row.parentPasswordId == passwordId else it.row.parentSecureItemId == itemId
        }.map { SingleVaultDedupNode("attachment", mapOf("name" to it.row.fileName, "mime" to it.row.mimeType,
            "size" to it.row.sizeBytes.toString(), "content" to it.comparisonMac, "deleted" to it.row.isDeleted.toString())) }
        val supplemental = passwords.associate { entry ->
            val nodes = mutableListOf<SingleVaultDedupNode>()
            try {
                rows.history.filter { it.entryId == entry.id }.forEach { history -> nodes += SingleVaultDedupNode("password-history",
                    mapOf("password" to decode(history.password), "lastUsedAt" to history.lastUsedAt.time.toString())) }
                rows.timeline.filter { it.itemType == "PASSWORD" && it.itemId == entry.id }.forEach { timeline ->
                    require(rows.logs.singleOrNull { it.id == timeline.operationLogId }?.let { it.itemType == timeline.itemType && it.itemId == timeline.itemId } == true)
                    nodes += SingleVaultDedupProjectBuilder.exactJsonNode("timeline", SingleVaultDedupProjectBuilder.strictJson(security.decryptTimelineSnapshot(timeline.encryptedChangesJson)))
                }
                nodes += assetNodes(passwordId = entry.id)
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { block(entry.id, SingleVaultDedupBlockReason.UNREADABLE_CONTENT) }
            entry.id to nodes
        }
        val passwordProjects = SingleVaultDedupProjectBuilder.build(source.key, passwords,
            rows.fields.groupBy { it.entryId }, ::decode,
            SingleVaultDedupEvidence(rows.deviceSync.complete, passwords.map { it.id }.toSet(), reasons, supplemental), includeArchived, checkActive)
        val itemProjects = items.filter { !it.isDeleted && (includeArchived || !it.isArchived) }.map { item ->
            checkActive()
            val blockers = mutableSetOf<SingleVaultDedupBlockReason>()
            if (!rows.deviceSync.complete) blockers += SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT
            if (item.id in syncItemIds) blockers += SingleVaultDedupBlockReason.EXTERNAL_REFERENCE
            val json = parsedItems[item.id]
            if (json == null || item.id in assetProblems) blockers += SingleVaultDedupBlockReason.UNREADABLE_CONTENT
            if (json != null && !SingleVaultDedupLocalContent.knownSchema(item.itemType, json)) blockers += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
            if (json != null && DedupStructuredPasskeyPolicy.containsPasskeyContent(json))
                blockers += SingleVaultDedupBlockReason.PASSKEY_PRESERVED
            if (item.replicaGroupId != null || item.imagePaths !in setOf("", "[]") ||
                rows.passwords.any { it.boundNoteId == item.id } || rows.stacks.any { it.itemKey in SingleVaultDedupLocalContent.secureStackKeys(item.id) } ||
                json?.let { referencedPasswordIds(it).isNotEmpty() || SingleVaultDedupLocalContent.externalAssets(it,
                    rows.attachments.filter { row -> row.parentSecureItemId == item.id }.map { row -> row.fileName }.toSet()) } == true)
                blockers += SingleVaultDedupBlockReason.EXTERNAL_REFERENCE
            var node: SingleVaultDedupNode? = null
            try {
                if (json != null) node = SingleVaultDedupNode("secure-item", mapOf("type" to item.itemType.name, "title" to item.title,
                    "notes" to decode(item.notes), "category" to item.categoryId.toString(), "favorite" to item.isFavorite.toString(),
                    "archived" to item.isArchived.toString()), listOf(SingleVaultDedupProjectBuilder.exactJsonNode("data", json)) +
                    assetNodes(itemId = item.id) + rows.timeline.filter { it.itemType == item.itemType.name && it.itemId == item.id }.map {
                        require(rows.logs.singleOrNull { log -> log.id == it.operationLogId }?.let { log -> log.itemType == it.itemType && log.itemId == it.itemId } == true)
                        SingleVaultDedupProjectBuilder.exactJsonNode("timeline", SingleVaultDedupProjectBuilder.strictJson(security.decryptTimelineSnapshot(it.encryptedChangesJson)))
                    })
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { blockers += SingleVaultDedupBlockReason.UNREADABLE_CONTENT }
            SingleVaultDedupProject(SingleVaultDedupProjectSummary("secure:${item.id}", source.key, item.title,
                listOf(item.id), node?.contentCount() ?: 0), node, blockers)
        }
        val passkeyProjects = rows.passkeys.filter { it.isLocalOnlyPasskey() }.map {
            SingleVaultDedupProject(SingleVaultDedupProjectSummary("passkey:${it.id}", source.key, it.rpName, listOf(it.id), 1), null,
                setOf(SingleVaultDedupBlockReason.PASSKEY_PRESERVED))
        }
        checkActive()
        check(db.withTransaction { readRows() } == rows) { "Source changed during scan" }
        return Capture(rows, passwordProjects + itemProjects + passkeyProjects, evidence)
    }

    override suspend fun clean(captured: SingleVaultDedupCapture, selected: List<SingleVaultDedupCandidate>, checkActive: () -> Unit): SingleVaultDedupUiOutcome {
        val capturedLocal = captured as Capture
        require(selected.isNotEmpty() && selected.map { it.candidateKey }.distinct().size == selected.size)
        val byKey = captured.projects.associateBy { it.summary.key }
        val deleting = selected.map { it.candidateKey }.toSet()
        require(selected.none { it.keeperKey in deleting })
        selected.forEach { require(SingleVaultDedupComparator.covers(byKey.getValue(it.keeperKey), byKey.getValue(it.candidateKey), checkActive)) }
        val selectedProjects = deleting.map(byKey::getValue)
        val passwordIds = selectedProjects.filter { !it.summary.key.startsWith("secure:") }.flatMap { it.summary.memberIds }.toSet()
        val itemIds = selectedProjects.filter { it.summary.key.startsWith("secure:") }.flatMap { it.summary.memberIds }.toSet()
        val originals = capturedLocal.rows
        val passwords = originals.passwords.filter { it.id in passwordIds }
        val items = originals.items.filter { it.id in itemIds }
        require(passwords.all { it.isLocalOnlyEntry() && !it.isDeleted } && items.all { it.isLocalOnlyItem() && !it.isDeleted })
        val attachments = originals.attachments.filter { it.parentPasswordId in passwordIds || it.parentSecureItemId in itemIds }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val backups = assets.backup(id, capturedLocal.assetEvidence.filter { it.row in attachments }, checkActive)
        try {
        val timeline = originals.timeline.filter { row -> if (row.itemType == "PASSWORD") row.itemId in passwordIds else items.any { it.id == row.itemId && it.itemType.name == row.itemType } }
        val logs = originals.logs.filter { log -> if (log.itemType == "PASSWORD") log.itemId in passwordIds else
            items.any { it.id == log.itemId && it.itemType.name == log.itemType } }
        val payload = buildJsonObject {
            put("version", 1); put("batchId", id); put("deletedAt", now)
            put("deviceSyncAudit", originals.deviceSync.revision)
            put("passwords", JsonArray(passwords.map(codec::encode))); put("items", JsonArray(items.map(codec::encode)))
            put("fields", JsonArray(originals.fields.filter { it.entryId in passwordIds }.map(codec::encode)))
            put("history", JsonArray(originals.history.filter { it.entryId in passwordIds }.map(codec::encode)))
            put("archive", JsonArray(originals.archive.filter { it.entryId in passwordIds }.map(codec::encode)))
            put("attachments", JsonArray(attachments.map(codec::encode)))
            put("timeline", JsonArray(timeline.map(codec::encode))); put("logs", JsonArray(logs.map(codec::encode)))
            put("blobs", JsonArray(backups.map { buildJsonObject {
                put("id", it.attachmentId); put("file", it.fileName); put("hash", it.ciphertextHash)
            } }))
        }.toString()
        // Room's cursor window and crypto strings are bounded; failure leaves every original intact.
        check(payload.toByteArray().size <= 512 * 1024) { "Recovery batch is too large" }
        val sealed = security.encryptTimelineSnapshot(payload)
        check(security.decryptTimelineSnapshot(sealed) == payload)
        val receipt = SingleVaultDedupRecoveryEntity(id, source.key, now, selected.size, sealed, "COMMITTED")
        db.withTransaction {
            checkActive()
            check(readRows() == originals) { "Source changed since review" }
            capturedLocal.assetEvidence.forEach { assets.verify(it, checkActive) }
            db.singleVaultDedupRecoveryDao().insert(receipt)
            check(db.singleVaultDedupRecoveryDao().get(id) == receipt)
            check(dao.updatePasswords(passwords.map { it.copy(isDeleted = true, deletedAt = Date(now), updatedAt = Date(now)) }) == passwords.size)
            check(dao.updateItems(items.map { it.copy(isDeleted = true, deletedAt = Date(now), updatedAt = Date(now)) }) == items.size)
            val activeAttachments = attachments.filterNot { it.isDeleted }
            check(dao.updateAttachments(activeAttachments.map { it.copy(isDeleted = true, deletedAt = now, updatedAt = now) }) == activeAttachments.size)
            checkActive()
        }
        return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.SUCCESS, selected.size, id)
        } finally {
            withContext(NonCancellable) {
                // Query the commit receipt before cleanup, including cancellation after commit.
                runCatching { if (db.singleVaultDedupRecoveryDao().get(id) == null) assets.discardBackup(id, backups) }
            }
        }
    }

    override suspend fun restore(batch: SingleVaultDedupRecoveryEntity, checkActive: () -> Unit): SingleVaultDedupUiOutcome {
        require(batch.sourceKey == source.key && batch.state == "COMMITTED")
        val obj = SingleVaultDedupProjectBuilder.strictJson(security.decryptTimelineSnapshot(batch.sealedPayload)).jsonObject
        require(obj["version"] == JsonPrimitive(1) && obj["batchId"] == JsonPrimitive(batch.id))
        val at = obj.getValue("deletedAt").jsonPrimitive.long
        fun <T> list(name: String, decode: (JsonObject) -> T) = obj.getValue(name).jsonArray.map { decode(it.jsonObject) }
        val passwords = list("passwords", codec::decodePasswordEntry)
        val items = list("items", codec::decodeSecureItem)
        val fields = list("fields", codec::decodeCustomField)
        val history = list("history", codec::decodePasswordHistoryEntry)
        val archive = list("archive", codec::decodePasswordArchiveSyncMeta)
        val attachments = list("attachments", codec::decodeAttachment)
        val timeline = list("timeline", codec::decodeTimelineVersionSnapshot)
        val logs = list("logs", codec::decodeOperationLog)
        val backups = obj.getValue("blobs").jsonArray.map { it.jsonObject.let { row ->
            SingleVaultDedupLocalAssets.Backup(row.getValue("id").jsonPrimitive.long, row.getValue("file").jsonPrimitive.content, row.getValue("hash").jsonPrimitive.content)
        } }
        require(passwords.all { it.isLocalOnlyEntry() && !it.isDeleted } && items.all { it.isLocalOnlyItem() && !it.isDeleted })
        require(backups.map { it.attachmentId }.toSet() == attachments.map { it.id }.toSet())
        val restoredPaths = assets.prepareRestore(batch.id, backups, checkActive)
        try {
        val restoreMac = ByteArray(32).also(SecureRandom()::nextBytes)
        try { attachments.forEach { assets.inspect(it.copy(localPath = restoredPaths.getValue(it.id)), restoreMac, checkActive) } }
        finally { restoreMac.fill(0) }
        db.withTransaction {
            checkActive()
            check(db.singleVaultDedupRecoveryDao().get(batch.id) == batch)
            val current = readRows()
            val passwordIds = passwords.map { it.id }.toSet()
            val itemIds = items.map { it.id }.toSet()
            check(current.deviceSync.complete && current.deviceSync.revision == obj.getValue("deviceSyncAudit").jsonPrimitive.content)
            check(current.deviceSync.passwordIds.none { it in passwordIds } && current.deviceSync.itemIds.none { it in itemIds })
            check(current.deviceSync.attachmentIds.none { id -> attachments.any { it.id == id } })
            require(passwordIds.size == passwords.size && itemIds.size == items.size)
            require(fields.all { it.entryId in passwordIds } && history.all { it.entryId in passwordIds } && archive.all { it.entryId in passwordIds })
            require(attachments.all { (it.parentPasswordId in passwordIds) xor (it.parentSecureItemId in itemIds) })
            require(timeline.all { row -> logs.singleOrNull { it.id == row.operationLogId }?.let { it.itemId == row.itemId && it.itemType == row.itemType } == true })
            // A later reference must not be rebound to a restored ID without a separate review.
            check(current.passwords.none { it.ssoRefEntryId in passwordIds || it.boundNoteId in itemIds })
            check(current.passkeys.none { it.boundPasswordId in passwordIds })
            check(current.stacks.none { it.itemKey in passwordIds.map { id -> "password:$id" } + itemIds.flatMap(SingleVaultDedupLocalContent::secureStackKeys) })
            current.items.forEach { item ->
                val value = SingleVaultDedupLocalContent.decodeJson(SingleVaultDedupProjectBuilder.strictJson(decode(item.itemData)), ::decode)
                check(SingleVaultDedupLocalContent.knownSchema(item.itemType, value))
                check(referencedPasswordIds(value).none { it in passwordIds })
            }
            // Same IDs are restored only when absent or byte-for-byte equal to our tombstones.
            passwords.forEach { old -> current.passwords.singleOrNull { it.id == old.id }?.let {
                check(it == old.copy(isDeleted = true, deletedAt = Date(at), updatedAt = Date(at)))
            } }
            items.forEach { old -> current.items.singleOrNull { it.id == old.id }?.let {
                check(it == old.copy(isDeleted = true, deletedAt = Date(at), updatedAt = Date(at)))
            } }
            attachments.forEach { old -> current.attachments.singleOrNull { it.id == old.id }?.let {
                check(it == if (old.isDeleted) old else old.copy(isDeleted = true, deletedAt = at, updatedAt = at))
            } }
            fun <T, K> missing(expected: List<T>, actual: List<T>, key: (T) -> K): List<T> = expected.filter { row ->
                val found = actual.singleOrNull { key(it) == key(row) }
                check(found == null || found == row); found == null
            }
            val missingFields = missing(fields, current.fields) { it.id }
            val missingHistory = missing(history, current.history) { it.id }
            val missingArchive = missing(archive, current.archive) { it.entryId }
            val missingTimeline = missing(timeline, current.timeline) { it.id }
            val missingLogs = missing(logs, current.logs) { it.id }
            // Reject later fields/history attached to a recycled identity; do not hide or overwrite them.
            check(current.fields.filter { it.entryId in passwordIds }.all { it in fields })
            check(current.history.filter { it.entryId in passwordIds }.all { it in history })
            check(current.archive.filter { it.entryId in passwordIds }.all { it in archive })
            check(current.timeline.filter { if (it.itemType == "PASSWORD") it.itemId in passwordIds else items.any { item -> item.id == it.itemId && item.itemType.name == it.itemType } }.all { it in timeline })
            check(current.logs.filter { if (it.itemType == "PASSWORD") it.itemId in passwordIds else items.any { item -> item.id == it.itemId && item.itemType.name == it.itemType } }.all { it in logs })
            check(current.attachments.filter { it.parentPasswordId in passwordIds || it.parentSecureItemId in items.map { row -> row.id } }
                .all { row -> attachments.any { it.id == row.id } })
            dao.insertPasswords(passwords.filter { row -> current.passwords.none { it.id == row.id } })
            dao.updatePasswords(passwords.filter { row -> current.passwords.any { it.id == row.id } })
            dao.insertItems(items.filter { row -> current.items.none { it.id == row.id } })
            dao.updateItems(items.filter { row -> current.items.any { it.id == row.id } })
            dao.insertFields(missingFields); dao.insertHistory(missingHistory); dao.insertArchive(missingArchive)
            dao.insertTimeline(missingTimeline); dao.insertLogs(missingLogs)
            val restored = attachments.map { it.copy(localPath = restoredPaths.getValue(it.id)) }
            dao.insertAttachments(restored.filter { row -> current.attachments.none { it.id == row.id } })
            dao.updateAttachments(restored.filter { row -> current.attachments.any { it.id == row.id } })
            check(db.singleVaultDedupRecoveryDao().transition(batch.id, "COMMITTED", "RESTORED") == 1)
            val verified = readRows()
            check(passwords.all { old -> verified.passwords.singleOrNull { it.id == old.id } == old })
            check(items.all { old -> verified.items.singleOrNull { it.id == old.id } == old })
            check(fields.all { old -> verified.fields.singleOrNull { it.id == old.id } == old })
            check(history.all { old -> verified.history.singleOrNull { it.id == old.id } == old })
            check(restored.all { old -> verified.attachments.singleOrNull { it.id == old.id } == old })
            checkActive()
        }
        return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.SUCCESS, batch.projectCount, batch.id)
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    val referenced = dao.attachments().mapNotNull { it.localPath }.toSet()
                    assets.discardPrepared(restoredPaths.values.filterNot { it in referenced })
                }
            }
        }
    }

    private fun decode(value: String): String {
        if (value.isEmpty()) return value
        if (security.looksLikeMonicaCiphertext(value)) {
            require(value == value.trim())
            return security.decryptData(value)
        }
        require(!SingleVaultDedupLocalContent.couldBeLegacyCiphertext(value)) { "Ambiguous legacy content" }
        return value
    }
    private fun referencedPasswordIds(value: JsonElement): List<Long> = SingleVaultDedupLocalContent.referencedPasswordIds(value)
}
