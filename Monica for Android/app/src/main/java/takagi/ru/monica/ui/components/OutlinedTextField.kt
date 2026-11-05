package takagi.ru.monica.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField as MaterialOutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

internal val LocalFilledEntryForm = androidx.compose.runtime.staticCompositionLocalOf { false }

private val TextFieldValueStateSaver: Saver<TextFieldValue, Any> = TextFieldValue.Saver

/**
 * Global text-field wrapper for Monica forms.
 *
 * Why: The Material3 expressive stack can occasionally lose cursor/selection scrolling
 * behavior for long single-line strings when using the String overload directly.
 * This wrapper keeps an internal TextFieldValue to preserve cursor/selection state
 * while remaining API-compatible with existing call sites.
 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    label: (@Composable (() -> Unit))? = null,
    placeholder: (@Composable (() -> Unit))? = null,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
    prefix: (@Composable (() -> Unit))? = null,
    suffix: (@Composable (() -> Unit))? = null,
    supportingText: (@Composable (() -> Unit))? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    saveTextState: Boolean = true,
    entryContentStyle: Boolean = false,
) {
    val textState = if (saveTextState) rememberSaveable(stateSaver = TextFieldValueStateSaver) {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    } else remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    var fieldValue by textState

    // An input and an external reset can happen before the next frame, returning value
    // to the previous effect key. Always render the authoritative value, even then.
    val displayedValue = if (value == fieldValue.text) fieldValue else TextFieldValue(
        text = value,
        selection = TextRange(fieldValue.selection.start.coerceIn(0, value.length),
            fieldValue.selection.end.coerceIn(0, value.length)),
    )
    SideEffect {
        if (fieldValue != displayedValue) fieldValue = displayedValue
    }

    if (LocalFilledEntryForm.current) {
    androidx.compose.material3.TextField(
        value = displayedValue,
        onValueChange = { newValue ->
            val previousText = fieldValue.text
            fieldValue = newValue
            // Keep cursor/selection-only updates local to avoid expensive parent
            // state updates while dragging the cursor handle near field edges.
            if (newValue.text != previousText) {
                onValueChange(newValue.text)
            }
        },
        modifier = modifier.then(rememberBringIntoViewOnFocusModifier()),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = if (LocalStandaloneTemplateFields.current) RoundedCornerShape(24.dp)
            else if (LocalTemplateFieldShape.current) RoundedCornerShape(4.dp) else shape,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            errorIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
    } else {
    MaterialOutlinedTextField(
        value = displayedValue,
        onValueChange = { newValue ->
            val previousText = fieldValue.text
            fieldValue = newValue
            // Keep cursor/selection-only updates local to avoid expensive parent
            // state updates while dragging the cursor handle near field edges.
            if (newValue.text != previousText) {
                onValueChange(newValue.text)
            }
        },
        modifier = modifier.then(rememberBringIntoViewOnFocusModifier()),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = rememberEntryFieldShape(shape, interactionSource, entryContentStyle && enabled && !readOnly),
        colors = colors,
    )
    }
}

@Composable
fun OutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    label: (@Composable (() -> Unit))? = null,
    placeholder: (@Composable (() -> Unit))? = null,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
    prefix: (@Composable (() -> Unit))? = null,
    suffix: (@Composable (() -> Unit))? = null,
    supportingText: (@Composable (() -> Unit))? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    entryContentStyle: Boolean = false,
) {
    if (LocalFilledEntryForm.current) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(rememberBringIntoViewOnFocusModifier()),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = if (LocalStandaloneTemplateFields.current) RoundedCornerShape(24.dp)
            else if (LocalTemplateFieldShape.current) RoundedCornerShape(4.dp) else shape,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            errorIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
    } else {
    MaterialOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(rememberBringIntoViewOnFocusModifier()),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = rememberEntryFieldShape(shape, interactionSource, entryContentStyle && enabled && !readOnly),
        colors = colors,
    )
    }
}

/** Morph the outline only: text metrics, cursor, hit box and scroll position stay stable. */
@Composable
private fun rememberEntryFieldShape(base: Shape, source: MutableInteractionSource, editable: Boolean): Shape {
    if (!editable || !LocalEntryContentStyle.current) return base
    val focused by source.collectIsFocusedAsState()
    val motion = LocalEntryFieldMotion.current && !takagi.ru.monica.ui.LocalReduceAnimations.current
    val radius by animateDpAsState(
        targetValue = if (focused) 22.dp else 12.dp,
        animationSpec = if (motion) spring(dampingRatio = 0.58f, stiffness = 420f) else snap(),
        label = "entryFieldFocusShape",
    )
    return RoundedCornerShape(radius)
}
