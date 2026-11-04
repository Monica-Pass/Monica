package takagi.ru.monica.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R

@Composable
fun importTypeOptions(): List<ImportTypeInfo> {
    return listOf(
        ImportTypeInfo(
            key = "bitwarden_json",
            icon = Icons.Default.Lock,
            title = stringResource(R.string.bw_json_title),
            description = stringResource(R.string.bw_json_description),
            fileHint = stringResource(R.string.bw_json_import_hint)
        ),
        ImportTypeInfo(
            key = "monica_zip",
            icon = Icons.Default.Archive,
            title = stringResource(R.string.import_type_monica_backup_title),
            description = stringResource(R.string.import_type_monica_backup_desc),
            fileHint = stringResource(R.string.import_type_monica_backup_file_hint)
        ),
        ImportTypeInfo(
            key = "kdbx",
            icon = Icons.Default.Key,
            title = stringResource(R.string.import_type_keepass_format_title),
            description = stringResource(R.string.import_type_keepass_format_desc),
            fileHint = stringResource(R.string.import_type_keepass_format_file_hint)
        ),
        ImportTypeInfo(
            key = "csv_group",
            icon = Icons.Default.TableChart,
            title = stringResource(R.string.import_type_csv_data_title),
            description = stringResource(R.string.import_type_csv_data_desc),
            fileHint = stringResource(R.string.import_type_csv_data_file_hint)
        ),
        ImportTypeInfo(
            key = "aegis",
            icon = Icons.Default.Security,
            title = stringResource(R.string.import_type_aegis_title),
            description = stringResource(R.string.import_type_aegis_desc),
            fileHint = stringResource(R.string.import_type_aegis_file_hint)
        ),
        ImportTypeInfo(
            key = "stratum",
            icon = Icons.Default.VerifiedUser,
            title = stringResource(R.string.import_type_stratum_title),
            description = stringResource(R.string.import_type_stratum_desc),
            fileHint = stringResource(R.string.import_type_stratum_file_hint)
        )
    )
}

@Composable
fun csvImportTypeOptions(): List<ImportTypeInfo> {
    return listOf(
        ImportTypeInfo(
            key = "normal",
            icon = Icons.Default.TableChart,
            title = stringResource(R.string.import_type_csv_monica_title),
            description = stringResource(R.string.import_type_csv_monica_desc),
            fileHint = stringResource(R.string.import_type_csv_monica_file_hint)
        ),
        ImportTypeInfo(
            key = "keepass_csv",
            icon = Icons.Default.Description,
            title = stringResource(R.string.import_type_csv_keepass_title),
            description = stringResource(R.string.import_type_csv_keepass_desc),
            fileHint = stringResource(R.string.import_type_csv_keepass_file_hint)
        ),
        ImportTypeInfo(
            key = "bitwarden_csv",
            icon = Icons.Default.Lock,
            title = stringResource(R.string.import_type_csv_bitwarden_title),
            description = stringResource(R.string.import_type_csv_bitwarden_desc),
            fileHint = stringResource(R.string.import_type_csv_bitwarden_file_hint)
        ),
        ImportTypeInfo(
            key = "bitwarden_authenticator_csv",
            icon = Icons.Default.Security,
            title = stringResource(R.string.import_type_csv_bitwarden_auth_title),
            description = stringResource(R.string.import_type_csv_bitwarden_auth_desc),
            fileHint = stringResource(R.string.import_type_csv_bitwarden_auth_file_hint)
        ),
        ImportTypeInfo(
            key = "proton_pass_csv",
            icon = Icons.Default.Security,
            title = stringResource(R.string.import_type_csv_proton_pass_title),
            description = stringResource(R.string.import_type_csv_proton_pass_desc),
            fileHint = stringResource(R.string.import_type_csv_proton_pass_file_hint)
        ),
        ImportTypeInfo(
            key = "chrome_csv",
            icon = Icons.Default.Language,
            title = stringResource(R.string.import_type_csv_chrome_title),
            description = stringResource(R.string.import_type_csv_chrome_desc),
            fileHint = stringResource(R.string.import_type_csv_chrome_file_hint)
        ),
        ImportTypeInfo(
            key = "password_keyboard_csv",
            icon = Icons.Default.Keyboard,
            title = stringResource(R.string.import_type_csv_password_keyboard_title),
            description = stringResource(R.string.import_type_csv_password_keyboard_desc),
            fileHint = stringResource(R.string.import_type_csv_password_keyboard_file_hint)
        )
    )
}
