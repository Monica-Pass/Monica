package takagi.ru.monica.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import takagi.ru.monica.ui.cardwallet.CardFaceCustomizer
import takagi.ru.monica.ui.cardwallet.CardFaceDetailHeader
import takagi.ru.monica.ui.cardwallet.billingAddressCardFacePreviewData
import takagi.ru.monica.ui.cardwallet.rememberCardFaceBitmap
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.BillingAddressData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.formatForDisplay
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.ui.components.ActionStrip
import takagi.ru.monica.ui.components.ActionStripItem
import takagi.ru.monica.ui.components.InfoFieldWithCopy
import takagi.ru.monica.ui.components.PasswordField
import takagi.ru.monica.viewmodel.BillingAddressViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingAddressDetailScreen(
    viewModel: BillingAddressViewModel,
    addressId: Long,
    onNavigateBack: () -> Unit,
    onEditAddress: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allAddresses by viewModel.allBillingAddresses.collectAsState(initial = emptyList())
    var addressItem by remember { mutableStateOf<SecureItem?>(null) }
    var addressData by remember { mutableStateOf<BillingAddressData?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCardFaceCustomizer by remember { mutableStateOf(false) }
    var isSavingCardFace by remember { mutableStateOf(false) }
    val cardFaceBitmap = rememberCardFaceBitmap(addressItem, addressData?.cardFace?.imageAttachmentName, maxDimension = 1200)

    LaunchedEffect(addressId) {
        viewModel.getAddressById(addressId)?.let { item ->
            addressItem = item
            addressData = viewModel.parseAddressData(item.itemData)
        }
    }

    val replicaTargets = remember(addressItem, allAddresses) {
        val currentItem = addressItem
        if (currentItem == null) {
            emptyList()
        } else if (!currentItem.replicaGroupId.isNullOrBlank()) {
            allAddresses
                .filter { address -> address.replicaGroupId == currentItem.replicaGroupId && !address.isDeleted }
                .map { it.toStorageTarget() }
                .distinctBy(StorageTarget::stableKey)
                .ifEmpty { listOf(currentItem.toStorageTarget()) }
        } else {
            listOf(currentItem.toStorageTarget())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(addressItem?.title ?: stringResource(R.string.billing_address)) },
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
                        icon = if (addressItem?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.favorite),
                        onClick = {
                            addressItem?.let { item ->
                                viewModel.toggleFavorite(item.id)
                                addressItem = item.copy(isFavorite = !item.isFavorite)
                            }
                        },
                        tint = if (addressItem?.isFavorite == true) MaterialTheme.colorScheme.primary else null
                    ),
                    ActionStripItem(
                        icon = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        onClick = { onEditAddress(addressId) }
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
        addressData?.let { data ->
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CardFaceDetailHeader(
                    previewData = billingAddressCardFacePreviewData(addressItem?.title.orEmpty(), data),
                    config = data.cardFace,
                    bitmap = cardFaceBitmap,
                    enabled = !isSavingCardFace,
                    onClick = { showCardFaceCustomizer = true }
                )

                DetailCard(title = stringResource(R.string.billing_address)) {
                    if (data.fullName.isNotBlank()) {
                        InfoFieldWithCopy(label = stringResource(R.string.full_name), value = data.fullName, context = context)
                    }
                    if (data.company.isNotBlank()) {
                        InfoFieldWithCopy(label = stringResource(R.string.document_company_label), value = data.company, context = context)
                    }
                    if (data.streetAddress.isNotBlank()) {
                        InfoFieldWithCopy(label = stringResource(R.string.street_address), value = data.streetAddress, context = context)
                    }
                    if (data.apartment.isNotBlank()) {
                        InfoFieldWithCopy(label = stringResource(R.string.apartment), value = data.apartment, context = context)
                    }
                    if (data.city.isNotBlank()) {
                        InfoFieldWithCopy(label = stringResource(R.string.city), value = data.city, context = context)
                    }
                    if (data.stateProvince.isNotBlank()) {
                        InfoFieldWithCopy(label = stringResource(R.string.state_province), value = data.stateProvince, context = context)
                    }
                    if (data.postalCode.isNotBlank()) {
                        InfoFieldWithCopy(label = stringResource(R.string.postal_code), value = data.postalCode, context = context)
                    }
                    if (data.country.isNotBlank()) {
                        InfoFieldWithCopy(label = stringResource(R.string.country), value = data.country, context = context)
                    }
                }

                if (data.email.isNotBlank() || data.phone.isNotBlank()) {
                    DetailCard(title = stringResource(R.string.billing_address_contact_title)) {
                        if (data.email.isNotBlank()) {
                            InfoFieldWithCopy(label = stringResource(R.string.email), value = data.email, context = context)
                        }
                        if (data.phone.isNotBlank()) {
                            InfoFieldWithCopy(label = stringResource(R.string.phone), value = data.phone, context = context)
                        }
                    }
                }

                if (data.customFields.isNotEmpty()) {
                    BillingAddressCustomFieldsCard(
                        fields = CardWalletDataCodec.customFieldsToDisplay(data.customFields),
                        context = context,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!addressItem?.notes.isNullOrBlank()) {
                    DetailCard(title = stringResource(R.string.notes)) {
                        Text(
                            text = addressItem?.notes.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    if (showCardFaceCustomizer && addressItem != null && addressData != null) {
        val item = requireNotNull(addressItem)
        val data = requireNotNull(addressData)
        CardFaceCustomizer(
            title = item.title,
            previewData = billingAddressCardFacePreviewData(item.title, data),
            initialConfig = data.cardFace,
            initialBitmap = cardFaceBitmap,
            isSaving = isSavingCardFace,
            onDismiss = { showCardFaceCustomizer = false },
            onApply = { edit ->
                if (isSavingCardFace) {
                    edit.imageBytes?.fill(0)
                } else {
                    isSavingCardFace = true
                    scope.launch {
                        try {
                            val updated = data.copy(cardFace = edit.config)
                            viewModel.updateAddress(
                                id = item.id, title = item.title, addressData = updated,
                                notes = item.notes, isFavorite = item.isFavorite, imagePaths = item.imagePaths,
                                categoryId = item.categoryId, mdbxDatabaseId = item.mdbxDatabaseId,
                                mdbxFolderId = item.mdbxFolderId, replicaGroupId = item.replicaGroupId,
                                cardFaceImageBytes = edit.imageBytes
                            ).await()
                            addressItem = viewModel.getAddressById(item.id)
                            addressData = updated
                            showCardFaceCustomizer = false
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(context, R.string.card_face_save_failed, android.widget.Toast.LENGTH_LONG).show()
                        } finally {
                            edit.imageBytes?.fill(0)
                            isSavingCardFace = false
                        }
                    }
                }
            }
        )
    }

    if (showDeleteDialog) {
        val deleteMessage = if (replicaTargets.size > 1) {
            context.getString(R.string.delete_current_replica_only_message, replicaTargets.size - 1)
        } else {
            context.getString(R.string.delete_billing_address_message)
        }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_billing_address_title)) },
            text = { Text(deleteMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        addressItem?.let { viewModel.deleteAddress(it.id) }
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
private fun DetailCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun BillingAddressCustomFieldsCard(
    fields: List<CustomField>,
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
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
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
