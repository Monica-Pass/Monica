package takagi.ru.monica.ui.screens

import takagi.ru.monica.R
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.repository.MdbxHealthIssueDiagnostic
import takagi.ru.monica.repository.MdbxHealthSeverity
import takagi.ru.monica.repository.MdbxVaultDiagnostics

internal enum class MdbxHealthGuidanceAction {
    RECHECK,
    MAINTENANCE,
    SNAPSHOTS,
    COMMIT_HISTORY,
    ATTACHMENTS
}

internal data class MdbxHealthGuidance(
    val id: String,
    val category: String,
    val severity: MdbxHealthSeverity,
    val title: String,
    val summary: String,
    val impact: String,
    val steps: List<String>,
    val action: MdbxHealthGuidanceAction,
    val technicalDetails: List<String>
)

private enum class MdbxHealthGuidanceKind {
    FILE_UNREADABLE,
    BASIC_INTEGRITY,
    HEADER_VERIFICATION_PENDING,
    HEADER_AUTHENTICATION_FAILED,
    INTEGRITY_ROOT_PENDING,
    INTEGRITY_ROOT_STALE,
    COMMIT_REFERENCE_MISSING,
    COMMIT_AUTHENTICATION_PENDING,
    COMMIT_AUTHENTICATION_FAILED,
    ATTACHMENT_STRUCTURE,
    SNAPSHOT_INVALID,
    ORPHAN_RECORD,
    COLLECTION_PROFILE,
    TOMBSTONE_DUPLICATE,
    TOMBSTONE_MISSING,
    TOMBSTONE_STALE,
    TOMBSTONE_ACKNOWLEDGEMENT,
    PURGE_RECORD,
    DEVICE_REFERENCE,
    INACTIVE_DEVICE,
    UNKNOWN
}

internal fun MdbxVaultDiagnostics.healthGuidance(strings: StringResolver): List<MdbxHealthGuidance> {
    val reportedIssues = buildList {
        if (!isReadable) {
            add(
                MdbxHealthIssueDiagnostic(
                    severity = MdbxHealthSeverity.CRITICAL,
                    category = "file-access",
                    description = unavailableReason ?: "database file is not readable"
                )
            )
        }
        addAll(
            when {
                healthIssues.isNotEmpty() -> healthIssues
                !integrityOk -> legacyHealthIssues()
                else -> emptyList()
            }
        )
    }

    return reportedIssues
        .groupBy { it.guidanceKind() }
        .map { (kind, issues) -> guidanceFor(strings, kind, issues) }
        .sortedWith(
            compareByDescending<MdbxHealthGuidance> { it.severity.ordinal }
                .thenBy(MdbxHealthGuidance::title)
        )
}

private fun MdbxVaultDiagnostics.legacyHealthIssues(): List<MdbxHealthIssueDiagnostic> {
    val message = integrityMessage?.takeIf {
        it.isNotBlank() && !it.contains("health check passed", ignoreCase = true)
    } ?: return listOf(
        MdbxHealthIssueDiagnostic(
            severity = MdbxHealthSeverity.ERROR,
            category = "integrity",
            description = "database integrity check failed"
        )
    )

    return message.split("; ")
        .filter(String::isNotBlank)
        .map { raw ->
            val category = raw.substringBefore(':', missingDelimiterValue = "integrity").trim()
            val description = raw.substringAfter(':', missingDelimiterValue = raw).trim()
            MdbxHealthIssueDiagnostic(
                severity = MdbxHealthSeverity.ERROR,
                category = category,
                description = description
            )
        }
}

