package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Shape
import takagi.ru.monica.ui.components.GroupedItemDefaults
import takagi.ru.monica.ui.components.MonicaExpandableContent
import takagi.ru.monica.ui.components.MonicaExpansionChevron
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.dedup.DedupConflictPolicy
import takagi.ru.monica.data.dedup.DedupMergeExecutionResult
import takagi.ru.monica.data.dedup.DedupMergePlan
import takagi.ru.monica.data.dedup.DedupMergeSourceKind
import takagi.ru.monica.data.dedup.DedupMergeSourceOption
import takagi.ru.monica.data.dedup.DedupMergeTarget
import takagi.ru.monica.data.dedup.DedupMergeTargetOption
import takagi.ru.monica.data.dedup.DedupResolvedPassword
import takagi.ru.monica.data.dedup.DedupResolvedSecureItem
import takagi.ru.monica.data.dedup.DedupResolvedPasskey
import takagi.ru.monica.data.dedup.DedupPasskeySkipReason
import takagi.ru.monica.data.dedup.dedupLabel
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.viewmodel.DedupEngineUiState

private enum class DedupSheet {
    SOURCES,
    POLICY,
    TARGET,
    PREVIEW,
    WARNINGS,
    RETAINED_SOURCES,
    FAILURES
}

private enum class DedupPreviewFilter {
    ALL,
    WRITE,
    CONFLICT,
    SKIP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedupEngineScreen(
    uiState: DedupEngineUiState,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSource: (String) -> Unit,
    onSelectAllSources: (Set<String>) -> Unit,
    onClearSources: () -> Unit,
    onSelectTarget: (DedupMergeTarget) -> Unit,
    onCreateMdbxTarget: () -> Unit,
    onConflictPolicyChange: (DedupConflictPolicy) -> Unit,
    onExecuteMerge: () -> Unit,
    onCancelMerge: () -> Unit,
    onConsumeMessage: () -> Unit,
    modeSwitch: (@Composable () -> Unit)? = null
) {
    if (!DedupEntryNotice(singleVault = false, onCancel = onNavigateBack)) return
    val strings = rememberScreenStrings()
    val snackbarHostState = remember { SnackbarHostState() }
    var mergeConfirmationBinding by remember { mutableStateOf<Any?>(null) }
    var passkeyReviewBinding by remember { mutableStateOf<Any?>(null) }
    var passkeyInfoOnly by remember { mutableStateOf(false) }
    var showCancelConfirmation by rememberSaveable { mutableStateOf(false) }
    var activeSheet by rememberSaveable { mutableStateOf<DedupSheet?>(null) }
    var previewFilter by rememberSaveable { mutableStateOf(DedupPreviewFilter.ALL) }
    val listState = rememberLazyListState()
    val busy = uiState.isLoading || uiState.isAnalyzing || uiState.isExecutingMerge
    val reviewBinding = remember(uiState.mergePlan, uiState.selectedMergeSourceKeys, uiState.selectedMergeTarget,
        uiState.conflictPolicy, busy, uiState.error) { Any() }
    val latestReviewBinding = rememberUpdatedState(reviewBinding)
    val latestUiState = rememberUpdatedState(uiState)
    val passkeyRows = remember(uiState.mergePlan) {
        val plan = uiState.mergePlan
        val retainedPasswordIds = plan.passkeyRetainedProjects.flatMap { it.passwordIds }.toSet()
        plan.passkeyRetainedProjects.map {
            DedupPasskeyRetentionRow("project:${it.sourceKey}:${it.projectKey}", it.title, it.sourceLabel)
        } + plan.previewPasskeys.filter { key -> key.entry.boundPasswordId?.let { it !in retainedPasswordIds } ?: true }.map {
            DedupPasskeyRetentionRow("passkey:${it.mergeKey}", it.entry.displayTitle(), it.sourceLabels.joinToString(" · "))
        }
    }

    LaunchedEffect(reviewBinding) {
        if (mergeConfirmationBinding !== reviewBinding) mergeConfirmationBinding = null
        if (passkeyReviewBinding !== reviewBinding) passkeyReviewBinding = null
    }

    fun requestBack() {
        if (uiState.isExecutingMerge) showCancelConfirmation = true else onNavigateBack()
    }

    BackHandler(enabled = uiState.isExecutingMerge) { showCancelConfirmation = true }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onConsumeMessage()
    }
    LaunchedEffect(uiState.isExecutingMerge) {
        if (uiState.isExecutingMerge) {
            mergeConfirmationBinding = null
            passkeyReviewBinding = null
            activeSheet = null
            listState.animateScrollToItem(0)
        }
    }

