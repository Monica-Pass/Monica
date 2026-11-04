package takagi.ru.monica.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.model.EntryContentFields

data class EntrySupplementalSpec(val key: String, @StringRes val label: Int = 0,
    val literal: String = "", val protected: Boolean = false, val legacyOnly: Boolean = false)

object EntrySupplementalSpecs {
    val payment = listOf(
        EntrySupplementalSpec("bankName", R.string.bank_name),
        EntrySupplementalSpec("bankBranch", R.string.bank_branch),
        EntrySupplementalSpec("cardType", R.string.card_type),
        EntrySupplementalSpec("brand", R.string.bank_card_brand_label, legacyOnly = true),
        EntrySupplementalSpec("nickname", R.string.bank_card_nickname_label, legacyOnly = true),
        EntrySupplementalSpec("validFromMonth", R.string.bank_card_valid_from_month),
        EntrySupplementalSpec("validFromYear", R.string.bank_card_valid_from_year),
        EntrySupplementalSpec("pin", R.string.bank_card_pin_label, protected = true),
        EntrySupplementalSpec("iban", literal = "IBAN", protected = true),
        EntrySupplementalSpec("swiftBic", literal = "SWIFT / BIC"),
        EntrySupplementalSpec("routingNumber", R.string.bank_card_routing_number_label),
        EntrySupplementalSpec("accountNumber", R.string.bank_card_account_number_label, protected = true),
        EntrySupplementalSpec("branchCode", R.string.bank_card_branch_code_label),
        EntrySupplementalSpec("currency", R.string.bank_card_currency_label),
        EntrySupplementalSpec("customerServicePhone", R.string.bank_card_customer_service_phone_label),
        EntrySupplementalSpec("billingAddress", R.string.billing_address),
    )
    val contact = listOf(
        EntrySupplementalSpec("fullName", R.string.full_name),
        EntrySupplementalSpec("title", R.string.document_title_prefix_label),
        EntrySupplementalSpec("firstName", R.string.document_first_name_label),
        EntrySupplementalSpec("middleName", R.string.document_middle_name_label),
        EntrySupplementalSpec("lastName", R.string.document_last_name_label),
        EntrySupplementalSpec("company", R.string.document_company_label),
        EntrySupplementalSpec("documentType", R.string.document_type),
        EntrySupplementalSpec("documentNumber", R.string.document_number, protected = true),
        EntrySupplementalSpec("issuedDate", R.string.issued_date),
        EntrySupplementalSpec("expiryDate", R.string.expiry_date),
        EntrySupplementalSpec("issuedBy", R.string.issued_by),
        EntrySupplementalSpec("nationality", R.string.nationality),
        EntrySupplementalSpec("ssn", R.string.document_ssn_label, protected = true),
        EntrySupplementalSpec("passportNumber", R.string.document_passport_number_label, protected = true),
        EntrySupplementalSpec("licenseNumber", R.string.document_license_number_label, protected = true),
        EntrySupplementalSpec("additionalInfo", R.string.document_additional_info_label),
    )
    val address = listOf(
        EntrySupplementalSpec("fullName", R.string.full_name),
        EntrySupplementalSpec("company", R.string.document_company_label),
        EntrySupplementalSpec("apartment", R.string.apartment),
        EntrySupplementalSpec("address3", R.string.document_address_line_3),
        EntrySupplementalSpec("phone", R.string.phone),
        EntrySupplementalSpec("email", R.string.email),
    )
    fun forSection(section: String) = when (section) { "PAYMENT" -> payment; "CONTACT" -> contact; "ADDRESS" -> address; else -> emptyList() }
    fun spec(title: String): EntrySupplementalSpec? = listOf("PAYMENT", "CONTACT", "ADDRESS").firstNotNullOfOrNull { section ->
        forSection(section).firstOrNull { EntryContentFields.key(section, it.key) == title }
    }
}

