package takagi.ru.monica.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class MonicaMenuChoice<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
)

/** Anchored, compact choices with a persistent selection mark and clipped press feedback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ExposedDropdownMenuBoxScope.MonicaExposedChoiceMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    choices: List<MonicaMenuChoice<T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
) {
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        choices.forEachIndexed { index, choice ->
            val isSelected = choice.value == selectedValue
            val shape = RoundedCornerShape(
                topStart = if (index == 0) 18.dp else 4.dp,
                topEnd = if (index == 0) 18.dp else 4.dp,
                bottomStart = if (index == choices.lastIndex) 18.dp else 4.dp,
                bottomEnd = if (index == choices.lastIndex) 18.dp else 4.dp,
            )
            val foreground = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface
            DropdownMenuItem(
                enabled = choice.enabled,
                text = { Text(choice.label, style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = choice.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
                trailingIcon = {
                    Box(Modifier.size(24.dp)) {
                        if (isSelected) Icon(Icons.Default.Check, contentDescription = null)
                    }
                },
                onClick = { onSelect(choice.value); onDismissRequest() },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
                    .clip(shape)
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer)
                    .semantics { selected = isSelected; role = Role.RadioButton },
                colors = MenuDefaults.itemColors(
                    textColor = foreground, leadingIconColor = foreground, trailingIconColor = foreground),
            )
        }
    }
}
