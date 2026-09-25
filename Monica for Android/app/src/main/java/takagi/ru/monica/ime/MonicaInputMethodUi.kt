package takagi.ru.monica.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.ui.rememberAppIcon
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.ui.PasswordListInitialLoadingIndicator
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.util.PasswordGenerator

internal data class MonicaImePasswordEntry(
    val id: Long,
    val title: String,
    val username: String,
    val website: String,
    val packageName: String,
    val password: String,
    val isFavorite: Boolean,
    val sourceLabel: String,
    val totpCode: String = "",
    val keepassDatabaseId: Long? = null,
    val mdbxDatabaseId: Long? = null,
    val bitwardenVaultId: Long? = null,
    val appName: String = "",
    val hasTotp: Boolean = false,
    val alphabeticalLetter: String? = null,
)

internal data class MonicaImeAuthenticatorEntry(
    val id: Long,
    val title: String,
    val issuer: String,
    val accountName: String,
    val code: String,
    val remainingSeconds: Int,
    val isFavorite: Boolean,
    val sourceLabel: String,
    val keepassDatabaseId: Long? = null,
    val mdbxDatabaseId: Long? = null,
    val bitwardenVaultId: Long? = null,
    val alphabeticalLetter: String? = null,
)

internal data class MonicaImeCardWalletField(
    val label: String,
    val value: String
)

internal data class MonicaImeCardWalletEntry(
    val id: Long,
    val title: String,
    val subtitle: String,
    val typeLabel: String,
    val isFavorite: Boolean,
    val sourceLabel: String,
    val fields: List<MonicaImeCardWalletField>,
    val keepassDatabaseId: Long? = null,
    val mdbxDatabaseId: Long? = null,
    val bitwardenVaultId: Long? = null,
    val alphabeticalLetter: String? = null,
)

internal data class MonicaImeUiState(
    val unlocked: Boolean = false,
    val activePackageName: String = "",
    val activePanel: MonicaImePanel = MonicaImePanel.KEYBOARD,
    val query: String = "",
    val entries: List<MonicaImePasswordEntry> = emptyList(),
    val authenticatorEntries: List<MonicaImeAuthenticatorEntry> = emptyList(),
    val cardWalletEntries: List<MonicaImeCardWalletEntry> = emptyList(),
    val databaseOptions: List<MonicaImeDatabaseOption> = emptyList(),
    val selectedDatabaseScope: MonicaImeDatabaseScope = MonicaImeDatabaseScope.All,
    val errorMessage: String? = null,
    val keyboardMode: MonicaKeyboardMode = MonicaKeyboardMode.LETTERS,
    val isUppercase: Boolean = false,
    val autoLockMinutes: Int = 5,
    val isAutofillPanelVisible: Boolean = false,
    val isAutofillLoading: Boolean = false,
    val isSearchEditing: Boolean = false,
    val passwordSortMode: MonicaImePasswordSortMode = MonicaImePasswordSortMode.ALPHABETICAL,
    val pendingClearedInput: String? = null,
    val keyboardOptions: ImeKeyboardOptions = ImeKeyboardOptions(),
    val pinDigits: List<String> = StandardImePinDigits,
    val isSensitiveInput: Boolean = false,
)

internal enum class MonicaKeyboardMode {
    LETTERS,
    NUMBERS,
    SYMBOLS
}

internal enum class MonicaImePasswordSortMode {
    RELEVANCE,
    ALPHABETICAL
}

internal enum class MonicaImePanel {
    KEYBOARD,
    PASSWORDS,
    AUTHENTICATORS,
    DOCUMENTS,
    GENERATOR
}

internal fun MonicaImePanel.isVaultContentPanel(): Boolean {
    return this == MonicaImePanel.PASSWORDS ||
        this == MonicaImePanel.AUTHENTICATORS ||
        this == MonicaImePanel.DOCUMENTS
}

internal sealed interface MonicaImeDatabaseScope {
    data object All : MonicaImeDatabaseScope
    data object Local : MonicaImeDatabaseScope
    data class KeePass(val databaseId: Long) : MonicaImeDatabaseScope
    data class Mdbx(val databaseId: Long) : MonicaImeDatabaseScope
    data class Bitwarden(val vaultId: Long) : MonicaImeDatabaseScope
}

internal data class MonicaImeDatabaseOption(
    val scope: MonicaImeDatabaseScope,
    val label: String
)

