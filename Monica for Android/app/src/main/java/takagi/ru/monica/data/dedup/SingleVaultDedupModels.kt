package takagi.ru.monica.data.dedup

import java.util.Collections

/** Reasons contain no field values and are safe to translate for a review screen. */
enum class SingleVaultDedupBlockReason {
    INCOMPLETE_SNAPSHOT,
    UNREADABLE_CONTENT,
    UNSUPPORTED_CONTENT,
    INCONSISTENT_PROJECT,
    EXTERNAL_REFERENCE,
    PASSKEY_PRESERVED,
    PASSKEY_REFERENCE,
    EMPTY_CONTENT,
    DIFFERENT_SOURCE,
    COMPARISON_LIMIT
}

data class SingleVaultDedupProjectSummary(
    val key: String,
    val sourceKey: String,
    val title: String,
    val memberIds: List<Long>,
    val contentCount: Int
)

/**
 * Ephemeral, already-decoded comparison content. Never put this in saved state,
 * logs, a recovery manifest, or UI state. Type/role and exact value stay separate;
 * no delimiter-joined fingerprint or lossy string normalization is used.
 */
class SingleVaultDedupNode(
    val kind: String,
    attributes: Map<String, String> = emptyMap(),
    children: List<SingleVaultDedupNode> = emptyList(),
    val orderedChildren: Boolean = true
) {
    internal val attributes: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(attributes))
    internal val children: List<SingleVaultDedupNode> = Collections.unmodifiableList(ArrayList(children))
    override fun toString(): String = "SingleVaultDedupNode(<redacted>)"

    internal fun contentCount(): Int = if (children.isEmpty()) {
        if (attributes.isEmpty()) 0 else 1
    } else children.sumOf { it.contentCount() }
}

class SingleVaultDedupProject(
    val summary: SingleVaultDedupProjectSummary,
    internal val content: SingleVaultDedupNode?,
    blockers: Set<SingleVaultDedupBlockReason> = emptySet()
) {
    val blockers: Set<SingleVaultDedupBlockReason> = Collections.unmodifiableSet(LinkedHashSet(blockers))
    override fun toString(): String = "SingleVaultDedupProject(<redacted>)"
}

data class SingleVaultDedupCandidate(
    val candidateKey: String,
    val keeperKey: String,
    val matchedContentCount: Int
)

data class SingleVaultDedupAnalysis(
    val projects: List<SingleVaultDedupProjectSummary>,
    val candidates: List<SingleVaultDedupCandidate>,
    val blockers: Map<String, Set<SingleVaultDedupBlockReason>>,
    val comparisonLimitReached: Boolean = false
)

/**
 * The source reader must attest a complete scoped read, including hidden members,
 * and must account for attachments, history, native properties and inbound links.
 * Missing evidence is not evidence of absence. Supplemental nodes are already
 * decoded and verified by that reader, and remain attached to their actual row.
 */
data class SingleVaultDedupEvidence(
    val complete: Boolean = false,
    val completeMemberIds: Set<Long> = emptySet(),
    val blockedReasonsByEntry: Map<Long, Set<SingleVaultDedupBlockReason>> = emptyMap(),
    val supplementalNodesByEntry: Map<Long, List<SingleVaultDedupNode>> = emptyMap()
)