    when (activeSheet) {
        DedupSheet.POLICY -> ModalBottomSheet(onDismissRequest = { activeSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 12.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
                SheetHeader(strings.get(R.string.dedup_merge_policy_title), "", { activeSheet = null })
                ConflictPolicyPanel(uiState.conflictPolicy, !busy) {
                    onConflictPolicyChange(it)
                    activeSheet = null
                }
            }
        }
        DedupSheet.SOURCES -> SourceSelectionSheet(
            sources = uiState.sourceOptions,
            selectedKeys = uiState.selectedMergeSourceKeys,
            targetSourceKey = uiState.selectedTargetOption?.sourceKey,
            onToggleSource = onToggleSource,
            onSelectAllSources = onSelectAllSources,
            onClearSources = onClearSources,
            onDismiss = { activeSheet = null }
        )
        DedupSheet.TARGET -> TargetSelectionSheet(
            targets = uiState.targetOptions,
            selectedTarget = uiState.selectedMergeTarget,
            selectedSourceKeys = uiState.selectedMergeSourceKeys,
            onSelectTarget = {
                onSelectTarget(it)
                activeSheet = null
            },
            onCreateMdbxTarget = {
                activeSheet = null
                onCreateMdbxTarget()
            },
            onDismiss = { activeSheet = null }
        )
        DedupSheet.PREVIEW -> MergePreviewSheet(
            plan = uiState.mergePlan,
            selectedFilter = previewFilter,
            onFilterSelected = { previewFilter = it },
            onDismiss = { activeSheet = null }
        )
        DedupSheet.WARNINGS -> WarningSheet(
            warnings = uiState.mergePlan.warnings,
            onDismiss = { activeSheet = null }
        )
        DedupSheet.RETAINED_SOURCES -> RetainedSourcesSheet(
            sources = uiState.mergePlan.retainedSources,
            onDismiss = { activeSheet = null }
        )
        DedupSheet.FAILURES -> uiState.executionResult?.let { result ->
            FailureSheet(result = result, onDismiss = { activeSheet = null })
        }
        null -> Unit
    }

