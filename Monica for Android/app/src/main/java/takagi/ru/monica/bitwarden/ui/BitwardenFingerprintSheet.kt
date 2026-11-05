package takagi.ru.monica.bitwarden.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitwardenFingerprintSheet(phrase: String?, error: String?, onDismiss: () -> Unit, onCopy: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(bottom = 24.dp).testTag("bitwarden_fingerprint_sheet"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Outlined.Fingerprint, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.bitwarden_account_fingerprint), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.bitwarden_account_fingerprint_hint), style = MaterialTheme.typography.bodyMedium)
            when {
                phrase != null -> Surface(shape = bitwardenGroupShape(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    // Display-only break opportunities keep words readable. Copy always
                    // uses the canonical protocol phrase, without these invisible hints.
                    Text(phrase.replace("-", "-\u200B"), modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("bitwarden_fingerprint_phrase"),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            lineBreak = LineBreak.Heading, localeList = LocaleList("en")))
                }
                error != null -> Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("bitwarden_fingerprint_error"))
                else -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("bitwarden_fingerprint_loading"))
            }
            Button(onClick = onCopy, enabled = phrase != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("bitwarden_fingerprint_copy")) {
                Icon(Icons.Outlined.ContentCopy, null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.bitwarden_account_fingerprint_copy))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.close)) }
        }
    }
}
