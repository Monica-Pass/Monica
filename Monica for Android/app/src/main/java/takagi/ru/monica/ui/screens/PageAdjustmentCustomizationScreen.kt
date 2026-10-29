@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.ui.components.CustomizationPage
import takagi.ru.monica.ui.components.CustomizationPreview
import takagi.ru.monica.ui.components.CustomizationSection
import takagi.ru.monica.ui.components.CustomizationToggle
import takagi.ru.monica.ui.components.CustomizationChoice
import takagi.ru.monica.ui.components.CustomizationDisclosure
import takagi.ru.monica.ui.components.CustomizationOrderGroup
import takagi.ru.monica.R
import takagi.ru.monica.data.AddButtonBehaviorMode
import takagi.ru.monica.data.AddButtonMenuAction
import takagi.ru.monica.data.AuthenticatorCardDisplayField
import takagi.ru.monica.data.AuthenticatorLayoutMode
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordCardDisplayField
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.ProgressBarStyle
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.UnifiedProgressBarMode
import takagi.ru.monica.data.UnmatchedIconHandlingStrategy
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.ui.components.SettingsSubpageHeading
import takagi.ru.monica.ui.components.SettingsSubpageRow
import takagi.ru.monica.ui.components.SettingsSubpageTopBar
import takagi.ru.monica.ui.components.TotpCodeCard
import takagi.ru.monica.ui.password.PasswordEntryCard
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageAdjustmentCustomizationScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPasswordListCustomization: () -> Unit,
    onNavigateToPasswordCardAdjustment: () -> Unit,
    onNavigateToAuthenticatorCardAdjustment: () -> Unit,
    onNavigateToPasswordFieldCustomization: () -> Unit,
    onNavigateToIconSettings: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            SettingsSubpageTopBar(stringResource(R.string.page_adjust_custom_title), onNavigateBack)
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.page_adjust_custom_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            SettingsSubpageHeading(stringResource(R.string.startup_behavior_title))
            SettingsSubpageRow(
                title = stringResource(R.string.auto_focus_search_on_startup_title),
                subtitle = stringResource(R.string.auto_focus_search_on_startup_desc),
                icon = Icons.Default.Search, index = 0, count = 1,
                checked = settings.autoFocusSearchOnStartup,
                onCheckedChange = viewModel::updateAutoFocusSearchOnStartup,
                modifier = Modifier.testTag("auto_focus_search_on_startup_setting")
            )
            SettingsSubpageHeading(stringResource(R.string.customization_group_library))
            SettingsSubpageRow(
                title = stringResource(R.string.vault_v2_hierarchical_layout_title),
                subtitle = stringResource(R.string.vault_v2_hierarchical_layout_desc),
                icon = Icons.Default.Folder, index = 0, count = 3,
                checked = settings.vaultV2LayoutMode == takagi.ru.monica.data.VaultV2LayoutMode.HIERARCHICAL,
                onCheckedChange = { enabled ->
                    viewModel.updateVaultV2LayoutMode(
                        if (enabled) takagi.ru.monica.data.VaultV2LayoutMode.HIERARCHICAL
                        else takagi.ru.monica.data.VaultV2LayoutMode.CLASSIC
                    )
                }
            )
            SettingsSubpageRow(
                title = stringResource(R.string.vault_overview_enabled_title),
                subtitle = stringResource(R.string.vault_overview_enabled_desc),
                icon = Icons.Default.GridView, index = 1, count = 3,
                checked = settings.vaultOverviewEnabled,
                onCheckedChange = viewModel::updateVaultOverviewEnabled
            )
            SettingsSubpageRow(
                title = stringResource(R.string.password_list_customization_title),
                subtitle = stringResource(R.string.password_list_customization_subtitle),
                icon = Icons.Default.FilterList, index = 2, count = 3,
                onClick = onNavigateToPasswordListCustomization
            )
            SettingsSubpageHeading(stringResource(R.string.customization_group_cards))
            SettingsSubpageRow(
                title = stringResource(R.string.password_card_adjust_title),
                subtitle = stringResource(R.string.password_card_adjust_subtitle),
                icon = Icons.Default.Apps, index = 0, count = 3,
                onClick = onNavigateToPasswordCardAdjustment
            )
            SettingsSubpageRow(
                title = stringResource(R.string.authenticator_card_adjust_title),
                subtitle = stringResource(R.string.authenticator_card_adjust_subtitle),
                icon = Icons.Default.Security, index = 1, count = 3,
                onClick = onNavigateToAuthenticatorCardAdjustment
            )
            SettingsSubpageRow(
                title = stringResource(R.string.wallet_stack_loop_title),
                subtitle = stringResource(R.string.wallet_stack_loop_desc),
                icon = Icons.Default.CreditCard, index = 2, count = 3,
                checked = settings.walletStackLoopEnabled,
                onCheckedChange = viewModel::updateWalletStackLoopEnabled,
                modifier = Modifier.testTag("wallet_stack_loop_setting")
            )
            SettingsSubpageHeading(stringResource(R.string.customization_group_fields_icons))
            SettingsSubpageRow(
                title = stringResource(R.string.password_field_customization_title),
                subtitle = stringResource(R.string.extensions_password_field_customization_desc),
                icon = Icons.Default.Tune, index = 0, count = 2,
                onClick = onNavigateToPasswordFieldCustomization
            )
            SettingsSubpageRow(
                title = stringResource(R.string.icon_settings_title),
                subtitle = stringResource(R.string.icon_settings_subtitle),
                icon = androidx.compose.material.icons.Icons.Default.Palette, index = 1, count = 2,
                onClick = onNavigateToIconSettings
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddButtonCustomizationScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val supportedActions = remember {
        listOf(
            AddButtonMenuAction.PASSWORD,
            AddButtonMenuAction.NOTE,
            AddButtonMenuAction.AUTHENTICATOR,
            AddButtonMenuAction.BANK_CARD
        )
    }
    val enabledActions = remember(settings.addButtonMenuEnabledActions, settings.addButtonMenuOrder) {
        mutableStateListOf<AddButtonMenuAction>().apply {
            addAll(
                AddButtonMenuAction.normalizeEnabledActions(
                    settings.addButtonMenuEnabledActions,
                    settings.addButtonMenuOrder
                ).filter { supportedActions.contains(it) }
            )
        }
    }
    var actionOrder by remember(settings.addButtonMenuOrder) {
        mutableStateOf(
            buildList {
                settings.addButtonMenuOrder
                    .filter { supportedActions.contains(it) }
                    .forEach { add(it) }
                supportedActions
                    .filter { !contains(it) }
                    .forEach { add(it) }
            }
        )
    }
    val previewActions = remember(actionOrder, enabledActions) {
        actionOrder.filter { enabledActions.contains(it) }
    }

    LaunchedEffect(settings.addButtonMenuEnabledActions, settings.addButtonMenuOrder) {
        val normalized = AddButtonMenuAction.normalizeEnabledActions(
            settings.addButtonMenuEnabledActions,
            settings.addButtonMenuOrder
        )
        if (normalized != settings.addButtonMenuEnabledActions) {
            viewModel.updateAddButtonMenuEnabledActions(normalized)
        }
    }


    CustomizationPage(stringResource(R.string.add_button_customization_title), onNavigateBack) {
        CustomizationPreview(stringResource(R.string.add_button_preview_title)) {
            Text(stringResource(if (settings.addButtonBehaviorMode == AddButtonBehaviorMode.DIRECT_PASSWORD)
                R.string.add_button_mode_direct_desc else R.string.add_button_mode_expand_desc))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (settings.addButtonBehaviorMode == AddButtonBehaviorMode.DIRECT_PASSWORD)
                    listOf(AddButtonMenuAction.PASSWORD) else previewActions).forEach { action ->
                    SuggestionChip(onClick = {}, label = { Text(stringResource(action.toLabelRes())) },
                        icon = { Icon(action.toIcon(), null, Modifier.size(18.dp)) })
                }
            }
        }
        CustomizationSection(stringResource(R.string.add_button_mode_title), stringResource(R.string.add_button_mode_subtitle)) {
            listOf(AddButtonBehaviorMode.DIRECT_PASSWORD, AddButtonBehaviorMode.EXPANDABLE_MENU).forEachIndexed { index, mode ->
                CustomizationChoice(stringResource(if (index == 0) R.string.add_button_mode_direct else R.string.add_button_mode_expand),
                    selected = settings.addButtonBehaviorMode == mode, onClick = { viewModel.updateAddButtonBehaviorMode(mode) }, index = index, count = 2)
            }
        }
        if (settings.addButtonBehaviorMode == AddButtonBehaviorMode.EXPANDABLE_MENU) {
            CustomizationSection(stringResource(R.string.add_button_actions_title), stringResource(R.string.add_button_actions_desc)) {
                CustomizationOrderGroup(actionOrder, enabledActions.toList(), label = { stringResource(it.toLabelRes()) }, icon = { it.toIcon() },
                    onOrder = { actionOrder = it; viewModel.updateAddButtonMenuOrder(it) },
                    onToggle = { action, checked ->
                        val values = actionOrder.filter { if (it == action) checked else it in enabledActions }
                        enabledActions.clear(); enabledActions.addAll(values)
                        viewModel.updateAddButtonMenuEnabledActions(values)
                    }, required = { it == AddButtonMenuAction.PASSWORD })
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordListCustomizationScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val supportedContentTypes = remember {
        listOf(
            PasswordPageContentType.PASSWORD,
            PasswordPageContentType.CARD_WALLET,
            PasswordPageContentType.NOTE,
            PasswordPageContentType.AUTHENTICATOR,
            PasswordPageContentType.PASSKEY
        )
    }
    val selectedContentTypes = remember(settings.passwordPageVisibleContentTypes) {
        mutableStateListOf<PasswordPageContentType>().apply {
            addAll(
                PasswordPageContentType.normalizeEnabledTypes(
                    settings.passwordPageVisibleContentTypes
                ).filter { supportedContentTypes.contains(it) }
            )
        }
    }
    LaunchedEffect(settings.passwordPageVisibleContentTypes) {
        val normalized = PasswordPageContentType.normalizeEnabledTypes(
            settings.passwordPageVisibleContentTypes
        ).filter { supportedContentTypes.contains(it) }
        if (normalized != settings.passwordPageVisibleContentTypes) {
            viewModel.updatePasswordPageVisibleContentTypes(normalized)
        }
        selectedContentTypes.clear()
        selectedContentTypes.addAll(normalized)
    }
    var contentTypeOrder by remember(settings.passwordPageVisibleContentTypes) {
        mutableStateOf(
            buildList {
                PasswordPageContentType.normalizeEnabledTypes(settings.passwordPageVisibleContentTypes)
                    .filter { supportedContentTypes.contains(it) }
                    .forEach { add(it) }
                supportedContentTypes
                    .filter { !contains(it) }
                    .forEach { add(it) }
            }
        )
    }
    val allAddButtonActions = remember {
        AddButtonMenuAction.values().toList()
    }

    fun syncAddButtonMenuFromContent(
        order: List<PasswordPageContentType>,
        enabledTypes: List<PasswordPageContentType>
    ) {
        val mappedOrder = order
            .mapNotNull { it.toAddButtonMenuActionOrNull() }
            .distinct()
        val targetOrder = buildList {
            addAll(mappedOrder)
            allAddButtonActions.forEach { action ->
                if (action !in this) {
                    add(action)
                }
            }
        }

        val mappedEnabled = enabledTypes
            .mapNotNull { it.toAddButtonMenuActionOrNull() }
            .distinct()

        val normalizedCurrentOrder = AddButtonMenuAction.sanitizeOrder(settings.addButtonMenuOrder)
        if (targetOrder != normalizedCurrentOrder) {
            viewModel.updateAddButtonMenuOrder(targetOrder)
        }

        val normalizedCurrentEnabled = AddButtonMenuAction.normalizeEnabledActions(
            settings.addButtonMenuEnabledActions,
            settings.addButtonMenuOrder
        )
        if (mappedEnabled != normalizedCurrentEnabled) {
            viewModel.updateAddButtonMenuEnabledActions(mappedEnabled)
        }
    }

    LaunchedEffect(
        settings.passwordPageVisibleContentTypes,
        settings.addButtonMenuOrder,
        settings.addButtonMenuEnabledActions
    ) {
        val normalizedTypes = PasswordPageContentType.normalizeEnabledTypes(
            settings.passwordPageVisibleContentTypes
        ).filter { supportedContentTypes.contains(it) }
        syncAddButtonMenuFromContent(
            order = normalizedTypes,
            enabledTypes = normalizedTypes
        )
    }

    LaunchedEffect(settings.passwordPageAggregateEnabled, settings.addButtonBehaviorMode) {
        val expectedMode = if (settings.passwordPageAggregateEnabled) {
            AddButtonBehaviorMode.EXPANDABLE_MENU
        } else {
            AddButtonBehaviorMode.DIRECT_PASSWORD
        }
        if (settings.addButtonBehaviorMode != expectedMode) {
            viewModel.updateAddButtonBehaviorMode(expectedMode)
        }
    }

    var previewAggregateEnabled by remember(settings.passwordPageAggregateEnabled) {
        mutableStateOf(settings.passwordPageAggregateEnabled)
    }
    var previewQuickFiltersEnabled by remember(settings.passwordListQuickFiltersEnabled) {
        mutableStateOf(settings.passwordListQuickFiltersEnabled)
    }
    var previewCategoryQuickFiltersEnabled by remember(settings.passwordListCategoryQuickFiltersEnabled) {
        mutableStateOf(settings.passwordListCategoryQuickFiltersEnabled)
    }
    var previewQuickFolderPathBannerEnabled by remember(settings.passwordListQuickFolderPathBannerEnabled) {
        mutableStateOf(settings.passwordListQuickFolderPathBannerEnabled)
    }
    var previewSystemBackToParentFolderEnabled by remember(settings.passwordListSystemBackToParentFolderEnabled) {
        mutableStateOf(settings.passwordListSystemBackToParentFolderEnabled)
    }
    var previewQuickAccessEnabled by remember(settings.passwordListQuickAccessEnabled) {
        mutableStateOf(settings.passwordListQuickAccessEnabled)
    }

    CustomizationPage(stringResource(R.string.password_list_customization_title), onNavigateBack) {
        CustomizationPreview(stringResource(R.string.customization_live_preview)) {
            if (previewQuickFolderPathBannerEnabled) Text(stringResource(R.string.password_list_quick_folder_back) + " / Monica", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (previewQuickFiltersEnabled) {
                    SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.password_list_quick_filter_favorite)) })
                    SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.password_list_quick_filter_2fa)) })
                }
                if (previewCategoryQuickFiltersEnabled) SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.page_adjustment_preview_games)) })
                if (previewAggregateEnabled) selectedContentTypes.filter { it != PasswordPageContentType.PASSWORD }.forEach { type ->
                    SuggestionChip(onClick = {}, label = { Text(stringResource(type.toLabelRes())) })
                }
            }
            PasswordEntryCard(entry = PasswordEntry(title = "GitHub", username = "monica", password = "", website = "github.com"),
                onClick = {}, isSingleCard = true, enableSharedBounds = false)
            if (previewQuickAccessEnabled) Text(stringResource(R.string.password_list_quick_access_switch_title), style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.padding(top = 24.dp)) {
            CustomizationToggle(stringResource(R.string.password_page_aggregate_switch_title), stringResource(R.string.password_page_aggregate_switch_desc), previewAggregateEnabled,
                { checked ->
                    previewAggregateEnabled = checked
                    viewModel.updatePasswordPageAggregateEnabled(checked)
                    viewModel.updateAddButtonBehaviorMode(if (checked) AddButtonBehaviorMode.EXPANDABLE_MENU else AddButtonBehaviorMode.DIRECT_PASSWORD)
                }, icon = Icons.Default.Dashboard)
        }
        if (previewAggregateEnabled) {
            CustomizationSection(stringResource(R.string.password_page_aggregate_content_title), stringResource(R.string.password_page_aggregate_content_desc)) {
                CustomizationOrderGroup(contentTypeOrder, selectedContentTypes.toList(), label = { stringResource(it.toLabelRes()) }, icon = { it.toIcon() },
                    onOrder = { order ->
                        contentTypeOrder = order
                        val values = order.filter { it in selectedContentTypes }
                        selectedContentTypes.clear(); selectedContentTypes.addAll(values)
                        viewModel.updatePasswordPageVisibleContentTypes(values)
                        syncAddButtonMenuFromContent(order, values)
                    }, onToggle = { type, checked ->
                        val values = contentTypeOrder.filter { if (it == type) checked else it in selectedContentTypes }
                        selectedContentTypes.clear(); selectedContentTypes.addAll(values)
                        viewModel.updatePasswordPageVisibleContentTypes(values)
                        syncAddButtonMenuFromContent(contentTypeOrder, values)
                    }, required = { it == PasswordPageContentType.PASSWORD })
            }
        }
        CustomizationSection(stringResource(R.string.customization_filters_navigation)) {
            CustomizationToggle(stringResource(R.string.password_list_quick_filters_switch_title), stringResource(R.string.password_list_quick_filters_switch_desc), previewQuickFiltersEnabled, { checked -> previewQuickFiltersEnabled = checked; viewModel.updatePasswordListQuickFiltersEnabled(checked) }, index = 0, count = 5)
            CustomizationToggle(stringResource(R.string.password_list_category_quick_filters_switch_title), stringResource(R.string.password_list_category_quick_filters_switch_desc), previewCategoryQuickFiltersEnabled, { checked -> previewCategoryQuickFiltersEnabled = checked; viewModel.updatePasswordListCategoryQuickFiltersEnabled(checked) }, index = 1, count = 5)
            CustomizationToggle(stringResource(R.string.password_list_quick_folder_path_banner_switch_title), stringResource(R.string.password_list_quick_folder_path_banner_switch_desc), previewQuickFolderPathBannerEnabled, { checked -> previewQuickFolderPathBannerEnabled = checked; viewModel.updatePasswordListQuickFolderPathBannerEnabled(checked) }, index = 2, count = 5)
            CustomizationToggle(stringResource(R.string.password_list_system_back_to_parent_folder_switch_title), stringResource(R.string.password_list_system_back_to_parent_folder_switch_desc), previewSystemBackToParentFolderEnabled, { checked -> previewSystemBackToParentFolderEnabled = checked; viewModel.updatePasswordListSystemBackToParentFolderEnabled(checked) }, index = 3, count = 5)
            CustomizationToggle(stringResource(R.string.password_list_quick_access_switch_title), stringResource(R.string.password_list_quick_access_switch_desc), previewQuickAccessEnabled, { checked -> previewQuickAccessEnabled = checked; viewModel.updatePasswordListQuickAccessEnabled(checked) }, index = 4, count = 5)
        }
    }
}


