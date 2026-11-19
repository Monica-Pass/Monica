package takagi.ru.monica.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.AutofillPreferences.AutofillDefaultSourceFilter
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.components.SettingsPanelGroup
import takagi.ru.monica.ui.components.SettingsPanelRow

private enum class AutofillSourcePicker { SOURCE, KEEPASS, BITWARDEN, MDBX }

@Composable
internal fun AutofillSourceSettings(
    source: AutofillDefaultSourceFilter,
    keepassId: Long?,
    bitwardenId: Long?,
    keepassDatabases: List<LocalKeePassDatabase>?,
    bitwardenVaults: List<BitwardenVault>?,
    onSourceSelected: (AutofillDefaultSourceFilter) -> Unit,
    onKeepassSelected: (Long?) -> Unit,
    onBitwardenSelected: (Long?) -> Unit,
    mdbxId: Long? = null,
    mdbxDatabases: List<LocalMdbxDatabase>? = emptyList(),
    onMdbxSelected: (Long?) -> Unit = {},
    defaultsReady: Boolean = true,
) {
    var picker by remember { mutableStateOf<AutofillSourcePicker?>(null) }
    val allDatabases = stringResource(R.string.password_picker_all_databases)
    val allVaults = stringResource(R.string.password_picker_all_vaults)
    val unavailable = stringResource(R.string.autofill_selected_source_unavailable)
    val loading = stringResource(R.string.loading)
    val keepassLabel = when {
        keepassDatabases == null -> loading
        keepassId == null -> allDatabases
        else -> keepassDatabases.firstOrNull { it.id == keepassId }?.name ?: unavailable
    }
    val bitwardenLabel = when {
        bitwardenVaults == null -> loading
        bitwardenId == null -> allVaults
        else -> bitwardenVaults.firstOrNull { it.id == bitwardenId }?.displayLabel() ?: unavailable
    }
    val mdbxLabel = when {
        mdbxDatabases == null -> loading
        mdbxId == null -> allDatabases
        else -> mdbxDatabases.firstOrNull { it.id == mdbxId }?.name ?: unavailable
    }
    SettingsPanelGroup("") {
            SettingsPanelRow(Icons.Outlined.AccountTree,
                Setting.AUTOFILL_V2_DEFAULT_SCOPE_TITLE,
                source.label() + "\n" + stringResource(R.string.autofill_v2_default_scope_desc),
                enabled = defaultsReady, onClick = { picker = AutofillSourcePicker.SOURCE },
                modifier = Modifier.testTag("autofill-source-setting"))
        if (source == AutofillDefaultSourceFilter.KEEPASS) {
                SettingsPanelRow(Icons.Outlined.Storage,
                    Setting.AUTOFILL_V2_DEFAULT_KEEPASS_TITLE,
                    keepassLabel + "\n" + stringResource(R.string.autofill_v2_default_keepass_desc),
                    enabled = defaultsReady && keepassDatabases != null, onClick = { picker = AutofillSourcePicker.KEEPASS },
                    modifier = Modifier.testTag("autofill-keepass-setting"))
        }
        if (source == AutofillDefaultSourceFilter.BITWARDEN) {
                SettingsPanelRow(Icons.Outlined.Storage,
                    Setting.AUTOFILL_V2_DEFAULT_BITWARDEN_TITLE,
                    bitwardenLabel + "\n" + stringResource(R.string.autofill_v2_default_bitwarden_desc),
                    enabled = defaultsReady && bitwardenVaults != null, onClick = { picker = AutofillSourcePicker.BITWARDEN },
                    modifier = Modifier.testTag("autofill-bitwarden-setting"))
        }
        if (source == AutofillDefaultSourceFilter.MDBX) {
            SettingsPanelRow(Icons.Outlined.Storage,
                Setting.AUTOFILL_V2_DEFAULT_MDBX_TITLE,
                mdbxLabel + "\n" + stringResource(R.string.autofill_v2_default_mdbx_desc),
                enabled = defaultsReady && mdbxDatabases != null, onClick = { picker = AutofillSourcePicker.MDBX },
                modifier = Modifier.testTag("autofill-mdbx-setting"))
        }
    }
    when (picker) {
        AutofillSourcePicker.SOURCE -> AutofillSettingsChoiceSheet(
            stringResource(R.string.autofill_v2_default_scope_title), stringResource(R.string.autofill_v2_default_scope_desc),
            AutofillDefaultSourceFilter.entries.map { AutofillSettingsChoice(it.name, it.label()) }, source.name,
            onSelect = { key -> onSourceSelected(AutofillDefaultSourceFilter.valueOf(key)); picker = null },
            onDismiss = { picker = null })
        AutofillSourcePicker.KEEPASS -> AutofillSettingsChoiceSheet(
            stringResource(R.string.autofill_v2_default_keepass_title), stringResource(R.string.autofill_v2_default_keepass_desc),
            listOf(AutofillSettingsChoice("all", allDatabases)) + keepassDatabases.orEmpty().map { AutofillSettingsChoice(it.id.toString(), it.name) },
            keepassId?.toString() ?: "all",
            onSelect = { key -> onKeepassSelected(if (key == "all") null else key.toLong()); picker = null },
            onDismiss = { picker = null })
        AutofillSourcePicker.BITWARDEN -> AutofillSettingsChoiceSheet(
            stringResource(R.string.autofill_v2_default_bitwarden_title), stringResource(R.string.autofill_v2_default_bitwarden_desc),
            listOf(AutofillSettingsChoice("all", allVaults)) + bitwardenVaults.orEmpty().map { AutofillSettingsChoice(it.id.toString(), it.displayLabel()) },
            bitwardenId?.toString() ?: "all",
            onSelect = { key -> onBitwardenSelected(if (key == "all") null else key.toLong()); picker = null },
            onDismiss = { picker = null })
        AutofillSourcePicker.MDBX -> AutofillSettingsChoiceSheet(
            stringResource(R.string.autofill_v2_default_mdbx_title), stringResource(R.string.autofill_v2_default_mdbx_desc),
            listOf(AutofillSettingsChoice("all", allDatabases)) + mdbxDatabases.orEmpty().map { AutofillSettingsChoice(it.id.toString(), it.name) },
            mdbxId?.toString() ?: "all",
            onSelect = { key -> onMdbxSelected(if (key == "all") null else key.toLong()); picker = null },
            onDismiss = { picker = null })
        null -> Unit
    }
}

@Composable
private fun AutofillDefaultSourceFilter.label(): String = stringResource(when (this) {
    AutofillDefaultSourceFilter.ALL -> R.string.filter_all
    AutofillDefaultSourceFilter.LOCAL -> R.string.filter_monica
    AutofillDefaultSourceFilter.KEEPASS -> R.string.filter_keepass
    AutofillDefaultSourceFilter.BITWARDEN -> R.string.filter_bitwarden
    AutofillDefaultSourceFilter.MDBX -> R.string.filter_mdbx
})

private fun BitwardenVault.displayLabel(): String = displayName?.takeIf { it.isNotBlank() }?.let { "$it ($email)" } ?: email