    if (passkeyReviewBinding === reviewBinding && !busy && passkeyRows.isNotEmpty()) {
        val displayedBinding = reviewBinding
        DedupPasskeyRetentionDialog(
            rows = passkeyRows,
            onDismiss = { if (passkeyReviewBinding === displayedBinding) passkeyReviewBinding = null },
            onContinueReview = if (!passkeyInfoOnly && uiState.canExecuteMerge) ({
                if (latestReviewBinding.value === displayedBinding && latestUiState.value.canExecuteMerge &&
                    passkeyReviewBinding === displayedBinding) {
                    passkeyReviewBinding = null
                    mergeConfirmationBinding = displayedBinding
                }
            }) else null
        )
    }
    if (mergeConfirmationBinding === reviewBinding && uiState.canExecuteMerge) {
        val displayedBinding = reviewBinding
        MergeConfirmationDialog(
            uiState = uiState,
            onDismiss = { if (mergeConfirmationBinding === displayedBinding) mergeConfirmationBinding = null },
            onConfirm = {
                if (latestReviewBinding.value === displayedBinding && latestUiState.value.canExecuteMerge &&
                    mergeConfirmationBinding === displayedBinding) {
                    mergeConfirmationBinding = null
                    onExecuteMerge()
                }
            }
        )
    }
    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(strings.get(R.string.dedup_merge_stop_title)) },
            text = { Text(strings.get(R.string.dedup_merge_stop_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelConfirmation = false
                        onCancelMerge()
                    }
                ) { Text(strings.get(R.string.dedup_merge_stop_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmation = false }) { Text(strings.get(R.string.dedup_merge_continue_action)) }
            }
        )
    }

    Scaffold(
        topBar = {
            Box {
                TopAppBar(
                    title = { Text(strings.get(R.string.dedup_engine_title), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = ::requestBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.get(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh, enabled = !busy) {
                            Icon(Icons.Default.Refresh, contentDescription = strings.get(R.string.dedup_merge_refresh))
                        }
                    }
                )
                if (uiState.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                    )
                }
            }
        },
        bottomBar = {
            MergeBottomBar(
                uiState = uiState,
                onReviewAndMerge = {
                    when {
                        uiState.selectedMergeSourceKeys.isEmpty() -> activeSheet = DedupSheet.SOURCES
                        uiState.selectedMergeTarget == null -> activeSheet = DedupSheet.TARGET
                        uiState.canExecuteMerge -> if (passkeyRows.isEmpty()) {
                            mergeConfirmationBinding = reviewBinding
                        } else {
                            passkeyInfoOnly = false
                            passkeyReviewBinding = reviewBinding
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("dedup_content")
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            modeSwitch?.let { item(key = "modes") { it() } }
            uiState.executionProgress?.let { progress ->
                item(key = "progress") {
                    ExecutionProgressPanel(progress.completedItems, progress.totalItems, progress.currentLabel,
                        progress.fraction, onCancel = { showCancelConfirmation = true })
                }
            }
            uiState.executionResult?.let { result ->
                item(key = "result") {
                    ExecutionResultPanel(result, onViewFailures = { activeSheet = DedupSheet.FAILURES })
                }
            }

            item(key = "intro") {
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(strings.get(R.string.dedup_merge_intro_title), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Text(strings.get(R.string.dedup_merge_intro_desc), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "setup") {
                CompactSetupPanel(
                    sources = uiState.sourceOptions,
                    selectedSourceKeys = uiState.selectedMergeSourceKeys,
                    selectedTarget = uiState.selectedTargetOption,
                    enabled = !uiState.isLoading && !uiState.isExecutingMerge,
                    policy = uiState.conflictPolicy,
                    onOpenPolicy = { activeSheet = DedupSheet.POLICY },
                    onOpenSources = { activeSheet = DedupSheet.SOURCES },
                    onOpenTarget = { activeSheet = DedupSheet.TARGET }
                )
            }
            if (uiState.isAnalyzing || (!uiState.isLoading && uiState.validation.canReview)) {
                item(key = "summary") { MergeSummaryPanel(uiState) }
            }

            if (passkeyRows.isNotEmpty()) {
                item(key = "passkey_retention") {
                    DedupPasskeyRetentionNotice(passkeyRows.size, enabled = !busy, onClick = {
                        passkeyInfoOnly = true
                        passkeyReviewBinding = reviewBinding
                    }, modifier = Modifier.testTag("dedup_passkey_notice"))
                }
            }
            if (uiState.mergePlan.retainedSources.isNotEmpty()) {
                item(key = "retained_sources") {
                    CompactLinkPanel(
                        icon = Icons.Default.Storage,
                        title = strings.get(R.string.dedup_merge_retained_sources_title),
                        subtitle = strings.get(R.string.dedup_merge_retained_sources_count, uiState.mergePlan.retainedSources.size),
                        tint = MaterialTheme.colorScheme.tertiary,
                        onClick = { activeSheet = DedupSheet.RETAINED_SOURCES },
                        modifier = Modifier.testTag("dedup_retained_sources_notice")
                    )
                }
            }
            if (uiState.mergePlan.mdbxTargetAddsIndependentCopies) {
                item(key = "mdbx_target_copies") {
                    MessagePanel(
                        icon = Icons.Default.Info,
                        tint = MaterialTheme.colorScheme.primary,
                        text = strings.get(R.string.dedup_merge_mdbx_target_copies),
                        modifier = Modifier.fillMaxWidth().testTag("dedup_mdbx_target_notice")
                    )
                }
            }

            if (uiState.mergePlan.warnings.isNotEmpty()) {
                item(key = "warnings") {
                    CompactLinkPanel(
                        icon = Icons.Default.Warning,
                        title = strings.get(R.string.dedup_merge_warnings_title),
                        subtitle = strings.get(R.string.dedup_merge_warning_count, uiState.mergePlan.warnings.size),
                        tint = MaterialTheme.colorScheme.tertiary,
                        onClick = { activeSheet = DedupSheet.WARNINGS }
                    )
                }
            }
            uiState.error?.let { error ->
                item(key = "error") {
                    MessagePanel(Icons.Default.Error, MaterialTheme.colorScheme.error, error)
                }
            }

            if (!busy && uiState.error == null && uiState.validation.canReview) {
                item(key = "preview") {
                    CompactLinkPanel(
                        icon = Icons.Default.Merge,
                        title = strings.get(R.string.dedup_merge_preview_title),
                        subtitle = strings.get(
                            R.string.dedup_merge_preview_summary,
                            uiState.mergePlan.previewItemCount,
                            uiState.mergePlan.reviewConflictGroups
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { activeSheet = DedupSheet.PREVIEW }
                    )
                }
            } else if (!busy && uiState.error == null) {
                item(key = "preview_empty") {
                    MessagePanel(
                        icon = Icons.Default.Info,
                        tint = MaterialTheme.colorScheme.primary,
                        text = when {
                            uiState.selectedMergeSourceKeys.isEmpty() -> strings.get(R.string.dedup_merge_preview_need_sources)
                            uiState.selectedMergeTarget == null -> strings.get(R.string.dedup_merge_preview_need_target)
                            else -> strings.get(R.string.dedup_merge_preview_empty)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSetupPanel(
    sources: List<DedupMergeSourceOption>, selectedSourceKeys: Set<String>,
    selectedTarget: DedupMergeTargetOption?, enabled: Boolean, policy: DedupConflictPolicy,
    onOpenSources: () -> Unit, onOpenTarget: () -> Unit, onOpenPolicy: () -> Unit
) {
    val strings = rememberScreenStrings()
    val selectedSources = sources.filter { it.key in selectedSourceKeys }
    val sourceSummary = if (selectedSources.isEmpty()) strings.get(R.string.dedup_merge_source_empty)
        else selectedSources.joinToString(strings.get(R.string.dedup_merge_list_separator)) { it.label }
    Column(verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)) {
        CompactSelectionRow(Icons.Default.ContentCopy, strings.get(R.string.dedup_merge_source_title),
            sourceSummary, GroupedItemDefaults.shape(0, 3), enabled, onOpenSources)
        CompactSelectionRow(Icons.Default.Storage, strings.get(R.string.dedup_merge_target_title),
            selectedTarget?.label ?: strings.get(R.string.dedup_merge_target_empty),
            GroupedItemDefaults.shape(1, 3), enabled, onOpenTarget)
        CompactSelectionRow(Icons.Default.Tune, strings.get(R.string.dedup_merge_policy_title),
            policy.label(strings), GroupedItemDefaults.shape(2, 3), enabled, onOpenPolicy)
    }
}

@Composable
private fun CompactSelectionRow(
    icon: ImageVector, title: String, subtitle: String, shape: Shape, enabled: Boolean, onClick: () -> Unit
) {
    Surface(onClick = onClick, enabled = enabled, shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
                }
            },
            trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null) }
        )
    }
}

@Composable
private fun CompactLinkPanel(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(GroupedItemDefaults.SingleShape)
            .clickable(onClick = onClick),
        shape = GroupedItemDefaults.SingleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
            trailingContent = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) }
        )
    }
}


@Composable
private fun DedupSearchField(query: String, onQueryChange: (String) -> Unit, tag: String) {
    val strings = rememberScreenStrings()
    OutlinedTextField(value = query, onValueChange = onQueryChange, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(tag),
        shape = RoundedCornerShape(28.dp), placeholder = { Text(strings.get(R.string.search)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQueryChange("") }) {
            Icon(Icons.Default.Close, strings.get(R.string.clear))
        } }) else null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSelectionSheet(
    sources: List<DedupMergeSourceOption>, selectedKeys: Set<String>, targetSourceKey: String?,
    onToggleSource: (String) -> Unit, onSelectAllSources: (Set<String>) -> Unit,
    onClearSources: () -> Unit, onDismiss: () -> Unit
) {
    val strings = rememberScreenStrings()
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(sources, query) { sources.filter { it.label.contains(query, true) || it.kind.label().contains(query, true) } }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
            SheetHeader(strings.get(R.string.dedup_merge_source_title), strings.get(R.string.dedup_merge_selected_count, selectedKeys.size), onDismiss)
            DedupSearchField(query, { query = it }, "dedup_source_search")
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { onSelectAllSources(visible.map { it.key }.toSet()) }, enabled = visible.isNotEmpty()) {
                    Text(strings.get(R.string.dedup_merge_select_results))
                }
                TextButton(onClick = onClearSources, enabled = selectedKeys.isNotEmpty()) { Text(strings.get(R.string.clear)) }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (visible.isEmpty()) item { SheetEmptyText(strings.get(R.string.dedup_merge_filter_empty)) }
                itemsIndexed(visible, key = { _, source -> source.key }) { index, source ->
                    Surface(onClick = { onToggleSource(source.key) }, shape = GroupedItemDefaults.shape(index, visible.size),
                        color = if (source.key in selectedKeys) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().semantics { selected = source.key in selectedKeys }) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(source.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (source.key == targetSourceKey) strings.get(R.string.dedup_merge_source_is_target)
                                else "${source.kind.label()} · ${source.countSummary(strings)}", style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { Checkbox(checked = source.key in selectedKeys, onCheckedChange = null) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetainedSourcesSheet(
    sources: List<DedupMergeSourceOption>,
    onDismiss: () -> Unit
) {
    val strings = rememberScreenStrings()
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
            SheetHeader(
                title = strings.get(R.string.dedup_merge_retained_sources_title),
                subtitle = strings.get(R.string.dedup_merge_retained_source_reason),
                onDismiss = onDismiss
            )
            LazyColumn(
                modifier = Modifier.weight(1f).testTag("dedup_retained_sources_list"),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)
            ) {
                itemsIndexed(sources, key = { _, source -> source.key }) { index, source ->
                    Surface(
                        shape = GroupedItemDefaults.shape(index, sources.size),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().testTag("dedup_retained_source:${source.key}")
                    ) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(source.label) },
                            supportingContent = { Text(source.kind.label()) },
                            leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSelectionSheet(
    targets: List<DedupMergeTargetOption>, selectedTarget: DedupMergeTarget?, selectedSourceKeys: Set<String>,
    onSelectTarget: (DedupMergeTarget) -> Unit, onCreateMdbxTarget: () -> Unit, onDismiss: () -> Unit
) {
    val strings = rememberScreenStrings()
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(targets, query) { targets.filter { it.label.contains(query, true) } }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
            SheetHeader(strings.get(R.string.dedup_merge_target_title), strings.get(R.string.dedup_merge_target_add_only), onDismiss)
            DedupSearchField(query, { query = it }, "dedup_target_search")
            Text(strings.get(R.string.dedup_merge_target_support), Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (visible.isEmpty()) item { SheetEmptyText(strings.get(R.string.dedup_merge_filter_empty)) }
                itemsIndexed(visible, key = { _, target -> target.sourceKey }) { index, target ->
                    Surface(onClick = { onSelectTarget(target.target) }, shape = GroupedItemDefaults.shape(index, visible.size),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().semantics { selected = target.target == selectedTarget }) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(target.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (target.sourceKey in selectedSourceKeys)
                                strings.get(R.string.dedup_merge_target_is_source, target.countSummary(strings)) else target.countSummary(strings)) },
                            leadingContent = { RadioButton(target.target == selectedTarget, onClick = null) }
                        )
                    }
                }
            }
            OutlinedButton(onClick = onCreateMdbxTarget, modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 52.dp)) {
                Icon(Icons.Default.Storage, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(strings.get(R.string.dedup_merge_create_target))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergePreviewSheet(
    plan: DedupMergePlan,
    selectedFilter: DedupPreviewFilter,
    onFilterSelected: (DedupPreviewFilter) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = rememberScreenStrings()
    var query by rememberSaveable { mutableStateOf("") }
    val passwords = remember(plan, selectedFilter, query) { plan.previewPasswords.filter {
        selectedFilter.matches(it.existsInTarget, it.conflictFields, it.targetHasDifferentContent) &&
            listOf(it.entry.title, it.entry.username, it.entry.website).any { text -> text.contains(query, true) }
    } }
    val secureItems = remember(plan, selectedFilter, query) { plan.previewSecureItems.filter {
        selectedFilter.matches(it.existsInTarget, it.conflictFields, it.targetHasDifferentContent) && it.item.title.contains(query, true)
    } }
    val passkeys = remember(plan, selectedFilter, query) { plan.previewPasskeys.filter {
        val matchesFilter = when (selectedFilter) {
            DedupPreviewFilter.ALL -> true
            DedupPreviewFilter.WRITE -> it.writable
            DedupPreviewFilter.SKIP -> !it.writable
            DedupPreviewFilter.CONFLICT -> it.skipReason == DedupPasskeySkipReason.CREDENTIAL_CONFLICT
        }
        matchesFilter && listOf(it.entry.displayTitle(), it.entry.rpId, it.entry.userName).any { text -> text.contains(query, true) }
    } }
    val unsupported = remember(plan, selectedFilter, query) { plan.unsupportedItems.filter {
        selectedFilter in setOf(DedupPreviewFilter.ALL, DedupPreviewFilter.SKIP) &&
            (it.title.contains(query, true) || it.sourceLabel.contains(query, true))
    } }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
        ) {
            SheetHeader(
                title = strings.get(R.string.dedup_merge_preview_title),
                subtitle = strings.get(R.string.dedup_merge_items_count, plan.previewItemCount),
                onDismiss = onDismiss
            )
            DedupSearchField(query, { query = it }, "dedup_preview_search")
            FlowRow(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DedupPreviewFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { onFilterSelected(filter) },
                        label = { Text(filter.label(plan, strings)) }
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (passwords.isEmpty() && secureItems.isEmpty() && passkeys.isEmpty() && unsupported.isEmpty()) {
                    item { SheetEmptyText(strings.get(R.string.dedup_merge_filter_empty)) }
                }
                items(passwords, key = { "password:${it.mergeKey}" }) { resolved ->
                    PasswordPreviewRow(resolved)
                }
                items(secureItems, key = { "secure:${it.mergeKey}" }) { resolved ->
                    SecureItemPreviewRow(resolved)
                }
                items(passkeys, key = { "passkey:${it.mergeKey}" }) { resolved ->
                    PasskeyPreviewRow(resolved)
                }
                itemsIndexed(unsupported, key = { index, _ -> "unsupported:$index" }) { _, retained ->
                    ListItem(headlineContent = { Text(retained.title) },
                        supportingContent = { Column {
                            Text(retained.sourceLabel)
                            Text(strings.get(R.string.single_dedup_unsupported))
                        } }, leadingContent = { Icon(Icons.Default.Warning, contentDescription = null) },
                        modifier = Modifier.clip(GroupedItemDefaults.SingleShape))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarningSheet(warnings: List<String>, onDismiss: () -> Unit) {
    val strings = rememberScreenStrings()
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .navigationBarsPadding()
        ) {
            SheetHeader(strings.get(R.string.dedup_merge_warnings_title), strings.get(R.string.dedup_merge_record_count, warnings.size), onDismiss)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(warnings) { warning ->
                    Card(
                        shape = GroupedItemDefaults.SingleShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(warning, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FailureSheet(result: DedupMergeExecutionResult, onDismiss: () -> Unit) {
    val strings = rememberScreenStrings()
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding()
        ) {
            SheetHeader(strings.get(R.string.dedup_merge_failures_title), strings.get(R.string.dedup_merge_record_count, result.failures.size), onDismiss)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(result.failures) { failure ->
                    Card(
                        shape = GroupedItemDefaults.SingleShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(failure.label, fontWeight = FontWeight.Medium)
                            Text(failure.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String, onDismiss: () -> Unit) {
    val strings = rememberScreenStrings()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDismiss) { Text(strings.get(R.string.dedup_merge_done)) }
    }
}

@Composable
private fun SheetEmptyText(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun DedupPreviewFilter.matches(existsInTarget: Boolean, conflictFields: Set<String>, targetDiffers: Boolean): Boolean = when (this) {
    DedupPreviewFilter.ALL -> true
    DedupPreviewFilter.WRITE -> !existsInTarget
    DedupPreviewFilter.CONFLICT -> conflictFields.isNotEmpty() || targetDiffers
    DedupPreviewFilter.SKIP -> existsInTarget
}

private fun DedupPreviewFilter.label(plan: DedupMergePlan, strings: StringResolver): String = when (this) {
    DedupPreviewFilter.ALL -> strings.get(R.string.dedup_merge_filter_all, plan.previewItemCount)
    DedupPreviewFilter.WRITE -> strings.get(R.string.dedup_merge_filter_write, plan.writableItems)
    DedupPreviewFilter.CONFLICT -> strings.get(R.string.dedup_merge_filter_conflict, plan.reviewConflictGroups)
    DedupPreviewFilter.SKIP -> strings.get(R.string.dedup_merge_filter_skip, plan.skippedItems)
}

@Composable
private fun ConflictPolicyPanel(selected: DedupConflictPolicy, enabled: Boolean, onSelected: (DedupConflictPolicy) -> Unit) {
    val strings = rememberScreenStrings()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DedupConflictPolicy.entries.forEachIndexed { index, policy ->
            Surface(onClick = { onSelected(policy) }, enabled = enabled, shape = GroupedItemDefaults.shape(index, 2),
                color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(policy.label(strings)) },
                    leadingContent = { RadioButton(policy == selected, onClick = null) })
            }
        }
        Text(strings.get(R.string.dedup_merge_policy_description), Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MergeSummaryPanel(uiState: DedupEngineUiState) {
    val strings = rememberScreenStrings()
    val plan = uiState.mergePlan
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (uiState.isAnalyzing) {
                Text(strings.get(R.string.dedup_merge_analyzing), style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text(strings.get(R.string.dedup_merge_new_count, plan.writableItems),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryPill(strings.get(R.string.dedup_merge_summary_sources), plan.totalSourcePasswords + plan.totalSourceSecureItems, Modifier.weight(1f))
                    SummaryPill(strings.get(R.string.dedup_merge_consolidated), plan.consolidatedCopies, Modifier.weight(1f))
                    SummaryPill(strings.get(R.string.dedup_merge_summary_existing), plan.targetExistingDuplicates + plan.targetExistingSecureItems, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SummaryPill(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MergeBottomBar(uiState: DedupEngineUiState, onReviewAndMerge: () -> Unit) {
    val strings = rememberScreenStrings()
    val validation = uiState.validation
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onReviewAndMerge,
                enabled = !uiState.isAnalyzing && !uiState.isLoading && !uiState.isExecutingMerge &&
                    (!validation.canReview || uiState.canExecuteMerge),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("dedup_primary_action")
            ) {
                Icon(Icons.Default.Merge, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when {
                        uiState.isExecutingMerge -> strings.get(R.string.dedup_merge_merging)
                        uiState.selectedMergeSourceKeys.isEmpty() -> strings.get(R.string.dedup_merge_need_sources)
                        uiState.selectedMergeTarget == null -> strings.get(R.string.dedup_merge_need_target)
                        uiState.mergePlan.writableItems <= 0 -> strings.get(R.string.dedup_merge_nothing_to_write)
                        else -> strings.get(R.string.dedup_merge_confirm_write, uiState.mergePlan.writableItems)
                    }
                )
            }

        }
    }
}

@Composable
private fun MergeConfirmationDialog(
    uiState: DedupEngineUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val strings = rememberScreenStrings()
    val plan = uiState.mergePlan
    AlertDialog(
        modifier = Modifier.testTag("dedup_merge_confirmation"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Merge, contentDescription = null) },
        title = { Text(strings.get(R.string.dedup_merge_confirm_title, uiState.selectedTargetOption?.label.orEmpty())) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.get(R.string.dedup_merge_confirm_summary, plan.selectedSources.size, plan.writableItems))
                if (plan.retainedSources.isNotEmpty()) {
                    Text(strings.get(R.string.dedup_merge_retained_sources_count, plan.retainedSources.size),
                        modifier = Modifier.testTag("dedup_confirm_retained_sources"))
                }
                if (plan.mdbxTargetAddsIndependentCopies) {
                    Text(strings.get(R.string.dedup_merge_mdbx_target_copies),
                        modifier = Modifier.testTag("dedup_confirm_mdbx_target"))
                }
                if (plan.conflictGroupsTotal > 0) {
                    Text(
                        strings.get(R.string.dedup_merge_confirm_conflicts, plan.conflictGroupsTotal, uiState.conflictPolicy.label(strings)),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (plan.skippedItems > 0) {
                    Text(strings.get(R.string.dedup_merge_confirm_skipped, plan.skippedItems))
                }
                if (plan.previewPasswords.any { it.targetHasDifferentContent } || plan.previewSecureItems.any { it.targetHasDifferentContent }) {
                    Text(strings.get(R.string.dedup_merge_target_variant))
                }
                Text(strings.get(R.string.dedup_merge_confirm_source_unchanged), fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(strings.get(R.string.dedup_merge_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.get(R.string.cancel)) } }
    )
}

@Composable
private fun ExecutionProgressPanel(
    completed: Int,
    total: Int,
    currentLabel: String,
    fraction: Float,
    onCancel: () -> Unit
) {
    val strings = rememberScreenStrings()
    Card(
        shape = GroupedItemDefaults.SingleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.get(R.string.dedup_merge_progress, completed, total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                currentLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(strings.get(R.string.dedup_merge_stop_action)) }
        }
    }
}

@Composable
private fun ExecutionResultPanel(
    result: DedupMergeExecutionResult,
    onViewFailures: () -> Unit
) {
    val strings = rememberScreenStrings()
    Card(
        shape = GroupedItemDefaults.SingleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (result.failedItems > 0) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (result.failedItems > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (result.failedItems > 0) strings.get(R.string.dedup_merge_result_partial) else strings.get(R.string.dedup_merge_result_complete),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(strings.get(R.string.dedup_merge_result_summary, result.targetLabel, result.insertedItems,
                        result.skippedExistingItems + result.skippedUnsupportedPasskeys))
                }
            }
            if (result.failures.isNotEmpty()) {
                TextButton(onClick = onViewFailures, modifier = Modifier.align(Alignment.End)) {
                    Text(strings.get(R.string.dedup_merge_view_failures, result.failures.size))
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun MessagePanel(icon: ImageVector, tint: Color, text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = GroupedItemDefaults.SingleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PasswordPreviewRow(resolved: DedupResolvedPassword) {
    val strings = rememberScreenStrings()
    PreviewRow(
        title = resolved.entry.title.ifBlank { strings.get(R.string.dedup_merge_untitled_password) },
        subtitle = listOf(resolved.entry.username, resolved.entry.website).filter { it.isNotBlank() }.joinToString(" · "),
        sourceLabels = resolved.sourceLabels,
        copyCount = resolved.sourceEntryIds.size,
        conflictFields = resolved.conflictFields,
        existsInTarget = resolved.existsInTarget,
        targetDiffers = resolved.targetHasDifferentContent,
        preferredSource = resolved.preferredSourceLabel,
        stableKey = resolved.mergeKey
    )
}

@Composable
private fun SecureItemPreviewRow(resolved: DedupResolvedSecureItem) {
    val strings = rememberScreenStrings()
    PreviewRow(
        title = resolved.item.title.ifBlank { resolved.item.itemType.dedupLabel(strings) },
        subtitle = resolved.item.itemType.dedupLabel(strings),
        sourceLabels = resolved.sourceLabels,
        copyCount = resolved.sourceItemIds.size,
        conflictFields = resolved.conflictFields,
        existsInTarget = resolved.existsInTarget,
        targetDiffers = resolved.targetHasDifferentContent,
        preferredSource = resolved.preferredSourceLabel,
        stableKey = resolved.mergeKey
    )
}

@Composable
private fun PasskeyPreviewRow(resolved: DedupResolvedPasskey) {
    val strings = rememberScreenStrings()
    PreviewRow(
        title = resolved.entry.displayTitle(),
        subtitle = listOf("Passkey", resolved.entry.rpId, resolved.entry.userName).filter { it.isNotBlank() }.joinToString(" · "),
        sourceLabels = resolved.sourceLabels, copyCount = resolved.sourceEntryIds.size,
        conflictFields = emptySet(), existsInTarget = !resolved.writable, targetDiffers = false,
        preferredSource = resolved.preferredSourceLabel, stableKey = resolved.mergeKey,
        skipReason = resolved.skipReason?.let { strings.get(when (it) {
            DedupPasskeySkipReason.REFERENCE_OR_MISSING_KEY -> R.string.dedup_passkey_missing_key
            DedupPasskeySkipReason.NONZERO_COUNTER -> R.string.dedup_passkey_nonzero_counter
            DedupPasskeySkipReason.BOUND_PASSWORD -> R.string.dedup_passkey_bound_password
            DedupPasskeySkipReason.DEVICE_KEY -> R.string.dedup_passkey_device_key
            DedupPasskeySkipReason.CREDENTIAL_CONFLICT -> R.string.dedup_passkey_identity_conflict
            DedupPasskeySkipReason.PRESERVED -> R.string.dedup_passkey_preserved
        }) }
    )
}

@Composable
private fun PreviewRow(
    title: String, subtitle: String, sourceLabels: List<String>, copyCount: Int,
    conflictFields: Set<String>, existsInTarget: Boolean, targetDiffers: Boolean,
    preferredSource: String, stableKey: String, skipReason: String? = null
) {
    val strings = rememberScreenStrings()
    var expanded by rememberSaveable(stableKey) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), shape = GroupedItemDefaults.SingleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column {
            Row(Modifier.fillMaxWidth().clip(GroupedItemDefaults.SingleShape)
                .clickable(role = Role.Button) { expanded = !expanded }.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(strings.get(if (existsInTarget) R.string.dedup_merge_skip else R.string.dedup_merge_write) +
                        " · " + strings.get(R.string.dedup_merge_copies_count, copyCount), style = MaterialTheme.typography.labelMedium,
                        color = if (targetDiffers || conflictFields.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                    if (skipReason != null) Text(skipReason, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MonicaExpansionChevron(expanded, strings.get(if (expanded) R.string.collapse else R.string.expand))
            }
            MonicaExpandableContent(expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(sourceLabels.joinToString(strings.get(R.string.dedup_merge_list_separator)), style = MaterialTheme.typography.bodySmall)
                    if (preferredSource.isNotBlank()) Text(strings.get(R.string.dedup_merge_kept_from, preferredSource), style = MaterialTheme.typography.bodyMedium)
                    if (conflictFields.isNotEmpty()) Text(strings.get(R.string.dedup_merge_conflict_fields, conflictFields.joinToString(strings.get(R.string.dedup_merge_list_separator))),
                        color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
                    if (targetDiffers) Text(strings.get(R.string.dedup_merge_target_variant), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun DedupMergeSourceKind.label(): String = when (this) {
    DedupMergeSourceKind.MONICA_LOCAL -> "Monica"
    DedupMergeSourceKind.MDBX -> "MDBX"
    DedupMergeSourceKind.KEEPASS -> "KeePass"
    DedupMergeSourceKind.BITWARDEN -> "Bitwarden"
}

private fun DedupConflictPolicy.label(strings: StringResolver): String = when (this) {
    DedupConflictPolicy.MOST_COMPLETE -> strings.get(R.string.dedup_merge_policy_complete)
    DedupConflictPolicy.NEWEST -> strings.get(R.string.dedup_merge_policy_newest)
}

private fun DedupMergeSourceOption.countSummary(strings: StringResolver): String =
    itemCountParts(strings, passwordCount, secureItemCount, passkeyCount)

private fun DedupMergeTargetOption.countSummary(strings: StringResolver): String =
    itemCountParts(strings, passwordCount, secureItemCount, passkeyCount)

private fun itemCountParts(
    strings: StringResolver,
    passwordCount: Int,
    secureItemCount: Int,
    passkeyCount: Int
): String = buildList {
    add(strings.get(R.string.dedup_merge_password_count, passwordCount))
    if (secureItemCount > 0) add(strings.get(R.string.dedup_merge_secure_item_count, secureItemCount))
    if (passkeyCount > 0) add(strings.get(R.string.dedup_merge_passkey_count, passkeyCount))
}.joinToString(" · ")
