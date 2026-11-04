package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.ui.AttachmentsDetailSection
import takagi.ru.monica.data.NoteCodeBlockCollapseMode
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.ui.components.ActionStrip
import takagi.ru.monica.ui.components.ActionStripItem
import takagi.ru.monica.ui.components.ImageDialog
import takagi.ru.monica.ui.components.MarkdownPreviewText
import takagi.ru.monica.ui.components.CustomFieldDisplayCard
import takagi.ru.monica.util.ImageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.viewmodel.NoteViewModel
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteDetailScreen(
    viewModel: NoteViewModel,
    noteId: Long,
    initialHighlightQuery: String? = null,
    onNavigateBack: () -> Unit,
    onEditNote: (Long) -> Unit,
    onCreateSend: (title: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
    embeddedAccess: takagi.ru.monica.attachments.EmbeddedWalletAccess? = null
) {
    val detailImageMaxDimension = 1440
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val database = remember { PasswordDatabase.getDatabase(context) }
    val untitledLabel = stringResource(R.string.untitled)
    val imageManager = remember { ImageManager(context) }
    val standaloneNote by viewModel.observeNoteById(noteId).collectAsState(initial = null)
    val noteItem = embeddedAccess?.snapshot?.displayItem() ?: standaloneNote
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    var showNoteImageDialog by remember { mutableStateOf<String?>(null) }
    val highlightQuery = initialHighlightQuery?.trim().orEmpty()
    var showSearchHighlight by remember(noteId, highlightQuery) {
        mutableStateOf(highlightQuery.isNotBlank())
    }
    val decodedNote = remember(noteItem) {
        noteItem?.let { NoteContentCodec.decodeFromItem(it) }
    }

    val imageBitmaps = remember(noteId) { mutableStateMapOf<String, Bitmap>() }
    val legacyImageIds = remember(noteItem?.imagePaths) {
        noteItem?.let { NoteContentCodec.decodeImagePaths(it.imagePaths) }.orEmpty()
    }
    val markdownSource = remember(decodedNote?.content, legacyImageIds) {
        decodedNote?.let {
            NoteContentCodec.appendInlineImageRefs(
                content = it.content.trimEnd(),
                imageIds = legacyImageIds
            )
        }.orEmpty()
    }
    val imageIds = remember(markdownSource, legacyImageIds) {
        (NoteContentCodec.extractInlineImageIds(markdownSource) + legacyImageIds).distinct()
    }
    val fallbackTitle by remember(markdownSource) {
        derivedStateOf {
            markdownSource
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("![](") }
                .orEmpty()
        }
    }
    val displayTitle by remember(noteItem?.title, fallbackTitle) {
        derivedStateOf {
            noteItem?.title
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .ifBlank { fallbackTitle }
        }
    }
    val sendText by remember(markdownSource, decodedNote?.content) {
        derivedStateOf {
            markdownSource.ifBlank { decodedNote?.content.orEmpty() }.trim()
        }
    }
    val attachmentBitwardenVault = remember(noteItem?.bitwardenVaultId, bitwardenVaults) {
        noteItem?.bitwardenVaultId?.let { vaultId ->
            bitwardenVaults.firstOrNull { it.id == vaultId }
        }
    }
    val attachmentBitwardenContext = remember(
        attachmentBitwardenVault,
        noteItem?.bitwardenCipherId
    ) {
        attachmentBitwardenVault?.let { vault ->
            viewModel.getAttachmentBitwardenContext(vault, noteItem?.bitwardenCipherId)
        }
    }
    val attachmentKeePassContext = remember(noteItem?.keepassDatabaseId, noteItem?.keepassEntryUuid) {
        val databaseId = noteItem?.keepassDatabaseId
        val entryUuid = noteItem?.keepassEntryUuid?.takeIf { it.isNotBlank() }
        if (databaseId != null && entryUuid != null) {
            AttachmentFacade.KeePassContext(databaseId = databaseId, entryUuid = entryUuid)
        } else {
            null
        }
    }

    LaunchedEffect(imageIds) {
        val staleKeys = imageBitmaps.keys.toSet() - imageIds.toSet()
        staleKeys.forEach { key ->
            imageBitmaps.remove(key)
        }
        imageIds.forEach { imageId ->
            if (!imageBitmaps.containsKey(imageId)) {
                val bitmap = withContext(Dispatchers.IO) {
                    if (embeddedAccess != null) embeddedAccess.image(imageId) else imageManager.loadImage(
                        fileName = imageId,
                        maxDimension = detailImageMaxDimension
                    )
                }
                bitmap?.let {
                    imageBitmaps[imageId] = bitmap
                }
            }
        }
    }

    LaunchedEffect(noteId, highlightQuery) {
        if (highlightQuery.isBlank()) return@LaunchedEffect
        showSearchHighlight = true
        delay(3000)
        showSearchHighlight = false
    }

    LaunchedEffect(noteItem?.id, noteItem?.keepassDatabaseId, noteItem?.keepassEntryUuid) {
        val item = noteItem ?: return@LaunchedEffect
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
                android.util.Log.w(
                    "NoteDetailScreen",
                    "KeePass attachment metadata reconcile failed: ${error::class.simpleName}"
                )
            }
        }
    }

    val archiveAction = takagi.ru.monica.ui.components.rememberBitwardenArchiveAction(
        if (embeddedAccess == null) noteItem else null) { /* observeNoteById publishes the committed row. */ }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (embeddedAccess == null) noteItem?.let {
                ActionStrip(
                    actions = listOf(
                        ActionStripItem(
                            icon = Icons.Default.Send,
                            contentDescription = stringResource(R.string.nav_v2_send),
                            onClick = {
                                onCreateSend(
                                    displayTitle.ifBlank { untitledLabel },
                                    sendText
                                )
                            }
                        ),
                        ActionStripItem(
                            icon = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit),
                            onClick = { onEditNote(it.id) }
                        )
                    ) + listOfNotNull(archiveAction),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    ) { paddingValues ->
        if (noteItem == null || decodedNote == null) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.note_detail_not_found_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.note_detail_not_found_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }
        val currentNote = noteItem ?: return@Scaffold
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = buildSearchHighlightedText(
                    input = displayTitle.ifBlank { untitledLabel },
                    searchQuery = highlightQuery,
                    showSearchHighlight = showSearchHighlight
                ),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (markdownSource.isBlank()) {
                Text(
                    text = stringResource(R.string.note_detail_empty_content),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SelectionContainer {
                    MarkdownPreviewText(
                        markdown = markdownSource,
                        imageBitmaps = imageBitmaps,
                        codeBlockCollapseMode = NoteCodeBlockCollapseMode.COMPACT,
                        searchHighlightQuery = highlightQuery,
                        showSearchHighlight = showSearchHighlight,
                        autoBringSearchHighlightIntoView = highlightQuery.isNotBlank(),
                        onInlineImageClick = { imageId -> showNoteImageDialog = imageId },
                        onTaskItemToggle = { lineIndex, checked ->
                            val updatedContent = toggleTaskLine(
                                content = decodedNote.content,
                                lineIndex = lineIndex,
                                checked = checked
                            )
                            if (updatedContent != null && updatedContent != decodedNote.content) {
                                viewModel.updateNote(
                                    id = currentNote.id,
                                    content = updatedContent,
                                    title = currentNote.title,
                                    tags = decodedNote.tags,
                                    isMarkdown = decodedNote.isMarkdown,
                                    isFavorite = currentNote.isFavorite,
                                    createdAt = currentNote.createdAt,
                                    categoryId = currentNote.categoryId,
                                    imagePaths = currentNote.imagePaths,
                                    keepassDatabaseId = currentNote.keepassDatabaseId,
                                    keepassGroupPath = currentNote.keepassGroupPath,
                                    bitwardenVaultId = currentNote.bitwardenVaultId,
                                    bitwardenFolderId = currentNote.bitwardenFolderId
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (decodedNote.tags.isNotEmpty()) {
                Text(
                    text = decodedNote.tags.joinToString(separator = "  ") { "#$it" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (decodedNote.customFields.isNotEmpty()) {
                CustomFieldDisplayCard(
                    fields = decodedNote.customFields.mapIndexed { index, field ->
                        takagi.ru.monica.data.CustomField(
                            id = index.toLong(),
                            entryId = currentNote.id,
                            title = field.label,
                            value = field.value,
                            isProtected = field.isProtected(),
                            sortOrder = index
                        )
                    }
                )
            }

            if (embeddedAccess?.isNative == true) takagi.ru.monica.attachments.ui.NativeEmbeddedWalletAttachments(embeddedAccess)
            else AttachmentsDetailSection(
                owner = embeddedAccess?.owner ?: AttachmentOwner.secureItem(currentNote.id),
                includedFileNames = embeddedAccess?.snapshot?.assets?.filter { it.role == takagi.ru.monica.data.model.EmbeddedWalletContent.AssetRole.ATTACHMENT }?.map { it.name }?.toSet(),
                displayFileNames = embeddedAccess?.snapshot?.assets?.associate { it.name to it.displayName }.orEmpty(),
                bitwardenContext = embeddedAccess?.bitwardenContext ?: attachmentBitwardenContext,
                keepassContext = embeddedAccess?.keepassContext ?: attachmentKeePassContext
            )

            Text(
                text = stringResource(
                    R.string.note_detail_updated_at,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(currentNote.updatedAt)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    if (showNoteImageDialog != null) {
        val bitmap = imageBitmaps[showNoteImageDialog!!]
        if (bitmap != null) {
            ImageDialog(
                bitmap = bitmap,
                onDismiss = { showNoteImageDialog = null }
            )
        } else {
            showNoteImageDialog = null
        }
    }
}

private fun toggleTaskLine(content: String, lineIndex: Int, checked: Boolean): String? {
    if (lineIndex < 0) return null
    val lines = content.lines().toMutableList()
    if (lineIndex >= lines.size) return null

    val pattern = Regex("^(\\s*(?:[-*+]\\s+)?)\\[([ xX])](\\s?.*)$")
    val target = lines[lineIndex]
    val match = pattern.find(target) ?: return null

    val prefix = match.groupValues[1]
    val suffix = match.groupValues[3]
    val marker = if (checked) "[x]" else "[ ]"
    lines[lineIndex] = prefix + marker + suffix
    return lines.joinToString("\n")
}

private fun buildSearchHighlightedText(
    input: String,
    searchQuery: String,
    showSearchHighlight: Boolean
): AnnotatedString {
    val builder = AnnotatedString.Builder(input)
    val query = searchQuery.trim()
    if (showSearchHighlight && query.isNotBlank()) {
        val start = input.indexOf(query, ignoreCase = true)
        if (start >= 0) {
            builder.addStyle(
                style = SpanStyle(
                    background = Color(0x66FFD54F),
                    color = Color.Unspecified
                ),
                start = start,
                end = start + query.length
            )
        }
    }
    return builder.toAnnotatedString()
}
