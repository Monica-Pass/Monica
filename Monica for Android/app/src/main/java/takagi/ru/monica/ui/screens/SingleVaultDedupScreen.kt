package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.GroupedItemDefaults
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.viewmodel.SingleVaultDedupUiCandidate
import takagi.ru.monica.viewmodel.SingleVaultDedupUiIssue
import takagi.ru.monica.viewmodel.SingleVaultDedupUiOutcomeStatus
import takagi.ru.monica.viewmodel.SingleVaultDedupUiProject
import takagi.ru.monica.viewmodel.SingleVaultDedupUiState
import java.text.DateFormat
import java.util.Date

@Composable
fun DedupEngineModeSwitch(singleVault: Boolean, enabled: Boolean = true, onSelectSingleVault: (Boolean) -> Unit) {
    val strings = rememberScreenStrings()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = singleVault, onClick = { onSelectSingleVault(true) }, enabled = enabled,
            label = { Text(strings.get(R.string.single_dedup_mode)) },
            modifier = Modifier.weight(1f).testTag("single_dedup_mode"))
        FilterChip(selected = !singleVault, onClick = { onSelectSingleVault(false) }, enabled = enabled,
            label = { Text(strings.get(R.string.single_dedup_merge_mode)) },
            modifier = Modifier.weight(1f).testTag("multi_dedup_mode"))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SingleVaultDedupScreen(
    uiState: SingleVaultDedupUiState,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectSource: (String) -> Unit,
    onIncludeArchived: (Boolean) -> Unit,
    onAnalyze: () -> Unit,
    onToggleCandidate: (String) -> Unit,
    onReviewSelection: () -> Unit,
    onDismissReview: () -> Unit,
    onConfirmCleanup: () -> Unit,
    onReviewRecovery: (String) -> Unit,
    onConfirmRecovery: () -> Unit,
    onCancel: () -> Unit,
    modeSwitch: (@Composable () -> Unit)? = null,
) {
    if (!DedupEntryNotice(singleVault = true, onCancel = { onCancel(); onNavigateBack() })) return
    val strings = rememberScreenStrings()
    var sourcePicker by remember(uiState.selectedSourceKey) { mutableStateOf(false) }
    var recoveries by remember { mutableStateOf(false) }
    var explanation by remember { mutableStateOf(false) }
    var detailKey by remember(uiState.analysis?.planId) { mutableStateOf<String?>(null) }
    val analysis = uiState.analysis
    val projects = remember(analysis) { analysis?.projects?.associateBy { it.key }.orEmpty() }
    val candidates = analysis?.candidates.orEmpty()
    val candidateKeys = remember(candidates) { candidates.map { it.candidateKey }.toSet() }
    val blocked = remember(analysis) { analysis?.projects.orEmpty().filter { it.issues.isNotEmpty() } }
    val retained = remember(analysis, candidateKeys) {
        analysis?.projects.orEmpty().filter { it.key !in candidateKeys && it.issues.isEmpty() }
    }
    val passkeyRows = remember(analysis, uiState.selectedSource) {
        analysis?.projects.orEmpty().filter { project -> project.issues.any {
            it == SingleVaultDedupUiIssue.PASSKEY_PRESERVED || it == SingleVaultDedupUiIssue.PASSKEY_REFERENCE
        } }.map { DedupPasskeyRetentionRow(it.key, it.title, analysis?.source?.label ?: uiState.selectedSource?.label.orEmpty()) }
    }
    val reviewBinding = remember(analysis, uiState.selectedProjectIds, uiState.selectedSourceKey,
        uiState.includeArchived, uiState.busy, uiState.review, uiState.selectedSource) { Any() }
    val latestReviewBinding = rememberUpdatedState(reviewBinding)
    val latestUiState = rememberUpdatedState(uiState)
    var passkeyInfoBinding by remember { mutableStateOf<Any?>(null) }
    var passkeyAcknowledgedBinding by remember { mutableStateOf<Any?>(null) }
    var submittedBinding by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(reviewBinding) {
        if (passkeyInfoBinding !== reviewBinding) passkeyInfoBinding = null
        if (passkeyAcknowledgedBinding !== reviewBinding) passkeyAcknowledgedBinding = null
        if (submittedBinding !== reviewBinding) submittedBinding = null
    }

    BackHandler(uiState.busy) { onCancel(); onNavigateBack() }

    if (sourcePicker && !uiState.busy) {
        ModalBottomSheet(onDismissRequest = { sourcePicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)) {
                item { SectionTitle(strings.get(R.string.single_dedup_source)) }
                if (uiState.sources.isEmpty()) item { Text(strings.get(R.string.single_dedup_source_unavailable)) }
                items(uiState.sources, key = { it.key }) { source ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        headlineContent = { Text(source.label) },
                        supportingContent = { Text(source.issue?.let { issueText(it, strings) }
                            ?: strings.get(if (source.canClean) R.string.single_dedup_readonly
                                else R.string.single_dedup_analysis_only)) },
                        leadingContent = { RadioButton(selected = source.key == uiState.selectedSourceKey, onClick = null) },
                        modifier = Modifier.heightIn(min = 48.dp).clip(GroupedItemDefaults.SingleShape)
                            .clickable(role = Role.RadioButton) { onSelectSource(source.key); sourcePicker = false }
                            .testTag("single_dedup_source_${source.key}"),
                    )
                }
            }
        }
    }

    if (explanation) {
        AlertDialog(onDismissRequest = { explanation = false },
            icon = { Icon(Icons.Default.Info, null) },
            title = { Text(strings.get(R.string.single_dedup_explain)) },
            text = { Text(strings.get(R.string.single_dedup_explain_body),
                Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { explanation = false }) { Text(strings.get(R.string.dedup_merge_done)) } })
    }

    detailKey?.let { key -> candidates.singleOrNull { it.candidateKey == key } }?.let { candidate ->
        ModalBottomSheet(onDismissRequest = { detailKey = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)
                .navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(strings.get(R.string.single_dedup_coverage_title))
                CoverageSummary(candidate, projects, strings)
                Text(strings.get(R.string.single_dedup_explain_body), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { detailKey = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.get(R.string.dedup_merge_done))
                }
            }
        }
    }

    if (recoveries) {
        ModalBottomSheet(onDismissRequest = { recoveries = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(Modifier.navigationBarsPadding().testTag("single_dedup_recoveries"),
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SectionTitle(strings.get(R.string.single_dedup_recovery)) }
                if (uiState.recoveryBatches.isEmpty()) item { Text(strings.get(R.string.single_dedup_recovery_empty)) }
                items(uiState.recoveryBatches, key = { it.id }) { batch ->
                    Surface(shape = GroupedItemDefaults.SingleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(batch.sourceLabel, style = MaterialTheme.typography.titleMedium)
                            Text(strings.get(R.string.single_dedup_recovery_date,
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(batch.createdAtEpochMillis)), batch.projectCount))
                            batch.issue?.let { Text(issueText(it, strings)) }
                            TextButton(onClick = { recoveries = false; onReviewRecovery(batch.id) },
                                enabled = batch.canRestore && !uiState.busy,
                                modifier = Modifier.testTag("single_dedup_restore_${batch.id}")) {
                                Text(strings.get(R.string.single_dedup_restore))
                            }
                        }
                    }
                }
            }
        }
    }

    if (passkeyInfoBinding === reviewBinding && !uiState.busy && passkeyRows.isNotEmpty()) {
        val displayedBinding = reviewBinding
        DedupPasskeyRetentionDialog(passkeyRows, onDismiss = {
            if (passkeyInfoBinding === displayedBinding) passkeyInfoBinding = null
        })
    }
    uiState.review?.takeIf { review -> review.planId == analysis?.planId &&
        review.selectedProjectIds == uiState.selectedProjectIds && uiState.canReview }?.let { review ->
        val displayedBinding = reviewBinding
        val dismiss = {
            if (latestReviewBinding.value === displayedBinding) onDismissReview()
        }
        if (passkeyRows.isNotEmpty() && passkeyAcknowledgedBinding !== reviewBinding) {
            DedupPasskeyRetentionDialog(passkeyRows, onDismiss = dismiss, onContinueReview = {
                if (latestReviewBinding.value === displayedBinding && latestUiState.value.canReview &&
                    latestUiState.value.review == review) passkeyAcknowledgedBinding = displayedBinding
            })
        } else AlertDialog(onDismissRequest = dismiss,
            title = { Text(strings.get(R.string.single_dedup_confirm_title, review.selectedProjectIds.size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("single_dedup_confirmation"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.get(R.string.single_dedup_confirm_message))
                    candidates.filter { it.candidateKey in review.selectedProjectIds }.forEach { candidate ->
                        CoverageSummary(candidate, projects, strings)
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                if (latestReviewBinding.value === displayedBinding && latestUiState.value.canReview &&
                    latestUiState.value.review == review && submittedBinding !== displayedBinding) {
                    submittedBinding = displayedBinding
                    onConfirmCleanup()
                }
            }, modifier = Modifier.testTag("single_dedup_confirm")) {
                Text(strings.get(R.string.single_dedup_confirm))
            } },
            dismissButton = { TextButton(onClick = dismiss) { Text(strings.get(R.string.cancel)) } })
    }

    uiState.recoveryBatches.singleOrNull { it.id == uiState.recoveryToReview && it.canRestore }
        ?.takeIf { !uiState.busy }?.let { batch ->
            AlertDialog(onDismissRequest = onDismissReview,
                title = { Text(strings.get(R.string.single_dedup_restore_confirm, batch.projectCount)) },
                text = { Text(strings.get(R.string.single_dedup_restore_message) +
                    if (batch.missingAsCopy) "\n\n" + strings.get(R.string.single_dedup_restore_copy_message) else "") },
                confirmButton = { TextButton(onClick = onConfirmRecovery,
                    modifier = Modifier.testTag("single_dedup_restore_confirm")) { Text(strings.get(R.string.single_dedup_restore)) } },
                dismissButton = { TextButton(onClick = onDismissReview) { Text(strings.get(R.string.cancel)) } })
        }

    Scaffold(
        topBar = { TopAppBar(title = { Text(strings.get(R.string.dedup_engine_title)) },
            navigationIcon = { IconButton(onClick = { if (uiState.busy) onCancel(); onNavigateBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.get(R.string.back))
            } },
            actions = { IconButton(onClick = onRefresh, enabled = !uiState.busy) {
                Icon(Icons.Default.Refresh, strings.get(R.string.dedup_merge_refresh))
            } }) },
        bottomBar = {
            SingleDedupBottomBar(uiState, strings, onAnalyze, onReviewSelection)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("single_dedup_content"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modeSwitch?.let { item("modes") { it() } }
            item("intro") {
                DedupNotice(strings.get(R.string.single_dedup_readonly))
            }
            item("setup") {
                Column(verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)) {
                    Surface(onClick = { sourcePicker = true }, enabled = !uiState.busy,
                        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = GroupedItemDefaults.shape(0, 2),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("single_dedup_choose_source")) {
                        ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            overlineContent = { Text(strings.get(R.string.single_dedup_source), style = MaterialTheme.typography.labelMedium) },
                            headlineContent = { Text(uiState.selectedSource?.label ?: strings.get(R.string.single_dedup_choose_source),
                                style = MaterialTheme.typography.titleMedium) },
                            leadingContent = { DedupLeadingIcon(Icons.Default.Storage) },
                            trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null) })
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = GroupedItemDefaults.shape(1, 2)) {
                        ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(strings.get(R.string.single_dedup_include_archive), style = MaterialTheme.typography.bodyMedium) },
                            leadingContent = { Icon(Icons.Default.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingContent = { Switch(checked = uiState.includeArchived, onCheckedChange = onIncludeArchived,
                                enabled = !uiState.busy, modifier = Modifier.testTag("single_dedup_include_archive")) })
                    }
                }
            }
            uiState.selectedSource?.let { source ->
                if (source.issue != null || !source.canClean) item("capability") {
                    DedupNotice(source.issue?.let { issueText(it, strings) } ?: strings.get(R.string.single_dedup_analysis_only),
                        modifier = Modifier.testTag("single_dedup_capability"), emphasized = true)
                }
            }
            if (uiState.busy) item("busy") {
                Card(shape = GroupedItemDefaults.SingleShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.get(if (uiState.isExecuting) R.string.single_dedup_executing else R.string.single_dedup_scanning),
                                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.get(R.string.cancel)) }
                        }
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            uiState.outcome?.let { outcome -> item("outcome") {
                DedupNotice(when (outcome.status) {
                    SingleVaultDedupUiOutcomeStatus.SUCCESS -> if (outcome.isRestoration && outcome.restoredCopyCount > 0)
                        strings.get(R.string.single_dedup_restored_copies, outcome.changedProjects, outcome.restoredCopyCount)
                        else strings.get(if (outcome.isRestoration) R.string.single_dedup_restored else R.string.single_dedup_success,
                            outcome.changedProjects)
                    SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED -> strings.get(R.string.single_dedup_nothing)
                    SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED -> strings.get(R.string.dedup_merge_changed_review)
                    SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED -> strings.get(R.string.single_dedup_unknown)
                    SingleVaultDedupUiOutcomeStatus.FAILED -> strings.get(R.string.single_dedup_failed)
                }, modifier = Modifier.testTag("single_dedup_outcome"), emphasized = true)
            } }
            item("tools") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { explanation = true },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("single_dedup_rules")) {
                        Text(strings.get(R.string.single_dedup_explain), style = MaterialTheme.typography.labelLarge)
                    }
                    FilledTonalButton(onClick = { recoveries = true }, enabled = !uiState.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("single_dedup_recovery")) {
                        Text(strings.get(R.string.single_dedup_recovery), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (passkeyRows.isNotEmpty()) {
                item("passkey_retention") {
                    DedupPasskeyRetentionNotice(passkeyRows.size, enabled = !uiState.busy, onClick = {
                        passkeyInfoBinding = reviewBinding
                    }, modifier = Modifier.testTag("single_dedup_passkey_notice"))
                }
            }
            if (analysis != null) {
                if (analysis.comparisonLimitReached) item("limit") { DedupNotice(strings.get(R.string.single_dedup_limit), emphasized = true) }
                item("statistics") {
                    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("single_dedup_statistics"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DedupStatistic(strings.get(R.string.single_dedup_candidates, candidates.size), true)
                        DedupStatistic(strings.get(R.string.single_dedup_needs_review, blocked.size))
                        DedupStatistic(strings.get(R.string.single_dedup_retained, retained.size))
                    }
                }
                if (candidates.isEmpty()) item("empty") { DedupNotice(strings.get(R.string.single_dedup_empty)) }
                items(candidates, key = { "candidate:${it.candidateKey}" }) { candidate ->
                    DedupCandidateCard(candidate, projects, strings,
                        selected = candidate.candidateKey in uiState.selectedProjectIds,
                        enabled = uiState.selectedSource?.canClean == true && !uiState.busy,
                        onToggle = { onToggleCandidate(candidate.candidateKey) },
                        onDetails = { detailKey = candidate.candidateKey })
                }
                if (blocked.isNotEmpty()) item("blocked_heading") {
                    SectionTitle(strings.get(R.string.single_dedup_needs_review, blocked.size), Modifier.testTag("single_dedup_blocked_heading"))
                }
                items(blocked, key = { "blocked:${it.key}" }) { project ->
                    ProjectSummary(project, project.issues.map { issueText(it, strings) }.distinct().joinToString("\n"))
                }
                if (retained.isNotEmpty()) item("retained_heading") { SectionTitle(strings.get(R.string.single_dedup_retained, retained.size)) }
                items(retained, key = { "retained:${it.key}" }) { project ->
                    ProjectSummary(project, strings.get(R.string.single_dedup_content_count, project.contentCount))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ProjectSummary(project: SingleVaultDedupUiProject, supporting: String) {
    Surface(Modifier.fillMaxWidth(), shape = GroupedItemDefaults.SingleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(project.title, style = MaterialTheme.typography.titleSmall) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (project.path.isNotBlank()) Text(project.path, style = MaterialTheme.typography.bodySmall)
                    Text(supporting, style = MaterialTheme.typography.bodySmall)
                }
            }, leadingContent = {
                Icon(if (project.issues.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Info, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            })
    }
}

@Composable
private fun DedupLeadingIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun DedupNotice(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Surface(Modifier.fillMaxWidth(), shape = GroupedItemDefaults.SingleShape,
        color = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Info, null, Modifier.size(18.dp))
            Text(text, modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DedupStatistic(text: String, emphasized: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DedupCandidateCard(
    candidate: SingleVaultDedupUiCandidate,
    projects: Map<String, SingleVaultDedupUiProject>,
    strings: StringResolver,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onDetails: () -> Unit,
) {
    val project = projects[candidate.candidateKey]
    Card(modifier = Modifier.fillMaxWidth().clip(GroupedItemDefaults.SingleShape)
        .clickable(role = Role.Button, onClick = onDetails).testTag("single_dedup_candidate_${candidate.candidateKey}"),
        shape = GroupedItemDefaults.SingleShape,
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled,
                    modifier = Modifier.size(48.dp).testTag("single_dedup_check_${candidate.candidateKey}"))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(strings.get(R.string.single_dedup_remove_title, project?.title.orEmpty()),
                        style = MaterialTheme.typography.titleSmall)
                    project?.path?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings.get(R.string.single_dedup_keeper, projects[candidate.keeperKey]?.title.orEmpty()),
                            style = MaterialTheme.typography.titleSmall)
                        Text(strings.get(R.string.single_dedup_coverage, candidate.matchedContentCount),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleDedupBottomBar(state: SingleVaultDedupUiState, strings: StringResolver,
    onAnalyze: () -> Unit, onReviewSelection: () -> Unit) {
    val hasAnalysis = state.analysis != null
    val fontScale = LocalDensity.current.fontScale
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("single_dedup_footer")) {
            val action: @Composable (Modifier) -> Unit = { modifier ->
                Button(onClick = if (hasAnalysis) onReviewSelection else onAnalyze,
                    enabled = if (hasAnalysis) state.canReview else state.canAnalyze,
                    modifier = modifier.heightIn(min = 48.dp).testTag("single_dedup_primary")) {
                    Text(strings.get(if (hasAnalysis) R.string.single_dedup_review else R.string.single_dedup_scan))
                }
            }
            val selection: @Composable (Modifier) -> Unit = { modifier ->
                Text(strings.get(R.string.single_dedup_selection, state.selectedProjectIds.size),
                    modifier.testTag("single_dedup_selection"), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hasAnalysis && maxWidth >= 320.dp && fontScale <= 1.3f) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    selection(Modifier.weight(0.35f))
                    action(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasAnalysis) selection(Modifier.fillMaxWidth())
                    action(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun CoverageSummary(candidate: SingleVaultDedupUiCandidate,
    projects: Map<String, SingleVaultDedupUiProject>, strings: StringResolver) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings.get(R.string.single_dedup_remove_title, projects[candidate.candidateKey]?.title.orEmpty()),
            style = MaterialTheme.typography.titleSmall)
        Text(strings.get(R.string.single_dedup_keeper, projects[candidate.keeperKey]?.title.orEmpty()))
        Text(strings.get(R.string.single_dedup_coverage, candidate.matchedContentCount))
        Text(strings.get(R.string.single_dedup_extra, candidate.additionalContentCount))
    }
}

private fun issueText(issue: SingleVaultDedupUiIssue, strings: StringResolver): String = strings.get(when (issue) {
    SingleVaultDedupUiIssue.INCOMPLETE_SNAPSHOT -> R.string.single_dedup_incomplete
    SingleVaultDedupUiIssue.UNREADABLE_CONTENT -> R.string.single_dedup_unreadable
    SingleVaultDedupUiIssue.UNSUPPORTED_CONTENT -> R.string.single_dedup_unsupported
    SingleVaultDedupUiIssue.INCONSISTENT_PROJECT -> R.string.single_dedup_inconsistent
    SingleVaultDedupUiIssue.EXTERNAL_REFERENCE -> R.string.single_dedup_references
    SingleVaultDedupUiIssue.EMPTY_CONTENT -> R.string.single_dedup_empty_content
    SingleVaultDedupUiIssue.DIFFERENT_SOURCE, SingleVaultDedupUiIssue.SOURCE_UNAVAILABLE -> R.string.single_dedup_source_unavailable
    SingleVaultDedupUiIssue.COMPARISON_LIMIT -> R.string.single_dedup_limit
    SingleVaultDedupUiIssue.ANALYSIS_ONLY -> R.string.single_dedup_analysis_only
    SingleVaultDedupUiIssue.LOCKED -> R.string.single_dedup_locked
    SingleVaultDedupUiIssue.RECOVERY_UNAVAILABLE -> R.string.single_dedup_no_recovery
    SingleVaultDedupUiIssue.PASSKEY_PRESERVED, SingleVaultDedupUiIssue.PASSKEY_REFERENCE -> R.string.dedup_passkey_project_preserved
})