private enum class MonicaToolbarSelection {
    MONICA,
    PASSWORDS,
    AUTHENTICATORS,
    DOCUMENTS,
    GENERATOR
}

private val MonicaImeContentAreaHeight = 240.dp
private val ImeVaultScrollRailWidth = 40.dp
private val ImeVaultScrollRailGap = 8.dp

@Composable
internal fun MonicaImeContent(
    settings: AppSettings,
    uiState: MonicaImeUiState,
    onDatabaseScopeSelected: (MonicaImeDatabaseScope) -> Unit,
    onInsertPassword: (MonicaImePasswordEntry) -> Unit,
    onInsertUsername: (MonicaImePasswordEntry) -> Unit,
    onInsertWebsite: (MonicaImePasswordEntry) -> Unit,
    onSmartFillPassword: (MonicaImePasswordEntry) -> Unit,
    onInsertAuthenticatorCode: (MonicaImeAuthenticatorEntry) -> Unit,
    onInsertCardWalletValue: (MonicaImeCardWalletField) -> Unit,
    onSmartFillCardWallet: (MonicaImeCardWalletEntry) -> Unit,
    onKeyPressed: (String) -> Unit,
    onBackspace: () -> Unit,
    onDeleteAll: () -> Unit,
    onUndoDeleteAll: () -> Unit,
    onEnter: () -> Unit,
    onSpace: () -> Unit,
    onShiftToggle: () -> Unit,
    onKeyboardModeChange: (MonicaKeyboardMode) -> Unit,
    onOpenUnlockApp: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
    onSearchEditRequested: () -> Unit,
    onSearchEditFinished: () -> Unit,
    onSearchCleared: () -> Unit,
    onPanelSelected: (MonicaImePanel) -> Unit,
    onSwitchInputMethod: () -> Unit,
    onDismiss: () -> Unit,
    onInsertPasswordTotp: ((MonicaImePasswordEntry) -> Unit)? = null,
) {
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val contentAreaHeight = if (androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE) 192.dp else MonicaImeContentAreaHeight
    val activePanelRequiresUnlock = when (uiState.activePanel) {
        MonicaImePanel.PASSWORDS,
        MonicaImePanel.AUTHENTICATORS,
        MonicaImePanel.DOCUMENTS -> true
        MonicaImePanel.KEYBOARD,
        MonicaImePanel.GENERATOR -> false
    }
    val showPanelContent = uiState.activePanel != MonicaImePanel.KEYBOARD &&
        !uiState.isSearchEditing &&
        (!activePanelRequiresUnlock || uiState.unlocked)
    val showUnlockPanel = activePanelRequiresUnlock && !uiState.unlocked

    MonicaTheme(
        darkTheme = darkTheme,
        colorScheme = settings.colorScheme,
        customPrimaryColor = settings.customPrimaryColor,
        customSecondaryColor = settings.customSecondaryColor,
        customTertiaryColor = settings.customTertiaryColor,
        customNeutralColor = settings.customNeutralColor,
        customNeutralVariantColor = settings.customNeutralVariantColor
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    MonicaImeToolbar(
                        modifier = Modifier.zIndex(0f),
                        uiState = uiState,
                        onPanelSelected = onPanelSelected,
                        onUndoDeleteAll = onUndoDeleteAll,
                        onOpenAutofillSettings = onOpenAutofillSettings,
                        onSearchEditFinished = onSearchEditFinished,
                        onSearchCleared = onSearchCleared,
                        onDismiss = onDismiss
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(contentAreaHeight)
                            .zIndex(30f)
                    ) {
                        if (showPanelContent) {
                            when (uiState.activePanel) {
                                MonicaImePanel.PASSWORDS -> {
                                    UnlockedVaultPane(
                                        modifier = Modifier.fillMaxSize(),
                                        uiState = uiState,
                                        onDatabaseScopeSelected = onDatabaseScopeSelected,
                                        onSearchEditRequested = onSearchEditRequested,
                                        onInsertPassword = onInsertPassword,
                                        onInsertUsername = onInsertUsername,
                                        onInsertWebsite = onInsertWebsite,
                                        onInsertTotp = { entry ->
                                            if (onInsertPasswordTotp != null) onInsertPasswordTotp(entry)
                                            else if (entry.totpCode.isNotBlank()) onKeyPressed(entry.totpCode)
                                        },
                                        onSmartFillPassword = onSmartFillPassword
                                    )
                                }
                                MonicaImePanel.AUTHENTICATORS -> {
                                    AuthenticatorPane(
                                        modifier = Modifier.fillMaxSize(),
                                        uiState = uiState,
                                        onDatabaseScopeSelected = onDatabaseScopeSelected,
                                        onSearchEditRequested = onSearchEditRequested,
                                        onInsertCode = onInsertAuthenticatorCode
                                    )
                                }
                                MonicaImePanel.DOCUMENTS -> {
                                    CardWalletPane(
                                        modifier = Modifier.fillMaxSize(),
                                        uiState = uiState,
                                        onDatabaseScopeSelected = onDatabaseScopeSelected,
                                        onSearchEditRequested = onSearchEditRequested,
                                        onInsertField = onInsertCardWalletValue,
                                        onSmartFill = onSmartFillCardWallet
                                    )
                                }
                                MonicaImePanel.GENERATOR -> {
                                    ImeGeneratorPane(
                                        modifier = Modifier.fillMaxSize(),
                                        onInsertPassword = onKeyPressed
                                    )
                                }
                                MonicaImePanel.KEYBOARD -> Unit
                            }
                        }

                        if (showUnlockPanel) {
                            ImeUnlockFloatingPanel(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                errorMessage = uiState.errorMessage,
                                onOpenUnlockApp = onOpenUnlockApp
                            )
                        }

                        if (!showPanelContent && !showUnlockPanel) {
                            MonicaKeyboard(
                                modifier = Modifier.fillMaxSize(),
                                mode = uiState.keyboardMode,
                                isUppercase = uiState.isUppercase,
                                pinDigits = uiState.pinDigits,
                                hideKeyFeedback = (!uiState.isSearchEditing && uiState.isSensitiveInput) ||
                                    (uiState.keyboardMode == MonicaKeyboardMode.NUMBERS && uiState.keyboardOptions.hidePinPreview),
                                onKeyPressed = onKeyPressed,
                                onBackspace = onBackspace,
                                onDeleteAll = onDeleteAll,
                                onEnter = onEnter,
                                onSpace = onSpace,
                                onShiftToggle = onShiftToggle,
                                onKeyboardModeChange = onKeyboardModeChange,
                                onSwitchInputMethod = onSwitchInputMethod
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                }
            }
        }
    }
}

