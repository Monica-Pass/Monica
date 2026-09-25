package takagi.ru.monica.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.primaryLinkedAppPackageName
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_NONE
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_SIMPLE
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_UPLOADED
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_EMOJI
import takagi.ru.monica.ui.icons.EmojiIconText
import takagi.ru.monica.ui.icons.rememberAutoMatchedSimpleIcon
import takagi.ru.monica.ui.icons.rememberSimpleIconBitmap
import takagi.ru.monica.ui.icons.rememberUploadedPasswordIcon
import takagi.ru.monica.autofill_ng.ui.rememberAppIcon
import takagi.ru.monica.autofill_ng.ui.rememberFavicon
import java.net.URI
import java.text.DateFormat
import java.util.Date

enum class PasswordQuickAccessMode {
    RECENT,
    FREQUENT
}

data class PasswordQuickAccessItem(
    val entry: PasswordEntry,
    val openCount: Int,
    val lastOpenedAt: Long
)

private const val QUICK_ACCESS_LIMIT = 80
private const val MIN_FREQUENT_OPEN_COUNT = 2
private const val QUICK_ACCESS_COMPACT_LIST_LIMIT = 6
private val QUICK_ACCESS_TOP_HANDOFF_GUARD = 8.dp

/**
 * Absorbs the tiny downward delta at the top edge of the list before the modal sheet receives it.
 * Without this handoff window, the list and ModalBottomSheet can both settle the same gesture at
 * the safe-area boundary, which appears as a one-frame sheet jump.
 */
internal class PasswordQuickAccessTopHandoffGuard(
    private val guardPx: Float
) {
    var consumedPx: Float = 0f
        private set

    fun consume(availableY: Float, listAtTop: Boolean): Float {
        if (availableY <= 0f || !listAtTop || guardPx <= 0f) {
            reset()
            return 0f
        }

        val remainingPx = (guardPx - consumedPx).coerceAtLeast(0f)
        val consumedNowPx = availableY.coerceAtMost(remainingPx)
        consumedPx = (consumedPx + consumedNowPx).coerceAtMost(guardPx)
        return consumedNowPx
    }

    fun reset() {
        consumedPx = 0f
    }
}

internal fun rankRecentPasswordQuickAccessItems(
    items: List<PasswordQuickAccessItem>,
    limit: Int = QUICK_ACCESS_LIMIT
): List<PasswordQuickAccessItem> {
    return items
        .filter { it.entry.id > 0L && it.openCount > 0 && it.lastOpenedAt > 0L }
        .sortedWith(
            compareByDescending<PasswordQuickAccessItem> { it.lastOpenedAt }
                .thenByDescending { it.entry.id }
        )
        .take(limit.coerceAtLeast(0))
}

internal fun rankFrequentPasswordQuickAccessItems(
    items: List<PasswordQuickAccessItem>,
    limit: Int = QUICK_ACCESS_LIMIT
): List<PasswordQuickAccessItem> {
    return items
        .filter {
            it.entry.id > 0L &&
                it.openCount >= MIN_FREQUENT_OPEN_COUNT &&
                it.lastOpenedAt > 0L
        }
        .sortedWith(
            compareByDescending<PasswordQuickAccessItem> { it.openCount }
                .thenByDescending { it.lastOpenedAt }
                .thenByDescending { it.entry.id }
        )
        .take(limit.coerceAtLeast(0))
}