private data class GroupModeOption(
    val mode: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)


private fun PasswordPageContentType.toLabelRes(): Int = when (this) {
    PasswordPageContentType.PASSWORD -> R.string.nav_passwords
    PasswordPageContentType.CARD_WALLET -> R.string.nav_card_wallet
    PasswordPageContentType.NOTE -> R.string.nav_notes
    PasswordPageContentType.AUTHENTICATOR -> R.string.nav_authenticator
    PasswordPageContentType.PASSKEY -> R.string.nav_passkey
    PasswordPageContentType.API_KEY -> R.string.content_block_api_key
    PasswordPageContentType.API_TOKEN -> R.string.entry_type_api_token
    PasswordPageContentType.GPG_KEY -> R.string.content_block_gpg
}

private fun PasswordPageContentType.toIcon(): ImageVector = when (this) {
    PasswordPageContentType.PASSWORD -> Icons.Default.Lock
    PasswordPageContentType.CARD_WALLET -> Icons.Default.CreditCard
    PasswordPageContentType.NOTE -> Icons.Default.Description
    PasswordPageContentType.AUTHENTICATOR -> Icons.Default.Security
    PasswordPageContentType.PASSKEY -> Icons.Default.VpnKey
    PasswordPageContentType.API_KEY, PasswordPageContentType.API_TOKEN, PasswordPageContentType.GPG_KEY -> Icons.Default.VpnKey
}

