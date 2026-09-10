package takagi.ru.monica.ui.screens

import takagi.ru.monica.R
import takagi.ru.monica.utils.StringResolver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.repository.MdbxHealthRepairItem
import takagi.ru.monica.repository.MdbxHealthRepairItemKind
import takagi.ru.monica.repository.MdbxHealthSeverity
import takagi.ru.monica.repository.MdbxVaultDiagnostics
import takagi.ru.monica.viewmodel.MdbxViewModel

private data class MdbxHealthCheckPresentation(
    val title: String,
    val description: String,
    val value: String,
    val icon: ImageVector,
    val hasIssue: Boolean
)

@Composable
internal fun MdbxHealthDetailPage(
    database: LocalMdbxDatabase,
    diagnostics: MdbxVaultDiagnostics?,
    onRefreshDiagnostics: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenSnapshots: () -> Unit,
    onOpenCommitHistory: () -> Unit,
    onOpenAttachments: () -> Unit,
    onStartAutomaticRepair: (() -> Unit)? = null,
    repairInProgress: Boolean = false
) {
    val strings = rememberScreenStrings()
    var showPassedChecks by rememberSaveable(database.id) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (diagnostics == null) {
            item {
                MdbxDetailHeroCard(
                    icon = Icons.Default.Security,
                    title = strings.get(R.string.mdbx_ui_health_checking_title),
                    subtitle = strings.get(R.string.mdbx_ui_health_checking_description, database.name),
                    warning = false
                )
            }
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            item {
                OutlinedButton(
                    onClick = onRefreshDiagnostics,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.get(R.string.mdbx_ui_recheck))
                }
            }
        } else {
            val checks = diagnostics.healthCheckPresentations(strings)
            val guidance = diagnostics.healthGuidance(strings)
            val issueCount = diagnostics.healthIssueCount
            val noticeCount = diagnostics.healthNoticeCount
            val passedCheckCount = checks.count { !it.hasIssue }
            val visibleChecks = if (issueCount > 0 && !showPassedChecks) {
                checks.filter(MdbxHealthCheckPresentation::hasIssue)
            } else {
                checks
            }
            item {
                MdbxDetailHeroCard(
                    icon = when {
                        issueCount > 0 -> Icons.Default.Warning
                        noticeCount > 0 -> Icons.Default.Info
                        else -> Icons.Default.CheckCircle
                    },
                    title = when {
                        issueCount > 0 -> strings.get(R.string.mdbx_ui_health_action_count, issueCount)
                        noticeCount > 0 -> strings.get(R.string.mdbx_ui_health_notice_count, noticeCount)
                        else -> strings.get(R.string.mdbx_ui_health_ok_title)
                    },
                    subtitle = when {
                        issueCount > 0 -> strings.get(R.string.mdbx_ui_health_issues_description, database.name)
                        noticeCount > 0 -> strings.get(R.string.mdbx_ui_health_notices_description)
                        else -> strings.get(R.string.mdbx_ui_health_ok_description, database.name)
                    },
                    warning = issueCount > 0
                )
            }
            item {
                if (issueCount > 0 && onStartAutomaticRepair != null) {
                    FilledTonalButton(
                        onClick = onStartAutomaticRepair,
                        enabled = !repairInProgress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        if (repairInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(19.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (repairInProgress) strings.get(R.string.mdbx_ui_health_preparing_repair) else strings.get(R.string.mdbx_ui_health_repair_action))
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefreshDiagnostics,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.get(R.string.mdbx_ui_recheck))
                    }
                    FilledTonalButton(
                        onClick = onOpenMaintenance,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.ReportProblem, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.get(R.string.mdbx_ui_maintenance))
                    }
                }
            }
            if (guidance.isNotEmpty()) {
                item {
                    MdbxDetailSectionLabel(
                        strings.get(R.string.mdbx_ui_health_recommended_actions),
                        strings.get(R.string.mdbx_ui_health_guidance_description)
                    )
                }
                guidance.forEach { item ->
                    item(key = "health-guidance-${item.id}") {
                        MdbxHealthGuidanceCard(
                            guidance = item,
                            onRefreshDiagnostics = onRefreshDiagnostics,
                            onOpenMaintenance = onOpenMaintenance,
                            onOpenSnapshots = onOpenSnapshots,
                            onOpenCommitHistory = onOpenCommitHistory,
                            onOpenAttachments = onOpenAttachments
                        )
                    }
                }
            }
            item {
                MdbxDetailSectionLabel(
                    strings.get(R.string.mdbx_ui_health_basic_checks),
                    if (issueCount > 0 && !showPassedChecks) {
                        strings.get(R.string.mdbx_ui_health_hidden_checks, passedCheckCount)
                    } else {
                        strings.get(R.string.mdbx_ui_health_verify_after_repair)
                    }
                )
            }
            visibleChecks.forEach { check ->
                item(key = "health-check-${check.title}") {
                    MdbxHealthCheckCard(check)
                }
            }
            if (issueCount > 0 && passedCheckCount > 0) {
                item {
                    OutlinedButton(
                        onClick = { showPassedChecks = !showPassedChecks },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(
                            if (showPassedChecks) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (showPassedChecks) strings.get(R.string.mdbx_ui_health_collapse_passed) else strings.get(R.string.mdbx_ui_health_expand_passed, passedCheckCount))
                    }
                }
            }
            item {
                MdbxDetailInformationCard(
                    title = strings.get(R.string.mdbx_ui_database_information),
                    rows = listOf(
                        MdbxDetailInformationRow(strings.get(R.string.keepass_remote_sync_status), diagnostics.lastSyncStatus),
                        MdbxDetailInformationRow(strings.get(R.string.mdbx_ui_format_version), diagnostics.formatVersion ?: strings.get(R.string.mdbx_ui_not_provided)),
                        MdbxDetailInformationRow(strings.get(R.string.mdbx_ui_file_size), formatBytes(diagnostics.fileSizeBytes)),
                        MdbxDetailInformationRow(strings.get(R.string.mdbx_ui_current_client), diagnostics.currentDeviceId ?: strings.get(R.string.mdbx_ui_not_provided)),
                        MdbxDetailInformationRow(strings.get(R.string.mdbx_ui_file_location), diagnostics.filePath ?: strings.get(R.string.mdbx_ui_not_provided))
                    )
                )
            }
        }
    }
}

