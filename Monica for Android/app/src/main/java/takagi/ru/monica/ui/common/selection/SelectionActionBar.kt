package takagi.ru.monica.ui.common.selection

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@Composable
fun SelectionActionBar(
    modifier: Modifier = Modifier,
    selectedCount: Int,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onFavorite: (() -> Unit)? = null,
    onMoveToCategory: (() -> Unit)? = null,
    onStack: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onRemoveFromFrequent: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme, containerColor, contentColor) {
        selectionBarColors(scheme, containerColor, contentColor)
    }
    val countDescription = stringResource(R.string.selected_items, selectedCount)
    Surface(
        modifier = modifier.testTag("selection_action_bar"),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                modifier = Modifier.semantics { contentDescription = countDescription },
                shape = CircleShape,
                color = scheme.primary,
                contentColor = readableSelectionContent(scheme.onPrimary, scheme.primary),
            ) {
                Text(
                    text = selectedCount.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    maxLines = 1,
                )
            }
            // Keep the count and close action visible; only actions scroll on narrow screens.
            Row(
                Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState())
                    .testTag("selection_actions"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ActionIcon(
                    colors = colors,
                    icon = Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(id = R.string.select_all),
                    onClick = onSelectAll
                )

                onFavorite?.let {
                    ActionIcon(
                        colors = colors,
                        icon = Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(id = R.string.favorite),
                        onClick = it
                    )
                }

                onMoveToCategory?.let {
                    ActionIcon(
                        colors = colors,
                        icon = Icons.Default.Folder,
                        contentDescription = stringResource(id = R.string.move_to_category),
                        onClick = it
                    )
                }

                onStack?.let {
                    ActionIcon(
                        colors = colors,
                        icon = Icons.Default.Layers,
                        contentDescription = stringResource(id = R.string.batch_stack),
                        onClick = it
                    )
                }

                onRemoveFromFrequent?.let {
                    ActionIcon(
                        colors = colors,
                        icon = Icons.Outlined.RemoveCircleOutline,
                        contentDescription = stringResource(R.string.vault_overview_remove_frequent_items),
                        onClick = it
                    )
                }

                onRestore?.let {
                    ActionIcon(
                        colors = colors,
                        icon = Icons.Outlined.Restore,
                        contentDescription = stringResource(R.string.restore),
                        onClick = it
                    )
                }

                onDelete?.let {
                    ActionIcon(
                        colors = colors,
                        icon = Icons.Outlined.Delete,
                        contentDescription = stringResource(id = R.string.delete),
                        destructive = true,
                        onClick = it
                    )
                }

            }
            ActionIcon(
                colors = colors,
                icon = Icons.Default.Close,
                contentDescription = stringResource(id = R.string.close),
                onClick = onExit
            )
        }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    colors: SelectionBarColors,
    destructive: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = if (destructive) colors.deleteContent else colors.actionContent,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription,
            modifier = Modifier.size(24.dp))
    }
}

internal data class SelectionBarColors(
    val container: Color,
    val content: Color,
    val actionContent: Color,
    val deleteContent: Color,
)

internal fun selectionContrast(a: Color, b: Color): Float {
    val first = a.luminance() + 0.05f
    val second = b.luminance() + 0.05f
    return maxOf(first, second) / minOf(first, second)
}

/** Preserve theme hues; use an opaque readable foreground for unusual custom palettes. */
internal fun readableSelectionContent(preferred: Color, background: Color): Color {
    val opaque = preferred.copy(alpha = 1f)
    if (selectionContrast(opaque, background) >= 4.5f) return opaque
    return if (selectionContrast(Color.Black, background) >= selectionContrast(Color.White, background))
        Color.Black else Color.White
}

internal fun selectionBarColors(
    scheme: ColorScheme,
    container: Color = Color.Unspecified,
    content: Color = scheme.onSurface,
): SelectionBarColors {
    // Keep the toolbar separate from list cards, including collapsed OLED roles.
    val preferred = if (container.isSpecified) container
        else lerp(scheme.surfaceContainerHighest, scheme.primary, 0.18f)
    val surface = separateSelectionSurface(preferred, scheme.surfaceContainerHighest)
    return SelectionBarColors(
        surface, readableSelectionContent(content, surface),
        readableSelectionContent(scheme.onSurfaceVariant, surface),
        readableSelectionContent(scheme.error, surface),
    )
}

private fun separateSelectionSurface(preferred: Color, background: Color): Color {
    val opaque = preferred.copy(alpha = 1f)
    if (selectionContrast(opaque, background) >= 1.25f) return opaque
    val edge = if (selectionContrast(Color.Black, background) >= selectionContrast(Color.White, background))
        Color.Black else Color.White
    for (step in 1..20) {
        val candidate = lerp(opaque, edge, step / 20f)
        if (selectionContrast(candidate, background) >= 1.25f) return candidate
    }
    return edge
}
