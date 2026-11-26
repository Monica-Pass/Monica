package takagi.ru.monica.ui.screens

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import takagi.ru.monica.ui.components.MonicaSlider as Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.ColorScheme
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.ui.theme.generateCustomMaterialColorScheme
import takagi.ru.monica.viewmodel.SettingsViewModel
import java.util.Locale

private const val DEFAULT_PRIMARY_SEED = 0xFF6650A4
private const val DEFAULT_SECONDARY_SEED = 0xFF625B71
private const val DEFAULT_TERTIARY_SEED = 0xFF7D5260
private const val DEFAULT_NEUTRAL_SEED = 0xFF605D66
private const val DEFAULT_NEUTRAL_VARIANT_SEED = 0xFF625B71

private enum class SeedTarget {
    PRIMARY, SECONDARY, TERTIARY, NEUTRAL, NEUTRAL_VARIANT
}

private data class SeedPalettePreset(
    val name: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val neutral: Color,
    val neutralVariant: Color
)

private fun Color.toStoreLong(): Long = toArgb().toLong() and 0xFFFFFFFF

private fun Color.toHexColor(): String {
    val rgb = toArgb() and 0xFFFFFF
    return String.format(Locale.US, "#%06X", rgb)
}

private fun Color.toHueSatValue(): FloatArray {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(toArgb(), hsv)
    return hsv
}

private fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val hsv = floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    return Color(AndroidColor.HSVToColor(hsv))
}