@Composable
fun EntrySupplementalFields(section: String, fields: List<CustomFieldDraft>, onFields: (List<CustomFieldDraft>) -> Unit) {
    val specs = EntrySupplementalSpecs.forSection(section)
    EntryOptionalFields(specs, specs.associate { it.key to EntryContentFields.value(fields, section, it.key) },
        onValue = { spec, value -> onFields(EntryContentFields.update(fields, section, spec.key, value, spec.protected)) })
}

/** Empty rare fields stay in the picker. Existing legacy values always remain editable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryOptionalFields(specs: List<EntrySupplementalSpec>, values: Map<String, String>,
    onValue: (EntrySupplementalSpec, String) -> Unit) {
    var added by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var picker by remember { mutableStateOf(false) }
    val visible = specs.filter { it.key in added || !values[it.key].isNullOrEmpty() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TemplateFormSection("") {
            visible.forEach { spec ->
                key(spec.key) {
                    var revealed by remember(spec.key) { mutableStateOf(false) }
                    val title = if (spec.label != 0) stringResource(spec.label) else spec.literal
                    val choices = when (spec.key) {
                        "cardType" -> listOf("CREDIT" to R.string.credit_card, "DEBIT" to R.string.debit_card, "PREPAID" to R.string.prepaid_card)
                        "documentType" -> listOf("ID_CARD" to R.string.id_card, "PASSPORT" to R.string.passport,
                            "DRIVER_LICENSE" to R.string.drivers_license, "SOCIAL_SECURITY" to R.string.social_security_card, "OTHER" to R.string.other_document)
                        else -> emptyList()
                    }
                    var choosing by remember { mutableStateOf(false) }
                    if (choices.isNotEmpty()) {
                        OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().testTag("entry_extra_${spec.key}")) {
                            Text(title + ": " + (choices.firstOrNull { it.first == values[spec.key] }?.let { stringResource(it.second) }
                                ?: values[spec.key].orEmpty()))
                        }
                        if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(title) },
                            text = { Column { choices.forEach { (key, label) ->
                                TextButton(onClick = { onValue(spec, key); choosing = false }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(label))
                                }
                            } } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } })
                    } else SuggestedOutlinedTextField(values[spec.key].orEmpty(), { onValue(spec, it) },
                        suggestionField = if (spec.protected) null else takagi.ru.monica.data.CommonSuggestionField.forSupplementalKey(spec.key),
                        label = { Text(title) }, entryContentStyle = true, singleLine = spec.key != "additionalInfo",
                        keyboardOptions = if (spec.protected) androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = if (spec.key == "pin") androidx.compose.ui.text.input.KeyboardType.NumberPassword else androidx.compose.ui.text.input.KeyboardType.Password,
                            autoCorrectEnabled = false) else androidx.compose.foundation.text.KeyboardOptions.Default,
                        visualTransformation = if (spec.protected && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                        trailingIcon = if (spec.protected) {{ IconButton(onClick = { revealed = !revealed },
                            modifier = Modifier.testTag("entry_extra_reveal_${spec.key}")) {
                            Icon(if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(if (revealed) R.string.hide_password else R.string.show_password))
                        } }} else null,
                        modifier = Modifier.fillMaxWidth().testTag("entry_extra_${spec.key}"))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth().testTag("entry_extra_add")) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.keepass_native_add_field))
            }
        }
    }
    if (picker) ModalBottomSheet(onDismissRequest = { picker = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp)) {
            Text(stringResource(R.string.keepass_native_add_field), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(12.dp))
            specs.filter { !it.legacyOnly && it !in visible }.forEach { spec ->
                ListItem(headlineContent = { Text(if (spec.label != 0) stringResource(spec.label) else spec.literal) },
                    modifier = Modifier.testTag("entry_extra_choose_${spec.key}").clickable {
                        added = added + spec.key; picker = false
                    })
            }
        }
    }
}
