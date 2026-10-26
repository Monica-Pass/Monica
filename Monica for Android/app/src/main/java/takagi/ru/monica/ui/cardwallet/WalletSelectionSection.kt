package takagi.ru.monica.ui.cardwallet

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@Composable
internal fun WalletSelectionSectionHeader(
    entry: WalletStackListEntry.SelectionHeader,
    selectedIds: Set<Long>,
    onToggleSelection: () -> Unit,
    onManageStack: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleExpanded: (() -> Unit)? = null
) {
    val selectedCount = entry.cards.count { it.id in selectedIds }
    val selectionState = when (selectedCount) {
        0 -> ToggleableState.Off
        entry.cards.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val title = if (entry.stack == null) {
        stringResource(R.string.wallet_selection_unstacked)
    } else {
        val coverTitle = entry.cover?.item?.title?.takeIf { it.isNotBlank() }
        if (coverTitle != null) stringResource(R.string.wallet_selection_stack_title, coverTitle)
        else stringResource(R.string.wallet_stack_default_name)
    }
    val shape = if (entry.stack != null && !entry.collapsed) {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    } else RoundedCornerShape(24.dp)
    val selectionInteraction = remember { MutableInteractionSource() }
    val expansionDescription = stringResource(
        if (entry.collapsed) R.string.wallet_selection_expand_stack else R.string.wallet_selection_collapse_stack
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("wallet_selection_header_${entry.stack?.id ?: "unstacked"}")
            .then(if (entry.stack != null) Modifier.semantics { stateDescription = expansionDescription } else Modifier)
            .clip(shape)
            .background(if (entry.stack != null) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .triStateToggleable(
                state = selectionState,
                interactionSource = selectionInteraction,
                indication = null,
                role = Role.Checkbox,
                onClick = onToggleSelection
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            // Keep feedback on the checkbox so the header and card frames remain one surface.
            Modifier.size(48.dp)
                .testTag("wallet_selection_checkbox_${entry.stack?.id ?: "unstacked"}")
                .clip(CircleShape)
                .indication(selectionInteraction, ripple(bounded = false, radius = 24.dp)),
            contentAlignment = Alignment.Center
        ) {
            TriStateCheckbox(state = selectionState, onClick = null)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    if (entry.stack != null && entry.cards.size < entry.stack.memberIds.size) {
                        R.string.wallet_selection_visible_group_count
                    } else R.string.wallet_selection_group_count,
                    selectedCount,
                    entry.cards.size
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.stack != null) {
            if (onToggleExpanded != null) {
                FilledTonalIconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.testTag("wallet_selection_expand_${entry.stack.id}")
                ) {
                    Icon(
                        if (entry.collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        expansionDescription
                    )
                }
            }
            IconButton(
                onClick = onManageStack,
                modifier = Modifier.testTag("wallet_selection_manage_${entry.stack.id}")
            ) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.wallet_stack_manage))
            }
        }
    }
}

/** Separate lazy rows share one surface so even long stacks keep a visible boundary. */
@Composable
internal fun WalletSelectionCardFrame(
    entry: WalletStackListEntry.Single,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val grouped = entry.selectionStackId != null
    if (!grouped) {
        Box(modifier) { content() }
        return
    }
    val shape = if (entry.isLastInSelectionStack) {
        RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    } else RectangleShape
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = if (entry.isLastInSelectionStack) 20.dp else 0.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp)
            .padding(bottom = if (entry.isLastInSelectionStack) 4.dp else 0.dp)
    ) {
        content()
    }
}