private fun MdbxHealthIssueDiagnostic.guidanceKind(): MdbxHealthGuidanceKind {
    val normalizedCategory = category.lowercase()
    val normalizedDescription = description.lowercase()
    return when (normalizedCategory) {
        "file-access" -> MdbxHealthGuidanceKind.FILE_UNREADABLE
        "integrity" -> MdbxHealthGuidanceKind.BASIC_INTEGRITY
        "vault-header-integrity" -> {
            if (
                normalizedDescription.contains("pending") ||
                normalizedDescription.contains("requires an unlocked keyring")
            ) {
                MdbxHealthGuidanceKind.HEADER_VERIFICATION_PENDING
            } else {
                MdbxHealthGuidanceKind.HEADER_AUTHENTICATION_FAILED
            }
        }
        "incremental-integrity-root" -> {
            if (
                normalizedDescription.contains("pending") ||
                normalizedDescription.contains("incomplete") ||
                normalizedDescription.contains("requires an unlocked keyring")
            ) {
                MdbxHealthGuidanceKind.INTEGRITY_ROOT_PENDING
            } else {
                MdbxHealthGuidanceKind.INTEGRITY_ROOT_STALE
            }
        }
        "commit-chain" -> MdbxHealthGuidanceKind.COMMIT_REFERENCE_MISSING
        "commit-integrity" -> {
            if (normalizedDescription.contains("cannot be verified without an unlocked keyring")) {
                MdbxHealthGuidanceKind.COMMIT_AUTHENTICATION_PENDING
            } else {
                MdbxHealthGuidanceKind.COMMIT_AUTHENTICATION_FAILED
            }
        }
        "attachment-chunks" -> MdbxHealthGuidanceKind.ATTACHMENT_STRUCTURE
        "snapshots" -> MdbxHealthGuidanceKind.SNAPSHOT_INVALID
        "orphans" -> MdbxHealthGuidanceKind.ORPHAN_RECORD
        "collection-profiles" -> MdbxHealthGuidanceKind.COLLECTION_PROFILE
        "tombstones" -> when {
            normalizedDescription.contains("typed tombstones") -> MdbxHealthGuidanceKind.TOMBSTONE_DUPLICATE
            normalizedDescription.contains("deleted without") -> MdbxHealthGuidanceKind.TOMBSTONE_MISSING
            normalizedDescription.contains("active but retains") -> MdbxHealthGuidanceKind.TOMBSTONE_STALE
            else -> MdbxHealthGuidanceKind.TOMBSTONE_STALE
        }
        "tombstone-acknowledgements" -> MdbxHealthGuidanceKind.TOMBSTONE_ACKNOWLEDGEMENT
        "purge-receipts" -> MdbxHealthGuidanceKind.PURGE_RECORD
        "stale-heads" -> {
            if (normalizedDescription.contains("last seen at")) {
                MdbxHealthGuidanceKind.INACTIVE_DEVICE
            } else {
                MdbxHealthGuidanceKind.DEVICE_REFERENCE
            }
        }
        else -> MdbxHealthGuidanceKind.UNKNOWN
    }
}

