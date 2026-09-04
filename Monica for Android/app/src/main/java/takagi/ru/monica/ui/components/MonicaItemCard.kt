package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Corner treatment shared by every item card and its gesture container.
 * Keeping one shape prevents the outer swipe surface from exposing a second,
 * larger radius around the actual card.
 */
val MonicaItemCardShape = RoundedCornerShape(8.dp)

/** Shared surface for compact vault items across list and tile layouts. */
@Composable
fun MonicaItemCard(
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    transparentContainer: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = MonicaItemCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                transparentContainer -> Color.Transparent
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        content = content
    )
}
