package takagi.ru.monica.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

/** Format an edit and map both selection and IME composition through the inserted separator. */
internal fun formatExpiryEdit(input: TextFieldValue, maxYearDigits: Int = 4): TextFieldValue {
    val digits = input.text.filter { it in '0'..'9' }.take(2 + maxYearDigits)
    val formatted = if (digits.length > 2) digits.take(2) + "/" + digits.drop(2) else digits
    fun offset(position: Int): Int {
        val prefix = input.text.take(position)
        val count = prefix.count { it in '0'..'9' }.coerceAtMost(digits.length)
        // A cursor immediately before an existing slash stays before it. When typing
        // the first year digit, the newly inserted slash advances the cursor as well.
        val afterSlash = count > 2 || (count == 2 && '/' in prefix)
        return count + if (digits.length > 2 && afterSlash) 1 else 0
    }
    fun range(value: TextRange) = TextRange(offset(value.start), offset(value.end))
    return input.copy(
        text = formatted,
        selection = range(input.selection),
        composition = input.composition?.let(::range)?.takeUnless { it.collapsed },
    )
}

/** Keeps edit positions local while callers retain their existing stored expiry format. */
@Composable
fun PaymentExpiryTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxYearDigits: Int = 4,
    entryContentStyle: Boolean = false,
    saveTextState: Boolean = true,
) {
    var editValue by if (saveTextState) rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    } else remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    // Loading/replacing an entry must not normalize its stored value or reuse a
    // selection from the previous entry. User edits already match the parent's echo.
    val fieldValue = if (editValue.text == value) editValue
        else TextFieldValue(value, TextRange(value.length))
    SideEffect { if (editValue != fieldValue) editValue = fieldValue }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { incoming ->
            val edited = if (incoming.text == fieldValue.text) incoming
                else formatExpiryEdit(incoming, maxYearDigits)
            editValue = edited
            if (edited.text != value) onValueChange(edited.text)
        },
        label = { Text(stringResource(R.string.field_expiry)) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        entryContentStyle = entryContentStyle,
    )
}