internal fun passwordQuickAccessIdentity(entry: PasswordEntry): String {
    val username = entry.username.trim()
    val website = entry.website.trim()
    val websiteLabel = if (website.isBlank()) {
        ""
    } else {
        val normalized = website.substringAfter("://", website)
            .substringBefore('/')
            .substringBefore('?')
            .trim()
        runCatching { URI("https://$normalized").host.orEmpty() }
            .getOrNull()
            .orEmpty()
            .ifBlank { normalized }
            .removePrefix("www.")
    }

    return listOf(username, websiteLabel)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" • ")
        .ifBlank { "-" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordQuickAccessSheet(
    visible: Boolean,
    recentItems: List<PasswordQuickAccessItem>,
    frequentItems: List<PasswordQuickAccessItem>,
    iconCardsEnabled: Boolean = true,
    onOpenPassword: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var selectedMode by rememberSaveable { mutableStateOf(PasswordQuickAccessMode.RECENT) }
    val density = LocalDensity.current
    val topHandoffGuardPx = remember(density) {
        with(density) { QUICK_ACCESS_TOP_HANDOFF_GUARD.toPx() }
    }
    val fixedStatusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val recentListState = rememberLazyListState()
    val frequentListState = rememberLazyListState()

    MonicaModalBottomSheet(
        onDismissRequest = onDismiss,
        showDragHandle = true,
        // Keep the sheet's measured height independent from the moving safe-area insets.
        // The content applies navigation-bar padding explicitly below.
        contentWindowInsets = {
            WindowInsets(0, 0, 0, 0)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = 16.dp,
                    top = fixedStatusBarPadding + 4.dp,
                    end = 16.dp,
                    bottom = 8.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.password_quick_access_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.password_quick_access_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedMode == PasswordQuickAccessMode.RECENT,
                    onClick = { selectedMode = PasswordQuickAccessMode.RECENT },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = { Text(text = stringResource(R.string.password_quick_access_recent)) }
                )
                SegmentedButton(
                    selected = selectedMode == PasswordQuickAccessMode.FREQUENT,
                    onClick = { selectedMode = PasswordQuickAccessMode.FREQUENT },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Whatshot,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = { Text(text = stringResource(R.string.password_quick_access_frequent)) }
                )
            }

            Crossfade(
                targetState = selectedMode,
                animationSpec = tween(durationMillis = 180),
                label = "password quick access mode"
            ) { mode ->
                val activeItems = when (mode) {
                    PasswordQuickAccessMode.RECENT -> recentItems
                    PasswordQuickAccessMode.FREQUENT -> frequentItems
                }
                val listState = when (mode) {
                    PasswordQuickAccessMode.RECENT -> recentListState
                    PasswordQuickAccessMode.FREQUENT -> frequentListState
                }
                val topHandoffGuard = remember(mode, topHandoffGuardPx) {
                    PasswordQuickAccessTopHandoffGuard(topHandoffGuardPx)
                }
                val topHandoffConnection = remember(listState, topHandoffGuardPx) {
                    object : NestedScrollConnection {
                        override fun onPreScroll(
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset {
                            if (source != NestedScrollSource.UserInput) {
                                return Offset.Zero
                            }

                            val listAtTop = listState.firstVisibleItemIndex == 0 &&
                                listState.firstVisibleItemScrollOffset == 0
                            val consumedPx = topHandoffGuard.consume(
                                availableY = available.y,
                                listAtTop = listAtTop
                            )
                            return Offset(0f, consumedPx)
                        }

                        override suspend fun onPreFling(available: Velocity): Velocity {
                            topHandoffGuard.reset()
                            return Velocity.Zero
                        }

                        override suspend fun onPostFling(
                            consumed: Velocity,
                            available: Velocity
                        ): Velocity {
                            topHandoffGuard.reset()
                            return Velocity.Zero
                        }
                    }
                }

                if (activeItems.isEmpty()) {
                    PasswordQuickAccessEmptyState(mode = mode)
                } else if (activeItems.size <= QUICK_ACCESS_COMPACT_LIST_LIMIT) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        activeItems.forEach { item ->
                            key(item.entry.id) {
                                PasswordQuickAccessRow(
                                    item = item,
                                    iconCardsEnabled = iconCardsEnabled,
                                    onClick = {
                                        onDismiss()
                                        onOpenPassword(item.entry.id)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .nestedScroll(topHandoffConnection),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        items(
                            items = activeItems,
                            key = { it.entry.id },
                            contentType = { "password_quick_access_item" }
                        ) { item ->
                            PasswordQuickAccessRow(
                                item = item,
                                iconCardsEnabled = iconCardsEnabled,
                                onClick = {
                                    onDismiss()
                                    onOpenPassword(item.entry.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordQuickAccessEmptyState(mode: PasswordQuickAccessMode) {
    val icon = when (mode) {
        PasswordQuickAccessMode.RECENT -> Icons.Default.History
        PasswordQuickAccessMode.FREQUENT -> Icons.Default.Whatshot
    }
    val message = when (mode) {
        PasswordQuickAccessMode.RECENT -> R.string.password_quick_access_empty_recent
        PasswordQuickAccessMode.FREQUENT -> R.string.password_quick_access_empty_frequent
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PasswordQuickAccessRow(
    item: PasswordQuickAccessItem,
    iconCardsEnabled: Boolean,
    onClick: () -> Unit
) {
    val identity = remember(item.entry) { passwordQuickAccessIdentity(item.entry) }
    val lastOpenedText = remember(item.lastOpenedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(item.lastOpenedAt))
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PasswordQuickAccessLeadingIcon(
                entry = item.entry,
                iconCardsEnabled = iconCardsEnabled
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.entry.title.ifBlank { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = identity,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.password_quick_access_last_opened, lastOpenedText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = stringResource(R.string.password_quick_access_open_count, item.openCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PasswordQuickAccessLeadingIcon(
    entry: PasswordEntry,
    iconCardsEnabled: Boolean
) {
    val simpleIcon = if (iconCardsEnabled && entry.customIconType == PASSWORD_ICON_TYPE_SIMPLE) {
        rememberSimpleIconBitmap(
            slug = entry.customIconValue,
            tintColor = MaterialTheme.colorScheme.primary,
            enabled = true
        )
    } else null
    val uploadedIcon = if (iconCardsEnabled && entry.customIconType == PASSWORD_ICON_TYPE_UPLOADED) {
        rememberUploadedPasswordIcon(entry.customIconValue)
    } else null
    val emojiIcon = entry.customIconValue.takeIf {
        iconCardsEnabled && entry.customIconType == PASSWORD_ICON_TYPE_EMOJI
    }
    val primaryAppPackageName = entry.primaryLinkedAppPackageName()
    val appIcon = if (
        iconCardsEnabled &&
        primaryAppPackageName.isNotBlank() &&
        !takagi.ru.monica.autofill_ng.ui.isWebAddress(entry.website)
    ) {
        rememberAppIcon(primaryAppPackageName)
    } else null
    val autoMatchedSimpleIcon = rememberAutoMatchedSimpleIcon(
        website = entry.website,
        title = entry.title,
        appPackageName = primaryAppPackageName,
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = iconCardsEnabled && entry.customIconType == PASSWORD_ICON_TYPE_NONE
    )
    val favicon = if (iconCardsEnabled && entry.website.isNotBlank()) {
        rememberFavicon(
            url = entry.website,
            enabled = autoMatchedSimpleIcon.resolved && autoMatchedSimpleIcon.slug == null
        )
    } else null

    Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            val image: ImageBitmap? = simpleIcon
                ?: uploadedIcon
                ?: autoMatchedSimpleIcon.bitmap
                ?: favicon
                ?: appIcon
            if (emojiIcon != null) {
                EmojiIconText(emoji = emojiIcon, size = 24.dp)
            } else if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(30.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
