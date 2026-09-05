package takagi.ru.monica.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import takagi.ru.monica.R
import takagi.ru.monica.ui.cardwallet.CardFaceDetailHeader
import takagi.ru.monica.ui.cardwallet.bankCardFacePreviewData
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.ui.AttachmentsDetailSection
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.formatForDisplay
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.keepass.KeePassSecureItemPhotoAttachments
import takagi.ru.monica.ui.components.ActionStrip
import takagi.ru.monica.ui.components.ActionStripItem
import takagi.ru.monica.ui.components.BankCardCard
import takagi.ru.monica.ui.cardwallet.CardFaceCustomizer
import takagi.ru.monica.ui.cardwallet.CardFaceEditResult
import takagi.ru.monica.ui.cardwallet.rememberCardFaceBitmap
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.util.ImageManager
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.ui.components.InfoFieldWithCopy
import takagi.ru.monica.ui.components.PasswordField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankCardDetailScreen(
    viewModel: BankCardViewModel,
    cardId: Long,
    onNavigateBack: () -> Unit,
    onEditCard: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val database = remember { PasswordDatabase.getDatabase(context) }
    val scrollState = rememberScrollState()
    val allCards by viewModel.allCards.collectAsState(initial = emptyList())
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    
    var cardItem by remember { mutableStateOf<SecureItem?>(null) }
    var cardData by remember { mutableStateOf<BankCardData?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var cvvVisible by remember { mutableStateOf(false) }
    var showCardFaceCustomizer by remember { mutableStateOf(false) }
    var isSavingCardFace by remember { mutableStateOf(false) }
    
    // 图片相关状态
    var frontImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var backImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fullScreenImage by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    val imageManager = remember { ImageManager(context) }
    
    // Load card details and images
    LaunchedEffect(cardId) {
        viewModel.getCardById(cardId)?.let { item ->
            cardItem = item
            cardData = viewModel.parseCardData(item.itemData)

            if (item.keepassDatabaseId != null && !item.keepassEntryUuid.isNullOrBlank()) {
                launch(Dispatchers.IO) {
                    runCatching {
                        AttachmentContainer.keepassReconciler(context).reconcile(
                            owner = AttachmentOwner.secureItem(item.id),
                            databaseId = item.keepassDatabaseId,
                            entryUuid = item.keepassEntryUuid,
                            excludedFileNames = KeePassSecureItemPhotoAttachments.managedFileNames(ItemType.BANK_CARD)
                        )
                    }.onFailure { error ->
                        android.util.Log.w(
                            "BankCardDetailScreen",
                            "KeePass attachment metadata reconcile failed: ${error::class.simpleName}"
                        )
                    }
                }
            }
            
            // 加载图片
            if (item.imagePaths.isNotBlank()) {
                try {
                    val pathsList = Json.decodeFromString<List<String>>(item.imagePaths)
                    withContext(Dispatchers.IO) {
                        if (pathsList.isNotEmpty() && pathsList[0].isNotBlank()) {
                            frontImageBitmap = imageManager.loadImage(pathsList[0])
                        }
                        if (pathsList.size > 1 && pathsList[1].isNotBlank()) {
                            backImageBitmap = imageManager.loadImage(pathsList[1])
                        }
                    }
                } catch (e: Exception) {
                    // 忽略解析错误
                }
            }
        }
    }
    val replicaTargets = remember(cardItem, allCards) {
        val currentItem = cardItem
        if (currentItem == null) {
            emptyList()
        } else if (!currentItem.replicaGroupId.isNullOrBlank()) {
            allCards
                .filter { card -> card.replicaGroupId == currentItem.replicaGroupId && !card.isDeleted }
                .map { it.toStorageTarget() }
                .distinctBy(StorageTarget::stableKey)
                .ifEmpty { listOf(currentItem.toStorageTarget()) }
        } else {
            listOf(currentItem.toStorageTarget())
        }
    }
    val attachmentBitwardenVault = remember(cardItem?.bitwardenVaultId, bitwardenVaults) {
        cardItem?.bitwardenVaultId?.let { vaultId ->
            bitwardenVaults.firstOrNull { it.id == vaultId }
        }
    }
    val attachmentBitwardenContext = remember(
        attachmentBitwardenVault,
        cardItem?.bitwardenCipherId
    ) {
        attachmentBitwardenVault?.let { vault ->
            viewModel.getAttachmentBitwardenContext(vault, cardItem?.bitwardenCipherId)
        }
    }
    val attachmentKeePassContext = remember(cardItem?.keepassDatabaseId, cardItem?.keepassEntryUuid) {
        val databaseId = cardItem?.keepassDatabaseId
        val entryUuid = cardItem?.keepassEntryUuid?.takeIf { it.isNotBlank() }
        if (databaseId != null && entryUuid != null) {
            AttachmentFacade.KeePassContext(databaseId = databaseId, entryUuid = entryUuid)
        } else {
            null
        }
    }
    val cardFaceBitmap = rememberCardFaceBitmap(
        item = cardItem,
        imageAttachmentName = cardData?.cardFace?.imageAttachmentName,
        maxDimension = 1200
    )
    val cardFaceImageAllowed = attachmentBitwardenVault?.let { vault ->
        BitwardenVaultPremiumStore.isPremium(context, vault.id)
    } ?: true
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cardItem?.title ?: stringResource(R.string.bank_card_details)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ActionStrip(
                actions = listOf(
                    ActionStripItem(
                        icon = if (cardItem?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.favorite),
                        onClick = {
                            cardItem?.let { item ->
                                viewModel.toggleFavorite(item.id)
                                cardItem = item.copy(isFavorite = !item.isFavorite)
                            }
                        },
                        tint = if (cardItem?.isFavorite == true) MaterialTheme.colorScheme.primary else null
                    ),
                    ActionStripItem(
                        icon = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        onClick = { onEditCard(cardId) }
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
        cardItem?.let { item ->
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                cardData?.let { data ->
                    CardFaceDetailHeader(
                        previewData = bankCardFacePreviewData(item.title, data),
                        config = data.cardFace,
                        bitmap = cardFaceBitmap,
                        enabled = !isSavingCardFace,
                        onClick = { showCardFaceCustomizer = true }
                    )
                }

                cardData?.let { data ->
                    // Card Details Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.card_details),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            
                            // Card Number
                            InfoFieldWithCopy(
                                label = stringResource(R.string.card_number),
                                value = data.cardNumber.chunked(4).joinToString(" "),
                                copyValue = data.cardNumber,
                                context = context
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Expiry
                                Box(modifier = Modifier.weight(1f)) {
                                    InfoFieldWithCopy(
                                        label = stringResource(R.string.expiry_date),
                                        value = "${data.expiryMonth}/${data.expiryYear}",
                                        context = context
                                    )
                                }
                                
                                // CVV
                                Box(modifier = Modifier.weight(1f)) {
                                    PasswordField(
                                        label = stringResource(R.string.cvv),
                                        value = data.cvv,
                                        visible = cvvVisible,
                                        onToggleVisibility = { cvvVisible = !cvvVisible },
                                        context = context
                                    )
                                }
                            }
                            
                            // Cardholder
                            InfoFieldWithCopy(
                                label = stringResource(R.string.card_holder),
                                value = data.cardholderName,
                                context = context
                            )
                            
                            // Bank Name
                            if (data.bankName.isNotEmpty()) {
                                InfoFieldWithCopy(
                                    label = stringResource(R.string.bank_name),
                                    value = data.bankName,
                                    context = context
                                )
                            }
                        }
                    }
                    
                    // Billing Address
                    if (data.billingAddress.isNotEmpty()) {
                        val billingAddress = CardWalletDataCodec.parseBillingAddress(data.billingAddress)
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
                                Text(
                                    text = stringResource(R.string.billing_address),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = billingAddress.formatForDisplay(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                             }
                         }
                    }

                    if (
                        data.brand.isNotBlank() ||
                        data.nickname.isNotBlank() ||
                        data.validFromMonth.isNotBlank() ||
                        data.validFromYear.isNotBlank() ||
                        data.iban.isNotBlank() ||
                        data.swiftBic.isNotBlank() ||
                        data.routingNumber.isNotBlank() ||
                        data.accountNumber.isNotBlank() ||
                        data.branchCode.isNotBlank() ||
                        data.currency.isNotBlank() ||
                        data.customerServicePhone.isNotBlank() ||
                        data.pin.isNotBlank()
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
                                Text(
                                    text = stringResource(R.string.extended_fields_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (data.nickname.isNotBlank()) {
                                    InfoFieldWithCopy(label = stringResource(R.string.bank_card_nickname_label), value = data.nickname, context = context)
                                }
                                if (data.brand.isNotBlank()) {
                                    InfoFieldWithCopy(label = stringResource(R.string.bank_card_brand_label), value = data.brand, context = context)
                                }
                                if (data.validFromMonth.isNotBlank() || data.validFromYear.isNotBlank()) {
                                    InfoFieldWithCopy(
                                        label = stringResource(R.string.bank_card_valid_from_date),
                                        value = listOf(data.validFromMonth, data.validFromYear).filter { it.isNotBlank() }.joinToString("/"),
                                        context = context
                                    )
                                }
                                if (data.iban.isNotBlank()) InfoFieldWithCopy(label = "IBAN", value = data.iban, context = context)
                                if (data.swiftBic.isNotBlank()) InfoFieldWithCopy(label = "SWIFT / BIC", value = data.swiftBic, context = context)
                                if (data.accountNumber.isNotBlank()) InfoFieldWithCopy(label = stringResource(R.string.bank_card_account_number_label), value = data.accountNumber, context = context)
                                if (data.routingNumber.isNotBlank()) InfoFieldWithCopy(label = stringResource(R.string.bank_card_routing_number_label), value = data.routingNumber, context = context)
                                if (data.branchCode.isNotBlank()) InfoFieldWithCopy(label = stringResource(R.string.bank_card_branch_code_label), value = data.branchCode, context = context)
                                if (data.currency.isNotBlank()) InfoFieldWithCopy(label = stringResource(R.string.bank_card_currency_label), value = data.currency, context = context)
                                if (data.customerServicePhone.isNotBlank()) InfoFieldWithCopy(label = stringResource(R.string.bank_card_customer_service_phone_label), value = data.customerServicePhone, context = context)
                                if (data.pin.isNotBlank()) {
                                    PasswordField(
                                        label = stringResource(R.string.bank_card_pin_label),
                                        value = data.pin,
                                        visible = cvvVisible,
                                        onToggleVisibility = { cvvVisible = !cvvVisible },
                                        context = context
                                    )
                                }
                            }
                        }
                    }

                    if (data.customFields.isNotEmpty()) {
                        BankCardCustomFieldsCard(
                            fields = CardWalletDataCodec.customFieldsToDisplay(data.customFields),
                            context = context,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                // Notes
                if (item.notes.isNotEmpty()) {
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
                                text = item.notes,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                // 银行卡照片
                if (frontImageBitmap != null || backImageBitmap != null) {
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
                            Text(
                                text = stringResource(R.string.card_photos),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // 正面照片
                                frontImageBitmap?.let { bitmap ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1.6f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { fullScreenImage = bitmap }
                                    ) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = stringResource(R.string.card_front),
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(4.dp),
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.card_front),
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                
                                // 背面照片
                                backImageBitmap?.let { bitmap ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1.6f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { fullScreenImage = bitmap }
                                    ) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = stringResource(R.string.card_back),
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(4.dp),
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.card_back),
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                AttachmentsDetailSection(
                    owner = AttachmentOwner.secureItem(item.id),
                    bitwardenContext = attachmentBitwardenContext,
                    keepassContext = attachmentKeePassContext,
                    hideManagedCardFaces = true,
                    excludedFileNames = KeePassSecureItemPhotoAttachments.managedFileNames(ItemType.BANK_CARD) +
                        listOfNotNull(cardData?.cardFace?.imageAttachmentName)
                )

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    if (showCardFaceCustomizer && cardItem != null && cardData != null) {
        val item = requireNotNull(cardItem)
        val data = requireNotNull(cardData)
        CardFaceCustomizer(
            title = item.title,
            previewData = bankCardFacePreviewData(item.title, data),
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
                    viewModel.saveCardAcrossTargets(
                        id = item.id,
                        title = item.title,
                        cardData = updatedData,
                        notes = item.notes,
                        isFavorite = item.isFavorite,
                        imagePaths = item.imagePaths,
                        targets = listOf(item.toStorageTarget()),
                        cardFaceImageBytes = edit.imageBytes,
                        onComplete = { result ->
                            if (result.isSuccess) {
                                scope.launch {
                                    cardItem = viewModel.getCardById(item.id)
                                    cardData = updatedData
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

    // 全屏图片查看对话框（支持双指缩放）
    fullScreenImage?.let { bitmap ->
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        
        val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(0.5f, 5f)
            offset += panChange
        }
        
        Dialog(onDismissRequest = { fullScreenImage = null }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))
                    .clickable { 
                        // 只有在未缩放时点击才关闭
                        if (scale <= 1.05f) {
                            fullScreenImage = null 
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                        .transformable(state = transformableState),
                    contentScale = ContentScale.Fit
                )
                
                // 关闭按钮
                IconButton(
                    onClick = { fullScreenImage = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // 重置缩放按钮（当缩放时显示）
                if (scale > 1.05f) {
                    FilledTonalButton(
                        onClick = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                    ) {
                        Text(stringResource(R.string.reset_zoom))
                    }
                }
            }
        }
    }
    
    if (showDeleteDialog) {
        val deleteMessage = if (replicaTargets.size > 1) {
            context.getString(R.string.delete_current_replica_only_message, replicaTargets.size - 1)
        } else {
            context.getString(R.string.delete_card_message)
        }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_card_title)) },
            text = { Text(deleteMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        cardItem?.let { viewModel.deleteCard(it.id) }
                        showDeleteDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
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
private fun BankCardCustomFieldsCard(
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
