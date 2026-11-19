package takagi.ru.monica.ui

import takagi.ru.monica.ui.common.layout.AdaptiveListDetailLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import takagi.ru.monica.R
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.AddEditNoteScreen
import takagi.ru.monica.ui.screens.NoteListScreen
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel

@Composable
internal fun NotePane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    noteViewModel: NoteViewModel,
    settingsViewModel: SettingsViewModel,
    securityManager: SecurityManager,
    passwordViewModel: takagi.ru.monica.viewmodel.PasswordViewModel,
    bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel,
    onNavigateToAddNote: (Long?) -> Unit,
    onNavigateToSearchedNote: (Long, String) -> Unit = { noteId, _ -> onNavigateToAddNote(noteId) },
    onSelectionModeChange: (Boolean) -> Unit,
    onBitwardenScopeChanged: (Long?) -> Unit,
    isAddingNoteInline: Boolean,
    inlineNoteEditorId: Long?,
    onInlineNoteEditorBack: () -> Unit,
    initialCategoryId: Long? = null,
    initialStorageExplicit: Boolean = false,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialMdbxDatabaseId: Long? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {}
) {
    val hasAdaptiveDetail = isAddingNoteInline || inlineNoteEditorId != null
    BackHandler(enabled = isCompactWidth && hasAdaptiveDetail) { onInlineNoteEditorBack() }
    AdaptiveListDetailLayout(
        compact = isCompactWidth,
        listWidth = wideListPaneWidth,
        hasDetail = hasAdaptiveDetail,
        list = { NoteListScreen(
                    viewModel = noteViewModel,
                    settingsViewModel = settingsViewModel,
                    onNavigateToAddNote = onNavigateToAddNote,
                    onNavigateToSearchedNote = onNavigateToSearchedNote,
                    securityManager = securityManager,
                    passwordViewModel = passwordViewModel,
                    bitwardenViewModel = bitwardenViewModel,
                    onSelectionModeChange = onSelectionModeChange,
                    onBitwardenScopeChanged = onBitwardenScopeChanged,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                ) },
        detail = { NoteDetailPaneContent(
                    noteViewModel = noteViewModel,
                    isAddingNoteInline = isAddingNoteInline,
                    inlineNoteEditorId = inlineNoteEditorId,
                    onInlineNoteEditorBack = onInlineNoteEditorBack,
                    initialCategoryId = initialCategoryId,
                    initialStorageExplicit = initialStorageExplicit,
                    initialKeePassDatabaseId = initialKeePassDatabaseId,
                    initialKeePassGroupPath = initialKeePassGroupPath,
                    initialMdbxDatabaseId = initialMdbxDatabaseId,
                    initialBitwardenVaultId = initialBitwardenVaultId,
                    initialBitwardenFolderId = initialBitwardenFolderId
                ) }
    )
}

@Composable
internal fun NoteDetailPaneContent(
    noteViewModel: NoteViewModel,
    isAddingNoteInline: Boolean,
    inlineNoteEditorId: Long?,
    onInlineNoteEditorBack: () -> Unit,
    initialCategoryId: Long? = null,
    initialStorageExplicit: Boolean = false,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialMdbxDatabaseId: Long? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null
) {
    if (isAddingNoteInline || inlineNoteEditorId != null) {
        AddEditNoteScreen(
            noteId = inlineNoteEditorId ?: -1L,
            initialCategoryId = initialCategoryId,
            initialStorageExplicit = initialStorageExplicit,
            initialKeePassDatabaseId = initialKeePassDatabaseId,
            initialKeePassGroupPath = initialKeePassGroupPath,
            initialMdbxDatabaseId = initialMdbxDatabaseId,
            initialBitwardenVaultId = initialBitwardenVaultId,
            initialBitwardenFolderId = initialBitwardenFolderId,
            onNavigateBack = onInlineNoteEditorBack,
            viewModel = noteViewModel
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.select_note_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
