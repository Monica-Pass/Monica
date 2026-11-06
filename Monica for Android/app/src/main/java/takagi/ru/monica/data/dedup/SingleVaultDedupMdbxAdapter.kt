package takagi.ru.monica.data.dedup

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.SingleVaultDedupUiIssue
import takagi.ru.monica.viewmodel.SingleVaultDedupUiOutcome
import takagi.ru.monica.viewmodel.SingleVaultDedupUiOutcomeStatus
import takagi.ru.monica.viewmodel.SingleVaultDedupUiSource
import uniffi.mdbx_ffi.MdbxObjectDisclosureLimits
import uniffi.mdbx_ffi.MdbxObjectSummary
import uniffi.mdbx_ffi.MdbxVault

/**
 * Native MDBX inventory, not a Room mirror. Analysis-only until native history disclosure,
 * conditional batch deletion and project-scoped verified recovery share one native protocol.
 */
internal class SingleVaultDedupMdbxAdapter(
    context: Context,
    database: PasswordDatabase,
    security: SecurityManager,
    private val databaseId: Long,
    label: String,
    private val repository: Mdbx2Repository = Mdbx2Repository(context, database.localMdbxDatabaseDao(), security),
) : SingleVaultDedupSourceAdapter {
    override val source = SingleVaultDedupUiSource("mdbx:$databaseId", label, false, SingleVaultDedupUiIssue.ANALYSIS_ONLY)

    override suspend fun capture(includeArchived: Boolean, checkActive: () -> Unit): SingleVaultDedupCapture =
        repository.withDedupNativeRead(databaseId, checkActive) { database, vault ->
            check(database.engineTypeEnum == MdbxEngineType.RUST_MDBX2)
            val summaries = summaries(vault, checkActive)
            val history = history(vault, checkActive)
            var payloadBytes = 0L
            val rows = summaries.mapNotNull { summary ->
                checkActive()
                var payload: JsonObject? = null
                val nodes = mutableListOf<SingleVaultDedupNode>()
                nodes += history[summary.objectId].orEmpty()
                val blockers = linkedSetOf(SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT)
                if (summary.objectTypeId.equals("passkey", true)) blockers += SingleVaultDedupBlockReason.PASSKEY_PRESERVED
                // The current FFI exposes history previews, not complete per-object historical
                // values/native policy snapshots. Present every row, but do not invent proof.
                try {
                    val record = requireNotNull(vault.revealObjectWithLimits(summary.objectId,
                        MdbxObjectDisclosureLimits(4uL * 1024uL * 1024uL)).`object`)
                    check(!record.deleted && record.collectionId == summary.collectionId &&
                        record.objectTypeId == summary.objectTypeId && record.payloadSchemaVersion == summary.payloadSchemaVersion)
                    payloadBytes += record.payloadJson.toByteArray(Charsets.UTF_8).size
                    check(payloadBytes <= 32L * 1024L * 1024L)
                    // Preserve the complete original payload even if strict parsing or a new
                    // schema fails. It stays in this capture, never in the UI or diagnostics.
                    nodes += SingleVaultDedupNode("mdbx-native-payload", mapOf("payload" to record.payloadJson,
                        "type" to record.objectTypeId, "schema" to record.payloadSchemaVersion.toString(),
                        "collection" to record.collectionId, "title" to record.title))
                    payload = SingleVaultDedupProjectBuilder.strictJson(record.payloadJson) as? JsonObject
                    if (payload?.get("passkey_bindings")?.let { it != JsonNull && it != JsonPrimitive("") } == true ||
                        payload?.get("bound_password_id")?.let { it != JsonNull } == true)
                        blockers += SingleVaultDedupBlockReason.PASSKEY_REFERENCE
                    if (payload == null) blockers += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
                    if (summary.objectTypeId !in knownTypes || summary.payloadSchemaVersion != 1u)
                        blockers += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
                    if (!includeArchived && payload?.get("is_archived") == JsonPrimitive(true)) return@mapNotNull null
                    val attachments = vault.listAttachments(summary.collectionId, summary.objectId).filterNot { it.deleted }
                    attachments.forEach { attachment ->
                        nodes += SingleVaultDedupNode("mdbx-attachment-unverified", mapOf(
                            "id" to attachment.attachmentId, "name" to attachment.fileName,
                            "media" to attachment.mediaType.orEmpty(), "size" to attachment.originalSize.toString(),
                            "contentHash" to attachment.contentHash))
                    }
                    val assignments = vault.listObjectLabelAssignments(summary.objectId).filterNot { it.deleted }
                    assignments.forEach { assignment ->
                        nodes += SingleVaultDedupNode("mdbx-label-reference", mapOf("label" to assignment.labelId))
                        val label = requireNotNull(vault.revealObjectLabelWithLimits(assignment.labelId,
                            uniffi.mdbx_ffi.MdbxObjectMetadataDisclosureLimits(1024uL * 1024uL)).label)
                        payloadBytes += label.payloadJson.toByteArray(Charsets.UTF_8).size
                        check(payloadBytes <= 32L * 1024L * 1024L)
                        nodes += SingleVaultDedupNode("mdbx-native-label", mapOf("name" to label.name,
                            "collection" to label.collectionId, "schema" to label.payloadSchemaVersion.toString(),
                            "payload" to label.payloadJson))
                    }
                    val outgoing = vault.listObjectRelationSummariesFrom(summary.objectId, null, 1u, null)
                    val incoming = vault.listObjectRelationSummariesTo(summary.objectId, null, 1u, null)
                    if (assignments.isNotEmpty() || outgoing.items.isNotEmpty() || incoming.items.isNotEmpty())
                        blockers += SingleVaultDedupBlockReason.EXTERNAL_REFERENCE
                    check(vault.getObjectSummary(summary.objectId) == summary)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { blockers += SingleVaultDedupBlockReason.UNREADABLE_CONTENT }
                checkActive()
                check(payloadBytes <= 32L * 1024L * 1024L)
                SingleVaultDedupMdbxDocument.Row(summary.objectId, summary.title, payload,
                    SingleVaultDedupNode("mdbx-native-record", children = nodes), blockers)
            }
            val projects = SingleVaultDedupMdbxDocument.projects(source.key, rows)
            checkActive()
            object : SingleVaultDedupCapture(projects) {}
        }

    private fun summaries(vault: MdbxVault, checkActive: () -> Unit): List<MdbxObjectSummary> {
        val result = mutableListOf<MdbxObjectSummary>()
        val collectionIds = hashSetOf<String>()
        var collectionCursor: String? = null
        val collectionCursors = hashSetOf<String>()
        do {
            checkActive()
            val page = vault.listCollectionSummaries(200u, collectionCursor)
            page.items.filterNot { it.deleted }.forEach { collection ->
                check(collectionIds.add(collection.collectionId))
                var cursor: String? = null
                val seen = hashSetOf<String>()
                do {
                    checkActive()
                    val objects = vault.listObjectSummaries(collection.collectionId, null, 200u, cursor)
                    result += objects.items.filterNot { it.deleted }
                    check(result.size <= 10_000)
                    cursor = objects.nextCursor
                    check(cursor == null || (objects.items.isNotEmpty() && seen.add(cursor!!)))
                } while (cursor != null)
            }
            collectionCursor = page.nextCursor
            check(collectionCursor == null || (page.items.isNotEmpty() && collectionCursors.add(collectionCursor!!)))
        } while (collectionCursor != null)
        check(result.map { it.objectId }.distinct().size == result.size)
        return result
    }

    /** Preserve the available history structure; previews are never mistaken for full historic values. */
    private fun history(vault: MdbxVault, checkActive: () -> Unit): Map<String, List<SingleVaultDedupNode>> {
        val result = mutableMapOf<String, MutableList<SingleVaultDedupNode>>()
        var cursor: String? = null
        val seen = hashSetOf<String>()
        var count = 0
        var changeCount = 0
        do {
            checkActive()
            val page = vault.listCommitHistoryV2(100u, cursor)
            page.items.forEach { version ->
                check(++count <= 10_000)
                val commit = version.item
                commit.changes.forEach { change ->
                    check(++changeCount <= 40_000)
                    result.getOrPut(change.objectId) { mutableListOf() } += SingleVaultDedupNode("mdbx-history-structure",
                        mapOf("commit" to commit.commitId, "branch" to version.branchId.orEmpty(),
                            "createdAt" to commit.createdAt, "type" to change.objectType, "action" to change.action,
                            "fields" to JsonArray(change.fields.map(::JsonPrimitive)).toString()))
                }
            }
            cursor = page.nextCursor
            check(cursor == null || (page.items.isNotEmpty() && seen.add(cursor!!)))
        } while (cursor != null)
        return result
    }

    override suspend fun clean(captured: SingleVaultDedupCapture, selected: List<SingleVaultDedupCandidate>,
        checkActive: () -> Unit): SingleVaultDedupUiOutcome {
        checkActive()
        return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED)
    }
    override suspend fun restore(batch: SingleVaultDedupRecoveryEntity, checkActive: () -> Unit): SingleVaultDedupUiOutcome {
        checkActive()
        return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED)
    }

    private val knownTypes = setOf("login", "note", "bank-card", "document", "totp", "passkey", "steam-mafile", "api-token")
}

internal object SingleVaultDedupMdbxDocument {
    class Row(val id: String, val title: String, val payload: JsonObject?, val node: SingleVaultDedupNode,
        val blockers: Set<SingleVaultDedupBlockReason>) {
        override fun toString() = "SingleVaultDedupMdbxRow(<redacted>)"
    }

    fun projects(sourceKey: String, rows: List<Row>): List<SingleVaultDedupProject> {
        val groups = rows.groupBy { row ->
            val explicit = (row.payload?.get("password_group_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!explicit.isNullOrBlank()) "group:$explicit" else "entry:${row.id}"
        }
        return groups.map { (group, members) ->
            val blockers = members.flatMapTo(linkedSetOf()) { it.blockers }
            if (group.startsWith("group:")) blockers += SingleVaultDedupBlockReason.INCONSISTENT_PROJECT
            val node = SingleVaultDedupNode("mdbx-project", children = members.sortedBy { it.id }.map { it.node })
            SingleVaultDedupProject(SingleVaultDedupProjectSummary("$sourceKey:$group", sourceKey,
                members.first().title, emptyList(), node.contentCount()), node, blockers)
        }
    }
}
