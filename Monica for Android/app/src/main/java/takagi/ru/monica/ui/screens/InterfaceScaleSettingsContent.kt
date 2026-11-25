package takagi.ru.monica.ui.screens

import android.util.DisplayMetrics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import takagi.ru.monica.ui.components.MonicaSlider as Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import takagi.ru.monica.R
import takagi.ru.monica.data.InterfaceScale

@Composable
fun InterfaceScaleSettingsItem(
    scalePercent: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sectionShape: androidx.compose.ui.graphics.Shape? = null
) {
    val baseDensityDpi = LocalConfiguration.current.densityDpi
    val normalizedPercent = InterfaceScale.normalizePercent(scalePercent)
    val effectiveDpi = InterfaceScale.calculateEffectiveDpi(
        baseDensityDpi = baseDensityDpi,
        percent = normalizedPercent
    )

    SettingsItem(
        icon = Icons.Default.Tune,
        setting = Setting.INTERFACE_SCALE_TITLE,
        subtitle = stringResource(
            R.string.interface_scale_current,
            normalizedPercent,
            effectiveDpi
        ),
        onClick = onClick,
        modifier = modifier,
        sectionShape = sectionShape
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceScaleSelectionSheet(
    currentPercent: Int,
    onPercentChanged: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val systemDensity = remember(configuration.densityDpi, configuration.fontScale) {
        Density(
            density = configuration.densityDpi / DisplayMetrics.DENSITY_DEFAULT.toFloat(),
            fontScale = configuration.fontScale
        )
    }

    CompositionLocalProvider(LocalDensity provides systemDensity) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            InterfaceScaleSheetContent(
                currentPercent = currentPercent,
                baseDensityDpi = configuration.densityDpi,
                onPercentChanged = onPercentChanged,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun InterfaceScaleSheetContent(
    currentPercent: Int,
    baseDensityDpi: Int,
    onPercentChanged: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val normalizedCurrentPercent = InterfaceScale.normalizePercent(currentPercent)
    var draftPercent by remember(normalizedCurrentPercent) {
        mutableIntStateOf(normalizedCurrentPercent)
    }
    val effectiveDpi = InterfaceScale.calculateEffectiveDpi(
        baseDensityDpi = baseDensityDpi,
        percent = draftPercent
    )
    val currentStateDescription = stringResource(
        R.string.interface_scale_current,
        draftPercent,
        effectiveDpi
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.interface_scale_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.interface_scale_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$draftPercent%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "$effectiveDpi DPI",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Slider(
            value = draftPercent.toFloat(),
            onValueChange = { value ->
                draftPercent = value.roundToInt().coerceIn(
                    InterfaceScale.MIN_PERCENT,
                    InterfaceScale.MAX_PERCENT
                )
            },
            onValueChangeFinished = {
                if (draftPercent != normalizedCurrentPercent) {
                    onPercentChanged(draftPercent)
                }
            },
            valueRange = InterfaceScale.MIN_PERCENT.toFloat()..
                InterfaceScale.MAX_PERCENT.toFloat(),
            steps = InterfaceScale.MAX_PERCENT - InterfaceScale.MIN_PERCENT - 1,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = currentStateDescription
                }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${InterfaceScale.MIN_PERCENT}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${InterfaceScale.MAX_PERCENT}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = {
                draftPercent = InterfaceScale.DEFAULT_PERCENT
                if (normalizedCurrentPercent != InterfaceScale.DEFAULT_PERCENT) {
                    onPercentChanged(InterfaceScale.DEFAULT_PERCENT)
                }
            },
            enabled = draftPercent != InterfaceScale.DEFAULT_PERCENT,
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.interface_scale_reset))
        }
    }
}