private fun PasswordPageContentType.toAddButtonMenuActionOrNull(): AddButtonMenuAction? = when (this) {
    PasswordPageContentType.PASSWORD -> AddButtonMenuAction.PASSWORD
    PasswordPageContentType.CARD_WALLET -> AddButtonMenuAction.BANK_CARD
    PasswordPageContentType.NOTE -> AddButtonMenuAction.NOTE
    PasswordPageContentType.AUTHENTICATOR -> AddButtonMenuAction.AUTHENTICATOR
    PasswordPageContentType.PASSKEY -> null
    PasswordPageContentType.API_KEY, PasswordPageContentType.API_TOKEN, PasswordPageContentType.GPG_KEY -> null
}

private fun AddButtonMenuAction.toLabelRes(): Int = when (this) {
    AddButtonMenuAction.PASSWORD -> R.string.item_type_password
    AddButtonMenuAction.NOTE -> R.string.v2_create_note
    AddButtonMenuAction.AUTHENTICATOR -> R.string.item_type_authenticator
    AddButtonMenuAction.BANK_CARD -> R.string.add_button_action_card
}

private fun AddButtonMenuAction.toIcon(): ImageVector = when (this) {
    AddButtonMenuAction.PASSWORD -> Icons.Default.Lock
    AddButtonMenuAction.NOTE -> Icons.Default.Description
    AddButtonMenuAction.AUTHENTICATOR -> Icons.Default.Security
    AddButtonMenuAction.BANK_CARD -> Icons.Default.CreditCard
}

