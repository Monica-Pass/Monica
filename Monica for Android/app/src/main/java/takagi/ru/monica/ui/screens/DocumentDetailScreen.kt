package takagi.ru.monica.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.cardwallet.CardFaceDetailHeader
import takagi.ru.monica.ui.cardwallet.documentCardFacePreviewData
import takagi.ru.monica.ui.cardwallet.CardFaceCustomizer
import takagi.ru.monica.ui.cardwallet.rememberCardFaceBitmap
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.ui.AttachmentsDetailSection
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.DocumentType
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.displayFullName
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.keepass.KeePassSecureItemPhotoAttachments
import takagi.ru.monica.ui.components.ActionStrip
import takagi.ru.monica.ui.components.ActionStripItem
import takagi.ru.monica.ui.components.ImageDialog
import takagi.ru.monica.ui.components.InfoFieldWithCopy
import takagi.ru.monica.ui.components.InfoField
import takagi.ru.monica.ui.components.PasswordField
import takagi.ru.monica.util.ImageManager
import takagi.ru.monica.viewmodel.DocumentViewModel
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    viewModel: DocumentViewModel,
    documentId: Long,
    onNavigateBack: () -> Unit,
    onEditDocument: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val database = remember { PasswordDatabase.getDatabase(context) }
    val imageManager = remember { ImageManager(context) }
    val scope = rememberCoroutineScope()
    val allDocuments by viewModel.allDocuments.collectAsState(initial = emptyList())
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    
    var documentItem by remember { mutableStateOf<SecureItem?>(null) }
    var documentData by remember { mutableStateOf<DocumentData?>(null) }
    var frontBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var backBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showFrontImageDialog by remember { mutableStateOf(false) }
    var showBackImageDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCardFaceCustomizer by remember { mutableStateOf(false) }
    var isSavingCardFace by remember { mutableStateOf(false) }
    
    // Load document details
    LaunchedEffect(documentId) {
        viewModel.getDocumentById(documentId)?.let { item ->
            documentItem = item
            
            documentData = viewModel.parseDocumentData(item.itemData)

            if (item.keepassDatabaseId != null && !item.keepassEntryUuid.isNullOrBlank()) {
                launch(Dispatchers.IO) {
                    runCatching {
                        AttachmentContainer.keepassReconciler(context).reconcile(
                            owner = AttachmentOwner.secureItem(item.id),
                            databaseId = item.keepassDatabaseId,
                            entryUuid = item.keepassEntryUuid,
                            excludedFileNames = KeePassSecureItemPhotoAttachments.managedFileNames(ItemType.DOCUMENT)
                        )
                    }.onFailure { error ->
                        android.util.Log.w(
                            "DocumentDetailScreen",
                            "KeePass attachment metadata reconcile failed: ${error::class.simpleName}"
                        )
                    }
                }
            }
            
            try {
                if (item.imagePaths.isNotBlank()) {
                    val paths = Json.decodeFromString<List<String>>(item.imagePaths)
                    if (paths.isNotEmpty() && paths[0].isNotBlank()) frontBitmap = imageManager.loadImage(paths[0])
                    if (paths.size > 1 && paths[1].isNotBlank()) backBitmap = imageManager.loadImage(paths[1])
                }
            } catch (e: Exception) {
                // Handle image loading error
            }
        }
    }
    val replicaTargets = remember(documentItem, allDocuments) {
        val currentItem = documentItem
        if (currentItem == null) {
            emptyList()
        } else if (!currentItem.replicaGroupId.isNullOrBlank()) {
            allDocuments
                .filter { document -> document.replicaGroupId == currentItem.replicaGroupId && !document.isDeleted }
                .map { it.toStorageTarget() }
                .distinctBy(StorageTarget::stableKey)
                .ifEmpty { listOf(currentItem.toStorageTarget()) }
        } else {
            listOf(currentItem.toStorageTarget())
        }
    }
    val attachmentBitwardenVault = remember(documentItem?.bitwardenVaultId, bitwardenVaults) {
        documentItem?.bitwardenVaultId?.let { vaultId ->
            bitwardenVaults.firstOrNull { it.id == vaultId }
        }
    }
    val attachmentBitwardenContext = remember(
        attachmentBitwardenVault,
        documentItem?.bitwardenCipherId
    ) {
        attachmentBitwardenVault?.let { vault ->
            viewModel.getAttachmentBitwardenContext(vault, documentItem?.bitwardenCipherId)
        }
    }
    val attachmentKeePassContext = remember(
        documentItem?.keepassDatabaseId,
        documentItem?.keepassEntryUuid
    ) {
        val databaseId = documentItem?.keepassDatabaseId
        val entryUuid = documentItem?.keepassEntryUuid?.takeIf { it.isNotBlank() }
        if (databaseId != null && entryUuid != null) {
            AttachmentFacade.KeePassContext(databaseId = databaseId, entryUuid = entryUuid)
        } else {
            null
        }
    }
    val cardFaceBitmap = rememberCardFaceBitmap(
        documentItem, documentData?.cardFace?.imageAttachmentName, maxDimension = 1200
    )
    val cardFaceImageAllowed = attachmentBitwardenVault?.let {
        BitwardenVaultPremiumStore.isPremium(context, it.id)
    } ?: true
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(documentItem?.title ?: stringResource(R.string.document_details)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            ActionStrip(
                actions = listOf(
                    ActionStripItem(
                        icon = if (documentItem?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.favorite),
                        onClick = {
                            documentItem?.let { item ->
                                viewModel.toggleFavorite(item.id)
                                documentItem = item.copy(isFavorite = !item.isFavorite)
                            }
                        },
                        tint = if (documentItem?.isFavorite == true) MaterialTheme.colorScheme.primary else null
                    ),
                    ActionStripItem(
                        icon = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        onClick = { onEditDocument(documentId) }
                    ),
                    ActionStripItem(
                        icon = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        onClick = { showDeleteDialog = true },
                        tint = MaterialTheme.colorScheme.error
                    )
                ),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    ) { paddingValues ->
        documentData?.let { data ->
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Card using M3E color roles based on type
                CardFaceDetailHeader(
                    previewData = documentCardFacePreviewData(documentItem?.title.orEmpty(), data),
                    config = data.cardFace,
                    bitmap = cardFaceBitmap,
                    enabled = !isSavingCardFace,
                    onClick = { showCardFaceCustomizer = true }
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (data.documentType) {
                            DocumentType.ID_CARD -> MaterialTheme.colorScheme.primaryContainer
                            DocumentType.PASSPORT -> MaterialTheme.colorScheme.secondaryContainer
                            DocumentType.DRIVER_LICENSE -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = when (data.documentType) {
                                    DocumentType.ID_CARD -> Icons.Default.Badge
                                    DocumentType.PASSPORT -> Icons.Default.FlightTakeoff
                                    DocumentType.DRIVER_LICENSE -> Icons.Default.DirectionsCar
                                    else -> Icons.Default.Description
                                },
                                contentDescription = null
                            )
                            Text(
                                text = getDocumentTypeName(data.documentType),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Divider(color = LocalContentColor.current.copy(alpha = 0.2f))
                        
                        InfoFieldWithCopy(
                            label = stringResource(R.string.document_number),
                            value = data.documentNumber,
                            context = context
                        )
                        
                         if (data.displayFullName().isNotBlank()) {
                            InfoFieldWithCopy(
                                label = stringResource(R.string.full_name),
                                value = data.displayFullName(),
                                context = context
                            )
                        }
                    }
                }
                
                // Details Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                         Text(
                            text = stringResource(R.string.details),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        if (data.issuedDate.isNotBlank() || data.expiryDate.isNotBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (data.issuedDate.isNotBlank()) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        InfoField(
                                            label = stringResource(R.string.issued_date),
                                            value = data.issuedDate
                                        )
                                    }
                                }
                                if (data.expiryDate.isNotBlank()) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        InfoField(
                                            label = stringResource(R.string.expiry_date),
                                            value = data.expiryDate
                                        )
                                    }
                                }
                            }
                        }
                        
                        if (data.issuedBy.isNotBlank()) {
                             InfoField(
                                label = stringResource(R.string.issued_by),
                                value = data.issuedBy
                            )
                        }
                        
                         if (data.nationality.isNotBlank()) {
                             InfoField(
                                label = stringResource(R.string.nationality),
                                value = data.nationality
                            )
                        }

                    }
                }

                if (
                    data.title.isNotBlank() ||
                    data.firstName.isNotBlank() ||
                    data.middleName.isNotBlank() ||
                    data.lastName.isNotBlank() ||
                    data.company.isNotBlank() ||
                    data.email.isNotBlank() ||
                    data.phone.isNotBlank() ||
                    data.username.isNotBlank() ||
                    data.address1.isNotBlank() ||
                    data.address2.isNotBlank() ||
                    data.address3.isNotBlank() ||
                    data.city.isNotBlank() ||
                    data.stateProvince.isNotBlank() ||
                    data.postalCode.isNotBlank() ||
                    data.country.isNotBlank() ||
                    data.ssn.isNotBlank() ||
                    data.passportNumber.isNotBlank() ||
                    data.licenseNumber.isNotBlank() ||
                    data.additionalInfo.isNotBlank()
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(text = stringResource(R.string.extended_fields_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (data.title.isNotBlank()) InfoField(label = stringResource(R.string.document_title_prefix_label), value = data.title)
                            if (data.company.isNotBlank()) InfoField(label = stringResource(R.string.document_company_label), value = data.company)
                            if (data.email.isNotBlank()) InfoField(label = stringResource(R.string.email), value = data.email)
                            if (data.phone.isNotBlank()) InfoField(label = stringResource(R.string.document_phone_label), value = data.phone)
                            if (data.username.isNotBlank()) InfoField(label = stringResource(R.string.username), value = data.username)
                            if (data.address1.isNotBlank()) InfoField(label = stringResource(R.string.document_address_line_1), value = data.address1)
                            if (data.address2.isNotBlank()) InfoField(label = stringResource(R.string.document_address_line_2), value = data.address2)
                            if (data.address3.isNotBlank()) InfoField(label = stringResource(R.string.document_address_line_3), value = data.address3)
                            if (data.city.isNotBlank()) InfoField(label = stringResource(R.string.city), value = data.city)
                            if (data.stateProvince.isNotBlank()) InfoField(label = stringResource(R.string.state), value = data.stateProvince)
                            if (data.postalCode.isNotBlank()) InfoField(label = stringResource(R.string.postal_code), value = data.postalCode)
                            if (data.country.isNotBlank()) InfoField(label = stringResource(R.string.country), value = data.country)
                            if (data.ssn.isNotBlank()) InfoField(label = stringResource(R.string.document_ssn_label), value = data.ssn)
                            if (data.passportNumber.isNotBlank()) InfoField(label = stringResource(R.string.document_passport_number_label), value = data.passportNumber)
                            if (data.licenseNumber.isNotBlank()) InfoField(label = stringResource(R.string.document_license_number_label), value = data.licenseNumber)
                            if (data.additionalInfo.isNotBlank()) InfoField(label = stringResource(R.string.document_additional_info_label), value = data.additionalInfo)
                        }
                    }
                }

                if (data.customFields.isNotEmpty()) {
                    DocumentCustomFieldsCard(
                        fields = CardWalletDataCodec.customFieldsToDisplay(data.customFields),
                        context = context,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Images
                if (frontBitmap != null || backBitmap != null) {
                     Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.document_images),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                             frontBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = stringResource(R.string.front_image),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { showFrontImageDialog = true },
                                    contentScale = ContentScale.Crop
                                )
                            }
                            backBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = stringResource(R.string.back_image),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { showBackImageDialog = true },
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                }
                
                // Notes
                if (!documentItem?.notes.isNullOrBlank()) {
                     Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                             Text(
                                text = stringResource(R.string.notes),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = documentItem?.notes ?: "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                documentItem?.let { item ->
                    AttachmentsDetailSection(
                        owner = AttachmentOwner.secureItem(item.id),
                        bitwardenContext = attachmentBitwardenContext,
                        keepassContext = attachmentKeePassContext,
                        hideManagedCardFaces = true,
                        excludedFileNames = KeePassSecureItemPhotoAttachments.managedFileNames(ItemType.DOCUMENT) +
                            listOfNotNull(data.cardFace?.imageAttachmentName)
                    )
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
    
    if (showCardFaceCustomizer && documentItem != null && documentData != null) {
        val item = requireNotNull(documentItem)
        val data = requireNotNull(documentData)
        CardFaceCustomizer(
            title = item.title,
            previewData = documentCardFacePreviewData(item.title, data),
            initialConfig = data.cardFace,
            initialBitmap = cardFaceBitmap,
            isSaving = isSavingCardFace,
            imageSelectionAllowed = cardFaceImageAllowed,
            imageSelectionWarning = if (cardFaceImageAllowed) null
                else stringResource(R.string.card_face_bitwarden_premium_required),
            onDismiss = { showCardFaceCustomizer = false },
            onApply = { edit ->
                if (!isSavingCardFace) {
                    isSavingCardFace = true
                    val updatedData = data.copy(cardFace = edit.config)
                    viewModel.saveDocumentAcrossTargets(
                        id = item.id,
                        title = item.title,
                        documentData = updatedData,
                        notes = item.notes,
                        isFavorite = item.isFavorite,
                        imagePaths = item.imagePaths,
                        targets = listOf(item.toStorageTarget()),
                        cardFaceImageBytes = edit.imageBytes,
                        onComplete = { result ->
                            if (result.isSuccess) {
                                scope.launch {
                                    documentItem = viewModel.getDocumentById(item.id)
                                    documentData = updatedData
                                    isSavingCardFace = false
                                    showCardFaceCustomizer = false
                                }
                            } else {
                                isSavingCardFace = false
                                val message = if (result.exceptionOrNull() is AttachmentError.PremiumRequired)
                                    R.string.card_face_bitwarden_premium_required else R.string.card_face_save_failed
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
                edit.imageBytes?.fill(0)
            }
        )
    }

    // Dialogs
    // Dialogs
    if (showFrontImageDialog) {
        frontBitmap?.let { bmp ->
            ImageDialog(
                bitmap = bmp, 
                onDismiss = { showFrontImageDialog = false },
                onDownload = {
                    scope.launch {
                        val item = documentItem ?: return@launch
                        try {
                            val paths = Json.decodeFromString<List<String>>(item.imagePaths)
                            if (paths.isNotEmpty() && paths[0].isNotBlank()) {
                                val success = imageManager.saveImageToGallery(paths[0], "Document_${item.title}_Front")
                                if (success) {
                                    Toast.makeText(context, context.getString(R.string.saved_to_gallery), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.photo_save_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, context.getString(R.string.photo_process_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }
    
    if (showBackImageDialog) {
        backBitmap?.let { bmp ->
            ImageDialog(
                bitmap = bmp, 
                onDismiss = { showBackImageDialog = false },
                 onDownload = {
                    scope.launch {
                        val item = documentItem ?: return@launch
                        try {
                            val paths = Json.decodeFromString<List<String>>(item.imagePaths)
                            if (paths.size > 1 && paths[1].isNotBlank()) {
                                val success = imageManager.saveImageToGallery(paths[1], "Document_${item.title}_Back")
                                if (success) {
                                    Toast.makeText(context, context.getString(R.string.saved_to_gallery), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.photo_save_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, context.getString(R.string.photo_process_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }
    
    if (showDeleteDialog) {
        val deleteMessage = if (replicaTargets.size > 1) {
            context.getString(R.string.delete_current_replica_only_message, replicaTargets.size - 1)
        } else {
            context.getString(R.string.delete_document_message)
        }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_document_title)) },
            text = { Text(deleteMessage) },
            confirmButton = {
                TextButton(
                     onClick = {
                        documentItem?.let { viewModel.deleteDocument(it.id) }
                        showDeleteDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun DocumentCustomFieldsCard(
    fields: List<takagi.ru.monica.data.CustomField>,
    context: Context,
    modifier: Modifier = Modifier
) {
    if (fields.isEmpty()) return

    val visibilityState = remember(fields) { mutableStateMapOf<Int, Boolean>() }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.custom_field_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            fields.forEachIndexed { index, field ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                val label = field.title.ifBlank { stringResource(R.string.custom_field_new_field) }
                val isVisible = visibilityState[index] ?: false

                if (field.isProtected) {
                    PasswordField(
                        label = label,
                        value = field.value,
                        visible = isVisible,
                        onToggleVisibility = {
                            visibilityState[index] = !isVisible
                        },
                        context = context
                    )
                } else {
                    InfoFieldWithCopy(
                        label = label,
                        value = field.value,
                        context = context
                    )
                }
            }
        }
    }
}

@Composable
private fun getDocumentTypeName(type: DocumentType): String {
    return when (type) {
        DocumentType.ID_CARD -> stringResource(R.string.id_card)
        DocumentType.PASSPORT -> stringResource(R.string.passport)
        DocumentType.DRIVER_LICENSE -> stringResource(R.string.drivers_license)
        DocumentType.SOCIAL_SECURITY -> stringResource(R.string.social_security_card)
        DocumentType.OTHER -> stringResource(R.string.other_document)
    }
}
