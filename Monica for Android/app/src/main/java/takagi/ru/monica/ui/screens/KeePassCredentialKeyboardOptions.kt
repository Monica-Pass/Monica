package takagi.ru.monica.ui.screens

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

internal fun keepassCredentialKeyboardOptions(
    imeAction: ImeAction = ImeAction.Default
): KeyboardOptions = KeyboardOptions.Default.copy(
    keyboardType = KeyboardType.Text,
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    imeAction = imeAction
)
