package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.MonicaExpandableContent
import takagi.ru.monica.ui.components.MonicaExpansionChevron
import takagi.ru.monica.data.CompromisedPassword
import takagi.ru.monica.data.DuplicatePasswordGroup
import takagi.ru.monica.data.DuplicateUrlGroup
import takagi.ru.monica.data.InactivePasskeyAccount
import takagi.ru.monica.data.No2FAAccount
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.primaryLinkedAppPackageName
import takagi.ru.monica.data.PasswordStrengthDistribution
import takagi.ru.monica.data.SecurityAnalysisData
import takagi.ru.monica.data.SecurityAnalysisScopeOption
import takagi.ru.monica.data.SecurityAnalysisScopeType
import kotlinx.coroutines.delay
import takagi.ru.monica.security.securityPasswordMask

private enum class SecurityIssueType {
    DUPLICATE_PASSWORDS,
    DUPLICATE_URLS,
    COMPROMISED,
    NO_2FA,
    INACTIVE_PASSKEY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityAnalysisScreen(
    analysisData: SecurityAnalysisData,
    autoAnalysisEnabled: Boolean,
    onStartAnalysis: () -> Unit,
    onAutoAnalysisEnabledChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToPassword: (Long) -> Unit,
    onSelectScope: (String) -> Unit
) {
    var selectedIssue by rememberSaveable { mutableStateOf<SecurityIssueType?>(null) }
    var lastSelectedIssue by rememberSaveable { mutableStateOf(SecurityIssueType.DUPLICATE_PASSWORDS) }

    val duplicatePasswordGroupCount = analysisData.duplicatePasswords.size
    val duplicatePasswordItemCount = analysisData.duplicatePasswords.sumOf { it.count }
    val duplicateUrlGroupCount = analysisData.duplicateUrls.size
    val duplicateUrlItemCount = analysisData.duplicateUrls.sumOf { it.count }
    val compromisedCount = analysisData.compromisedPasswords.size
    val no2faCount = analysisData.no2FAAccounts.count { it.supports2FA }
    val inactivePasskeyCount = analysisData.inactivePasskeyAccounts.size
    val affectedAccountCount = buildSet {
        analysisData.duplicatePasswords.flatMap { it.entries }.forEach { add(it.id) }
        analysisData.duplicateUrls.flatMap { it.entries }.forEach { add(it.id) }
        analysisData.compromisedPasswords.forEach { add(it.entry.id) }
        analysisData.no2FAAccounts.filter { it.supports2FA }.forEach { add(it.entry.id) }
        analysisData.inactivePasskeyAccounts.forEach { add(it.entry.id) }
    }.size
    var showAnalyzingUi by remember { mutableStateOf(analysisData.isAnalyzing) }
    LaunchedEffect(analysisData.isAnalyzing) {
        if (analysisData.isAnalyzing) {
            delay(300)
            if (analysisData.isAnalyzing) {
                showAnalyzingUi = true
            }
        } else {
            showAnalyzingUi = false
        }
    }
    val analyzingTransition = rememberInfiniteTransition(label = "security_analysis_progress")
    val loopingProgress by analyzingTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "security_analysis_looping_progress"
    )
    val effectiveProgress = if (analysisData.analysisProgress in 1..99) {
        analysisData.analysisProgress / 100f
    } else {
        loopingProgress
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(R.string.security_analysis)) },
                        navigationIcon = {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back)
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = onStartAnalysis) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.refresh)
                                )
                            }
                        }
                    )
                    if (showAnalyzingUi) {
                        LinearProgressIndicator(
                            progress = { effectiveProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                        )
                    }
                }
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            item {
                SecurityOverviewHeader(
                    score = analysisData.securityScore,
                    affectedAccountCount = affectedAccountCount,
                    isAnalyzing = showAnalyzingUi
                )
            }

            item {
                SecurityIssueGrid(
                    items = listOf(
                        SecurityIssueGridItem(
                            icon = Icons.Default.Warning,
                            title = stringResource(R.string.compromised_passwords),
                            count = compromisedCount,
                            subtitle = stringResource(R.string.security_issue_simple_count_subtitle, compromisedCount),
                            issueType = SecurityIssueType.COMPROMISED
                        ),
                        SecurityIssueGridItem(
                            icon = Icons.Default.ContentCopy,
                            title = stringResource(R.string.duplicate_passwords),
                            count = duplicatePasswordItemCount,
                            subtitle = stringResource(
                                R.string.security_issue_duplicate_password_subtitle,
                                duplicatePasswordGroupCount,
                                duplicatePasswordItemCount
                            ),
                            issueType = SecurityIssueType.DUPLICATE_PASSWORDS
                        ),
                        SecurityIssueGridItem(
                            icon = Icons.Default.Security,
                            title = stringResource(R.string.no_twofa),
                            count = no2faCount,
                            subtitle = stringResource(R.string.security_issue_simple_count_subtitle, no2faCount),
                            issueType = SecurityIssueType.NO_2FA
                        ),
                        SecurityIssueGridItem(
                            icon = Icons.Default.VpnKey,
                            title = stringResource(R.string.inactive_passkeys),
                            count = inactivePasskeyCount,
                            subtitle = stringResource(R.string.security_issue_simple_count_subtitle, inactivePasskeyCount),
                            issueType = SecurityIssueType.INACTIVE_PASSKEY
                        ),
                        SecurityIssueGridItem(
                            icon = Icons.Default.Link,
                            title = stringResource(R.string.duplicate_urls),
                            count = duplicateUrlItemCount,
                            subtitle = stringResource(
                                R.string.security_issue_duplicate_url_subtitle,
                                duplicateUrlGroupCount,
                                duplicateUrlItemCount
                            ),
                            issueType = SecurityIssueType.DUPLICATE_URLS
                        )
                    ),
                    onSelectIssue = {
                        lastSelectedIssue = it
                        selectedIssue = it
                    }
                )
            }

            item {
                SecurityStrengthDistributionCard(
                    distribution = analysisData.passwordStrengthDistribution
                )
            }

            item {
                ScopeSelectorCard(
                    scopes = analysisData.availableScopes,
                    selectedScopeKey = analysisData.selectedScopeKey,
                    onSelectScope = onSelectScope
                )
            }

            item {
                AutoAnalysisToggleCard(
                    autoAnalysisEnabled = autoAnalysisEnabled,
                    onAutoAnalysisEnabledChange = onAutoAnalysisEnabledChange
                )
            }
        }

            analysisData.error?.let { error ->
                Snackbar(modifier = Modifier.padding(16.dp)) {
                    Text(error)
                }
            }
        }

        AnimatedVisibility(
            visible = selectedIssue != null,
            enter = slideInHorizontally(
                animationSpec = tween(durationMillis = 300),
                initialOffsetX = { fullWidth -> fullWidth / 8 }
            ) + fadeIn(animationSpec = tween(durationMillis = 280)),
            exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = 280),
                targetOffsetX = { fullWidth -> fullWidth / 8 }
            ) + fadeOut(animationSpec = tween(durationMillis = 150))
        ) {
            SecurityIssueDetailScreen(
                issueType = selectedIssue ?: lastSelectedIssue,
                analysisData = analysisData,
                onNavigateBack = { selectedIssue = null },
                onNavigateToPassword = onNavigateToPassword
            )
        }
    }
}

