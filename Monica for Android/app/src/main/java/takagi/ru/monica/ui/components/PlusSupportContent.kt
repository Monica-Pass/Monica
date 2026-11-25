package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@Composable
internal fun PlusHonestyStatement(actions: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("plus_honesty"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Favorite, null, Modifier.padding(top = 2.dp).size(24.dp))
                Text(stringResource(R.string.plus_honesty_title),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.testTag("plus_honesty_body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                stringResource(R.string.plus_honesty_body).split("\n\n").forEach { paragraph ->
                    MarkdownPreviewText(markdown = paragraph, imageBitmaps = emptyMap(), renderImages = false)
                }
            }
            Column(Modifier.padding(top = 4.dp), content = actions)
        }
    }
}
