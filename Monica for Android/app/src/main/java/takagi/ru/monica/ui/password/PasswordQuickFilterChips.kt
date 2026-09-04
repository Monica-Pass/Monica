package takagi.ru.monica.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.ui.components.MonicaExpressiveFilterChip
import takagi.ru.monica.ui.password.icon
import takagi.ru.monica.ui.password.labelRes
import takagi.ru.monica.ui.password.toPasswordPageContentTypeOrNull

@Composable
internal fun PasswordQuickFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    leadingIcon: ImageVector? = null,
    selectedLeadingIcon: ImageVector? = leadingIcon,
    animated: Boolean = true
) {
    MonicaExpressiveFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        interactionSource = interactionSource,
        leadingIcon = leadingIcon,
        selectedLeadingIcon = selectedLeadingIcon,
        animated = animated
    )
}

internal fun shouldShowQuickFilterItem(
    item: PasswordListQuickFilterItem,
    aggregateVisibleContentTypes: List<PasswordPageContentType>
): Boolean {
    // Legacy order placeholder; TWO_FA is the actual visible authenticator filter.
    if (item == PasswordListQuickFilterItem.AUTHENTICATOR) return false
    if (item == PasswordListQuickFilterItem.TWO_FA ||
        item == PasswordListQuickFilterItem.PASSKEY ||
        item == PasswordListQuickFilterItem.NOTE ||
        item == PasswordListQuickFilterItem.ATTACHMENTS
    ) {
        return true
    }
    val type = item.toPasswordPageContentTypeOrNull() ?: return true
    return type in aggregateVisibleContentTypes
}