@Composable
internal fun MdbxAttachmentDetailPage(
    database: LocalMdbxDatabase,
    diagnostics: MdbxVaultDiagnostics?,
    onRefreshDiagnostics: () -> Unit
) {
    val strings = rememberScreenStrings()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (diagnostics == null) {
            item {
                MdbxDetailHeroCard(
                    icon = Icons.Default.Storage,
                    title = strings.get(R.string.mdbx_ui_attachments_loading),
                    subtitle = strings.get(R.string.mdbx_ui_attachments_loading_description, database.name),
                    warning = false
                )
            }
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        } else {
            val mismatchCount = diagnostics.attachmentChunkMismatchCount
            item {
                MdbxDetailHeroCard(
                    icon = if (mismatchCount > 0) Icons.Default.Warning else Icons.Default.Storage,
                    title = when {
                        mismatchCount > 0 -> strings.get(R.string.mdbx_ui_attachment_chunk_issue_count, mismatchCount)
                        diagnostics.attachmentCount == 0 -> strings.get(R.string.mdbx_ui_attachments_none)
                        else -> strings.get(R.string.mdbx_ui_attachments_healthy)
                    },
                    subtitle = when {
                        mismatchCount > 0 -> strings.get(R.string.mdbx_ui_attachments_issue_description)
                        diagnostics.attachmentCount == 0 -> strings.get(R.string.mdbx_ui_attachments_empty_description, database.name)
                        else -> strings.get(R.string.mdbx_ui_attachments_total, database.name, diagnostics.attachmentCount)
                    },
                    warning = mismatchCount > 0
                )
            }
            item {
                OutlinedButton(
                    onClick = onRefreshDiagnostics,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.get(R.string.mdbx_ui_attachments_recheck))
                }
            }
            item { MdbxDetailSectionLabel(strings.get(R.string.mdbx_ui_storage_overview), strings.get(R.string.mdbx_ui_storage_overview_description)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MdbxDetailMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Storage,
                        label = strings.get(R.string.mdbx_ui_attachment_files),
                        value = diagnostics.attachmentCount.toString()
                    )
                    MdbxDetailMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Folder,
                        label = strings.get(R.string.mdbx_ui_external_references),
                        value = diagnostics.externalAttachmentCount.toString()
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MdbxDetailMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Info,
                        label = strings.get(R.string.mdbx_ui_original_size),
                        value = formatBytes(diagnostics.originalAttachmentBytes)
                    )
                    MdbxDetailMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Storage,
                        label = strings.get(R.string.mdbx_ui_storage_used),
                        value = formatBytes(diagnostics.storedAttachmentBytes)
                    )
                }
            }
            item {
                MdbxAttachmentIntegrityCard(
                    mismatchCount = mismatchCount,
                    attachmentCount = diagnostics.attachmentCount
                )
            }
            item {
                MdbxDetailInformationCard(
                    title = strings.get(R.string.mdbx_ui_storage_details),
                    rows = listOf(
                        MdbxDetailInformationRow(
                            strings.get(R.string.mdbx_ui_database_attachments),
                            strings.get(R.string.mdbx_ui_database_attachments_description)
                        ),
                        MdbxDetailInformationRow(
                            strings.get(R.string.mdbx_ui_external_references),
                            if (diagnostics.externalAttachmentCount > 0) {
                                strings.get(R.string.mdbx_ui_external_attachment_count, diagnostics.externalAttachmentCount)
                            } else {
                                strings.get(R.string.mdbx_ui_external_references_none)
                            }
                        ),
                        MdbxDetailInformationRow(
                            strings.get(R.string.mdbx_ui_chunk_status),
                            if (mismatchCount > 0) strings.get(R.string.mdbx_ui_chunks_need_check, mismatchCount) else strings.get(R.string.mdbx_ui_attachment_index_consistent)
                        )
                    )
                )
            }
        }
    }
}