@Composable
private fun MonicaImeToolbar(
    modifier: Modifier = Modifier,
    uiState: MonicaImeUiState,
    onPanelSelected: (MonicaImePanel) -> Unit,
    onUndoDeleteAll: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
    onSearchEditFinished: () -> Unit,
    onSearchCleared: () -> Unit,
    onDismiss: () -> Unit
) {
    if (uiState.isSearchEditing) {
        ImeSearchToolbar(query = uiState.query, resultCount = uiState.activeEntryCount,
            onFinish = onSearchEditFinished, onClear = onSearchCleared)
        return
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        listOf(MonicaImePanel.KEYBOARD, MonicaImePanel.PASSWORDS, MonicaImePanel.AUTHENTICATORS,
            MonicaImePanel.DOCUMENTS, MonicaImePanel.GENERATOR).forEach { panel ->
            val label = stringResource(when (panel) {
                MonicaImePanel.KEYBOARD -> R.string.ime_toolbar_keyboard
                MonicaImePanel.PASSWORDS -> R.string.ime_toolbar_autofill
                MonicaImePanel.AUTHENTICATORS -> R.string.authenticator
                MonicaImePanel.DOCUMENTS -> R.string.nav_card_wallet
                MonicaImePanel.GENERATOR -> R.string.generator
            })
            ImeToolbarItem(label, "ime_toolbar_${panel.name.lowercase()}", uiState.activePanel == panel,
                onClick = { onPanelSelected(panel) }) {
                Icon(when (panel) {
                    MonicaImePanel.KEYBOARD -> Icons.Default.Keyboard
                    MonicaImePanel.PASSWORDS -> Icons.Default.Key
                    MonicaImePanel.AUTHENTICATORS -> Icons.Default.VerifiedUser
                    MonicaImePanel.DOCUMENTS -> Icons.Default.Badge
                    MonicaImePanel.GENERATOR -> Icons.Default.AutoAwesome
                }, null, Modifier.size(22.dp))
            }
        }
        val undo = uiState.pendingClearedInput != null
        ImeToolbarItem(stringResource(if (undo) R.string.ime_clear_all_undo_action else R.string.autofill),
            "ime_toolbar_more", undo, if (undo) onUndoDeleteAll else onOpenAutofillSettings) {
            Icon(if (undo) Icons.AutoMirrored.Filled.Undo else Icons.Default.MoreHoriz, null, Modifier.size(22.dp))
        }
        ImeToolbarItem(stringResource(R.string.ime_hide_keyboard), "ime_toolbar_hide", false, onDismiss) {
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(22.dp))
        }
    }
}

