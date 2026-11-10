package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@Composable
fun WalletSplitNameFields(first: String, middle: String, last: String,
    onFirst: (String) -> Unit, onMiddle: (String) -> Unit, onLast: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WalletNamePartsHelp()
        TemplateFormSection("") {
            ConnectedFieldGroup(
                { WalletProfileField(first, onFirst, R.string.document_first_name_label) },
                { WalletProfileField(middle, onMiddle, R.string.document_middle_name_label) },
                { WalletProfileField(last, onLast, R.string.document_last_name_label) },
            )
        }
    }
}

/** Supporting copy stays outside the field group so all lines keep their padding. */
@Composable
internal fun WalletNamePartsHelp() {
    Text(stringResource(R.string.wallet_name_parts_help), style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("wallet_name_parts_help"),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun WalletContactGroup(email: String, phone: String, onEmail: (String) -> Unit, onPhone: (String) -> Unit) {
    val fields = arrayOf<@Composable () -> Unit>(
        { WalletProfileField(email, onEmail, R.string.email, KeyboardType.Email) },
        { WalletProfileField(phone, onPhone, R.string.phone, KeyboardType.Phone) },
    )
    if (LocalStandaloneTemplateFields.current || LocalTemplateFieldShape.current || LocalConnectedFieldEdges.current != null) {
        ConnectedFieldGroup(*fields)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { fields.forEach { it() } }
    }
}

@Composable
fun WalletProfileField(value: String, onValue: (String) -> Unit, label: Int, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true, shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

@Composable
fun WalletProfileDetails(title: Int, fields: List<Pair<Int, String>>) {
    val visible = fields.filter { it.second.isNotBlank() }
    if (visible.isEmpty()) return
    val context = LocalContext.current
    DetailCardSurface {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            visible.forEach { (label, value) -> InfoFieldWithCopy(label = stringResource(label), value = value, context = context) }
        }
    }
}

@Composable
fun WalletNamePartsDetails(first: String, middle: String, last: String, title: Int = R.string.wallet_name_parts) {
    WalletProfileDetails(title, listOf(R.string.document_first_name_label to first,
        R.string.document_middle_name_label to middle, R.string.document_last_name_label to last))
}

@Composable
fun WalletContactsDetails(homeEmail: String, homePhone: String, workEmail: String, workPhone: String) {
    WalletProfileDetails(R.string.wallet_home_contact, listOf(R.string.email to homeEmail, R.string.phone to homePhone))
    WalletProfileDetails(R.string.wallet_work_contact, listOf(R.string.email to workEmail, R.string.phone to workPhone))
}