private fun parseHexColorOrNull(text: String): Color? {
    val normalized = text.trim().removePrefix("#")
    if (normalized.length != 6 || normalized.any { !it.isDigit() && it.uppercaseChar() !in 'A'..'F' }) {
        return null
    }
    return try {
        Color(AndroidColor.parseColor("#$normalized"))
    } catch (_: IllegalArgumentException) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomColorSettingsScreen(
    settingsViewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by settingsViewModel.settings.collectAsState()

    var primarySeed by remember(settings.customPrimaryColor) {
        mutableStateOf(Color(settings.customPrimaryColor))
    }
    var secondarySeed by remember(settings.customSecondaryColor) {
        mutableStateOf(Color(settings.customSecondaryColor))
    }
    var tertiarySeed by remember(settings.customTertiaryColor) {
        mutableStateOf(Color(settings.customTertiaryColor))
    }
    var neutralSeed by remember(settings.customNeutralColor) {
        mutableStateOf(Color(settings.customNeutralColor))
    }
    var neutralVariantSeed by remember(settings.customNeutralVariantColor) {
        mutableStateOf(Color(settings.customNeutralVariantColor))
    }

    var pickerTarget by remember { mutableStateOf<SeedTarget?>(null) }
    var pickerInitialColor by remember { mutableStateOf<Color?>(null) }
    val systemDarkTheme = isSystemInDarkTheme()
    val previewDarkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val builtInPalettePresets = listOf(
        SeedPalettePreset(
            name = stringResource(R.string.default_color_scheme),
            primary = Color(0xFF6650A4),
            secondary = Color(0xFF625B71),
            tertiary = Color(0xFF7D5260),
            neutral = Color(0xFF605D66),
            neutralVariant = Color(0xFF625B71)
        ),
        SeedPalettePreset(
            name = stringResource(R.string.ocean_blue_scheme),
            primary = Color(0xFF1565C0),
            secondary = Color(0xFF0277BD),
            tertiary = Color(0xFF26C6DA),
            neutral = Color(0xFF4F616E),
            neutralVariant = Color(0xFF4A6376)
        ),
        SeedPalettePreset(
            name = stringResource(R.string.sunset_orange_scheme),
            primary = Color(0xFFE65100),
            secondary = Color(0xFFF57C00),
            tertiary = Color(0xFFFFA726),
            neutral = Color(0xFF6B5D57),
            neutralVariant = Color(0xFF7A5F4D)
        ),
        SeedPalettePreset(
            name = stringResource(R.string.forest_green_scheme),
            primary = Color(0xFF1B5E20),
            secondary = Color(0xFF2E7D32),
            tertiary = Color(0xFF388E3C),
            neutral = Color(0xFF4E6350),
            neutralVariant = Color(0xFF506A56)
        ),
        SeedPalettePreset(
            name = stringResource(R.string.tech_purple_scheme),
            primary = Color(0xFF4A148C),
            secondary = Color(0xFF6A1B9A),
            tertiary = Color(0xFF8E24AA),
            neutral = Color(0xFF5E5870),
            neutralVariant = Color(0xFF655A7A)
        )
    )

    fun currentSeed(target: SeedTarget): Color = when (target) {
        SeedTarget.PRIMARY -> primarySeed
        SeedTarget.SECONDARY -> secondarySeed
        SeedTarget.TERTIARY -> tertiarySeed
        SeedTarget.NEUTRAL -> neutralSeed
        SeedTarget.NEUTRAL_VARIANT -> neutralVariantSeed
    }

    fun applySeed(target: SeedTarget, color: Color) {
        when (target) {
            SeedTarget.PRIMARY -> primarySeed = color
            SeedTarget.SECONDARY -> secondarySeed = color
            SeedTarget.TERTIARY -> tertiarySeed = color
            SeedTarget.NEUTRAL -> neutralSeed = color
            SeedTarget.NEUTRAL_VARIANT -> neutralVariantSeed = color
        }
    }

    fun openPicker(target: SeedTarget) {
        pickerTarget = target
        pickerInitialColor = currentSeed(target)
    }
    val previewScheme = remember(
        primarySeed,
        secondarySeed,
        tertiarySeed,
        neutralSeed,
        neutralVariantSeed,
        previewDarkTheme
    ) {
        generateCustomMaterialColorScheme(
            darkTheme = previewDarkTheme,
            primarySeed = primarySeed.toStoreLong(),
            secondarySeed = secondarySeed.toStoreLong(),
            tertiarySeed = tertiarySeed.toStoreLong(),
            neutralSeed = neutralSeed.toStoreLong(),
            neutralVariantSeed = neutralVariantSeed.toStoreLong()
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_color_scheme)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            primarySeed = Color(DEFAULT_PRIMARY_SEED)
                            secondarySeed = Color(DEFAULT_SECONDARY_SEED)
                            tertiarySeed = Color(DEFAULT_TERTIARY_SEED)
                            neutralSeed = Color(DEFAULT_NEUTRAL_SEED)
                            neutralVariantSeed = Color(DEFAULT_NEUTRAL_VARIANT_SEED)
                        }
                    ) {
                        Text(stringResource(R.string.reset_custom_colors))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.custom_color_scheme_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.custom_color_scheme_generated_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = stringResource(R.string.custom_color_presets),
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                builtInPalettePresets.forEach { preset ->
                    Surface(
                        modifier = Modifier
                            .width(132.dp)
                            .clickable {
                                primarySeed = preset.primary
                                secondarySeed = preset.secondary
                                tertiarySeed = preset.tertiary
                                neutralSeed = preset.neutral
                                neutralVariantSeed = preset.neutralVariant
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    preset.primary,
                                    preset.secondary,
                                    preset.tertiary,
                                    preset.neutral,
                                    preset.neutralVariant
                                ).forEach { dot ->
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(dot)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.seed_colors),
                style = MaterialTheme.typography.titleMedium
            )

            SeedColorCard(
                label = stringResource(R.string.primary_color),
                color = primarySeed,
                onClick = { openPicker(SeedTarget.PRIMARY) }
            )
            SeedColorCard(
                label = stringResource(R.string.secondary_color),
                color = secondarySeed,
                onClick = { openPicker(SeedTarget.SECONDARY) }
            )
            SeedColorCard(
                label = stringResource(R.string.tertiary_color),
                color = tertiarySeed,
                onClick = { openPicker(SeedTarget.TERTIARY) }
            )
            SeedColorCard(
                label = stringResource(R.string.neutral_color),
                color = neutralSeed,
                onClick = { openPicker(SeedTarget.NEUTRAL) }
            )
            SeedColorCard(
                label = stringResource(R.string.neutral_variant_color),
                color = neutralVariantSeed,
                onClick = { openPicker(SeedTarget.NEUTRAL_VARIANT) }
            )

            Text(
                text = stringResource(R.string.live_preview),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (previewDarkTheme) {
                    stringResource(R.string.custom_preview_mode_dark)
                } else {
                    stringResource(R.string.custom_preview_mode_light)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 3.dp,
                color = previewScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PreviewDot(color = previewScheme.primary)
                        PreviewDot(color = previewScheme.secondary)
                        PreviewDot(color = previewScheme.tertiary)
                        PreviewDot(color = previewScheme.surfaceContainerHighest)
                        PreviewDot(color = previewScheme.error)
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = previewScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Circle,
                                contentDescription = null,
                                tint = previewScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.custom_preview_mini_title),
                                color = previewScheme.onSurface,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = previewScheme.primary
                            ) {
                                Text(
                                    text = "A",
                                    color = previewScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = previewScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.custom_preview_surface_outline),
                                color = previewScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(previewScheme.outlineVariant)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = previewScheme.primary,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = stringResource(R.string.custom_preview_button),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        color = previewScheme.onPrimary,
                                        style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = previewScheme.secondaryContainer,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = stringResource(R.string.custom_preview_chip),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        color = previewScheme.onSecondaryContainer,
                                        style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = previewScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.custom_preview_primary_container),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    color = previewScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    settingsViewModel.updateCustomColors(
                        primary = primarySeed.toStoreLong(),
                        secondary = secondarySeed.toStoreLong(),
                        tertiary = tertiarySeed.toStoreLong(),
                        neutral = neutralSeed.toStoreLong(),
                        neutralVariant = neutralVariantSeed.toStoreLong()
                    )
                    settingsViewModel.updateColorScheme(ColorScheme.CUSTOM)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 20.dp)
            ) {
                Text(stringResource(R.string.apply_custom_colors))
            }
        }
    }

    val target = pickerTarget
    if (target != null) {
        val initialColor = pickerInitialColor ?: currentSeed(target)
        ColorPickerDialog(
            initialColor = initialColor,
            onColorChanged = { selected -> applySeed(target, selected) },
            onColorSelected = { selected ->
                applySeed(target, selected)
                pickerInitialColor = null
                pickerTarget = null
            },
            onCancel = {
                pickerInitialColor?.let { original -> applySeed(target, original) }
                pickerInitialColor = null
                pickerTarget = null
            }
        )
    }
}