private fun MdbxVaultDiagnostics.healthCheckPresentations(strings: StringResolver): List<MdbxHealthCheckPresentation> {
    val checks = listOf(
        MdbxHealthCheckPresentation(
            title = if (isReadable) strings.get(R.string.mdbx_ui_file_readable) else strings.get(R.string.mdbx_ui_file_unreadable),
            description = if (isReadable) {
                strings.get(R.string.mdbx_ui_file_readable_description)
            } else {
                unavailableReason ?: strings.get(R.string.mdbx_ui_file_unavailable_description)
            },
            value = if (isReadable) strings.get(R.string.mdbx_health_ok_short) else strings.get(R.string.security_score_needs_attention),
            icon = if (isReadable) Icons.Default.CheckCircle else Icons.Default.CloudOff,
            hasIssue = !isReadable
        ),
        MdbxHealthCheckPresentation(
            title = if (integrityOk) strings.get(R.string.mdbx_ui_integrity_passed) else strings.get(R.string.mdbx_ui_integrity_failed),
            description = when {
                integrityOk && healthNoticeCount > 0 -> {
                    strings.get(R.string.mdbx_ui_integrity_notices, healthNoticeCount)
                }
                integrityOk -> strings.get(R.string.mdbx_ui_integrity_consistent)
                healthIssues.count { it.severity.requiresAction } > 0 -> {
                    strings.get(R.string.mdbx_ui_integrity_issues, healthIssues.count { it.severity.requiresAction })
                }
                else -> strings.get(R.string.mdbx_ui_integrity_issues_description)
            },
            value = if (integrityOk) strings.get(R.string.mdbx_health_ok_short) else strings.get(R.string.security_score_needs_attention),
            icon = Icons.Default.Security,
            hasIssue = !integrityOk
        ),
        MdbxHealthCheckPresentation(
            title = strings.get(R.string.mdbx_ui_parent_commit_references),
            description = if (danglingParentCount > 0) {
                strings.get(R.string.mdbx_ui_parent_commit_missing, danglingParentCount)
            } else {
                strings.get(R.string.mdbx_ui_parent_commit_valid)
            },
            value = if (danglingParentCount > 0) strings.get(R.string.mdbx_ui_issue_count, danglingParentCount) else strings.get(R.string.mdbx_health_ok_short),
            icon = Icons.Default.History,
            hasIssue = danglingParentCount > 0
        ),
        MdbxHealthCheckPresentation(
            title = strings.get(R.string.mdbx_ui_branch_head_references),
            description = if (danglingBranchHeadCount > 0) {
                strings.get(R.string.mdbx_ui_branch_head_missing, danglingBranchHeadCount)
            } else {
                strings.get(R.string.mdbx_ui_branch_head_valid)
            },
            value = if (danglingBranchHeadCount > 0) strings.get(R.string.mdbx_ui_issue_count, danglingBranchHeadCount) else strings.get(R.string.mdbx_health_ok_short),
            icon = Icons.AutoMirrored.Filled.CallMerge,
            hasIssue = danglingBranchHeadCount > 0
        ),
        MdbxHealthCheckPresentation(
            title = strings.get(R.string.mdbx_ui_device_sync_positions),
            description = if (danglingDeviceHeadCount > 0) {
                strings.get(R.string.mdbx_ui_device_head_invalid, danglingDeviceHeadCount)
            } else {
                strings.get(R.string.mdbx_ui_device_head_valid)
            },
            value = if (danglingDeviceHeadCount > 0) strings.get(R.string.mdbx_ui_issue_count, danglingDeviceHeadCount) else strings.get(R.string.mdbx_health_ok_short),
            icon = Icons.Default.Storage,
            hasIssue = danglingDeviceHeadCount > 0
        ),
        MdbxHealthCheckPresentation(
            title = strings.get(R.string.mdbx_ui_attachment_chunks),
            description = if (attachmentChunkMismatchCount > 0) {
                strings.get(R.string.mdbx_ui_attachment_chunks_mismatch, attachmentChunkMismatchCount)
            } else {
                strings.get(R.string.mdbx_ui_attachment_chunks_valid)
            },
            value = if (attachmentChunkMismatchCount > 0) strings.get(R.string.mdbx_ui_issue_count, attachmentChunkMismatchCount) else strings.get(R.string.mdbx_health_ok_short),
            icon = Icons.Default.Storage,
            hasIssue = attachmentChunkMismatchCount > 0
        )
    )
    return checks.sortedByDescending(MdbxHealthCheckPresentation::hasIssue)
}

