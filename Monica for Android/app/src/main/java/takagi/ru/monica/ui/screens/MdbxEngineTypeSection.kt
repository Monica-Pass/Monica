package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.MonicaExpandableContent
import takagi.ru.monica.ui.components.MonicaExpansionChevron
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxTigaMode

@Composable
fun MdbxEngineTypeSection(
    selectedEngine: MdbxEngineType,
    onEngineChange: (MdbxEngineType) -> Unit,
    remote: Boolean,
    allowLegacyImport: Boolean = false,
    selectedTigaMode: MdbxTigaMode? = null,
    onTigaModeChange: ((MdbxTigaMode) -> Unit)? = null
) {
    val strings = rememberScreenStrings()
    var expanded by remember { mutableStateOf(false) }
    val engineLabel = if (selectedEngine == MdbxEngineType.KOTLIN_MDBX1) "MDBX 1" else "MDBX 2"
    val summary = listOfNotNull(engineLabel, selectedTigaMode?.label).joinToString(" · ")

    MdbxCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            ListItem(
                headlineContent = {
                    Text(strings.get(R.string.mdbx_ui_database_options), fontWeight = FontWeight.SemiBold)
                },
                supportingContent = { Text(summary) },
                leadingContent = {
                    MdbxIconBadge(Icons.Default.Tune)
                },
                trailingContent = {
                    MonicaExpansionChevron(
                        expanded = expanded,
                        contentDescription = if (expanded) strings.get(R.string.mdbx_ui_collapse_database_options) else strings.get(R.string.mdbx_ui_expand_database_options)
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().mdbxClickable(shape = MdbxPanelShape) { expanded = !expanded }
            )
            MonicaExpandableContent(expanded = expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider()
                    Text(strings.get(R.string.mdbx_ui_database_engine), style = MaterialTheme.typography.labelLarge)
                    if (allowLegacyImport) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val engines = listOf(MdbxEngineType.RUST_MDBX2, MdbxEngineType.KOTLIN_MDBX1)
                            engines.forEachIndexed { index, engine ->
                                SegmentedButton(
                                    selected = selectedEngine == engine,
                                    onClick = { onEngineChange(engine) },
                                    shape = SegmentedButtonDefaults.itemShape(index, engines.size)
                                ) {
                                    Text(if (engine == MdbxEngineType.KOTLIN_MDBX1)
                                        strings.get(R.string.mdbx_legacy_import_option) else "MDBX 2")
                                }
                            }
                        }
                    } else {
                        Text("MDBX 2", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = when {
                            !allowLegacyImport -> strings.get(R.string.mdbx_legacy_creation_disabled)
                            selectedEngine == MdbxEngineType.KOTLIN_MDBX1 ->
                                strings.get(R.string.mdbx_legacy_unavailable_description)
                            selectedEngine == MdbxEngineType.RUST_MDBX2 && remote ->
                                strings.get(R.string.mdbx_ui_engine_remote_description)
                            selectedEngine == MdbxEngineType.RUST_MDBX2 ->
                                strings.get(R.string.mdbx_ui_engine_local_description)
                            else -> strings.get(R.string.mdbx_ui_engine_local_description)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (selectedTigaMode != null && onTigaModeChange != null) {
                        HorizontalDivider()
                        Text(stringResource(R.string.mdbx_tiga_section), style = MaterialTheme.typography.labelLarge)
                        Text(
                            stringResource(
                                when (selectedTigaMode) {
                                    MdbxTigaMode.POWER -> R.string.mdbx_tiga_power_desc
                                    MdbxTigaMode.MULTI -> R.string.mdbx_tiga_multi_desc
                                    MdbxTigaMode.SKY -> R.string.mdbx_tiga_sky_desc
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            MdbxTigaMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = selectedTigaMode == mode,
                                    onClick = { onTigaModeChange(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, MdbxTigaMode.entries.size)
                                ) {
                                    Text(mode.label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
