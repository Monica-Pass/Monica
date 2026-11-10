package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

internal val LocalStandaloneTemplateFields = staticCompositionLocalOf { false }
internal val LocalTemplateFieldShape = staticCompositionLocalOf { false }

internal data class ConnectedFieldEdges(val first: Boolean, val last: Boolean)
internal val LocalConnectedFieldEdges = staticCompositionLocalOf<ConnectedFieldEdges?> { null }

/** Connect only explicit field rows. Each field owns its outline; supporting content is never clipped. */
@Composable
internal fun ConnectedFieldGroup(vararg fields: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val parent = LocalConnectedFieldEdges.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        fields.forEachIndexed { index, field ->
            CompositionLocalProvider(LocalConnectedFieldEdges provides ConnectedFieldEdges(
                first = index == 0 && (parent?.first != false),
                last = index == fields.lastIndex && (parent?.last != false),
            )) { field() }
        }
    }
}

@Composable
internal fun connectedFieldShape(fallback: Shape): Shape {
    val edges = LocalConnectedFieldEdges.current ?: return fallback
    return RoundedCornerShape(
        topStart = if (edges.first) 24.dp else 4.dp,
        topEnd = if (edges.first) 24.dp else 4.dp,
        bottomStart = if (edges.last) 24.dp else 4.dp,
        bottomEnd = if (edges.last) 24.dp else 4.dp,
    )
}

/** A section can contain fields, buttons and supporting text; each owns its outline. */
@Composable
internal fun TemplateFormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title.isNotBlank()) Text(title, modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompositionLocalProvider(
            LocalFilledEntryForm provides true,
            LocalConnectedFieldEdges provides null,
            LocalTemplateFieldShape provides false,
            LocalStandaloneTemplateFields provides true,
        ) {
            Column(Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}