@Composable
internal fun MdbxDetailHeroCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    warning: Boolean
) {
    val containerColor = if (warning) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (warning) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = contentColor.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(26.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.82f)
                )
            }
        }
    }
}

@Composable
private fun MdbxDetailSectionLabel(title: String, subtitle: String) {
    Column(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MdbxHealthCheckCard(check: MdbxHealthCheckPresentation) {
    val accentColor = if (check.hasIssue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val iconContainer = if (check.hasIssue) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = iconContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(check.icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(21.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        check.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        check.value,
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    check.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MdbxHealthGuidanceCard(
    guidance: MdbxHealthGuidance,
    onRefreshDiagnostics: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenSnapshots: () -> Unit,
    onOpenCommitHistory: () -> Unit,
    onOpenAttachments: () -> Unit
) {
    val strings = rememberScreenStrings()
    var detailsExpanded by rememberSaveable(guidance.id) { androidx.compose.runtime.mutableStateOf(false) }
    val requiresAction = guidance.severity.requiresAction
    val accentColor = when (guidance.severity) {
        MdbxHealthSeverity.CRITICAL, MdbxHealthSeverity.ERROR -> MaterialTheme.colorScheme.error
        MdbxHealthSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        MdbxHealthSeverity.INFO -> MaterialTheme.colorScheme.primary
    }
    val containerColor = when (guidance.severity) {
        MdbxHealthSeverity.CRITICAL, MdbxHealthSeverity.ERROR -> {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)
        }
        MdbxHealthSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.46f)
        MdbxHealthSeverity.INFO -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val icon = when {
        guidance.category.contains("tombstone") || guidance.category == "purge-receipts" -> Icons.Default.Delete
        guidance.category == "attachment-chunks" -> Icons.Default.Storage
        guidance.category == "snapshots" -> Icons.Default.Restore
        guidance.category.startsWith("commit") || guidance.category == "stale-heads" -> Icons.Default.History
        guidance.category == "orphans" || guidance.category == "collection-profiles" -> Icons.Default.Folder
        else -> Icons.Default.Security
    }
    val actionClick = when (guidance.action) {
        MdbxHealthGuidanceAction.RECHECK -> onRefreshDiagnostics
        MdbxHealthGuidanceAction.MAINTENANCE -> onOpenMaintenance
        MdbxHealthGuidanceAction.SNAPSHOTS -> onOpenSnapshots
        MdbxHealthGuidanceAction.COMMIT_HISTORY -> onOpenCommitHistory
        MdbxHealthGuidanceAction.ATTACHMENTS -> onOpenAttachments
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = accentColor.copy(alpha = 0.14f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(22.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            guidance.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when (guidance.severity) {
                                MdbxHealthSeverity.CRITICAL -> strings.get(R.string.mdbx_ui_severity_critical)
                                MdbxHealthSeverity.ERROR -> strings.get(R.string.security_score_needs_attention)
                                MdbxHealthSeverity.WARNING -> strings.get(R.string.mdbx_ui_severity_warning)
                                MdbxHealthSeverity.INFO -> strings.get(R.string.mdbx_ui_severity_info)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        guidance.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        strings.get(R.string.mdbx_ui_potential_impact),
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        guidance.impact,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    strings.get(R.string.mdbx_ui_health_recommended_actions),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                guidance.steps.forEachIndexed { index, step ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Surface(
                            modifier = Modifier.size(22.dp),
                            shape = MaterialTheme.shapes.small,
                            color = accentColor.copy(alpha = 0.14f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    (index + 1).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            step,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = detailsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text(
                        strings.get(R.string.mdbx_ui_technical_details),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    guidance.technicalDetails.take(6).forEach { detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (guidance.technicalDetails.size > 6) {
                        Text(
                            strings.get(R.string.mdbx_ui_more_diagnostics, guidance.technicalDetails.size - 6),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { detailsExpanded = !detailsExpanded },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text(
                        if (detailsExpanded) strings.get(R.string.mdbx_ui_collapse_details) else strings.get(R.string.mdbx_ui_technical_details),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                TextButton(
                    onClick = actionClick,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Icon(
                        when (guidance.action) {
                            MdbxHealthGuidanceAction.RECHECK -> Icons.Default.Sync
                            MdbxHealthGuidanceAction.MAINTENANCE -> Icons.Default.ReportProblem
                            MdbxHealthGuidanceAction.SNAPSHOTS -> Icons.Default.Restore
                            MdbxHealthGuidanceAction.COMMIT_HISTORY -> Icons.Default.History
                            MdbxHealthGuidanceAction.ATTACHMENTS -> Icons.Default.Storage
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when (guidance.action) {
                            MdbxHealthGuidanceAction.RECHECK -> strings.get(R.string.mdbx_ui_recheck)
                            MdbxHealthGuidanceAction.MAINTENANCE -> strings.get(R.string.mdbx_ui_maintenance)
                            MdbxHealthGuidanceAction.SNAPSHOTS -> strings.get(R.string.mdbx_ui_view_snapshots)
                            MdbxHealthGuidanceAction.COMMIT_HISTORY -> strings.get(R.string.mdbx_ui_view_history)
                            MdbxHealthGuidanceAction.ATTACHMENTS -> strings.get(R.string.mdbx_ui_view_attachments)
                        },
                        fontWeight = if (requiresAction) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun MdbxHealthRepairDialog(
    state: MdbxViewModel.MdbxHealthRepairState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onKeepContent: () -> Unit,
    onDeleteObject: (MdbxHealthRepairItem) -> Unit
) {
    val strings = rememberScreenStrings()
    when (state) {
        MdbxViewModel.MdbxHealthRepairState.Hidden -> Unit
        is MdbxViewModel.MdbxHealthRepairState.Planning -> {
            MdbxHealthRepairProgressDialog(
                title = strings.get(R.string.mdbx_ui_repair_analyzing),
                message = strings.get(R.string.mdbx_ui_repair_analyzing_description, state.databaseName)
            )
        }
        is MdbxViewModel.MdbxHealthRepairState.Applying -> {
            MdbxHealthRepairProgressDialog(
                title = strings.get(R.string.mdbx_ui_repair_processing),
                message = strings.get(R.string.mdbx_ui_repair_processing_description, state.itemCount)
            )
        }
        is MdbxViewModel.MdbxHealthRepairState.Reviewing -> {
            val item = state.currentItem ?: return
            AlertDialog(
                onDismissRequest = onCancel,
                icon = { Icon(Icons.Default.ReportProblem, contentDescription = null) },
                title = { Text(strings.get(R.string.mdbx_ui_repair_choose_resolution)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            strings.get(
                                R.string.mdbx_ui_repair_conflict_position,
                                state.currentIndex + 1,
                                state.plan.conflictItems.size,
                                state.plan.automaticItems.size
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    "${item.displayObjectType(strings)} · ${item.objectId.take(8)}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    item.conflictExplanation(strings),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = onKeepContent,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) {
                            Text(strings.get(R.string.mdbx_ui_repair_keep_content))
                        }
                        Button(
                            onClick = { onDeleteObject(item) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text(strings.get(R.string.mdbx_ui_repair_delete_content))
                        }
                        Text(
                            strings.get(R.string.mdbx_ui_repair_cancel_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onCancel) { Text(strings.get(R.string.mdbx_ui_repair_cancel_all)) }
                }
            )
        }
        is MdbxViewModel.MdbxHealthRepairState.Blocked -> {
            AlertDialog(
                onDismissRequest = onCancel,
                icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                title = { Text(strings.get(R.string.mdbx_ui_repair_blocked)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(strings.get(R.string.mdbx_ui_repair_blocked_description))
                        state.blockers.forEach { blocker ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(blocker.category, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        blocker.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onCancel) { Text(strings.get(R.string.mdbx_ui_acknowledge)) } }
            )
        }
        is MdbxViewModel.MdbxHealthRepairState.Failed -> {
            AlertDialog(
                onDismissRequest = onCancel,
                icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                title = { Text(strings.get(R.string.mdbx_ui_repair_incomplete)) },
                text = { Text(state.message) },
                confirmButton = { TextButton(onClick = onRetry) { Text(strings.get(R.string.mdbx_ui_repair_replan)) } },
                dismissButton = { TextButton(onClick = onCancel) { Text(strings.get(R.string.close)) } }
            )
        }
    }
}

@Composable
private fun MdbxHealthRepairProgressDialog(
    title: String,
    message: String
) {
    AlertDialog(
        onDismissRequest = {},
        icon = { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {}
    )
}

private fun MdbxHealthRepairItem.displayObjectType(strings: StringResolver): String = when (objectType.lowercase()) {
    "entry" -> strings.get(R.string.mdbx_ui_object_secure_entry)
    "project" -> strings.get(R.string.category_selection_menu_folders)
    "attachment" -> strings.get(R.string.attachments)
    else -> strings.get(R.string.mdbx_ui_object_database)
}

private fun MdbxHealthRepairItem.conflictExplanation(strings: StringResolver): String = when (kind) {
    MdbxHealthRepairItemKind.ACTIVE_OBJECT_TOMBSTONE_CONFLICT ->
        strings.get(R.string.mdbx_ui_repair_stale_tombstone)
    MdbxHealthRepairItemKind.MISSING_TOMBSTONE ->
        strings.get(R.string.mdbx_ui_repair_missing_tombstone)
    MdbxHealthRepairItemKind.DUPLICATE_TOMBSTONES ->
        strings.get(R.string.mdbx_ui_repair_duplicate_tombstone)
}

@Composable
private fun MdbxDetailMetricCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String
) {
    Card(
        modifier = modifier.heightIn(min = 92.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MdbxAttachmentIntegrityCard(
    mismatchCount: Int,
    attachmentCount: Int
) {
    val strings = rememberScreenStrings()
    val warning = mismatchCount > 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (warning) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (warning) strings.get(R.string.mdbx_ui_attachment_integrity_action) else strings.get(R.string.mdbx_ui_attachment_integrity_ok),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (warning) {
                        strings.get(R.string.mdbx_ui_attachment_integrity_issues, mismatchCount)
                    } else if (attachmentCount == 0) {
                        strings.get(R.string.mdbx_ui_attachment_integrity_empty)
                    } else {
                        strings.get(R.string.mdbx_ui_attachment_integrity_valid, attachmentCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class MdbxDetailInformationRow(
    val label: String,
    val value: String
)

@Composable
private fun MdbxDetailInformationCard(
    title: String,
    rows: List<MdbxDetailInformationRow>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(76.dp)
                    )
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