private fun guidanceFor(
    strings: StringResolver,
    kind: MdbxHealthGuidanceKind,
    issues: List<MdbxHealthIssueDiagnostic>
): MdbxHealthGuidance {
    val severity = issues.maxBy(MdbxHealthIssueDiagnostic::severity).severity
    val category = issues.first().category
    val countSuffix = if (issues.size > 1) strings.get(R.string.mdbx_ui_guidance_count_suffix, issues.size) else ""
    val technicalDetails = issues.map(MdbxHealthIssueDiagnostic::description).distinct()

    fun guidance(
        title: String,
        summary: String,
        impact: String,
        steps: List<String>,
        action: MdbxHealthGuidanceAction
    ) = MdbxHealthGuidance(
        id = kind.name.lowercase(),
        category = category,
        severity = severity,
        title = title + countSuffix,
        summary = summary,
        impact = impact,
        steps = steps,
        action = action,
        technicalDetails = technicalDetails
    )

    return when (kind) {
        MdbxHealthGuidanceKind.FILE_UNREADABLE -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_file_title),
            summary = strings.get(R.string.mdbx_ui_guidance_file_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_file_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_file_step_location),
                strings.get(R.string.mdbx_ui_guidance_file_step_download),
                strings.get(R.string.mdbx_ui_guidance_file_step_restore)
            ),
            action = MdbxHealthGuidanceAction.RECHECK
        )
        MdbxHealthGuidanceKind.BASIC_INTEGRITY -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_integrity_title),
            summary = strings.get(R.string.mdbx_ui_guidance_integrity_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_integrity_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_integrity_step_backup),
                strings.get(R.string.mdbx_ui_guidance_integrity_step_close),
                strings.get(R.string.mdbx_ui_guidance_integrity_step_restore)
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.HEADER_VERIFICATION_PENDING -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_header_pending_title),
            summary = strings.get(R.string.mdbx_ui_guidance_header_pending_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_header_pending_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_header_pending_step_unlock),
                strings.get(R.string.mdbx_ui_guidance_header_pending_step_check),
                strings.get(R.string.mdbx_ui_guidance_header_pending_step_diagnostics)
            ),
            action = MdbxHealthGuidanceAction.RECHECK
        )
        MdbxHealthGuidanceKind.HEADER_AUTHENTICATION_FAILED -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_header_failed_title),
            summary = strings.get(R.string.mdbx_ui_guidance_header_failed_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_header_failed_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_header_failed_step_backup),
                strings.get(R.string.mdbx_ui_guidance_header_failed_step_credentials),
                strings.get(R.string.mdbx_ui_guidance_header_failed_step_restore)
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.INTEGRITY_ROOT_PENDING -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_root_pending_title),
            summary = strings.get(R.string.mdbx_ui_guidance_root_pending_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_root_pending_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_root_pending_step_wait),
                strings.get(R.string.mdbx_ui_guidance_root_pending_step_instances),
                strings.get(R.string.mdbx_ui_guidance_root_pending_step_check)
            ),
            action = MdbxHealthGuidanceAction.RECHECK
        )
        MdbxHealthGuidanceKind.INTEGRITY_ROOT_STALE -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_root_stale_title),
            summary = strings.get(R.string.mdbx_ui_guidance_root_stale_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_root_stale_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_root_stale_step_backup),
                strings.get(R.string.mdbx_ui_guidance_root_stale_step_reopen),
                strings.get(R.string.mdbx_ui_guidance_root_stale_step_restore)
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.COMMIT_REFERENCE_MISSING -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_reference_title),
            summary = strings.get(R.string.mdbx_ui_guidance_reference_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_reference_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_reference_step_sync),
                strings.get(R.string.mdbx_ui_guidance_reference_step_history),
                strings.get(R.string.mdbx_ui_guidance_reference_step_restore)
            ),
            action = MdbxHealthGuidanceAction.COMMIT_HISTORY
        )
        MdbxHealthGuidanceKind.COMMIT_AUTHENTICATION_PENDING -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_commit_pending_title),
            summary = strings.get(R.string.mdbx_ui_guidance_commit_pending_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_commit_pending_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_commit_pending_step_unlock),
                strings.get(R.string.mdbx_ui_guidance_commit_pending_step_check),
                strings.get(R.string.mdbx_ui_guidance_commit_pending_step_restore)
            ),
            action = MdbxHealthGuidanceAction.RECHECK
        )
        MdbxHealthGuidanceKind.COMMIT_AUTHENTICATION_FAILED -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_commit_failed_title),
            summary = strings.get(R.string.mdbx_ui_guidance_commit_failed_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_commit_failed_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_commit_failed_step_backup),
                strings.get(R.string.mdbx_ui_guidance_commit_failed_step_history),
                strings.get(R.string.mdbx_ui_guidance_commit_failed_step_restore)
            ),
            action = MdbxHealthGuidanceAction.COMMIT_HISTORY
        )
        MdbxHealthGuidanceKind.ATTACHMENT_STRUCTURE -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_chunks_title),
            summary = strings.get(R.string.mdbx_ui_guidance_chunks_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_chunks_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_chunks_step_export),
                strings.get(R.string.mdbx_ui_guidance_chunks_step_sync),
                strings.get(R.string.mdbx_ui_guidance_chunks_step_restore)
            ),
            action = MdbxHealthGuidanceAction.ATTACHMENTS
        )
        MdbxHealthGuidanceKind.SNAPSHOT_INVALID -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_snapshot_title),
            summary = strings.get(R.string.mdbx_ui_guidance_snapshot_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_snapshot_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_snapshot_step_avoid),
                strings.get(R.string.mdbx_ui_guidance_snapshot_step_create),
                strings.get(R.string.mdbx_ui_guidance_snapshot_step_restore)
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.ORPHAN_RECORD -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_orphan_title),
            summary = strings.get(R.string.mdbx_ui_guidance_orphan_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_orphan_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_orphan_step_sync),
                strings.get(R.string.mdbx_ui_guidance_orphan_step_copy),
                strings.get(R.string.mdbx_ui_guidance_orphan_step_restore)
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
        MdbxHealthGuidanceKind.COLLECTION_PROFILE -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_profile_title),
            summary = strings.get(R.string.mdbx_ui_guidance_profile_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_profile_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_profile_step_sync),
                strings.get(R.string.mdbx_ui_guidance_profile_step_move),
                strings.get(R.string.mdbx_ui_guidance_profile_step_recreate)
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
        MdbxHealthGuidanceKind.TOMBSTONE_DUPLICATE -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_duplicate_title),
            summary = strings.get(R.string.mdbx_ui_guidance_duplicate_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_duplicate_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_duplicate_step_backup),
                strings.get(R.string.mdbx_ui_guidance_duplicate_step_sync),
                strings.get(R.string.mdbx_ui_guidance_duplicate_step_restore)
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.TOMBSTONE_MISSING -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_missing_deletion_title),
            summary = strings.get(R.string.mdbx_ui_guidance_missing_deletion_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_missing_deletion_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_duplicate_step_backup),
                strings.get(R.string.mdbx_ui_guidance_missing_deletion_step_sync),
                strings.get(R.string.mdbx_ui_guidance_missing_deletion_step_restore)
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.TOMBSTONE_STALE -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_stale_deletion_title),
            summary = strings.get(R.string.mdbx_ui_guidance_stale_deletion_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_stale_deletion_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_stale_deletion_step_backup),
                strings.get(R.string.mdbx_ui_guidance_stale_deletion_step_sync),
                strings.get(R.string.mdbx_ui_guidance_stale_deletion_step_restore)
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.TOMBSTONE_ACKNOWLEDGEMENT -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_ack_title),
            summary = strings.get(R.string.mdbx_ui_guidance_ack_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_ack_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_ack_step_close),
                strings.get(R.string.mdbx_ui_guidance_ack_step_sync),
                strings.get(R.string.mdbx_ui_guidance_ack_step_restore)
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
        MdbxHealthGuidanceKind.PURGE_RECORD -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_purge_title),
            summary = strings.get(R.string.mdbx_ui_guidance_purge_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_purge_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_purge_step_sync),
                strings.get(R.string.mdbx_ui_guidance_purge_step_backup),
                strings.get(R.string.mdbx_ui_guidance_purge_step_restore)
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
        MdbxHealthGuidanceKind.DEVICE_REFERENCE -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_device_title),
            summary = strings.get(R.string.mdbx_ui_guidance_device_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_device_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_device_step_sync),
                strings.get(R.string.mdbx_ui_guidance_device_step_history),
                strings.get(R.string.mdbx_ui_guidance_device_step_restore)
            ),
            action = MdbxHealthGuidanceAction.COMMIT_HISTORY
        )
        MdbxHealthGuidanceKind.INACTIVE_DEVICE -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_inactive_title),
            summary = strings.get(R.string.mdbx_ui_guidance_inactive_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_inactive_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_inactive_step_check),
                strings.get(R.string.mdbx_ui_guidance_inactive_step_sync),
                strings.get(R.string.mdbx_ui_guidance_inactive_step_keep)
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
        MdbxHealthGuidanceKind.UNKNOWN -> guidance(
            title = strings.get(R.string.mdbx_ui_guidance_unknown_title),
            summary = strings.get(R.string.mdbx_ui_guidance_unknown_summary),
            impact = strings.get(R.string.mdbx_ui_guidance_unknown_impact),
            steps = listOf(
                strings.get(R.string.mdbx_ui_guidance_root_stale_step_backup),
                strings.get(R.string.mdbx_ui_guidance_unknown_step_check),
                strings.get(R.string.mdbx_ui_guidance_unknown_step_logs)
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
    }
}