@Composable
private fun SeedColorCard(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = color.toHexColor(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = onClick) {
                Text(stringResource(R.string.select_color))
            }
        }
    }
}

@Composable
private fun PreviewDot(color: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onColorChanged: (Color) -> Unit,
    onColorSelected: (Color) -> Unit,
    onCancel: () -> Unit
) {
    val initialHsv = remember(initialColor) { initialColor.toHueSatValue() }
    var hue by remember(initialColor) { mutableStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableStateOf(initialHsv[1]) }
    var value by remember(initialColor) { mutableStateOf(initialHsv[2]) }
    var hexText by remember(initialColor) { mutableStateOf(initialColor.toHexColor()) }
    var hexError by remember { mutableStateOf(false) }

    fun syncFromColor(newColor: Color) {
        val hsv = newColor.toHueSatValue()
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        hexText = newColor.toHexColor()
        hexError = false
        onColorChanged(newColor)
    }

    fun currentColor(): Color = hsvToColor(hue, saturation, value)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.select_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = currentColor(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {}

                Text(
                    text = stringResource(R.string.custom_color_quick_swatches),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Color(0xFF6650A4), Color(0xFF1565C0), Color(0xFFE65100), Color(0xFF1B5E20),
                        Color(0xFF4A148C), Color(0xFF552583), Color(0xFFFDB927), Color(0xFF616161),
                        Color(0xFF26C6DA), Color(0xFFFFA726), Color(0xFF388E3C), Color(0xFF8E24AA)
                    ).forEach { swatch ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .clickable { syncFromColor(swatch) }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.custom_color_hex_label),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        val filtered = buildString {
                            input.uppercase(Locale.US).forEachIndexed { index, c ->
                                if (index == 0 && c == '#') {
                                    append(c)
                                } else if (c.isDigit() || c in 'A'..'F') {
                                    append(c)
                                }
                            }
                        }
                        val normalized = if (filtered.startsWith("#")) filtered else "#$filtered"
                        hexText = normalized.take(7)
                        val parsed = parseHexColorOrNull(hexText)
                        if (parsed != null) {
                            syncFromColor(parsed)
                        } else {
                            hexError = hexText.length == 7
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    isError = hexError,
                    modifier = Modifier.fillMaxWidth()
                )
                if (hexError) {
                    Text(
                        text = stringResource(R.string.custom_color_invalid_hex),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(stringResource(R.string.custom_color_hue))
                Slider(
                    value = hue,
                    onValueChange = {
                        hue = it
                        val c = currentColor()
                        hexText = c.toHexColor()
                        hexError = false
                        onColorChanged(c)
                    },
                    valueRange = 0f..360f
                )

                Text(stringResource(R.string.custom_color_saturation))
                Slider(
                    value = saturation,
                    onValueChange = {
                        saturation = it
                        val c = currentColor()
                        hexText = c.toHexColor()
                        hexError = false
                        onColorChanged(c)
                    },
                    valueRange = 0f..1f
                )

                Text(stringResource(R.string.custom_color_value))
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        val c = currentColor()
                        hexText = c.toHexColor()
                        hexError = false
                        onColorChanged(c)
                    },
                    valueRange = 0f..1f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(currentColor()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