@Composable
private fun AutoAnalysisToggleCard(
    autoAnalysisEnabled: Boolean,
    onAutoAnalysisEnabledChange: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(R.string.security_analysis_auto_toggle_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.security_analysis_auto_toggle_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = autoAnalysisEnabled,
            onCheckedChange = onAutoAnalysisEnabledChange
        )
    }
}

@Composable
private fun SecurityOverviewHeader(
    score: Int,
    affectedAccountCount: Int,
    isAnalyzing: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
    val scoreColor = when {
        score >= 80 -> Color(0xFF22C55E)
        score >= 55 -> Color(0xFFF59E0B)
        else -> colorScheme.error
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (isAnalyzing) "—" else score.coerceIn(0, 100).toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isAnalyzing) colorScheme.onSurfaceVariant else scoreColor
                )
                Text(
                    text = stringResource(R.string.security_score_out_of_100),
                    style = MaterialTheme.typography.labelLarge,
                    color = colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (isAnalyzing) {
                        stringResource(R.string.security_analysis_in_progress_short)
                    } else {
                        stringResource(
                            when {
                                score >= 80 -> R.string.security_score_good
                                score >= 55 -> R.string.security_score_fair
                                else -> R.string.security_score_needs_attention
                            }
                        )
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (affectedAccountCount == 0) {
                        stringResource(R.string.security_no_accounts_need_attention)
                    } else {
                        stringResource(R.string.security_accounts_need_attention, affectedAccountCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun ScopeSelectorCard(
    scopes: List<SecurityAnalysisScopeOption>,
    selectedScopeKey: String,
    onSelectScope: (String) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.security_analysis_scope_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scopes.forEach { scope ->
                    FilterChip(
                        selected = scope.key == selectedScopeKey,
                        onClick = { onSelectScope(scope.key) },
                        label = { Text("${scopeDisplayName(scope, context)} ${scope.itemCount}") }
                    )
                }
            }
    }
}

@Composable
private fun SecurityStrengthDistributionCard(
    distribution: PasswordStrengthDistribution
) {
    val colorScheme = MaterialTheme.colorScheme
    val total = distribution.total
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.security_strength_distribution),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val segments = listOf(
                    Pair(distribution.weak, colorScheme.error),
                    Pair(distribution.medium, colorScheme.tertiary),
                    Pair(distribution.strong, colorScheme.secondary),
                    Pair(distribution.veryStrong, colorScheme.primary)
                )
                if (total > 0) {
                    segments.forEach { (count, color) ->
                        if (count > 0) {
                            val rawWeight = count / total.toFloat()
                            val weight = if (rawWeight < 0.08f) 0.08f else rawWeight
                            Box(
                                modifier = Modifier
                                    .weight(weight)
                                    .fillMaxSize()
                                    .background(
                                        color = color.copy(alpha = 0.72f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    StrengthStatistic(stringResource(R.string.strength_weak), distribution.weak, colorScheme.error, Modifier.weight(1f))
                    StrengthStatistic(stringResource(R.string.security_strength_medium), distribution.medium, colorScheme.tertiary, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    StrengthStatistic(stringResource(R.string.strength_strong), distribution.strong, colorScheme.secondary, Modifier.weight(1f))
                    StrengthStatistic(stringResource(R.string.security_strength_very_strong), distribution.veryStrong, colorScheme.primary, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StrengthStatistic(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class SecurityIssueGridItem(
    val icon: ImageVector,
    val title: String,
    val count: Int,
    val subtitle: String,
    val issueType: SecurityIssueType
)

@Composable
private fun SecurityIssueGrid(
    items: List<SecurityIssueGridItem>,
    onSelectIssue: (SecurityIssueType) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.security_risk_cards_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
        )
        items.forEach { item ->
            SecurityIssueRow(
                item = item,
                onClick = { onSelectIssue(item.issueType) }
            )
        }
    }
}

@Composable
private fun SecurityIssueRow(
    item: SecurityIssueGridItem,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val accent = if (item.count == 0) Color(0xFF22C55E) else colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(text = item.count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityIssueDetailScreen(
    issueType: SecurityIssueType,
    analysisData: SecurityAnalysisData,
    onNavigateBack: () -> Unit,
    onNavigateToPassword: (Long) -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (issueType) {
                            SecurityIssueType.DUPLICATE_PASSWORDS -> stringResource(R.string.duplicate_passwords)
                            SecurityIssueType.DUPLICATE_URLS -> stringResource(R.string.duplicate_urls)
                            SecurityIssueType.COMPROMISED -> stringResource(R.string.compromised_passwords)
                            SecurityIssueType.NO_2FA -> stringResource(R.string.no_twofa)
                            SecurityIssueType.INACTIVE_PASSKEY -> stringResource(R.string.inactive_passkeys)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        when (issueType) {
            SecurityIssueType.DUPLICATE_PASSWORDS -> DuplicatePasswordsFlatList(
                groups = analysisData.duplicatePasswords,
                onNavigateToPassword = onNavigateToPassword,
                modifier = Modifier.padding(paddingValues)
            )
            SecurityIssueType.DUPLICATE_URLS -> DuplicateUrlsFlatList(
                groups = analysisData.duplicateUrls,
                onNavigateToPassword = onNavigateToPassword,
                modifier = Modifier.padding(paddingValues)
            )
            SecurityIssueType.COMPROMISED -> CompromisedFlatList(
                items = analysisData.compromisedPasswords,
                onNavigateToPassword = onNavigateToPassword,
                modifier = Modifier.padding(paddingValues)
            )
            SecurityIssueType.NO_2FA -> No2faFlatList(
                items = analysisData.no2FAAccounts,
                onNavigateToPassword = onNavigateToPassword,
                modifier = Modifier.padding(paddingValues)
            )
            SecurityIssueType.INACTIVE_PASSKEY -> InactivePasskeyFlatList(
                items = analysisData.inactivePasskeyAccounts,
                onNavigateToPassword = onNavigateToPassword,
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
private fun DuplicatePasswordsFlatList(
    groups: List<DuplicatePasswordGroup>,
    onNavigateToPassword: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) {
        EmptyStateView(
            icon = Icons.Default.CheckCircle,
            message = stringResource(R.string.no_duplicate_passwords),
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(groups, key = { it.passwordHash }) { group ->
            CollapsibleSecurityGroupCard(
                title = securityPasswordMask(group.entries.firstOrNull()?.password.orEmpty()),
                subtitle = stringResource(R.string.used_in_accounts, group.count),
                icon = Icons.Default.ContentCopy,
                stateKey = group.passwordHash,
                initiallyExpanded = false
            ) {
                group.entries.forEachIndexed { index, entry ->
                    SecurityDetailEntryRow(
                                entry = entry,
                                title = entry.title,
                                subtitle = entry.username,
                                detail = entry.website,
                                onClick = { onNavigateToPassword(entry.id) }
                            )
                    if (index < group.entries.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DuplicateUrlsFlatList(
    groups: List<DuplicateUrlGroup>,
    onNavigateToPassword: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) {
        EmptyStateView(
            icon = Icons.Default.CheckCircle,
            message = stringResource(R.string.no_duplicate_urls),
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(groups, key = { it.url }) { group ->
            CollapsibleSecurityGroupCard(
                title = group.url,
                subtitle = stringResource(R.string.used_in_accounts, group.count),
                icon = Icons.Default.Public,
                stateKey = group.url,
                initiallyExpanded = false
            ) {
                group.entries.forEachIndexed { index, entry ->
                    SecurityDetailEntryRow(
                                entry = entry,
                                title = entry.title,
                                subtitle = entry.username,
                                detail = entry.website,
                                onClick = { onNavigateToPassword(entry.id) }
                            )
                    if (index < group.entries.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSecurityGroupCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    stateKey: String,
    initiallyExpanded: Boolean,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { expanded = !expanded }) {
                MonicaExpansionChevron(
                    expanded = expanded,
                    contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand)
                )
            }
        }
        MonicaExpandableContent(expanded = expanded) {
            Column {
                HorizontalDivider()
                content()
            }
        }
    }
}

@Composable
private fun CompromisedFlatList(
    items: List<CompromisedPassword>,
    onNavigateToPassword: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        EmptyStateView(
            icon = Icons.Default.CheckCircle,
            message = stringResource(R.string.no_compromised_passwords),
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items) { item ->
            SecurityDetailEntryRow(
                entry = item.entry,
                title = item.entry.title,
                subtitle = item.entry.username,
                detail = stringResource(R.string.breached_times, item.breachCount),
                onClick = { onNavigateToPassword(item.entry.id) }
            )
        }
    }
}

@Composable
private fun No2faFlatList(
    items: List<No2FAAccount>,
    onNavigateToPassword: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        EmptyStateView(
            icon = Icons.Default.CheckCircle,
            message = stringResource(R.string.all_accounts_have_twofa),
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items) { item ->
            SecurityDetailEntryRow(
                entry = item.entry,
                title = item.entry.title,
                subtitle = item.entry.username,
                detail = item.domain,
                onClick = { onNavigateToPassword(item.entry.id) }
            )
        }
    }
}

@Composable
private fun InactivePasskeyFlatList(
    items: List<InactivePasskeyAccount>,
    onNavigateToPassword: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        EmptyStateView(
            icon = Icons.Default.CheckCircle,
            message = stringResource(R.string.all_accounts_have_passkeys),
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items) { item ->
            SecurityDetailEntryRow(
                entry = item.entry,
                title = item.entry.title,
                subtitle = item.entry.username,
                detail = item.domain,
                onClick = { onNavigateToPassword(item.entry.id) }
            )
        }
    }
}

@Composable
private fun SecurityDetailEntryRow(
    entry: PasswordEntry,
    title: String,
    subtitle: String?,
    detail: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SecurityPasswordEntryIcon(entry = entry)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title.ifBlank { "—" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SecurityPasswordEntryIcon(entry: PasswordEntry) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val simpleIcon = if (entry.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_SIMPLE) {
        takagi.ru.monica.ui.icons.rememberSimpleIconBitmap(
            slug = entry.customIconValue,
            tintColor = primaryColor
        )
    } else null
    val uploadedIcon = if (entry.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_UPLOADED) {
        takagi.ru.monica.ui.icons.rememberUploadedPasswordIcon(entry.customIconValue)
    } else null
    val emojiIcon = entry.customIconValue.takeIf {
        entry.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_EMOJI
    }
    val appPackageName = entry.primaryLinkedAppPackageName()
    val appIcon = if (
        appPackageName.isNotBlank() &&
        !takagi.ru.monica.autofill_ng.ui.isWebAddress(entry.website)
    ) {
        takagi.ru.monica.autofill_ng.ui.rememberAppIcon(appPackageName)
    } else null
    val autoMatchedIcon = takagi.ru.monica.ui.icons.rememberAutoMatchedSimpleIcon(
        website = entry.website,
        title = entry.title,
        appPackageName = appPackageName,
        tintColor = primaryColor,
        enabled = entry.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_NONE
    )
    val favicon = if (entry.website.isNotBlank()) {
        takagi.ru.monica.autofill_ng.ui.rememberFavicon(
            url = entry.website,
            enabled = autoMatchedIcon.resolved && autoMatchedIcon.slug == null
        )
    } else null
    val bitmap = simpleIcon ?: uploadedIcon ?: autoMatchedIcon.bitmap ?: favicon ?: appIcon

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (emojiIcon != null) {
            takagi.ru.monica.ui.icons.EmojiIconText(emoji = emojiIcon, size = 22.dp)
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(32.dp).padding(2.dp)
            )
        } else {
            Text(
                text = entry.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun EmptyStateView(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(52.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun scopeDisplayName(
    scope: SecurityAnalysisScopeOption,
    context: android.content.Context
): String {
    return when (scope.type) {
        SecurityAnalysisScopeType.ALL -> context.getString(R.string.security_analysis_scope_all)
        SecurityAnalysisScopeType.LOCAL -> context.getString(R.string.security_analysis_scope_local)
        SecurityAnalysisScopeType.KEEPASS -> {
            val name = scope.displayName
            if (!name.isNullOrBlank()) {
                "KeePass · $name"
            } else {
                context.getString(R.string.security_analysis_scope_keepass, scope.sourceId ?: 0L)
            }
        }
        SecurityAnalysisScopeType.BITWARDEN -> {
            val name = scope.displayName
            if (!name.isNullOrBlank()) {
                "Bitwarden · $name"
            } else {
                context.getString(R.string.security_analysis_scope_bitwarden, scope.sourceId ?: 0L)
            }
        }
    }
}
