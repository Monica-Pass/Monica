package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

/** UI shared with the wallet; callers keep their existing expiry representation and save pipeline. */
@Composable
fun EntryPaymentFields(
    number: String, holder: String, expiry: String, cvv: String,
    onNumber: (String) -> Unit, onHolder: (String) -> Unit,
    onExpiry: (String) -> Unit, onCvv: (String) -> Unit,
    onPickHolder: (() -> Unit)? = null,
    saveTextState: Boolean = true,
) {
    val fields = arrayOf<@Composable () -> Unit>(
        { PaymentField(number, { onNumber(EntryPaymentFormat.cardNumber(it)) }, R.string.field_card_number, "number", true, true, saveTextState = saveTextState) },
        { PaymentField(holder, onHolder, R.string.cardholder_name, "holder", false, false, onPickHolder, saveTextState) },
        { PaymentExpiryTextField(expiry, onExpiry,
            modifier = Modifier.fillMaxWidth().testTag("entry_payment_expiry"), entryContentStyle = true, saveTextState = saveTextState) },
        { PaymentField(cvv, { onCvv(EntryPaymentFormat.cvv(it)) }, R.string.cvv, "cvv", true, true) },
    )
    if (LocalStandaloneTemplateFields.current || LocalTemplateFieldShape.current || LocalConnectedFieldEdges.current != null) {
        ConnectedFieldGroup(*fields)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { fields.forEach { it() } }
    }
}

@Composable
private fun PaymentField(value: String, onChange: (String) -> Unit, label: Int, tag: String,
    secret: Boolean, numeric: Boolean, onPick: (() -> Unit)? = null, saveTextState: Boolean = true) {
    if (tag == "holder") {
        SuggestedOutlinedTextField(value, onChange, takagi.ru.monica.data.CommonSuggestionField.FULL_NAME,
            label = { Text(stringResource(label)) }, singleLine = true, entryContentStyle = true, saveTextState = saveTextState,
            trailingIcon = if (onPick != null) {{ IconButton(onClick = onPick) {
                Icon(Icons.Default.PersonAdd, stringResource(R.string.common_name_fill_title))
            } }} else null,
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("entry_payment_$tag"))
        return
    }
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) }, singleLine = true, entryContentStyle = true, saveTextState = saveTextState,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {{
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    stringResource(if (visible) R.string.hide_password else R.string.show_password))
            }
        }} else if (onPick != null) {{
            IconButton(onClick = onPick) {
                Icon(Icons.Default.PersonAdd, stringResource(R.string.common_name_fill_title))
            }
        }} else null,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("entry_payment_$tag"))
}