private data class DisplayFieldOption(
    val field: PasswordCardDisplayField,
    val title: String,
    val icon: ImageVector
)

private data class AuthenticatorDisplayFieldOption(
    val field: AuthenticatorCardDisplayField,
    val title: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordCardAdjustmentScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    var groupModeExpanded by rememberSaveable { mutableStateOf(false) }
    val supportedDisplayFields = remember {
        setOf(
            PasswordCardDisplayField.USERNAME,
            PasswordCardDisplayField.WEBSITE
        )
    }
    val selectedFields = remember(settings.passwordCardDisplayFields) {
        mutableStateListOf<PasswordCardDisplayField>().apply {
            addAll(
                settings.passwordCardDisplayFields
                    .filter { supportedDisplayFields.contains(it) }
                    .distinct()
            )
        }
    }
    LaunchedEffect(settings.passwordCardDisplayFields) {
        val normalized = settings.passwordCardDisplayFields
            .filter { supportedDisplayFields.contains(it) }
            .distinct()
        if (normalized != settings.passwordCardDisplayFields) {
            viewModel.updatePasswordCardDisplayFields(normalized)
        }
    }

    val availableFields = listOf(
        DisplayFieldOption(PasswordCardDisplayField.USERNAME, stringResource(R.string.username), Icons.Default.Person),
        DisplayFieldOption(PasswordCardDisplayField.WEBSITE, stringResource(R.string.website), Icons.Default.Language)
    )
    var fieldOrder by remember(settings.passwordCardDisplayFields) {
        mutableStateOf(
            buildList {
                settings.passwordCardDisplayFields
                    .filter { supportedDisplayFields.contains(it) }
                    .forEach { add(it) }
                supportedDisplayFields
                    .filter { !contains(it) }
                    .forEach { add(it) }
            }
        )
    }

    val previewEntry = remember {
        PasswordEntry(
            title = "GitHub - Monica-all",
            website = "github.com",
            username = "joyins",
            password = "******",
            appName = "GitHub",
            authenticatorKey = "JBSWY3DPEHPK3PXP"
        )
    }

    val groupOptions = listOf(
        GroupModeOption("smart", stringResource(R.string.group_mode_smart), stringResource(R.string.group_mode_smart_desc), Icons.Default.Apps),
        GroupModeOption("note", stringResource(R.string.group_mode_note), stringResource(R.string.group_mode_note_desc), Icons.Default.Description),
        GroupModeOption("website", stringResource(R.string.group_mode_website), stringResource(R.string.group_mode_website_desc), Icons.Default.Language),
        GroupModeOption("app", stringResource(R.string.group_mode_app), stringResource(R.string.group_mode_app_desc), Icons.Default.Apps),
        GroupModeOption("title", stringResource(R.string.group_mode_title), stringResource(R.string.group_mode_title_desc), Icons.Default.Person),
        GroupModeOption("folder", stringResource(R.string.group_mode_folder), stringResource(R.string.group_mode_folder_desc), Icons.Default.Folder)
    )
    val selectedGroupOption = remember(settings.passwordGroupMode, groupOptions) {
        groupOptions.firstOrNull { it.mode == settings.passwordGroupMode } ?: groupOptions.first()
    }
    val websiteStackMatchMode = remember(settings.passwordWebsiteStackMatchMode) {
        when (settings.passwordWebsiteStackMatchMode.lowercase()) {
            "relaxed" -> "relaxed"
            else -> "strict"
        }
    }


    CustomizationPage(stringResource(R.string.password_card_adjust_title), onNavigateBack) {
        CustomizationPreview(stringResource(R.string.password_card_preview_title)) {
            PasswordEntryCard(entry = previewEntry, onClick = {}, isSingleCard = true,
                iconCardsEnabled = settings.iconCardsEnabled && settings.passwordPageIconEnabled,
                unmatchedIconHandlingStrategy = settings.unmatchedIconHandlingStrategy,
                passwordCardDisplayMode = settings.passwordCardDisplayMode,
                passwordCardDisplayFields = selectedFields.toList(), showAuthenticator = settings.passwordCardShowAuthenticator,
                hideOtherContentWhenAuthenticator = settings.passwordCardHideOtherContentWhenAuthenticator,
                totpTimeOffsetSeconds = settings.totpTimeOffset, smoothAuthenticatorProgress = settings.validatorSmoothProgress,
                enableSharedBounds = false)
        }
        CustomizationSection(stringResource(R.string.password_card_show_authenticator_title)) {
            CustomizationToggle(stringResource(R.string.password_card_show_authenticator_switch_label),
                stringResource(R.string.password_card_show_authenticator_desc), settings.passwordCardShowAuthenticator,
                viewModel::updatePasswordCardShowAuthenticator, 0, 2, Icons.Default.Security)
            CustomizationToggle(stringResource(R.string.password_card_hide_other_content_when_authenticator_title),
                stringResource(R.string.password_card_hide_other_content_when_authenticator_desc), settings.passwordCardHideOtherContentWhenAuthenticator,
                viewModel::updatePasswordCardHideOtherContentWhenAuthenticator, 1, 2, Icons.Default.Description,
                enabled = settings.passwordCardShowAuthenticator)
        }
        CustomizationSection(stringResource(R.string.password_card_display_mode_title), stringResource(R.string.password_card_display_field_desc)) {
            CustomizationOrderGroup(fieldOrder, selectedFields.toList(),
                label = { field -> availableFields.first { it.field == field }.title },
                icon = { field -> availableFields.first { it.field == field }.icon },
                onOrder = { order ->
                    fieldOrder = order
                    viewModel.updatePasswordCardDisplayFields(order.filter { it in selectedFields })
                }, onToggle = { field, checked ->
                    viewModel.updatePasswordCardDisplayFields(fieldOrder.filter { if (it == field) checked else it in selectedFields })
                })
        }
        CustomizationSection(stringResource(R.string.stack_mode_menu_title)) {
            listOf(StackCardMode.AUTO, StackCardMode.ALWAYS_EXPANDED).forEachIndexed { index, mode ->
                CustomizationChoice(stringResource(if (mode == StackCardMode.AUTO) R.string.stack_mode_auto else R.string.stack_mode_expand),
                    selected = settings.stackCardMode == mode.name, onClick = { viewModel.updateStackCardMode(mode.name) }, index = index, count = 2)
            }
        }
        CustomizationDisclosure(stringResource(R.string.group_mode_menu_title), selectedGroupOption.title,
            groupModeExpanded, { groupModeExpanded = !groupModeExpanded }) {
            groupOptions.forEachIndexed { index, option ->
                CustomizationChoice(option.title, option.description, settings.passwordGroupMode == option.mode,
                    { viewModel.updatePasswordGroupMode(option.mode) }, index, groupOptions.size)
            }
        }
        CustomizationSection(stringResource(R.string.website_stack_match_mode_title), stringResource(R.string.website_stack_match_mode_desc)) {
            listOf("strict", "relaxed").forEachIndexed { index, mode ->
                CustomizationChoice(stringResource(if (mode == "strict") R.string.website_stack_match_mode_strict else R.string.website_stack_match_mode_relaxed),
                    selected = websiteStackMatchMode == mode, onClick = { viewModel.updatePasswordWebsiteStackMatchMode(mode) }, index = index, count = 2)
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatorCardAdjustmentScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val supportedDisplayFields = remember {
        setOf(
            AuthenticatorCardDisplayField.ISSUER,
            AuthenticatorCardDisplayField.ACCOUNT_NAME
        )
    }
    val selectedFields = remember(settings.authenticatorCardDisplayFields) {
        mutableStateListOf<AuthenticatorCardDisplayField>().apply {
            addAll(
                settings.authenticatorCardDisplayFields
                    .filter { supportedDisplayFields.contains(it) }
                    .distinct()
            )
        }
    }
    LaunchedEffect(settings.authenticatorCardDisplayFields) {
        val normalized = settings.authenticatorCardDisplayFields
            .filter { supportedDisplayFields.contains(it) }
            .distinct()
        if (normalized != settings.authenticatorCardDisplayFields) {
            viewModel.updateAuthenticatorCardDisplayFields(normalized)
        }
    }

    val availableFields = listOf(
        AuthenticatorDisplayFieldOption(
            AuthenticatorCardDisplayField.ISSUER,
            stringResource(R.string.issuer),
            Icons.Default.Security
        ),
        AuthenticatorDisplayFieldOption(
            AuthenticatorCardDisplayField.ACCOUNT_NAME,
            stringResource(R.string.account_name),
            Icons.Default.Person
        )
    )
    var fieldOrder by remember(settings.authenticatorCardDisplayFields) {
        mutableStateOf(
            buildList {
                settings.authenticatorCardDisplayFields
                    .filter { supportedDisplayFields.contains(it) }
                    .forEach { add(it) }
                supportedDisplayFields
                    .filter { !contains(it) }
                    .forEach { add(it) }
            }
        )
    }
    var showProgressStyleDialog by rememberSaveable { mutableStateOf(false) }

    val previewItem = remember {
        SecureItem(
            itemType = ItemType.TOTP,
            title = "GitHub",
            itemData = Json.encodeToString(
                TotpData(
                    secret = "JBSWY3DPEHPK3PXP",
                    issuer = "GitHub",
                    accountName = "joyins@example.com",
                    link = "github.com"
                )
            )
        )
    }


    CustomizationPage(stringResource(R.string.authenticator_card_adjust_title), onNavigateBack) {
        CustomizationPreview(stringResource(R.string.authenticator_card_preview_title)) {
            TotpCodeCard(item = previewItem, onCopyCode = {}, compactTile = settings.authenticatorLayoutMode == AuthenticatorLayoutMode.TILE,
                appSettings = settings.copy(authenticatorCardDisplayFields = selectedFields.toList(),
                    iconCardsEnabled = settings.iconCardsEnabled && settings.authenticatorPageIconEnabled))
        }
        CustomizationSection(stringResource(R.string.authenticator_layout_title), stringResource(R.string.authenticator_layout_description)) {
            listOf(AuthenticatorLayoutMode.STANDARD, AuthenticatorLayoutMode.TILE).forEachIndexed { index, mode ->
                CustomizationChoice(stringResource(if (mode == AuthenticatorLayoutMode.STANDARD) R.string.authenticator_layout_standard else R.string.authenticator_layout_tile),
                    selected = settings.authenticatorLayoutMode == mode, onClick = { viewModel.updateAuthenticatorLayoutMode(mode) }, index = index, count = 2)
            }
        }
        CustomizationSection(stringResource(R.string.authenticator_card_display_content_title), stringResource(R.string.authenticator_card_display_field_desc)) {
            CustomizationOrderGroup(fieldOrder, selectedFields.toList(),
                label = { field -> availableFields.first { it.field == field }.title },
                icon = { field -> availableFields.first { it.field == field }.icon },
                onOrder = { order -> fieldOrder = order; viewModel.updateAuthenticatorCardDisplayFields(order.filter { it in selectedFields }) },
                onToggle = { field, checked -> viewModel.updateAuthenticatorCardDisplayFields(fieldOrder.filter { if (it == field) checked else it in selectedFields }) })
        }
        CustomizationSection(stringResource(R.string.validator_settings_section)) {
            CustomizationToggle(stringResource(R.string.authenticator_card_hide_code_title), stringResource(R.string.authenticator_card_hide_code_description),
                settings.authenticatorCardHideCodeByDefault, viewModel::updateAuthenticatorCardHideCodeByDefault, 0, 3, Icons.Default.VisibilityOff)
            CustomizationToggle(stringResource(R.string.unified_progress_bar_title), stringResource(R.string.unified_progress_bar_description),
                settings.validatorUnifiedProgressBar == UnifiedProgressBarMode.ENABLED,
                { viewModel.updateValidatorUnifiedProgressBar(if (it) UnifiedProgressBarMode.ENABLED else UnifiedProgressBarMode.DISABLED) }, 1, 3, Icons.Default.LinearScale)
            CustomizationToggle(stringResource(R.string.smooth_progress_bar_title), stringResource(R.string.smooth_progress_bar_description),
                settings.validatorSmoothProgress, viewModel::updateValidatorSmoothProgress, 2, 3, Icons.Default.Speed)
        }
        CustomizationSection(stringResource(R.string.validator_progress_bar_style)) {
            ProgressBarStyle.entries.forEachIndexed { index, style ->
                CustomizationChoice(validatorProgressBarStyleDisplayName(style), selected = settings.validatorProgressBarStyle == style,
                    onClick = { viewModel.updateValidatorProgressBarStyle(style) }, index = index, count = ProgressBarStyle.entries.size)
            }
        }
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CustomizationToggle(stringResource(R.string.haptic_feedback), stringResource(R.string.haptic_feedback_description),
                settings.hapticFeedbackEnabled, viewModel::updateHapticFeedbackEnabled, 0, if (settings.isPlusActivated) 3 else 1, Icons.Default.Vibration)
            if (settings.isPlusActivated) {
                CustomizationToggle(stringResource(R.string.validator_vibration), stringResource(R.string.validator_vibration_description),
                    settings.validatorVibrationEnabled, viewModel::updateValidatorVibrationEnabled, 1, 3, Icons.Default.Vibration,
                    enabled = settings.hapticFeedbackEnabled)
                CustomizationToggle(stringResource(R.string.copy_next_code_when_expiring), stringResource(R.string.copy_next_code_when_expiring_description),
                    settings.copyNextCodeWhenExpiring, viewModel::updateCopyNextCodeWhenExpiring, 2, 3, Icons.Default.Update)
            }
        }
    }

}

@Composable
private fun validatorProgressBarStyleDisplayName(style: ProgressBarStyle): String {
    return when (style) {
        ProgressBarStyle.LINEAR -> stringResource(R.string.progress_bar_style_linear)
        ProgressBarStyle.WAVE -> stringResource(R.string.progress_bar_style_wave)
    }
}


private data class IconSettingOption(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    var showImageSources by rememberSaveable { mutableStateOf(false) }
    if (showImageSources) takagi.ru.monica.ui.images.ImageSubscriptionBrowser(onDismiss = { showImageSources = false })

    val unmatchedStrategyOptions = listOf(
        UnmatchedIconHandlingStrategy.DEFAULT_ICON to stringResource(R.string.icon_settings_unmatched_strategy_default),
        UnmatchedIconHandlingStrategy.WEBSITE_OR_TITLE_INITIAL to stringResource(R.string.icon_settings_unmatched_strategy_initial),
        UnmatchedIconHandlingStrategy.HIDE to stringResource(R.string.icon_settings_unmatched_strategy_hide)
    )
    val selectedStrategyLabel = unmatchedStrategyOptions
        .firstOrNull { it.first == settings.unmatchedIconHandlingStrategy }
        ?.second
        ?: unmatchedStrategyOptions.first().second

    val options = listOf(
        IconSettingOption(
            title = stringResource(R.string.icon_settings_password_page_title),
            subtitle = stringResource(R.string.icon_settings_password_page_subtitle),
            icon = Icons.Default.Key,
            checked = settings.passwordPageIconEnabled,
            onCheckedChange = viewModel::updatePasswordPageIconEnabled
        ),
        IconSettingOption(
            title = stringResource(R.string.icon_settings_authenticator_page_title),
            subtitle = stringResource(R.string.icon_settings_authenticator_page_subtitle),
            icon = Icons.Default.Security,
            checked = settings.authenticatorPageIconEnabled,
            onCheckedChange = viewModel::updateAuthenticatorPageIconEnabled
        ),
        IconSettingOption(
            title = stringResource(R.string.icon_settings_passkey_page_title),
            subtitle = stringResource(R.string.icon_settings_passkey_page_subtitle),
            icon = Icons.Default.VpnKey,
            checked = settings.passkeyPageIconEnabled,
            onCheckedChange = viewModel::updatePasskeyPageIconEnabled
        )
    )


    var sourceExpanded by rememberSaveable { mutableStateOf(false) }
    CustomizationPage(stringResource(R.string.icon_settings_title), onNavigateBack) {
        AppLauncherIconSettings(settings.appLauncherIcon, viewModel::updateAppLauncherIcon)
        CustomizationSection(stringResource(R.string.image_sources_title)) {
            Surface(onClick = { showImageSources = true }, shape = settingsSectionItemShape(0, 1), color = MaterialTheme.colorScheme.surfaceContainer) {
                androidx.compose.material3.ListItem(headlineContent = { Text(stringResource(R.string.image_sources_title)) },
                    supportingContent = { Text(stringResource(R.string.image_sources_settings_hint)) },
                    leadingContent = { Icon(Icons.Default.Image, null) },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
            }
        }
        CustomizationSection(stringResource(R.string.icon_settings_master_title), stringResource(R.string.icon_settings_master_desc)) {
            CustomizationToggle(stringResource(R.string.icon_settings_master_switch), "", settings.iconCardsEnabled, viewModel::updateIconCardsEnabled,
                icon = Icons.Default.Image)
        }
        CustomizationSection(stringResource(R.string.icon_settings_page_switches_title), stringResource(R.string.icon_settings_page_switches_desc)) {
            options.forEachIndexed { index, option ->
                CustomizationToggle(option.title, option.subtitle, option.checked, option.onCheckedChange,
                    index, options.size, option.icon, enabled = settings.iconCardsEnabled)
            }
        }
        CustomizationSection(stringResource(R.string.icon_settings_unmatched_strategy_title)) {
            unmatchedStrategyOptions.forEachIndexed { index, (strategy, label) ->
                CustomizationChoice(label, selected = settings.unmatchedIconHandlingStrategy == strategy,
                    onClick = { viewModel.updateUnmatchedIconHandlingStrategy(strategy) }, index = index, count = unmatchedStrategyOptions.size)
            }
        }
        CustomizationDisclosure(stringResource(R.string.icon_settings_source_title), stringResource(R.string.icon_settings_source_desc),
            sourceExpanded, { sourceExpanded = !sourceExpanded }) {
            Surface(shape = settingsSectionItemShape(1, 2), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.icon_settings_priority_title), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.settingsSearchAnchor(stringResource(R.string.icon_settings_priority_title)))
                    Text(stringResource(R.string.icon_settings_priority_desc))
                    Text(stringResource(R.string.icon_settings_priority_unified))
                    Text(stringResource(R.string.icon_settings_source_line_1))
                    Text(stringResource(R.string.icon_settings_source_line_2))
                    Text(stringResource(R.string.icon_settings_source_line_3))
                }
            }
        }
    }

}
