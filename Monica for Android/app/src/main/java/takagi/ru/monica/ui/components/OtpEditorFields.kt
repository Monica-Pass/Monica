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
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.util.OtpParametersDraft

fun otpTypeLabel(type: OtpType): Int = when (type) {
    OtpType.TOTP -> R.string.otp_type_totp
    OtpType.HOTP -> R.string.otp_type_hotp
    OtpType.STEAM -> R.string.otp_type_steam
    OtpType.YANDEX -> R.string.otp_type_yandex
    OtpType.MOTP -> R.string.otp_type_motp
}

private fun otpTypeDescription(type: OtpType): Int = when (type) {
    OtpType.TOTP -> R.string.otp_type_description_totp
    OtpType.HOTP -> R.string.otp_type_description_hotp
    OtpType.STEAM -> R.string.otp_type_description_steam
    OtpType.YANDEX -> R.string.otp_type_description_yandex
    OtpType.MOTP -> R.string.otp_type_description_motp
}

/** Both password and standalone editors use this same set of types. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpTypeSelector(type: OtpType, onChange: (OtpType) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = stringResource(otpTypeLabel(type)), onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.otp_type)) },
            leadingIcon = { Icon(Icons.Default.Shield, null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag("otp_type_selector"),
            shape = RoundedCornerShape(12.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OtpType.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(otpTypeLabel(option)))
                            Text(stringResource(otpTypeDescription(option)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    modifier = Modifier.testTag("otp_type_${option.name}"),
                    onClick = { onChange(option); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpParameterFields(
    type: OtpType, draft: OtpParametersDraft, onChange: (OtpParametersDraft) -> Unit,
    includeRequired: Boolean = true, includeAdvanced: Boolean = true,
    compact: Boolean = false,
) {
    val fields = buildList<@Composable () -> Unit> {
        if (includeRequired && type == OtpType.HOTP) {
            add {
                OutlinedTextField(
                    value = draft.counter, onValueChange = { onChange(draft.copy(counter = it.filter { c -> c in '0'..'9' })) },
                    label = { Text(stringResource(R.string.initial_counter)) },
                    supportingText = if (!compact || draft.counter.toLongOrNull()?.let { it >= 0 } != true)
                        ({ Text(stringResource(R.string.hotp_counter_hint)) }) else null,
                    isError = draft.counter.toLongOrNull()?.let { it >= 0 } != true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("otp_counter"),
                )
            }
        }
        if (includeRequired && type in listOf(OtpType.MOTP, OtpType.YANDEX)) {
            add {
                var visible by remember(type) { mutableStateOf(false) }
                OutlinedTextField(
                    value = draft.pin, onValueChange = { value ->
                        if (type != OtpType.MOTP || (value.length <= 4 && value.all { it in '0'..'9' })) {
                            onChange(draft.copy(pin = value))
                        }
                    },
                    label = { Text(stringResource(R.string.pin_code)) },
                    supportingText = if (type == OtpType.MOTP && (!compact || draft.pin.length != 4 || draft.pin.any { it !in '0'..'9' }))
                        ({ Text(stringResource(R.string.motp_pin_hint)) }) else null,
                    isError = type == OtpType.MOTP && (draft.pin.length != 4 || draft.pin.any { it !in '0'..'9' }),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(if (visible) R.string.hide_password else R.string.show_password))
                    } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("otp_pin"),
                )
            }
        }
        if (includeAdvanced) {
            val fixed = type == OtpType.STEAM || type == OtpType.MOTP
            if (type != OtpType.HOTP) {
                add {
                    OutlinedTextField(
                        value = draft.period, onValueChange = { onChange(draft.copy(period = it.filter { c -> c in '0'..'9' })) },
                        label = { Text(stringResource(R.string.time_period_seconds)) },
                        enabled = !fixed, isError = !fixed && draft.period.toIntOrNull()?.let { it > 0 } != true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("otp_period"),
                    )
                }
            }
            add {
                OutlinedTextField(
                    value = draft.digits, onValueChange = { onChange(draft.copy(digits = it.filter { c -> c in '0'..'9' })) },
                    label = { Text(stringResource(R.string.code_digits)) }, enabled = !fixed,
                    isError = !fixed && draft.digits.toIntOrNull() !in 4..10,
                    supportingText = if (!compact || (!fixed && draft.digits.toIntOrNull() !in 4..10))
                        ({ Text(stringResource(if (type == OtpType.STEAM) R.string.steam_uses_5_chars else R.string.usually_6_digits)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("otp_digits"),
                )
            }
            if (!fixed) {
                add {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = draft.algorithm, onValueChange = {}, readOnly = true,
                            label = { Text(stringResource(R.string.otp_algorithm)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag("otp_algorithm"),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("SHA1", "SHA256", "SHA512").forEach { algorithm ->
                                DropdownMenuItem(text = { Text(algorithm) }, onClick = {
                                    onChange(draft.copy(algorithm = algorithm)); expanded = false
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    if (LocalStandaloneTemplateFields.current || LocalTemplateFieldShape.current || LocalConnectedFieldEdges.current != null) {
        ConnectedFieldGroup(*fields.toTypedArray())
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 12.dp)) { fields.forEach { it() } }
    }
}
