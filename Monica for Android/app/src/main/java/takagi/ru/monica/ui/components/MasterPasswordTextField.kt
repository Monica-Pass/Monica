package takagi.ru.monica.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.security.MasterPasswordPolicy

@Composable
fun MasterPasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    visible: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: (@Composable (() -> Unit))? = null,
    leadingIcon: (@Composable (() -> Unit))? = null,
    focusRequester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Done,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    showVisibilityToggle: Boolean = true,
    onUnsupportedCharacterAttempt: (() -> Unit)? = null,
    saveTextState: Boolean = true
) {
    val fieldModifier = if (focusRequester != null) {
        modifier.focusRequester(focusRequester)
    } else {
        modifier
    }

    OutlinedTextField(
        saveTextState = saveTextState,
        value = value,
        onValueChange = { raw ->
            val transformed = MasterPasswordPolicy.transformInput(raw)
            onValueChange(transformed.sanitized)
            if (transformed.filteredUnsupportedCharacters) {
                onUnsupportedCharacterAttempt?.invoke()
            }
        },
        modifier = fieldModifier,
        label = label,
        placeholder = placeholder,
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText,
        leadingIcon = leadingIcon,
        shape = RoundedCornerShape(28.dp),
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = if (showVisibilityToggle) {
            {
                IconButton(onClick = { onVisibilityChange(!visible) }) {
                    Icon(
                        imageVector = if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (visible) {
                            stringResource(R.string.hide_password)
                        } else {
                            stringResource(R.string.show_password)
                        }
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions.Default.copy(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = imeAction
        ),
        keyboardActions = keyboardActions
    )
}
