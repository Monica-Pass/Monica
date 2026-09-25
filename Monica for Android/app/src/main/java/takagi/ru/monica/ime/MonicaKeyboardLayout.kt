package takagi.ru.monica.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import takagi.ru.monica.R

private val LocalKeyHeight = staticCompositionLocalOf { 48.dp }
private val LocalKeyFeedback = staticCompositionLocalOf { true }
private enum class KeyStyle { STANDARD, ACTION, ENTER }

@Composable
internal fun MonicaKeyboard(
    modifier: Modifier = Modifier,
    mode: MonicaKeyboardMode,
    isUppercase: Boolean,
    pinDigits: List<String>,
    hideKeyFeedback: Boolean,
    onKeyPressed: (String) -> Unit,
    onBackspace: () -> Unit,
    onDeleteAll: () -> Unit,
    onEnter: () -> Unit,
    onSpace: () -> Unit,
    onShiftToggle: () -> Unit,
    onKeyboardModeChange: (MonicaKeyboardMode) -> Unit,
    onSwitchInputMethod: () -> Unit,
) {
    BoxWithConstraints(modifier.padding(horizontal = 8.dp, vertical = 12.dp).testTag("ime_key_grid")) {
        val density = LocalDensity.current
        val geometry = ImeKeyGeometry(maxWidth.value,
            gap = with(density) { 4.dp.roundToPx().toDp().value }, pixel = 1f / density.density)
        val height = (maxHeight - 24.dp) / 4
        CompositionLocalProvider(LocalKeyHeight provides height, LocalKeyFeedback provides !hideKeyFeedback) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    MonicaKeyboardMode.LETTERS -> {
                        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(geometry.gap.dp, Alignment.CenterHorizontally)) {
                                if (row == 2) ImeKey("", "shift", geometry.action.dp, onShiftToggle,
                                    icon = if (isUppercase) Icons.Default.KeyboardCapslock else Icons.Default.ArrowUpward,
                                    description = stringResource(R.string.ime_key_shift), style = KeyStyle.ACTION, selected = isUppercase)
                                letters.forEach { letter ->
                                    val output = if (isUppercase) letter.uppercaseChar().toString() else letter.toString()
                                    ImeKey(output, "letter_$letter", geometry.letter.dp, { onKeyPressed(output) })
                                }
                                if (row == 2) DeleteKey(geometry.action.dp, onBackspace, onDeleteAll)
                            }
                        }
                        BottomKeyboardRow(geometry, mode, onKeyboardModeChange, onSwitchInputMethod,
                            onSpace, onKeyPressed, onEnter)
                    }
                    MonicaKeyboardMode.NUMBERS -> {
                        val digits = pinDigits.takeIf { it.size == 10 && it.toSet() == StandardImePinDigits.toSet() }
                            ?: StandardImePinDigits
                        repeat(4) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(geometry.gap.dp)) {
                                if (row < 3) {
                                    digits.subList(row * 3, row * 3 + 3).forEach { digit ->
                                        ImeKey(digit, "digit_$digit", geometry.number.dp, { onKeyPressed(digit) })
                                    }
                                } else {
                                    ModeKey(mode, geometry.number.dp, onKeyboardModeChange)
                                    ImeKey(digits[9], "digit_${digits[9]}", geometry.number.dp, { onKeyPressed(digits[9]) })
                                    ImeKey(".", "decimal", geometry.number.dp, { onKeyPressed(".") })
                                }
                                when (row) {
                                    0 -> DeleteKey(geometry.number.dp, onBackspace, onDeleteAll)
                                    1 -> ImeKey("−", "minus", geometry.number.dp, { onKeyPressed("-") }, style = KeyStyle.ACTION)
                                    2 -> SwitchKey(geometry.number.dp, onSwitchInputMethod)
                                    else -> EnterKey(geometry.number.dp, onEnter)
                                }
                            }
                        }
                    }
                    MonicaKeyboardMode.SYMBOLS -> {
                        listOf("1234567890", "@#$%&*-+=/").forEach { letters ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(geometry.gap.dp)) {
                                letters.forEach { key ->
                                    ImeKey(key.toString(), "symbol_${key.code}", geometry.letter.dp, { onKeyPressed(key.toString()) })
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(geometry.gap.dp)) {
                            "!?()[]{}".forEach { key ->
                                ImeKey(key.toString(), "symbol_${key.code}", geometry.letter.dp, { onKeyPressed(key.toString()) })
                            }
                            DeleteKey((geometry.letter * 2 + geometry.gap).dp, onBackspace, onDeleteAll)
                        }
                        BottomKeyboardRow(geometry, mode, onKeyboardModeChange,
                            { onKeyPressed(",") }, onSpace, onKeyPressed, onEnter)
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomKeyboardRow(geometry: ImeKeyGeometry, mode: MonicaKeyboardMode,
    onModeChange: (MonicaKeyboardMode) -> Unit, onSwitch: () -> Unit, onSpace: () -> Unit,
    onKey: (String) -> Unit, onEnter: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(geometry.gap.dp)) {
        ModeKey(mode, geometry.action.dp, onModeChange)
        if (mode == MonicaKeyboardMode.LETTERS) SwitchKey(geometry.letter.dp, onSwitch)
        else ImeKey(",", "comma", geometry.letter.dp, onSwitch)
        ImeKey(stringResource(R.string.ime_key_space), "space", geometry.space.dp, onSpace,
            repeatAction = onSpace, preview = false, smallLabel = true)
        ImeKey(".", "period", geometry.letter.dp, { onKey(".") })
        EnterKey(geometry.action.dp, onEnter)
    }
}

@Composable
private fun ModeKey(mode: MonicaKeyboardMode, width: Dp, onChange: (MonicaKeyboardMode) -> Unit) {
    val next = when (mode) {
        MonicaKeyboardMode.LETTERS -> MonicaKeyboardMode.NUMBERS
        MonicaKeyboardMode.NUMBERS -> MonicaKeyboardMode.SYMBOLS
        MonicaKeyboardMode.SYMBOLS -> MonicaKeyboardMode.LETTERS
    }
    ImeKey(when (mode) { MonicaKeyboardMode.LETTERS -> "?123"; MonicaKeyboardMode.NUMBERS -> "#+="; else -> "ABC" },
        "mode", width, { onChange(next) }, description = stringResource(R.string.ime_key_mode),
        style = KeyStyle.ACTION, capsule = true, preview = false, smallLabel = true)
}

@Composable
private fun DeleteKey(width: Dp, onDelete: () -> Unit, onDeleteAll: () -> Unit) =
    ImeKey("", "delete", width, onDelete, Icons.AutoMirrored.Filled.Backspace,
        stringResource(R.string.ime_key_delete), KeyStyle.ACTION, repeatAction = onDelete, swipeUp = onDeleteAll)

@Composable
private fun EnterKey(width: Dp, onEnter: () -> Unit) =
    ImeKey("", "enter", width, onEnter, Icons.AutoMirrored.Filled.KeyboardReturn,
        stringResource(R.string.ime_key_enter), KeyStyle.ENTER, capsule = true)

@Composable
private fun SwitchKey(width: Dp, onSwitch: () -> Unit) =
    ImeKey("", "switch", width, onSwitch, Icons.Default.Keyboard,
        stringResource(R.string.ime_switch_keyboard))

@Composable
private fun ImeKey(label: String, id: String, width: Dp, onClick: () -> Unit,
    icon: ImageVector? = null, description: String = label, style: KeyStyle = KeyStyle.STANDARD,
    selected: Boolean = false, capsule: Boolean = false, preview: Boolean = true, smallLabel: Boolean = false,
    repeatAction: (() -> Unit)? = null, swipeUp: (() -> Unit)? = null) {
    val height = LocalKeyHeight.current
    val feedback = LocalKeyFeedback.current
    val fontScale = LocalDensity.current.fontScale
    val onClickNow by rememberUpdatedState(onClick)
    val repeatNow by rememberUpdatedState(repeatAction)
    val swipeNow by rememberUpdatedState(swipeUp)
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val container = when {
        selected -> MaterialTheme.colorScheme.primary
        style == KeyStyle.ENTER -> MaterialTheme.colorScheme.primaryContainer
        style == KeyStyle.ACTION -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val foreground = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        style == KeyStyle.ENTER -> MaterialTheme.colorScheme.onPrimaryContainer
        style == KeyStyle.ACTION -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val shape = RoundedCornerShape(if (capsule) height / 2 else 8.dp)
    Box(Modifier.width(width).height(height).testTag("ime_key_$id")
        .zIndex(if (pressed && feedback) 2f else 0f)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = description
            onClick { onClickNow(); true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val click = onClickNow
                val repeat = repeatNow
                val swipe = swipeNow
                down.consume()
                pressed = true
                var didRepeat = false
                var released = false
                var lastPosition = down.position
                val repeatJob = repeat?.let { action -> scope.launch {
                    delay(360)
                    while (true) { didRepeat = true; action(); delay(58) }
                } }
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        lastPosition = change.position
                        if (change.isConsumed) break
                        if (!change.pressed) { released = true; change.consume(); break }
                    }
                    val swipeDistance = down.position.y - lastPosition.y
                    if (released && swipe != null && swipeDistance > 32.dp.toPx()) swipe()
                    else if (released && !didRepeat && lastPosition.x in 0f..size.width.toFloat() &&
                        lastPosition.y in 0f..size.height.toFloat()) click()
                } finally {
                    repeatJob?.cancel()
                    pressed = false
                }
            }
        }) {
        Surface(Modifier.fillMaxSize(), shape = shape,
            color = if (pressed && feedback) lerp(container, foreground, 0.12f) else container,
            contentColor = foreground) {
            Box(contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, null, Modifier.size(if (height < 44.dp) 23.dp else 26.dp))
                else Text(label, maxLines = 1, softWrap = false, fontWeight = FontWeight.Normal,
                    fontSize = ((if (smallLabel) 14f else if (height < 44.dp) 20f else 22f) *
                        fontScale.coerceAtMost(1.3f) / fontScale).sp)
            }
        }
        if (pressed && feedback && preview && icon == null) {
            Surface(Modifier.align(Alignment.TopCenter).offset(y = (-54).dp).size(48.dp, 58.dp)
                .testTag("ime_key_preview"), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 4.dp) {
                Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 28.sp) }
            }
        }
    }
}