@Composable
private fun RowScope.ImeToolbarItem(label: String, tag: String, selected: Boolean,
    onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.weight(1f).height(48.dp).testTag(tag)
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.size(40.dp), shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}

@Composable
private fun ToolbarCircleButton(selected: Boolean, onClick: () -> Unit,
    contentDescription: String, content: @Composable () -> Unit) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(48.dp)
        .semantics { this.contentDescription = contentDescription }, shape = CircleShape,
        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )) { content() }
}

@Composable
private fun ImeSearchToolbar(
    query: String,
    resultCount: Int,
    onFinish: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ime_search_toolbar")
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarCircleButton(
            selected = false,
            onClick = onFinish,
            contentDescription = stringResource(R.string.back)
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = query.ifBlank { stringResource(R.string.search) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (query.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = resultCount.toString(),
                    modifier = Modifier.testTag("ime_search_result_count"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (query.isNotBlank()) {
            ToolbarCircleButton(
                selected = false,
                onClick = onClear,
                contentDescription = stringResource(R.string.clear)
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
        }

        ToolbarCircleButton(
            selected = true,
            onClick = onFinish,
            contentDescription = stringResource(R.string.confirm)
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
        }
    }
}

@Composable
private fun ImeVaultControls(
    uiState: MonicaImeUiState,
    databaseMenuExpanded: Boolean,
    onDatabaseMenuExpandedChange: (Boolean) -> Unit,
    onSearchEditRequested: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 10.dp,
                end = ImeVaultScrollRailWidth + ImeVaultScrollRailGap,
                top = 4.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (uiState.databaseOptions.isNotEmpty()) {
            ImeDatabaseScopeSelector(
                modifier = Modifier.weight(1f),
                options = uiState.databaseOptions,
                selectedScope = uiState.selectedDatabaseScope,
                expanded = databaseMenuExpanded,
                onExpandedChange = onDatabaseMenuExpandedChange
            )
        }

        Surface(
            onClick = onSearchEditRequested,
            modifier = Modifier
                .weight(1f)
                .testTag("ime_vault_search")
                .height(40.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (uiState.query.isBlank()) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = uiState.query.ifBlank { stringResource(R.string.search) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.query.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (uiState.query.isNotBlank()) {
                    Text(
                        text = uiState.activeEntryCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

    }
}

@Composable
private fun ImeDatabaseScopeSelector(
    modifier: Modifier = Modifier,
    options: List<MonicaImeDatabaseOption>,
    selectedScope: MonicaImeDatabaseScope,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val selected = options.firstOrNull { it.scope == selectedScope } ?: options.first()

    Surface(
        onClick = { onExpandedChange(!expanded) },
        modifier = modifier
            .testTag("ime_vault_database_filter")
            .height(40.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (expanded) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = selected.scope.icon(),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = selected.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = if (expanded) 180f else 0f }
            )
        }
    }
}

@Composable
private fun ImeDatabaseScopeMenu(
    options: List<MonicaImeDatabaseOption>,
    selectedScope: MonicaImeDatabaseScope,
    onDismiss: () -> Unit,
    onSelected: (MonicaImeDatabaseScope) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 58.dp, end = 42.dp, bottom = 10.dp)
                .widthIn(min = 260.dp, max = 360.dp)
                .fillMaxHeight()
                .zIndex(1f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(options) { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = option.scope.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = if (option.scope == selectedScope) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            null
                        },
                        onClick = { onSelected(option.scope) }
                    )
                }
            }
        }
    }
}

private enum class ConnectedToolbarPosition {
    LEADING,
    MIDDLE,
    TRAILING
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.ConnectedToolbarButton(
    selected: Boolean,
    position: ConnectedToolbarPosition,
    contentDescription: String,
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val animatedWeight by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
        label = "toolbarWeight"
    )

    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        modifier = Modifier
            .zIndex(if (selected) 1f else 0f)
            .weight(animatedWeight)
            .height(48.dp)
            .sizeIn(minWidth = 42.dp)
            .semantics { role = Role.RadioButton },
        shapes = when (position) {
            ConnectedToolbarPosition.LEADING -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            ConnectedToolbarPosition.MIDDLE -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            ConnectedToolbarPosition.TRAILING -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        },
        contentPadding = PaddingValues(4.dp)
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ImeGeneratorPane(
    modifier: Modifier = Modifier,
    onInsertPassword: (String) -> Unit
) {
    var length by rememberSaveable { mutableStateOf(16) }
    var generatedPassword by rememberSaveable {
        mutableStateOf(generateImePassword(length))
    }
    var refreshTick by rememberSaveable { mutableStateOf(0) }
    val refreshRotation by animateFloatAsState(
        targetValue = refreshTick * 360f,
        animationSpec = tween(durationMillis = 420),
        label = "imeGeneratorRefreshRotation"
    )

    fun updatePassword(nextLength: Int = length) {
        length = nextLength
        generatedPassword = generateImePassword(nextLength)
        refreshTick += 1
    }

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.generator),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.generator_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledIconButton(
                    onClick = { updatePassword() },
                    modifier = Modifier
                        .size(42.dp)
                        .graphicsLayer { rotationZ = refreshRotation }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.generate_password)
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                AnimatedContent(
                    targetState = generatedPassword,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(160)) + scaleIn(initialScale = 0.96f)) togetherWith
                            (fadeOut(animationSpec = tween(120)) + scaleOut(targetScale = 1.02f)) using
                            SizeTransform(clip = false)
                    },
                    label = "imeGeneratedPassword"
                ) { password ->
                    Text(
                        text = password,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { updatePassword((length - 4).coerceAtLeast(8)) },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text("-")
                }
                Text(
                    text = stringResource(R.string.ime_generator_length_value, length),
                    modifier = Modifier.width(54.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                OutlinedButton(
                    onClick = { updatePassword((length + 4).coerceAtMost(32)) },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text("+")
                }
                OutlinedButton(
                    onClick = { onInsertPassword(generatedPassword) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.use_password),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun generateImePassword(length: Int): String {
    return PasswordGenerator.generatePassword(
        length = length,
        includeUppercase = true,
        includeLowercase = true,
        includeNumbers = true,
        includeSymbols = true,
        excludeSimilar = true,
        uppercaseMin = 1,
        lowercaseMin = 1,
        numbersMin = 1,
        symbolsMin = 1
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImeVaultPane(
    modifier: Modifier = Modifier,
    uiState: MonicaImeUiState,
    onDatabaseScopeSelected: (MonicaImeDatabaseScope) -> Unit,
    onSearchEditRequested: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    var databaseMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.databaseOptions) {
        if (uiState.databaseOptions.isEmpty()) databaseMenuExpanded = false
    }

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("ime_vault_pane"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                uiState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                ImeVaultControls(
                    uiState = uiState,
                    databaseMenuExpanded = databaseMenuExpanded,
                    onDatabaseMenuExpandedChange = { databaseMenuExpanded = it },
                    onSearchEditRequested = onSearchEditRequested
                )
                content()
            }

            if (databaseMenuExpanded && uiState.databaseOptions.isNotEmpty()) {
                ImeDatabaseScopeMenu(
                    options = uiState.databaseOptions,
                    selectedScope = uiState.selectedDatabaseScope,
                    onDismiss = { databaseMenuExpanded = false },
                    onSelected = { scope ->
                        databaseMenuExpanded = false
                        onDatabaseScopeSelected(scope)
                    }
                )
            }
        }
    }
}

@Composable
private fun <T> ImeVaultList(
    entries: List<T>,
    key: (T) -> Any,
    title: (T) -> String,
    alphabeticalLetter: ((T) -> String?)? = null,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit
) {
    val lazyListState = rememberLazyListState()
    val letterIndex = remember(entries) {
        buildImeLetterIndex(itemCount = entries.size,
            cachedLetterAt = alphabeticalLetter?.let { letter -> { index -> letter(entries[index]) } }) { index -> title(entries[index]) }
    }

    Row(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("ime_vault_list"),
            contentPadding = PaddingValues(
                start = 10.dp,
                end = ImeVaultScrollRailGap,
                bottom = 6.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(entries, key = key) { entry -> itemContent(entry) }
        }
        VelocityScrollBar(
            lazyListState = lazyListState,
            letterIndex = letterIndex,
            modifier = Modifier
                .width(ImeVaultScrollRailWidth)
                .fillMaxHeight()
                .testTag("ime_vault_scroll_rail")
                .padding(end = 2.dp)
        )
    }
}

@Composable
private fun UnlockedVaultPane(
    modifier: Modifier = Modifier,
    uiState: MonicaImeUiState,
    onDatabaseScopeSelected: (MonicaImeDatabaseScope) -> Unit,
    onSearchEditRequested: () -> Unit,
    onInsertPassword: (MonicaImePasswordEntry) -> Unit,
    onInsertUsername: (MonicaImePasswordEntry) -> Unit,
    onInsertWebsite: (MonicaImePasswordEntry) -> Unit,
    onInsertTotp: (MonicaImePasswordEntry) -> Unit,
    onSmartFillPassword: (MonicaImePasswordEntry) -> Unit
) {
    val showAutofillLoading = uiState.isAutofillLoading ||
        (uiState.unlocked && uiState.errorMessage == null && uiState.databaseOptions.isEmpty())

    ImeVaultPane(
        modifier = modifier,
        uiState = uiState,
        onDatabaseScopeSelected = onDatabaseScopeSelected,
        onSearchEditRequested = onSearchEditRequested
    ) {
        if (uiState.entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (showAutofillLoading) {
                    AutofillLoadingState()
                } else {
                    EmptyVaultState(query = uiState.query)
                }
            }
        } else {
            ImeVaultList(
                entries = uiState.entries,
                key = { it.id },
                title = ::imePasswordAlphabeticalLabel,
                alphabeticalLetter = { it.alphabeticalLetter },
                modifier = Modifier.weight(1f)
            ) { entry ->
                PasswordEntryCard(
                    entry = entry,
                    onSmartFill = { onSmartFillPassword(entry) },
                    onInsertPassword = { onInsertPassword(entry) },
                    onInsertUsername = { onInsertUsername(entry) },
                    onInsertWebsite = { onInsertWebsite(entry) },
                    onInsertTotp = { onInsertTotp(entry) }
                )
            }
        }
    }
}

@Composable
private fun VelocityScrollBar(
    lazyListState: LazyListState,
    letterIndex: List<Pair<String, Int>>,  // letter -> item index
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    // 当前手指在滚动条上的绝对 Y（用于字母跳转）
    var fingerY by remember { mutableFloatStateOf(0f) }
    var barHeightPx by remember { mutableFloatStateOf(1f) }
    // 当前显示的首字母（拖动时）
    var currentLetter by remember { mutableStateOf("") }

    // 根据手指 Y 位置映射到字母索引
    fun letterAtY(y: Float): Pair<String, Int>? {
        if (letterIndex.isEmpty()) return null
        val ratio = (y / barHeightPx).coerceIn(0f, 1f)
        val idx = (ratio * letterIndex.size).toInt().coerceIn(0, letterIndex.size - 1)
        return letterIndex[idx]
    }

    val trackColor by animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "scrollBarColor"
    )

    Box(
        modifier = modifier
            .onSizeChanged { barHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(letterIndex) {
                detectDragGestures(
                    onDragStart = { offset ->
                        fingerY = offset.y
                        isDragging = true
                        // 立即跳转到对应字母
                        val target = letterAtY(offset.y)
                        if (target != null) {
                            currentLetter = target.first
                            coroutineScope.launch {
                                lazyListState.scrollToItem(target.second)
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        fingerY = change.position.y
                        val target = letterAtY(change.position.y)
                        if (target != null) {
                            if (target.first != currentLetter) {
                                currentLetter = target.first
                                coroutineScope.launch {
                                    lazyListState.scrollToItem(target.second)
                                }
                            }
                        } else {
                            currentLetter = ""
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        currentLetter = ""
                    },
                    onDragCancel = {
                        isDragging = false
                        currentLetter = ""
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (letterIndex.isNotEmpty()) {
            Text(
                text = letterIndex.first().first,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = letterIndex.last().first,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 轨道竖条
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight(0.58f)
                .clip(RoundedCornerShape(2.dp))
                .background(trackColor)
        )

        // 拖动时：在滚动条左侧显示首字母气泡
        if (isDragging && currentLetter.isNotEmpty()) {
            val bubbleOffsetY = (fingerY - barHeightPx / 2f)
                .coerceIn(-barHeightPx / 2f + 16f, barHeightPx / 2f - 16f)
            Box(
                modifier = Modifier
                    .offset(x = (-36).dp, y = with(LocalDensity.current) { bubbleOffsetY.toDp() })
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentLetter,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

internal fun buildImeLetterIndex(
    itemCount: Int,
    itemOffset: Int = 0,
    cachedLetterAt: ((Int) -> String?)? = null,
    titleAt: (Int) -> String
): List<Pair<String, Int>> {
    val result = mutableListOf<Pair<String, Int>>()
    val seenLetters = mutableSetOf<String>()

    repeat(itemCount) { index ->
        val letter = cachedLetterAt?.invoke(index) ?: imeIndexLetter(normalizedImeSortKey(titleAt(index)))

        if (seenLetters.add(letter)) {
            result += letter to (index + itemOffset)
        }
    }

    return result
}

private fun MonicaImeDatabaseScope.icon(): androidx.compose.ui.graphics.vector.ImageVector {
    return when (this) {
        MonicaImeDatabaseScope.All -> Icons.AutoMirrored.Filled.List
        MonicaImeDatabaseScope.Local -> Icons.Default.Smartphone
        is MonicaImeDatabaseScope.KeePass -> Icons.Default.Key
        is MonicaImeDatabaseScope.Mdbx -> Icons.Default.Storage
        is MonicaImeDatabaseScope.Bitwarden -> Icons.Default.CloudSync
    }
}

@Composable
private fun AutofillLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        PasswordListInitialLoadingIndicator()
    }
}

@Composable
private fun EmptyVaultState(query: String) {
    ImeEmptyState(
        icon = Icons.Default.Key,
        title = stringResource(if (query.isBlank()) R.string.ime_empty_title else R.string.ime_no_matches_title),
        message = stringResource(if (query.isBlank()) R.string.ime_empty_message else R.string.ime_no_matches_message)
    )
}

@Composable
private fun AuthenticatorPane(
    modifier: Modifier = Modifier,
    uiState: MonicaImeUiState,
    onDatabaseScopeSelected: (MonicaImeDatabaseScope) -> Unit,
    onSearchEditRequested: () -> Unit,
    onInsertCode: (MonicaImeAuthenticatorEntry) -> Unit
) {
    ImeVaultPane(
        modifier = modifier,
        uiState = uiState,
        onDatabaseScopeSelected = onDatabaseScopeSelected,
        onSearchEditRequested = onSearchEditRequested
    ) {
        if (uiState.isAutofillLoading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                AutofillLoadingState()
            }
        } else if (uiState.authenticatorEntries.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                ImeEmptyState(
                    icon = Icons.Default.VerifiedUser,
                    title = stringResource(
                        if (uiState.query.isBlank()) R.string.ime_empty_authenticator_title
                        else R.string.ime_no_matches_title
                    ),
                    message = stringResource(
                        if (uiState.query.isBlank()) R.string.ime_empty_authenticator_message
                        else R.string.ime_no_matches_message
                    )
                )
            }
        } else {
            ImeVaultList(
                entries = uiState.authenticatorEntries,
                key = { it.id },
                title = ::imeAuthenticatorAlphabeticalLabel,
                alphabeticalLetter = { it.alphabeticalLetter },
                modifier = Modifier.weight(1f)
            ) { entry ->
                AuthenticatorEntryCard(entry = entry, onInsertCode = { onInsertCode(entry) })
            }
        }
    }
}

@Composable
private fun CardWalletPane(
    modifier: Modifier = Modifier,
    uiState: MonicaImeUiState,
    onDatabaseScopeSelected: (MonicaImeDatabaseScope) -> Unit,
    onSearchEditRequested: () -> Unit,
    onInsertField: (MonicaImeCardWalletField) -> Unit,
    onSmartFill: (MonicaImeCardWalletEntry) -> Unit
) {
    ImeVaultPane(
        modifier = modifier,
        uiState = uiState,
        onDatabaseScopeSelected = onDatabaseScopeSelected,
        onSearchEditRequested = onSearchEditRequested
    ) {
        if (uiState.isAutofillLoading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                AutofillLoadingState()
            }
        } else if (uiState.cardWalletEntries.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                ImeEmptyState(
                    icon = Icons.Default.CreditCard,
                    title = stringResource(
                        if (uiState.query.isBlank()) R.string.ime_empty_card_wallet_title
                        else R.string.ime_no_matches_title
                    ),
                    message = stringResource(
                        if (uiState.query.isBlank()) R.string.ime_empty_card_wallet_message
                        else R.string.ime_no_matches_message
                    )
                )
            }
        } else {
            ImeVaultList(
                entries = uiState.cardWalletEntries,
                key = { it.id },
                title = ::imeCardWalletAlphabeticalLabel,
                alphabeticalLetter = { it.alphabeticalLetter },
                modifier = Modifier.weight(1f)
            ) { entry ->
                CardWalletEntryCard(
                    entry = entry,
                    onSmartFill = { onSmartFill(entry) },
                    onInsertField = onInsertField
                )
            }
        }
    }
}

@Composable
private fun ImeEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ImeEntryIcon {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ImeEntryCard(id: Long, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ime_vault_entry_$id")
            .animateContentSize(),
        content = content
    )
}

@Composable
private fun ImeEntryIcon(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) {
            content()
        }
    }
}

@Composable
private fun ImeEntryHeader(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ImeEntryIcon(content = icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing()
    }
}

@Composable
private fun ImeExpandIndicator(expanded: Boolean) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "imeEntryExpansion")
    Icon(
        imageVector = Icons.Default.KeyboardArrowDown,
        contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(32.dp).padding(6.dp).graphicsLayer { rotationZ = rotation }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImeEntryActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun ImeFillAction(label: String, onClick: () -> Unit, icon: @Composable (() -> Unit)? = null) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.widthIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (icon != null) {
            icon()
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AuthenticatorEntryCard(entry: MonicaImeAuthenticatorEntry, onInsertCode: () -> Unit) {
    ImeEntryCard(id = entry.id) {
        ImeEntryHeader(
            title = entry.title,
            subtitle = listOf(entry.issuer, entry.accountName)
                .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { entry.sourceLabel },
            onClick = onInsertCode,
            enabled = entry.code.isNotBlank(),
            icon = { Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(24.dp)) }
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = entry.code,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (entry.remainingSeconds > 0) {
                    Text(
                        text = stringResource(R.string.ime_totp_seconds_remaining, entry.remainingSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardWalletEntryCard(
    entry: MonicaImeCardWalletEntry,
    onSmartFill: () -> Unit,
    onInsertField: (MonicaImeCardWalletField) -> Unit
) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    ImeEntryCard(id = entry.id) {
        ImeEntryHeader(
            title = entry.title,
            subtitle = listOf(entry.typeLabel, entry.subtitle)
                .filter { it.isNotBlank() }.distinct().joinToString(" · "),
            onClick = { expanded = !expanded },
            icon = { Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(24.dp)) }
        ) { ImeExpandIndicator(expanded) }

        if (expanded) {
            ImeEntryActions {
                ImeFillAction(stringResource(R.string.ime_quick_fill), onClick = {
                    expanded = false
                    onSmartFill()
                })
                entry.fields.forEach { field ->
                    ImeFillAction(field.label, onClick = {
                        expanded = false
                        onInsertField(field)
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PasswordEntryCard(
    entry: MonicaImePasswordEntry,
    onSmartFill: () -> Unit,
    onInsertPassword: () -> Unit,
    onInsertUsername: () -> Unit,
    onInsertWebsite: () -> Unit,
    onInsertTotp: () -> Unit
) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val appIcon = entry.packageName.takeIf { it.isNotBlank() }?.let { rememberAppIcon(it) }

    ImeEntryCard(id = entry.id) {
        ImeEntryHeader(
            title = entry.title.ifBlank {
                entry.website.ifBlank { stringResource(R.string.ime_untitled_account) }
            },
            subtitle = entry.username,
            onClick = { expanded = !expanded },
            icon = {
                if (appIcon != null) {
                    Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
        ) { ImeExpandIndicator(expanded) }

        if (expanded) {
            ImeEntryActions {
                ImeFillAction(stringResource(R.string.ime_quick_fill), onSmartFill)
                ImeFillAction(stringResource(R.string.password), onInsertPassword)
                if (entry.username.isNotBlank()) {
                    ImeFillAction(stringResource(R.string.username), onInsertUsername)
                }
                if (entry.website.isNotBlank()) {
                    ImeFillAction(stringResource(R.string.website), onInsertWebsite)
                }
                if (entry.hasTotp || entry.totpCode.isNotBlank()) {
                    ImeFillAction(stringResource(R.string.ime_fill_current_otp), onInsertTotp) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
