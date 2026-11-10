package takagi.ru.monica.ui.screens

import takagi.ru.monica.notes.domain.NoteContentCodec
import kotlinx.serialization.json.jsonObject
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.ui.AttachmentPendingDraft
import takagi.ru.monica.attachments.ui.AttachmentsEditSection
import takagi.ru.monica.attachments.ui.flushPendingDraftsTo
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.NoteCodeBlockCollapseMode
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.ui.components.CustomFieldEditorSection
import takagi.ru.monica.ui.components.SecondaryPasswordVerificationDialog
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.notes.domain.NoteDraftStore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.ImageImportConfirmDialog
import takagi.ru.monica.ui.components.ImageDialog
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.buildMultiStorageTarget
import takagi.ru.monica.util.ImageManager
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.utils.RememberedStorageTarget
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.NoteEditorViewModel
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.NoteViewModel

private const val ADD_EDIT_NOTE_SCREEN_TAG = "AddEditNoteScreen"

private data class PendingNoteImageImport(
    val bitmap: Bitmap,
    val originalSizeBytes: Long?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditNoteScreen(
    noteId: Long,
    onNavigateBack: () -> Unit,
    initialCategoryId: Long? = null,
    initialStorageExplicit: Boolean = false,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialMdbxDatabaseId: Long? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    viewModel: NoteViewModel = viewModel(),
    editorViewModel: NoteEditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "note-editor-$noteId"
    ),
    embeddedDraft: takagi.ru.monica.data.model.EmbeddedWalletContent.Snapshot? = null,
    embeddedImageLoader: (suspend (String) -> Bitmap?)? = null,
    embeddedAttachmentsContent: (@Composable () -> Unit)? = null,
    onEmbeddedSave: (suspend (takagi.ru.monica.attachments.EmbeddedWalletEditorResult) -> Unit)? = null
) {
    val context = LocalContext.current
    takagi.ru.monica.lansync.DeviceSyncEditorLease()
    val draftStore = remember { NoteDraftStore.init(context) }
    val biometricHelper = remember { BiometricHelper(context) }
    val securityManager = takagi.ru.monica.ui.rememberUiSecurityManager()
    val database = remember { PasswordDatabase.getDatabase(context) }
    val localKeePassViewModel: LocalKeePassViewModel = viewModel {
        LocalKeePassViewModel(
            context.applicationContext as android.app.Application,
            database.localKeePassDatabaseDao(),
            securityManager
        )
    }
    val settingsManager = remember { SettingsManager(context) }
    val appSettings by settingsManager.settingsFlow.collectAsState(
        initial = AppSettings(biometricEnabled = false)
    )
    val editorState by editorViewModel.uiState.collectAsState()

    val noteImageBitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    val pendingAttachmentDrafts = remember { mutableStateListOf<AttachmentPendingDraft>() }

    var showNoteImageDialog by remember { mutableStateOf<String?>(null) } // 存文件名
    var showConfirmDelete by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }

    var showAddImageDialog by remember { mutableStateOf(false) }
    var showRestoreNewNoteDraftDialog by rememberSaveable(noteId) { mutableStateOf(false) }
    var pendingImageInsertionCursor by rememberSaveable { mutableStateOf(-1) }
    var pendingCameraImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingNoteImageImport by remember { mutableStateOf<PendingNoteImageImport?>(null) }
    var isSavingPendingNoteImage by remember { mutableStateOf(false) }
    var isFullScreenEditor by rememberSaveable { mutableStateOf(false) }
    var isEditorModeAnimating by remember { mutableStateOf(false) }
    var transitionFromFullScreen by remember { mutableStateOf(false) }
    var transitionToFullScreen by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val editorModeProgress = remember { Animatable(1f) }
    val normalEditorScrollState = rememberScrollState()
    val imageManager = remember { ImageManager(context) }
    val isEditing = embeddedDraft == null && noteId != -1L
    val isBitwardenNoteTarget = editorState.selectedStorageTargets.any { it is StorageTarget.Bitwarden } ||
        (editorState.selectedStorageTargets.isEmpty() && editorState.bitwardenVaultId != null)
    val isMarkdown = embeddedDraft?.itemData?.get("isMarkdown")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: true
    val canSave = editorViewModel.canSave()
    val shouldLiftSaveFab = !isFullScreenEditor && !editorState.isMarkdownPreview
    
    val categories by database.categoryDao().getAllCategories().collectAsState(initial = emptyList())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    val allNotes by viewModel.allNotes.collectAsState(initial = emptyList())
    val attachmentOwnerItem = if (embeddedDraft != null) null else editorState.currentNote?.takeIf { isEditing }
    val attachmentBitwardenVault = remember(attachmentOwnerItem?.bitwardenVaultId, bitwardenVaults) {
        attachmentOwnerItem?.bitwardenVaultId?.let { vaultId ->
            bitwardenVaults.firstOrNull { it.id == vaultId }
        }
    }
    val attachmentBitwardenContext = takagi.ru.monica.attachments.ui.rememberAttachmentBitwardenContext(
        attachmentBitwardenVault, attachmentOwnerItem?.bitwardenCipherId
    )
    val attachmentKeePassContext = remember(
        attachmentOwnerItem?.keepassDatabaseId,
        attachmentOwnerItem?.keepassEntryUuid
    ) {
        val databaseId = attachmentOwnerItem?.keepassDatabaseId
        val entryUuid = attachmentOwnerItem?.keepassEntryUuid?.takeIf { it.isNotBlank() }
        if (databaseId != null && entryUuid != null) {
            AttachmentFacade.KeePassContext(databaseId = databaseId, entryUuid = entryUuid)
        } else {
            null
        }
    }
    val draftStorageTarget by viewModel.draftStorageTarget.collectAsState()
    val rememberedStorageTarget by settingsManager
        .rememberedStorageTargetFlow(SettingsManager.StorageTargetScope.NOTE)
        .collectAsState(initial = null as RememberedStorageTarget?)
    var showStorageTargetSheet by remember { mutableStateOf(false) }
    var hasInitializedReplicaTargets by rememberSaveable(noteId) { mutableStateOf(false) }

    fun clearPendingNoteImageImport(resetInsertionCursor: Boolean) {
        pendingNoteImageImport?.bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        pendingNoteImageImport = null
        isSavingPendingNoteImage = false
        if (resetInsertionCursor) {
            pendingImageInsertionCursor = -1
        }
    }

    fun applySelectedStorageTargets(targets: List<StorageTarget>) {
        if (targets.any { it is StorageTarget.Bitwarden } && editorState.noteImagePaths.isNotEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.note_bitwarden_inline_image_disabled_toast),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        editorViewModel.setSelectedStorageTargets(targets)
    }

    suspend fun prepareSelectedNoteImage(uri: Uri) {
        try {
            Log.d(ADD_EDIT_NOTE_SCREEN_TAG, "Preparing note image import")
            if (editorState.bitwardenVaultId != null) {
                Log.w(ADD_EDIT_NOTE_SCREEN_TAG, "Skipped note image import because Bitwarden target is active")
                Toast.makeText(
                    context,
                    context.getString(R.string.note_bitwarden_inline_image_disabled_toast),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val preparedImport = imageManager.prepareImageImport(uri)
            if (preparedImport != null) {
                clearPendingNoteImageImport(resetInsertionCursor = false)
                pendingNoteImageImport = PendingNoteImageImport(
                    bitmap = preparedImport.bitmap,
                    originalSizeBytes = preparedImport.originalSizeBytes
                )
            } else {
                Log.w(ADD_EDIT_NOTE_SCREEN_TAG, "prepareImageImport returned null")
                Toast.makeText(
                    context,
                    context.getString(R.string.photo_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e(ADD_EDIT_NOTE_SCREEN_TAG, "Failed to prepare note image", e)
            Toast.makeText(
                context,
                context.getString(R.string.photo_process_failed, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    suspend fun confirmPendingNoteImageImport(quality: Int) {
        val importRequest = pendingNoteImageImport ?: return
        try {
            if (editorState.bitwardenVaultId != null) {
                clearPendingNoteImageImport(resetInsertionCursor = true)
                Toast.makeText(
                    context,
                    context.getString(R.string.note_bitwarden_inline_image_disabled_toast),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            isSavingPendingNoteImage = true
            Log.d(
                ADD_EDIT_NOTE_SCREEN_TAG,
                "Confirming note image import quality=$quality insertionCursor=$pendingImageInsertionCursor"
            )
            val fileName = imageManager.saveImage(
                bitmap = importRequest.bitmap,
                compressionFormat = Bitmap.CompressFormat.JPEG,
                compressionQuality = quality
            )
            if (fileName != null) {
                editorViewModel.insertInlineImage(
                    imageId = fileName,
                    insertionIndex = pendingImageInsertionCursor.takeIf { it >= 0 }
                )
                clearPendingNoteImageImport(resetInsertionCursor = true)
            } else {
                isSavingPendingNoteImage = false
                Log.w(ADD_EDIT_NOTE_SCREEN_TAG, "confirmPendingNoteImageImport returned null")
                Toast.makeText(
                    context,
                    context.getString(R.string.photo_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            isSavingPendingNoteImage = false
            Log.e(ADD_EDIT_NOTE_SCREEN_TAG, "confirmPendingNoteImageImport failed", e)
            Toast.makeText(
                context,
                context.getString(R.string.photo_process_failed, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val noteGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        Log.d(ADD_EDIT_NOTE_SCREEN_TAG, "Gallery result received=${uri != null}")
        if (uri == null) {
            pendingImageInsertionCursor = -1
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            prepareSelectedNoteImage(uri)
        }
    }

    val noteGalleryFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        Log.d(ADD_EDIT_NOTE_SCREEN_TAG, "Fallback gallery result received=${uri != null}")
        if (uri == null) {
            pendingImageInsertionCursor = -1
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            prepareSelectedNoteImage(uri)
        }
    }

    val noteCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val tempPath = pendingCameraImagePath
        val tempUri = pendingCameraImageUri
        pendingCameraImagePath = null
        pendingCameraImageUri = null
        Log.d(ADD_EDIT_NOTE_SCREEN_TAG, "Camera result success=$success hasTemp=${tempPath != null}")
        if (!success || tempPath.isNullOrBlank()) {
            tempPath?.let { path: String -> java.io.File(path).delete() }
            pendingImageInsertionCursor = -1
            return@rememberLauncherForActivityResult
        }
        val resolvedTempPath = tempPath
        val resolvedTempUri = tempUri
        scope.launch {
            val tempFile = java.io.File(resolvedTempPath)
            try {
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.photo_file_missing_or_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                    pendingImageInsertionCursor = -1
                    return@launch
                }
                prepareSelectedNoteImage(resolvedTempUri?.let(Uri::parse) ?: Uri.fromFile(tempFile))
            } finally {
                tempFile.delete()
            }
        }
    }

    val launchNoteCameraCapture: () -> Unit = {
        runCatching {
            val (tempFile, tempUri) = imageManager.createTempPhotoCaptureRequest()
            pendingCameraImagePath = tempFile.absolutePath
            pendingCameraImageUri = tempUri.toString()
            Log.d(ADD_EDIT_NOTE_SCREEN_TAG, "Launching camera for note image")
            noteCameraLauncher.launch(tempUri)
        }.onFailure { error ->
            pendingCameraImagePath = null
            pendingCameraImageUri = null
            pendingImageInsertionCursor = -1
            Log.e(ADD_EDIT_NOTE_SCREEN_TAG, "Camera launch failed", error)
            Toast.makeText(
                context,
                context.getString(R.string.photo_process_failed, error.message ?: error.javaClass.simpleName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val noteCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(ADD_EDIT_NOTE_SCREEN_TAG, "Camera permission result granted=$granted")
        if (granted) {
            launchNoteCameraCapture()
        } else {
            pendingCameraImagePath = null
            pendingCameraImageUri = null
            pendingImageInsertionCursor = -1
            Toast.makeText(context, context.getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(noteId, isEditing) {
        if (embeddedDraft != null) {
            editorViewModel.loadForEdit(embeddedDraft.displayItem())
            return@LaunchedEffect
        }
        if (!isEditing) {
            hasInitializedReplicaTargets = false
            editorViewModel.resetForNewNote()
            showRestoreNewNoteDraftDialog = draftStore.hasDraft(noteId)
            return@LaunchedEffect
        }
        showRestoreNewNoteDraftDialog = false
        val note = viewModel.getNoteById(noteId)
        note?.let {
            editorViewModel.loadForEdit(it)
            editorViewModel.restoreDraft(noteId)
            hasInitializedReplicaTargets = false
        }
    }

    LaunchedEffect(
        attachmentOwnerItem?.id,
        attachmentOwnerItem?.keepassDatabaseId,
        attachmentOwnerItem?.keepassEntryUuid
    ) {
        val item = attachmentOwnerItem ?: return@LaunchedEffect
        if (item.keepassDatabaseId == null || item.keepassEntryUuid.isNullOrBlank()) {
            return@LaunchedEffect
        }
        launch(Dispatchers.IO) {
            runCatching {
                AttachmentContainer.keepassReconciler(context).reconcile(
                    owner = AttachmentOwner.secureItem(item.id),
                    databaseId = item.keepassDatabaseId,
                    entryUuid = item.keepassEntryUuid
                )
            }.onFailure { error ->
                Log.w(
                    ADD_EDIT_NOTE_SCREEN_TAG,
                    "KeePass attachment metadata reconcile failed: ${error::class.simpleName}"
                )
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, noteId, editorViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                editorViewModel.saveDraftImmediate(noteId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isEditing, allNotes, editorState.currentNote?.id, hasInitializedReplicaTargets) {
        if (!isEditing || hasInitializedReplicaTargets) return@LaunchedEffect
        val currentNote = editorState.currentNote ?: return@LaunchedEffect
        if (allNotes.none { it.id == currentNote.id }) return@LaunchedEffect

        val selectedTargets = if (!currentNote.replicaGroupId.isNullOrBlank()) {
            allNotes
                .filter { note ->
                    note.replicaGroupId == currentNote.replicaGroupId && !note.isDeleted
                }
                .map { it.toStorageTarget() }
                .distinctBy(StorageTarget::stableKey)
                .ifEmpty { listOf(currentNote.toStorageTarget()) }
        } else {
            listOf(currentNote.toStorageTarget())
        }
        editorViewModel.setSelectedStorageTargets(
            targets = selectedTargets,
            existingTargetKeys = selectedTargets.map(StorageTarget::stableKey).toSet(),
            replicaGroupId = currentNote.replicaGroupId
        )
        hasInitializedReplicaTargets = true
    }

    LaunchedEffect(
        isEditing,
        initialCategoryId,
        initialStorageExplicit,
        initialKeePassDatabaseId,
        initialKeePassGroupPath,
        initialMdbxDatabaseId,
        initialBitwardenVaultId,
        initialBitwardenFolderId,
        draftStorageTarget,
        rememberedStorageTarget,
        editorState.hasAppliedInitialStorage
    ) {
        editorViewModel.applyInitialStorageIfNeeded(
            isEditing = isEditing,
            initialCategoryId = initialCategoryId,
            initialStorageExplicit = initialStorageExplicit,
            initialKeePassDatabaseId = initialKeePassDatabaseId,
            initialKeePassGroupPath = initialKeePassGroupPath,
            initialMdbxDatabaseId = initialMdbxDatabaseId,
            initialBitwardenVaultId = initialBitwardenVaultId,
            initialBitwardenFolderId = initialBitwardenFolderId,
            draftStorageTarget = draftStorageTarget,
            rememberedStorageTarget = rememberedStorageTarget
        )
    }

    LaunchedEffect(editorState.noteImagePaths) {
        editorState.noteImagePaths.forEach { fileName ->
            if (!noteImageBitmaps.containsKey(fileName)) {
                val bitmap = if (embeddedDraft?.assets?.any { it.name == fileName } == true) embeddedImageLoader?.invoke(fileName) else imageManager.loadImage(fileName)
                if (bitmap != null) {
                    noteImageBitmaps[fileName] = bitmap
                }
            }
        }
    }

    fun animateEditorModeChange(targetFullScreen: Boolean) {
        if (isFullScreenEditor == targetFullScreen || isEditorModeAnimating) return
        scope.launch {
            isEditorModeAnimating = true
            transitionFromFullScreen = isFullScreenEditor
            transitionToFullScreen = targetFullScreen
            editorModeProgress.snapTo(0f)
            editorModeProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 320)
            )
            isFullScreenEditor = targetFullScreen
            isEditorModeAnimating = false
        }
    }

    fun togglePreviewTask(lineIndex: Int, checked: Boolean) {
        val updatedContent = toggleTaskLine(
            content = editorState.contentField.text,
            lineIndex = lineIndex,
            checked = checked
        ) ?: return
        if (updatedContent == editorState.contentField.text) return
        editorViewModel.updateContent(editorState.contentField.copy(text = updatedContent))
    }

    BackHandler(enabled = isFullScreenEditor) {
        animateEditorModeChange(false)
    }

    fun saveNote(shouldNavigateBack: Boolean = true) {
        if (!editorViewModel.tryStartSaving()) return
        val currentState = editorViewModel.uiState.value
        val payload = editorViewModel.buildSavePayload(isMarkdown = isMarkdown)
        if (embeddedDraft != null && onEmbeddedSave != null) {
            scope.launch {
                try {
                    val encoded = NoteContentCodec.encode(payload.content, payload.tags, payload.isMarkdown, payload.customFields).first
                    val data = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject
                    onEmbeddedSave(takagi.ru.monica.attachments.EmbeddedWalletEditorResult(
                        embeddedDraft.edited(payload.title, embeddedDraft.notes, data).withFavorite(currentState.isFavorite),
                        NoteContentCodec.decodeImagePaths(payload.imagePathsJson), pendingAttachmentDrafts.toList()))
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                } catch (_: Exception) { Toast.makeText(context, R.string.embedded_copy_failed, Toast.LENGTH_LONG).show()
                } finally { editorViewModel.stopSaving() }
            }
            return
        }
        val effectiveTargets = currentState.selectedStorageTargets.ifEmpty {
            listOf(
                buildMultiStorageTarget(
                    categoryId = currentState.selectedCategoryId,
                    keepassDatabaseId = currentState.keepassDatabaseId,
                    keepassGroupPath = currentState.keepassGroupPath,
                    mdbxDatabaseId = currentState.mdbxDatabaseId,
                    bitwardenVaultId = currentState.bitwardenVaultId,
                    bitwardenFolderId = currentState.bitwardenFolderId
                )
            )
        }
        val hasBitwardenTarget = effectiveTargets.any { it is StorageTarget.Bitwarden }

        if (hasBitwardenTarget && currentState.noteImagePaths.isNotEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.note_bitwarden_inline_image_disabled_toast),
                Toast.LENGTH_SHORT
            ).show()
            editorViewModel.stopSaving()
            return
        }

        val primaryTarget = effectiveTargets.first()

        val shouldFlushAttachmentDrafts = shouldNavigateBack && !isEditing && pendingAttachmentDrafts.isNotEmpty()
        viewModel.saveNotesAcrossTargets(
            id = noteId.takeIf { isEditing },
            content = payload.content,
            title = payload.title,
            tags = payload.tags,
            customFields = payload.customFields,
            isMarkdown = payload.isMarkdown,
            isFavorite = currentState.isFavorite,
            createdAt = currentState.createdAt,
            imagePaths = payload.imagePathsJson,
            targets = effectiveTargets,
            onPrimaryCreated = if (shouldFlushAttachmentDrafts) {
                { newId ->
                    val savedItem = viewModel.getNoteById(newId)
                    val savedKeePassContext = savedItem?.let { item ->
                        val databaseId = item.keepassDatabaseId
                        val entryUuid = item.keepassEntryUuid?.takeIf { it.isNotBlank() }
                        if (databaseId != null && entryUuid != null) {
                            AttachmentFacade.KeePassContext(databaseId, entryUuid)
                        } else {
                            null
                        }
                    }
                    val savedVault = savedItem?.bitwardenVaultId?.let { vaultId ->
                        bitwardenVaults.firstOrNull { it.id == vaultId }
                    }
                    val savedBitwardenContext = savedVault?.let { vault ->
                        viewModel.getAttachmentBitwardenContext(vault, savedItem?.bitwardenCipherId)
                    }
                    flushPendingDraftsTo(
                        context = context,
                        owner = AttachmentOwner.secureItem(newId),
                        pendingDrafts = pendingAttachmentDrafts,
                        isPlusActivated = appSettings.isPlusActivated,
                        attachmentSource = when {
                            savedBitwardenContext != null -> AttachmentSource.BITWARDEN
                            savedKeePassContext != null -> AttachmentSource.KEEPASS
                            else -> AttachmentSource.LOCAL
                        },
                        bitwardenContext = savedBitwardenContext,
                        bitwardenPremium = savedVault?.let {
                            BitwardenVaultPremiumStore.isPremium(context, it.id)
                        } ?: true,
                        keepassContext = savedKeePassContext
                    )
                    onNavigateBack()
                }
            } else {
                {}
            }
        )
        if (currentState.deletedImagePaths.isNotEmpty()) {
            viewModel.cleanupUnreferencedNoteImages(currentState.deletedImagePaths)
        }
        scope.launch {
            settingsManager.updateRememberedStorageTarget(
                scope = SettingsManager.StorageTargetScope.NOTE,
                target = RememberedStorageTarget(
                    categoryId = (primaryTarget as? StorageTarget.MonicaLocal)?.categoryId,
                    keepassDatabaseId = (primaryTarget as? StorageTarget.KeePass)?.databaseId,
                    keepassGroupPath = (primaryTarget as? StorageTarget.KeePass)?.groupPath,
                    mdbxDatabaseId = (primaryTarget as? StorageTarget.Mdbx)?.databaseId,
                    bitwardenVaultId = (primaryTarget as? StorageTarget.Bitwarden)?.vaultId,
                    bitwardenFolderId = (primaryTarget as? StorageTarget.Bitwarden)?.folderId
                )
            )
        }
        editorViewModel.stopSaving()
        editorViewModel.clearDraft(noteId)
        if (shouldNavigateBack && !shouldFlushAttachmentDrafts) {
            onNavigateBack()
        }
    }

    val storageSelectorContent: @Composable () -> Unit = {
        if (embeddedDraft == null) MultiStorageTargetSelectorCard(
            selectedTargets = editorState.selectedStorageTargets,
            existingTargetKeys = editorState.existingReplicaTargetKeys,
            categories = categories,
            keepassDatabases = keepassDatabases,
            bitwardenVaults = bitwardenVaults,
            bitwardenFolderDao = database.bitwardenFolderDao(),
            isEditing = isEditing,
            onAddTargetClick = { showStorageTargetSheet = true },
            onRemoveTarget = { target -> editorViewModel.removeSelectedStorageTarget(target) }
        )
    }
    val attachmentsContent: @Composable () -> Unit = {
        val draftAttachmentTarget = editorState.selectedStorageTargets.firstOrNull()
            ?: buildMultiStorageTarget(
                categoryId = editorState.selectedCategoryId,
                keepassDatabaseId = editorState.keepassDatabaseId,
                keepassGroupPath = editorState.keepassGroupPath,
                mdbxDatabaseId = editorState.mdbxDatabaseId,
                bitwardenVaultId = editorState.bitwardenVaultId,
                bitwardenFolderId = editorState.bitwardenFolderId
            )
        embeddedAttachmentsContent?.invoke()
        AttachmentsEditSection(
            owner = attachmentOwnerItem?.let { AttachmentOwner.secureItem(it.id) },
            isPlusActivated = appSettings.isPlusActivated,
            attachmentSource = when {
                attachmentOwnerItem?.bitwardenVaultId != null -> AttachmentSource.BITWARDEN
                attachmentOwnerItem?.keepassDatabaseId != null -> AttachmentSource.KEEPASS
                !isEditing && draftAttachmentTarget is StorageTarget.KeePass -> AttachmentSource.KEEPASS
                else -> AttachmentSource.LOCAL
            },
            bitwardenContext = attachmentBitwardenContext,
            bitwardenPremium = attachmentBitwardenVault?.let {
                BitwardenVaultPremiumStore.isPremium(context, it.id)
            } ?: true,
            keepassContext = attachmentKeePassContext,
            pendingDrafts = if (isEditing) null else pendingAttachmentDrafts
        )
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(NoteEditorTopBarHeight)
                ) {
                    if (isEditorModeAnimating) {
                        NoteEditorModeTopBar(
                            fullScreen = transitionFromFullScreen,
                            isEditing = isEditing,
                            isFavorite = editorState.isFavorite,
                            isMarkdownPreview = editorState.isMarkdownPreview,
                            canSave = canSave,
                            isSaving = editorState.isSaving,
                            alpha = 1f - editorModeProgress.value,
                            scale = 1f - (0.06f * editorModeProgress.value),
                            onNavigateBack = onNavigateBack,
                            onToggleFavorite = { editorViewModel.toggleFavorite() },
                            onDelete = { showConfirmDelete = true },
                            onEnterFullScreen = { animateEditorModeChange(true) },
                            onExitFullScreen = { animateEditorModeChange(false) },
                            onPreviewModeChange = { editorViewModel.updatePreviewMode(it) },
                            onSave = {
                                saveNote(shouldNavigateBack = false)
                                animateEditorModeChange(false)
                            },
                            enabled = false
                        )
                        NoteEditorModeTopBar(
                            fullScreen = transitionToFullScreen,
                            isEditing = isEditing,
                            isFavorite = editorState.isFavorite,
                            isMarkdownPreview = editorState.isMarkdownPreview,
                            canSave = canSave,
                            isSaving = editorState.isSaving,
                            alpha = editorModeProgress.value,
                            scale = 0.96f + (0.04f * editorModeProgress.value),
                            onNavigateBack = onNavigateBack,
                            onToggleFavorite = { editorViewModel.toggleFavorite() },
                            onDelete = { showConfirmDelete = true },
                            onEnterFullScreen = { animateEditorModeChange(true) },
                            onExitFullScreen = { animateEditorModeChange(false) },
                            onPreviewModeChange = { editorViewModel.updatePreviewMode(it) },
                            onSave = {
                                saveNote(shouldNavigateBack = false)
                                animateEditorModeChange(false)
                            },
                            enabled = false
                        )
                    } else {
                        NoteEditorModeTopBar(
                            fullScreen = isFullScreenEditor,
                            isEditing = isEditing,
                            isFavorite = editorState.isFavorite,
                            isMarkdownPreview = editorState.isMarkdownPreview,
                            canSave = canSave,
                            isSaving = editorState.isSaving,
                            alpha = 1f,
                            scale = 1f,
                            onNavigateBack = onNavigateBack,
                            onToggleFavorite = { editorViewModel.toggleFavorite() },
                            onDelete = { showConfirmDelete = true },
                            onEnterFullScreen = { animateEditorModeChange(true) },
                            onExitFullScreen = { animateEditorModeChange(false) },
                            onPreviewModeChange = { editorViewModel.updatePreviewMode(it) },
                            onSave = {
                                saveNote(shouldNavigateBack = false)
                                animateEditorModeChange(false)
                            },
                            enabled = !isEditorModeAnimating
                        )
                    }
            }
        },
        floatingActionButton = {
            val fabAlpha = when {
                isEditorModeAnimating && !transitionFromFullScreen && transitionToFullScreen -> {
                    (1f - editorModeProgress.value * 1.25f).coerceIn(0f, 1f)
                }

                isEditorModeAnimating && transitionFromFullScreen && !transitionToFullScreen -> {
                    ((editorModeProgress.value - 0.3f) / 0.7f).coerceIn(0f, 1f)
                }

                !isFullScreenEditor -> 1f
                else -> 0f
            }

            if (fabAlpha > 0.01f) {
                FloatingActionButton(
                    onClick = { saveNote() },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = if (shouldLiftSaveFab) NoteEditorFabToolbarClearance else 0.dp)
                        .graphicsLayer {
                            alpha = fabAlpha
                            scaleX = 0.92f + (0.08f * fabAlpha)
                            scaleY = 0.92f + (0.08f * fabAlpha)
                        },
                    containerColor = if (canSave && !editorState.isSaving) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (canSave && !editorState.isSaving) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    if (editorState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.save)
                        )
                    }
                }
            }
        },
        bottomBar = {}
    ) { paddingValues ->
        val toolbarContent: @Composable () -> Unit = {
            MarkdownQuickToolbar(
                onAction = { action ->
                    when (action) {
                        MarkdownEditorAction.Image -> {
                            if (isBitwardenNoteTarget) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.note_bitwarden_inline_image_disabled_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                pendingImageInsertionCursor = editorState.contentField.selection.start
                                    .coerceIn(0, editorState.contentField.text.length)
                                showAddImageDialog = true
                            }
                        }

                        else -> {
                            editorViewModel.updateContent(
                                applyMarkdownAction(editorState.contentField, action)
                            )
                        }
                    }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (isEditorModeAnimating) {
                    NoteEditorModeBody(
                        fullScreen = transitionFromFullScreen,
                        alpha = 1f - editorModeProgress.value,
                        scale = 1f - (0.06f * editorModeProgress.value),
                        editorState = editorState,
                        noteImageBitmaps = noteImageBitmaps,
                        normalEditorScrollState = normalEditorScrollState,
                        isEditing = isEditing,
                        isBitwardenNoteTarget = isBitwardenNoteTarget,
                        toolbarContent = toolbarContent,
                        storageSelectorContent = storageSelectorContent,
                        attachmentsContent = attachmentsContent,
                        onTitleChange = { editorViewModel.updateTitle(it) },
                        onContentChange = { editorViewModel.updateContent(it) },
                        onPreviewModeChange = { editorViewModel.updatePreviewMode(it) },
                        onTaskItemToggle = ::togglePreviewTask,
                        onPreviewInlineImage = { fileName -> showNoteImageDialog = fileName },
                        onTagsTextChange = { editorViewModel.updateTagsText(it) },
                        customFields = editorState.customFields,
                        onCustomFieldsChange = editorViewModel::updateCustomFields,
                        onAddImageClick = {
                            if (isBitwardenNoteTarget) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.note_bitwarden_inline_image_disabled_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                pendingImageInsertionCursor = editorState.contentField.selection.start
                                    .coerceIn(0, editorState.contentField.text.length)
                                showAddImageDialog = true
                            }
                        },
                        onRemoveImage = { fileName ->
                            if (isBitwardenNoteTarget) return@NoteEditorModeBody
                            editorViewModel.removeInlineImage(fileName)
                        }
                    )
                    NoteEditorModeBody(
                        fullScreen = transitionToFullScreen,
                        alpha = editorModeProgress.value,
                        scale = 0.96f + (0.04f * editorModeProgress.value),
                        editorState = editorState,
                        noteImageBitmaps = noteImageBitmaps,
                        normalEditorScrollState = normalEditorScrollState,
                        isEditing = isEditing,
                        isBitwardenNoteTarget = isBitwardenNoteTarget,
                        toolbarContent = toolbarContent,
                        storageSelectorContent = storageSelectorContent,
                        attachmentsContent = attachmentsContent,
                        onTitleChange = { editorViewModel.updateTitle(it) },
                        onContentChange = { editorViewModel.updateContent(it) },
                        onPreviewModeChange = { editorViewModel.updatePreviewMode(it) },
                        onTaskItemToggle = ::togglePreviewTask,
                        onPreviewInlineImage = { fileName -> showNoteImageDialog = fileName },
                        onTagsTextChange = { editorViewModel.updateTagsText(it) },
                        customFields = editorState.customFields,
                        onCustomFieldsChange = editorViewModel::updateCustomFields,
                        onAddImageClick = {
                            if (isBitwardenNoteTarget) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.note_bitwarden_inline_image_disabled_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                pendingImageInsertionCursor = editorState.contentField.selection.start
                                    .coerceIn(0, editorState.contentField.text.length)
                                showAddImageDialog = true
                            }
                        },
                        onRemoveImage = { fileName ->
                            if (isBitwardenNoteTarget) return@NoteEditorModeBody
                            editorViewModel.removeInlineImage(fileName)
                        }
                    )
                } else {
                    NoteEditorModeBody(
                        fullScreen = isFullScreenEditor,
                        alpha = 1f,
                        scale = 1f,
                        editorState = editorState,
                        noteImageBitmaps = noteImageBitmaps,
                        normalEditorScrollState = normalEditorScrollState,
                        isEditing = isEditing,
                        isBitwardenNoteTarget = isBitwardenNoteTarget,
                        toolbarContent = toolbarContent,
                        storageSelectorContent = storageSelectorContent,
                        attachmentsContent = attachmentsContent,
                        onTitleChange = { editorViewModel.updateTitle(it) },
                        onContentChange = { editorViewModel.updateContent(it) },
                        onPreviewModeChange = { editorViewModel.updatePreviewMode(it) },
                        onTaskItemToggle = ::togglePreviewTask,
                        onPreviewInlineImage = { fileName -> showNoteImageDialog = fileName },
                        onTagsTextChange = { editorViewModel.updateTagsText(it) },
                        customFields = editorState.customFields,
                        onCustomFieldsChange = editorViewModel::updateCustomFields,
                        onAddImageClick = {
                            if (isBitwardenNoteTarget) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.note_bitwarden_inline_image_disabled_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                pendingImageInsertionCursor = editorState.contentField.selection.start
                                    .coerceIn(0, editorState.contentField.text.length)
                                showAddImageDialog = true
                            }
                        },
                        onRemoveImage = { fileName ->
                            if (isBitwardenNoteTarget) return@NoteEditorModeBody
                            editorViewModel.removeInlineImage(fileName)
                        }
                    )
                }
            }
        }
    }

    MultiStorageTargetPickerBottomSheet(
        visible = showStorageTargetSheet,
        selectedTargets = editorState.selectedStorageTargets,
        lockedTargetKeys = editorState.existingReplicaTargetKeys,
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
        getKeePassGroups = localKeePassViewModel::getGroups,
        onDismiss = { showStorageTargetSheet = false },
        onSelectedTargetsChange = ::applySelectedStorageTargets
    )

    if (showRestoreNewNoteDraftDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreNewNoteDraftDialog = false },
            title = { Text(stringResource(R.string.note_draft_restore_title)) },
            text = { Text(stringResource(R.string.note_draft_restore_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreNewNoteDraftDialog = false
                        editorViewModel.restoreDraft(noteId)
                    }
                ) {
                    Text(stringResource(R.string.note_draft_restore_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestoreNewNoteDraftDialog = false
                        editorViewModel.clearDraft(noteId)
                    }
                ) {
                    Text(
                        text = stringResource(R.string.note_draft_discard_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    }

    // Image Detail Dialog
    if (showNoteImageDialog != null) {
        val bitmap = noteImageBitmaps[showNoteImageDialog!!]
        if (bitmap != null) {
            ImageDialog(
                bitmap = bitmap,
                onDismiss = { showNoteImageDialog = null }
            )
        } else {
            showNoteImageDialog = null
        }
    }

    pendingNoteImageImport?.let { importRequest ->
        ImageImportConfirmDialog(
            imageManager = imageManager,
            bitmap = importRequest.bitmap,
            originalSizeBytes = importRequest.originalSizeBytes,
            isSaving = isSavingPendingNoteImage,
            onDismiss = {
                clearPendingNoteImageImport(resetInsertionCursor = true)
            },
            onConfirm = { quality ->
                scope.launch {
                    confirmPendingNoteImageImport(quality)
                }
            }
        )
    }
    
    // Add Image Selection Dialog (Bottom Sheet or Dialog)
    if (showAddImageDialog) {
        AlertDialog(
            onDismissRequest = { showAddImageDialog = false },
            icon = { Icon(Icons.Default.Image, contentDescription = null) },
            title = { Text(stringResource(R.string.section_photos)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.note_image_source_title), style = MaterialTheme.typography.bodyMedium)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showAddImageDialog = false
                                pendingImageInsertionCursor = editorState.contentField.selection.start
                                    .coerceIn(0, editorState.contentField.text.length)
                                Log.d(
                                    ADD_EDIT_NOTE_SCREEN_TAG,
                                    "Gallery button clicked insertionCursor=$pendingImageInsertionCursor"
                                )
                                runCatching {
                                    noteGalleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }.onFailure { error ->
                                    if (error is ActivityNotFoundException) {
                                        Log.w(ADD_EDIT_NOTE_SCREEN_TAG, "PickVisualMedia unavailable, falling back to GetContent", error)
                                        runCatching {
                                            noteGalleryFallbackLauncher.launch("image/*")
                                        }.onFailure { fallbackError ->
                                            Log.e(ADD_EDIT_NOTE_SCREEN_TAG, "Fallback gallery launch failed", fallbackError)
                                            pendingImageInsertionCursor = -1
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.photo_process_failed, fallbackError.message ?: fallbackError.javaClass.simpleName),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } else {
                                        Log.e(ADD_EDIT_NOTE_SCREEN_TAG, "Gallery launch failed", error)
                                        pendingImageInsertionCursor = -1
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.photo_process_failed, error.message ?: error.javaClass.simpleName),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Image, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.gallery))
                        }
                        OutlinedButton(
                            onClick = {
                                showAddImageDialog = false
                                pendingImageInsertionCursor = editorState.contentField.selection.start
                                    .coerceIn(0, editorState.contentField.text.length)
                                Log.d(
                                    ADD_EDIT_NOTE_SCREEN_TAG,
                                    "Camera button clicked insertionCursor=$pendingImageInsertionCursor permission=${ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)}"
                                )
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    launchNoteCameraCapture()
                                } else {
                                    noteCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.camera))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showAddImageDialog = false
                    pendingImageInsertionCursor = -1
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_note_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDelete = false
                    showPasswordDialog = true
                }) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    fun performDelete() {
        val noteToDelete = editorState.currentNote
        scope.launch {
            noteToDelete?.let { note ->
                viewModel.deleteNote(note)
            }
            showPasswordDialog = false
            onNavigateBack()
        }
    }

    if (showPasswordDialog) {
        val deletionNoteId = remember { editorState.currentNote?.id }
        val fragmentActivity = context as? FragmentActivity
        val skipIdentityVerification = appSettings.disablePasswordVerification
        val biometricAction = if (
            !skipIdentityVerification &&
            fragmentActivity != null &&
            appSettings.biometricEnabled &&
            biometricHelper.isBiometricAvailable()
        ) {
            {
                biometricHelper.authenticate(
                    activity = fragmentActivity,
                    title = context.getString(R.string.verify_identity),
                    subtitle = context.getString(R.string.verify_to_delete),
                    onSuccess = { performDelete() },
                    onError = { error ->
                        android.widget.Toast.makeText(
                            context,
                            error,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailed = {}
                )
            }
        } else {
            null
        }
        if (skipIdentityVerification) {
            M3IdentityVerifyDialog(
                title = stringResource(R.string.verify_identity),
                message = stringResource(R.string.verify_to_delete),
                passwordValue = "",
                onPasswordChange = {},
                onDismiss = { showPasswordDialog = false },
                onConfirm = { performDelete() },
                confirmText = stringResource(R.string.delete),
                destructiveConfirm = true,
                requireIdentityVerification = false,
            )
        } else {
            SecondaryPasswordVerificationDialog(
                manager = securityManager,
                title = stringResource(R.string.verify_identity),
                message = stringResource(R.string.verify_to_delete),
                onDismiss = { showPasswordDialog = false },
                onSuccess = { performDelete() },
                isRequestCurrent = { showPasswordDialog && deletionNoteId != null && editorState.currentNote?.id == deletionNoteId },
                confirmText = stringResource(R.string.delete),
                icon = Icons.Default.Warning,
                destructiveConfirm = true,
                onBiometricClick = biometricAction,
                biometricHintText = if (biometricAction == null) {
                    stringResource(R.string.biometric_not_available)
                } else null,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorModeTopBar(
    fullScreen: Boolean,
    isEditing: Boolean,
    isFavorite: Boolean,
    isMarkdownPreview: Boolean,
    canSave: Boolean,
    isSaving: Boolean,
    alpha: Float,
    scale: Float,
    onNavigateBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onEnterFullScreen: () -> Unit,
    onExitFullScreen: () -> Unit,
    onPreviewModeChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    enabled: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
    ) {
        if (fullScreen) {
            FullScreenNoteTopBar(
                isMarkdownPreview = isMarkdownPreview,
                onModeChange = onPreviewModeChange,
                canSave = canSave,
                isSaving = isSaving,
                onExit = onExitFullScreen,
                onSave = onSave
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NoteEditorTopBarHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack, enabled = enabled) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
                Text(
                    text = stringResource(
                        if (isEditing) R.string.edit_note else R.string.new_note
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onToggleFavorite, enabled = enabled) {
                        Icon(
                            imageVector = if (isFavorite) {
                                Icons.Default.Favorite
                            } else {
                                Icons.Default.FavoriteBorder
                            },
                            contentDescription = stringResource(R.string.favorite)
                        )
                    }
                    if (isEditing) {
                        IconButton(onClick = onDelete, enabled = enabled) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                    IconButton(onClick = onEnterFullScreen, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = stringResource(R.string.legacy_ui_fullscreen_edit)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorModeBody(
    fullScreen: Boolean,
    alpha: Float,
    scale: Float,
    editorState: takagi.ru.monica.viewmodel.NoteEditorUiState,
    noteImageBitmaps: Map<String, Bitmap>,
    normalEditorScrollState: androidx.compose.foundation.ScrollState,
    isEditing: Boolean,
    isBitwardenNoteTarget: Boolean,
    toolbarContent: @Composable () -> Unit,
    storageSelectorContent: @Composable () -> Unit,
    attachmentsContent: @Composable () -> Unit,
    onTitleChange: (String) -> Unit,
    onContentChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onPreviewModeChange: (Boolean) -> Unit,
    onTaskItemToggle: (lineIndex: Int, checked: Boolean) -> Unit,
    onPreviewInlineImage: (String) -> Unit,
    onTagsTextChange: (String) -> Unit,
    customFields: List<CustomFieldDraft> = emptyList(),
    onCustomFieldsChange: (List<CustomFieldDraft>) -> Unit = {},
    onAddImageClick: () -> Unit,
    onRemoveImage: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
    ) {
        if (fullScreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp)
            ) {
                NoteEditorSection(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    title = editorState.title,
                    onTitleChange = onTitleChange,
                    content = editorState.contentField,
                    onContentChange = onContentChange,
                    isMarkdownPreview = editorState.isMarkdownPreview,
                    onMarkdownPreviewModeChange = onPreviewModeChange,
                    showModeSwitcher = false,
                    codeBlockCollapseMode = NoteCodeBlockCollapseMode.COMPACT,
                    inlineImageBitmaps = noteImageBitmaps,
                    onPreviewInlineImage = onPreviewInlineImage,
                    onTaskItemToggle = onTaskItemToggle,
                    tagsText = editorState.tagsText,
                    onTagsTextChange = onTagsTextChange,
                    borderless = true,
                    showTags = false,
                    editorTakesRemainingSpace = true
                )

                if (!editorState.isMarkdownPreview) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                toolbarContent()
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(normalEditorScrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    storageSelectorContent()

                    NoteEditorSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = editorState.title,
                        onTitleChange = onTitleChange,
                        content = editorState.contentField,
                        onContentChange = onContentChange,
                        isMarkdownPreview = editorState.isMarkdownPreview,
                        onMarkdownPreviewModeChange = onPreviewModeChange,
                        showModeSwitcher = true,
                        codeBlockCollapseMode = NoteCodeBlockCollapseMode.COMPACT,
                        inlineImageBitmaps = noteImageBitmaps,
                        onPreviewInlineImage = onPreviewInlineImage,
                        onTaskItemToggle = onTaskItemToggle,
                        tagsText = editorState.tagsText,
                        onTagsTextChange = onTagsTextChange,
                        borderless = false,
                        showTags = true,
                        editorTakesRemainingSpace = false
                    )

                    CustomFieldEditorSection(
                        fields = customFields,
                        onFieldsChange = onCustomFieldsChange,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isEditing) {
                        NoteImagesSection(
                            noteImagePaths = editorState.noteImagePaths,
                            noteImageBitmaps = noteImageBitmaps,
                            imageActionsEnabled = !isBitwardenNoteTarget,
                            disabledReason = if (isBitwardenNoteTarget) {
                                stringResource(R.string.note_bitwarden_inline_image_disabled_reason)
                            } else {
                                null
                            },
                            onAddImageClick = onAddImageClick,
                            onPreviewImage = onPreviewInlineImage,
                            onRemoveImage = onRemoveImage
                        )
                    }

                    attachmentsContent()
                }

                if (!editorState.isMarkdownPreview) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .navigationBarsPadding(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                toolbarContent()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorModeCapsule(
    isMarkdownPreview: Boolean,
    onModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val editSelected = !isMarkdownPreview
            val previewSelected = isMarkdownPreview

            TextButton(
                onClick = { onModeChange(false) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (editSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (editSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(text = stringResource(R.string.note_mode_edit))
            }

            TextButton(
                onClick = { onModeChange(true) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (previewSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (previewSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(text = stringResource(R.string.note_mode_preview))
            }
        }
    }
}

@Composable
private fun FullScreenNoteTopBar(
    isMarkdownPreview: Boolean,
    onModeChange: (Boolean) -> Unit,
    canSave: Boolean,
    isSaving: Boolean,
    onExit: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NoteEditorTopBarHeight)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
            shadowElevation = 0.dp
        ) {
            IconButton(onClick = onExit) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.legacy_ui_fullscreen_exit)
                )
            }
        }

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            EditorModeCapsule(
                isMarkdownPreview = isMarkdownPreview,
                onModeChange = onModeChange,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (canSave && !isSaving) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            tonalElevation = 2.dp,
            shadowElevation = 0.dp
        ) {
            IconButton(
                onClick = onSave,
                enabled = canSave && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.save),
                        tint = if (canSave) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

private val NoteEditorTopBarHeight = 64.dp
private val NoteEditorFabToolbarClearance = 88.dp

private fun toggleTaskLine(content: String, lineIndex: Int, checked: Boolean): String? {
    if (lineIndex < 0) return null
    val lines = content.lines().toMutableList()
    if (lineIndex >= lines.size) return null

    val pattern = Regex("^(\\s*(?:[-*+]\\s+)?)\\[([ xX])]((?:\\s.*)?)$")
    val target = lines[lineIndex]
    val match = pattern.find(target) ?: return null

    val prefix = match.groupValues[1]
    val suffix = match.groupValues[3]
    val marker = if (checked) "[x]" else "[ ]"
    lines[lineIndex] = prefix + marker + suffix
    return lines.joinToString("\n")
}