@Composable
internal fun PasswordQuickFilterChipItem(
    item: PasswordListQuickFilterItem,
    categoryEditMode: Boolean,
    quickFilterFavorite: Boolean,
    onQuickFilterFavoriteChange: (Boolean) -> Unit,
    quickFilter2fa: Boolean,
    onQuickFilter2faChange: (Boolean) -> Unit,
    quickFilterNotes: Boolean,
    onQuickFilterNotesChange: (Boolean) -> Unit,
    quickFilterPasskey: Boolean,
    onQuickFilterPasskeyChange: (Boolean) -> Unit,
    quickFilterBoundNote: Boolean,
    onQuickFilterBoundNoteChange: (Boolean) -> Unit,
    quickFilterAttachments: Boolean,
    onQuickFilterAttachmentsChange: (Boolean) -> Unit,
    quickFilterUncategorized: Boolean,
    onQuickFilterUncategorizedChange: (Boolean) -> Unit,
    quickFilterLocalOnly: Boolean,
    onQuickFilterLocalOnlyChange: (Boolean) -> Unit,
    quickFilterManualStackOnly: Boolean,
    onQuickFilterManualStackOnlyChange: (Boolean) -> Unit,
    quickFilterNeverStack: Boolean,
    onQuickFilterNeverStackChange: (Boolean) -> Unit,
    quickFilterUnstacked: Boolean,
    onQuickFilterUnstackedChange: (Boolean) -> Unit,
    aggregateSelectedTypes: Set<PasswordPageContentType> = emptySet(),
    aggregateVisibleTypes: List<PasswordPageContentType> = emptyList(),
    onToggleAggregateType: ((PasswordPageContentType) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    modifier: Modifier = Modifier
) {
    when (item) {
        PasswordListQuickFilterItem.FAVORITE -> {
            PasswordQuickFilterChip(
                selected = quickFilterFavorite,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterFavoriteChange(!quickFilterFavorite)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_favorite),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Outlined.FavoriteBorder,
                selectedLeadingIcon = Icons.Default.Favorite
            )
        }

        PasswordListQuickFilterItem.TWO_FA -> {
            val type = PasswordPageContentType.AUTHENTICATOR
            val useAggregateFilter = type in aggregateVisibleTypes
            PasswordQuickFilterChip(
                selected = if (useAggregateFilter) aggregateSelectedTypes.contains(type) else quickFilter2fa,
                onClick = {
                    if (!categoryEditMode) {
                        if (useAggregateFilter) {
                            onToggleAggregateType?.invoke(type)
                        } else {
                            onQuickFilter2faChange(!quickFilter2fa)
                        }
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_2fa),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.Security
            )
        }

        PasswordListQuickFilterItem.NOTES -> {
            PasswordQuickFilterChip(
                selected = quickFilterNotes,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterNotesChange(!quickFilterNotes)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_notes),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.Description
            )
        }

        PasswordListQuickFilterItem.UNCATEGORIZED -> {
            PasswordQuickFilterChip(
                selected = quickFilterUncategorized,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterUncategorizedChange(!quickFilterUncategorized)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_uncategorized),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.FolderOff
            )
        }

        PasswordListQuickFilterItem.LOCAL_ONLY -> {
            PasswordQuickFilterChip(
                selected = quickFilterLocalOnly,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterLocalOnlyChange(!quickFilterLocalOnly)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_local_only),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.Key
            )
        }

        PasswordListQuickFilterItem.MANUAL_STACK_ONLY -> {
            PasswordQuickFilterChip(
                selected = quickFilterManualStackOnly,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterManualStackOnlyChange(!quickFilterManualStackOnly)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_manual_stack_only),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.Apps
            )
        }

        PasswordListQuickFilterItem.NEVER_STACK -> {
            PasswordQuickFilterChip(
                selected = quickFilterNeverStack,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterNeverStackChange(!quickFilterNeverStack)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_never_stack),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.LinearScale
            )
        }

        PasswordListQuickFilterItem.UNSTACKED -> {
            PasswordQuickFilterChip(
                selected = quickFilterUnstacked,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterUnstackedChange(!quickFilterUnstacked)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_unstacked),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.Straighten
            )
        }

        PasswordListQuickFilterItem.CARD_WALLET -> {
            val type = item.toPasswordPageContentTypeOrNull() ?: return
            if (type !in aggregateVisibleTypes) return
            PasswordQuickFilterChip(
                selected = aggregateSelectedTypes.contains(type),
                onClick = {
                    if (!categoryEditMode) {
                        onToggleAggregateType?.invoke(type)
                    }
                },
                label = stringResource(type.labelRes()),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = type.icon()
            )
        }

        PasswordListQuickFilterItem.PASSKEY -> {
            val type = PasswordPageContentType.PASSKEY
            val useAggregateFilter = type in aggregateVisibleTypes
            PasswordQuickFilterChip(
                selected = if (useAggregateFilter) aggregateSelectedTypes.contains(type) else quickFilterPasskey,
                onClick = {
                    if (!categoryEditMode) {
                        if (useAggregateFilter) {
                            onToggleAggregateType?.invoke(type)
                        } else {
                            onQuickFilterPasskeyChange(!quickFilterPasskey)
                        }
                    }
                },
                label = stringResource(type.labelRes()),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = type.icon()
            )
        }

        PasswordListQuickFilterItem.NOTE -> {
            val type = PasswordPageContentType.NOTE
            val useAggregateFilter = type in aggregateVisibleTypes
            PasswordQuickFilterChip(
                selected = if (useAggregateFilter) aggregateSelectedTypes.contains(type) else quickFilterBoundNote,
                onClick = {
                    if (!categoryEditMode) {
                        if (useAggregateFilter) {
                            onToggleAggregateType?.invoke(type)
                        } else {
                            onQuickFilterBoundNoteChange(!quickFilterBoundNote)
                        }
                    }
                },
                label = stringResource(type.labelRes()),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = type.icon()
            )
        }

        PasswordListQuickFilterItem.ATTACHMENTS -> {
            PasswordQuickFilterChip(
                selected = quickFilterAttachments,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterAttachmentsChange(!quickFilterAttachments)
                    }
                },
                label = stringResource(R.string.attachment_section_title),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.AttachFile
            )
        }

        PasswordListQuickFilterItem.AUTHENTICATOR -> Unit
    }
}
