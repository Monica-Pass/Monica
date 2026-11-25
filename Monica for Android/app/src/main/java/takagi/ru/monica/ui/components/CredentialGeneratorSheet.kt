package takagi.ru.monica.ui.components

import takagi.ru.monica.ui.components.MonicaSlider as Slider
import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.GeneratorPreferences
import takagi.ru.monica.data.toSymbolPasswordGeneratorOptions
import takagi.ru.monica.util.PasswordGenerator

internal data class GeneratorSuggestion(val label: String, val value: String)
private enum class CredentialGeneratorKind(val title: Int) {
    SYMBOL(R.string.cg_symbols), PHRASE(R.string.generator_passphrase), PIN(R.string.pin_code),
    USERNAME(R.string.cg_random_username), EMAIL(R.string.cg_email_alias)
}

/** Shared entry generators. Results stay in memory until the user explicitly applies them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CredentialGeneratorSheet(
    username: Boolean,
    suggestions: List<GeneratorSuggestion>,
    preferences: GeneratorPreferences = GeneratorPreferences(),
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    val context = LocalContext.current
    val defaults = remember(preferences) { preferences.toSymbolPasswordGeneratorOptions() }
    var kind by remember { mutableStateOf(if (username) CredentialGeneratorKind.USERNAME else CredentialGeneratorKind.SYMBOL) }
    var menu by remember { mutableStateOf(false) }
    var length by remember { mutableIntStateOf(if (username) 12 else defaults.length) }
    var words by remember { mutableIntStateOf(preferences.passphraseWordCount.coerceIn(3, 12)) }
    var pinLength by remember { mutableIntStateOf(preferences.pinLength.coerceIn(3, 9)) }
    var aliasLength by remember { mutableIntStateOf(5) }
    var email by remember { mutableStateOf(suggestions.firstOrNull { android.util.Patterns.EMAIL_ADDRESS.matcher(it.value).matches() }?.value.orEmpty()) }
    var upper by remember { mutableStateOf(defaults.includeUppercase) }
    var lower by remember { mutableStateOf(defaults.includeLowercase) }
    var numbers by remember { mutableStateOf(defaults.includeNumbers) }
    var symbols by remember { mutableStateOf(defaults.includeSymbols) }
    var similar by remember { mutableStateOf(defaults.excludeSimilar) }
    var upperMin by remember { mutableIntStateOf(defaults.uppercaseMin.coerceAtMost(128)) }
    var lowerMin by remember { mutableIntStateOf(defaults.lowercaseMin.coerceAtMost(128)) }
    var numberMin by remember { mutableIntStateOf(defaults.numbersMin.coerceAtMost(128)) }
    var symbolMin by remember { mutableIntStateOf(defaults.symbolsMin.coerceAtMost(128)) }
    var nonce by remember { mutableIntStateOf(0) }
    val draft = listOf(kind, length, words, pinLength, aliasLength, email, upper, lower, numbers, symbols,
        similar, upperMin, lowerMin, numberMin, symbolMin, nonce)
    val sliderInteractions = remember { MutableInteractionSource() }
    val dragging by sliderInteractions.collectIsDraggedAsState()
    var result by remember { mutableStateOf("") }
    var completedDraft by remember { mutableStateOf<List<Any>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val minimumTotal = (if (upper) upperMin else 0) + (if (lower) lowerMin else 0) +
        (if (numbers) numberMin else 0) + (if (symbols) symbolMin else 0)
    val valid = when (kind) {
        CredentialGeneratorKind.SYMBOL -> (upper || lower || numbers || symbols) && minimumTotal <= length
        CredentialGeneratorKind.EMAIL -> android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
        else -> true
    }
    LaunchedEffect(draft, dragging) {
        if (valid && !dragging) {
            result = withContext(Dispatchers.Default) {
                runCatching {
                    when (kind) {
                        CredentialGeneratorKind.SYMBOL -> PasswordGenerator.generatePassword(length, upper, lower, numbers, symbols,
                            defaults.allowedSymbols, similar, defaults.excludeAmbiguous,
                            if (upper) upperMin else 0, if (lower) lowerMin else 0,
                            if (numbers) numberMin else 0, if (symbols) symbolMin else 0)
                        CredentialGeneratorKind.PHRASE -> PasswordGenerator.generatePassphrase(words,
                            preferences.passphraseDelimiter, preferences.passphraseCapitalize, preferences.passphraseIncludeNumber,
                            preferences.passphraseCustomWord, preferences.passphraseCustomWords.lines(), context)
                        CredentialGeneratorKind.PIN -> PasswordGenerator.generatePinCode(pinLength)
                        CredentialGeneratorKind.USERNAME -> PasswordGenerator.generatePassword(length,
                            includeUppercase = false, includeLowercase = true, includeNumbers = true, includeSymbols = false)
                        CredentialGeneratorKind.EMAIL -> {
                            val base = email.trim()
                            val suffix = PasswordGenerator.generatePassword(aliasLength, false, true, true, false)
                            base.substringBeforeLast('@') + "+" + suffix + "@" + base.substringAfterLast('@')
                        }
                    }
                }.getOrDefault("")
            }
            failed = result.isEmpty()
            completedDraft = draft
        }
    }
    val canApply = valid && !dragging && completedDraft == draft && result.isNotEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            .testTag("credential_generator_scroll").padding(horizontal = 12.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (username) R.string.cg_username_title else R.string.password_generator_title),
                    Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            Box {
                FilledTonalButton(onClick = { menu = true }, modifier = Modifier.testTag("generator_kind")) {
                    Text(stringResource(kind.title)); Spacer(Modifier.width(8.dp)); Icon(Icons.Default.ExpandMore, null)
                }
                DropdownMenu(menu, { menu = false }) {
                    (if (username) listOf(CredentialGeneratorKind.USERNAME, CredentialGeneratorKind.EMAIL)
                    else listOf(CredentialGeneratorKind.SYMBOL, CredentialGeneratorKind.PHRASE, CredentialGeneratorKind.PIN)).forEach { option ->
                        DropdownMenuItem(text = { Text(stringResource(option.title)) }, modifier = Modifier.testTag("generator_kind_${option.name}"),
                            onClick = { kind = option; menu = false })
                    }
                }
            }
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(96.dp)) {
                    val availableWidth = with(LocalDensity.current) { maxWidth.roundToPx() }
                    val availableHeight = with(LocalDensity.current) { maxHeight.roundToPx() }
                    if (!valid || (failed && completedDraft == draft)) Text(stringResource(if (kind == CredentialGeneratorKind.EMAIL) R.string.cg_valid_email else R.string.cg_invalid_options),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("generator_error"))
                    else SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                        val colored = buildAnnotatedString {
                            result.forEach { char ->
                                withStyle(SpanStyle(color = when {
                                    char.isDigit() -> MaterialTheme.colorScheme.primary
                                    !char.isLetter() -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                })) { append(char) }
                            }
                        }
                        val measurer = rememberTextMeasurer()
                        val baseStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace)
                        val resultStyle = remember(colored, baseStyle, availableWidth, availableHeight, measurer) {
                            // Measure before drawing: no intermediate font-size states or flashing layout.
                            listOf(1f, .9f, .8f, .7f, .6f, .5f).map { scale ->
                                baseStyle.copy(fontSize = baseStyle.fontSize * scale, lineHeight = baseStyle.lineHeight * scale)
                            }.let { styles ->
                                styles.firstOrNull { style ->
                                    measurer.measure(colored, style, constraints = Constraints(maxWidth = availableWidth)).size.height <= availableHeight
                                } ?: styles.last()
                            }
                        }
                        Text(colored, Modifier.fillMaxWidth().testTag("generator_result"), style = resultStyle)
                    }
                    }
                    // Actions sit below the full-width result, including on narrow screens and large fonts.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { nonce++ }, modifier = Modifier.testTag("generator_refresh")) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.regenerate))
                        }
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(onClick = { if (canApply) onApply(result) },
                            enabled = canApply, modifier = Modifier.testTag("generator_apply")) {
                            Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.cg_use))
                        }
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                // LazyRow measures visible items only. Reserve identical text slots so scrolling
                // from a short suggestion to a long one cannot resize the sheet or move its controls.
                val suggestionTitleStyle = MaterialTheme.typography.labelMedium
                val suggestionValueStyle = MaterialTheme.typography.bodyLarge
                val suggestionHeight = with(LocalDensity.current) {
                    suggestionTitleStyle.lineHeight.toDp() + suggestionValueStyle.lineHeight.toDp() * 2
                } + 40.dp
                Text(stringResource(R.string.cg_saved_suggestions), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(modifier = Modifier.height(suggestionHeight).testTag("generator_suggestions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(suggestions) { index, suggestion ->
                        Surface(onClick = { onApply(suggestion.value) }, shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.height(suggestionHeight).widthIn(min = 160.dp, max = 260.dp).testTag("generator_suggestion_$index")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(suggestion.label, style = suggestionTitleStyle, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(suggestion.value, style = suggestionValueStyle, fontFamily = FontFamily.Monospace,
                                    minLines = 2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            val count = when (kind) {
                CredentialGeneratorKind.PHRASE -> words
                CredentialGeneratorKind.PIN -> pinLength
                CredentialGeneratorKind.EMAIL -> aliasLength
                else -> length
            }
            val range = when (kind) {
                CredentialGeneratorKind.PHRASE -> 3..12
                CredentialGeneratorKind.PIN -> 3..9
                CredentialGeneratorKind.EMAIL -> 3..16
                CredentialGeneratorKind.USERNAME -> 4..32
                else -> 4..128
            }
            fun setCount(value: Int) { when (kind) {
                CredentialGeneratorKind.PHRASE -> words = value
                CredentialGeneratorKind.PIN -> pinLength = value
                CredentialGeneratorKind.EMAIL -> aliasLength = value
                else -> length = value
            } }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (kind == CredentialGeneratorKind.PHRASE) R.string.cg_words else R.string.cg_length), Modifier.weight(1f))
                        GeneratorStepper(count, range, "length", ::setCount)
                    }
                    Slider(count.toFloat(), { setCount(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(),
                        interactionSource = sliderInteractions,
                        steps = if (range.last > 32) 0 else range.last - range.first - 1, modifier = Modifier.testTag("generator_length_slider"))
                }
            }
            if (kind == CredentialGeneratorKind.EMAIL) {
                TextField(email, { email = it }, label = { Text(stringResource(R.string.field_email)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("generator_email"), shape = RoundedCornerShape(24.dp))
            }
            if (kind == CredentialGeneratorKind.SYMBOL) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GeneratorOption(stringResource(R.string.uppercase_az), upper, { upper = it }, upperMin, { upperMin = it }, 0, "upper")
                GeneratorOption(stringResource(R.string.lowercase_az), lower, { lower = it }, lowerMin, { lowerMin = it }, 1, "lower")
                GeneratorOption(stringResource(R.string.numbers_09), numbers, { numbers = it }, numberMin, { numberMin = it }, 2, "numbers")
                GeneratorOption(stringResource(R.string.symbols), symbols, { symbols = it }, symbolMin, { symbolMin = it }, 3, "symbols")
                Surface(shape = entryGroupShape(4, 5), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.exclude_similar), Modifier.weight(1f))
                        Switch(similar, { similar = it }, modifier = Modifier.testTag("generator_similar"))
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneratorStepper(value: Int, range: IntRange, tag: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.testTag("generator_${tag}_decrease")) {
            Icon(Icons.Default.Remove, stringResource(R.string.cg_decrease))
        }
        Text(value.toString(), fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("generator_${tag}_value"))
        IconButton({ onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.testTag("generator_${tag}_increase")) {
            Icon(Icons.Default.Add, stringResource(R.string.cg_increase))
        }
    }
}

@Composable
private fun GeneratorOption(title: String, checked: Boolean, onChange: (Boolean) -> Unit,
    minimum: Int, onMinimum: (Int) -> Unit, index: Int, tag: String) {
    Surface(shape = entryGroupShape(index, 5), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f))
                Checkbox(checked, onChange, modifier = Modifier.testTag("generator_$tag"))
            }
            if (checked) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cg_minimum), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                GeneratorStepper(minimum, 0..128, tag, onMinimum)
            }
        }
    }
}
