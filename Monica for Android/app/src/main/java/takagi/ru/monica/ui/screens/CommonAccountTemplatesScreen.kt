package takagi.ru.monica.ui.screens

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import takagi.ru.monica.R
import takagi.ru.monica.data.CommonAccountPreferences
import takagi.ru.monica.data.CommonAccountTemplate
import takagi.ru.monica.data.model.BillingAddress
import takagi.ru.monica.data.model.formatForDisplay
import takagi.ru.monica.data.model.isEmpty

private data class TemplateTypeStyle(
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CommonAccountTemplatesScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { CommonAccountPreferences(context) }
    val templates by preferences.templatesFlow.collectAsState(initial = emptyList())
    val billingAddress by preferences.billingAddress.collectAsState(initial = BillingAddress())

    val emailType = stringResource(R.string.common_account_type_email)
    val accountType = stringResource(R.string.common_account_type_account)
    val phoneType = stringResource(R.string.common_account_type_phone)
    val passwordType = stringResource(R.string.common_account_type_password)
    val nameType = stringResource(R.string.common_account_type_name)
    val allFilter = stringResource(R.string.filter_all)
    val typeOptions = listOf(emailType, accountType, phoneType, passwordType, nameType)

    var showEditor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingType by remember { mutableStateOf(accountType) }
    var editingContent by remember { mutableStateOf("") }
    var templateToDelete by remember { mutableStateOf<CommonAccountTemplate?>(null) }
    var selectedFilter by remember { mutableStateOf(allFilter) }
    var showBillingAddressEditor by remember { mutableStateOf(false) }
    var isResolvingLocation by remember { mutableStateOf(false) }
    var pendingLocationAddressConsumer by remember { mutableStateOf<((BillingAddress) -> Unit)?>(null) }

    fun normalizeType(raw: String): String {
        val normalized = raw.trim().lowercase(Locale.ROOT)
        return when {
            normalized == emailType.lowercase(Locale.ROOT) ||
                normalized == "email" || normalized == "邮箱" -> emailType
            normalized == accountType.lowercase(Locale.ROOT) ||
                normalized == "account" || normalized == "账号" -> accountType
            normalized == phoneType.lowercase(Locale.ROOT) ||
                normalized == "phone" || normalized == "手机号" || normalized == "电话" -> phoneType
            normalized == passwordType.lowercase(Locale.ROOT) ||
                normalized == "password" || normalized == "密码" -> passwordType
            normalized == nameType.lowercase(Locale.ROOT) ||
                normalized == "name" || normalized == "姓名" -> nameType
            else -> accountType
        }
    }

    fun openCreateEditor(type: String = accountType) {
        editingId = null
        editingType = normalizeType(type)
        editingContent = ""
        showEditor = true
    }

    fun openEditEditor(template: CommonAccountTemplate) {
        editingId = template.id
        editingType = normalizeType(template.type)
        editingContent = template.content
        showEditor = true
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun resolvePendingLocationAddress() {
        val consumer = pendingLocationAddressConsumer ?: return
        scope.launch {
            isResolvingLocation = true
            try {
                val resolvedAddress = resolveBillingAddressFromCurrentLocation(context)
                if (resolvedAddress == null || resolvedAddress.isEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.common_account_billing_location_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    consumer(resolvedAddress)
                    Toast.makeText(
                        context,
                        context.getString(R.string.common_account_billing_location_filled),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                pendingLocationAddressConsumer = null
                isResolvingLocation = false
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            resolvePendingLocationAddress()
        } else {
            pendingLocationAddressConsumer = null
            Toast.makeText(
                context,
                context.getString(R.string.common_account_billing_location_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun requestBillingAddressFromLocation(onResolved: (BillingAddress) -> Unit) {
        pendingLocationAddressConsumer = onResolved
        if (hasLocationPermission()) {
            resolvePendingLocationAddress()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val filteredTemplates = remember(templates, selectedFilter, allFilter) {
        if (selectedFilter == allFilter) {
            templates
        } else {
            templates.filter { normalizeType(it.type) == selectedFilter }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.common_account_templates_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openCreateEditor() },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.common_account_template_add)) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                BillingAddressSummaryCard(
                    billingAddress = billingAddress,
                    isResolvingLocation = isResolvingLocation,
                    onEdit = { showBillingAddressEditor = true },
                    onUseLocation = {
                        requestBillingAddressFromLocation { address ->
                            scope.launch {
                                preferences.setBillingAddress(address)
                            }
                        }
                    },
                    onRemove = {
                        scope.launch {
                            preferences.setBillingAddress(BillingAddress())
                            Toast.makeText(
                                context,
                                context.getString(R.string.billing_address_removed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedFilter == allFilter,
                        onClick = { selectedFilter = allFilter },
                        label = { Text(allFilter) }
                    )
                    typeOptions.forEach { type ->
                        FilterChip(
                            selected = selectedFilter == type,
                            onClick = { selectedFilter = type },
                            label = { Text(type) },
                            leadingIcon = {
                                Icon(
                                    imageVector = when (type) {
                                        emailType -> Icons.Default.Email
                                        phoneType -> Icons.Default.Phone
                                        passwordType -> Icons.Default.Lock
                                        nameType -> Icons.Default.Person
                                        else -> Icons.Default.Person
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.common_account_templates_count, filteredTemplates.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (templates.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.common_account_templates_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.common_account_templates_empty_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = { openCreateEditor() }) {
                                Text(stringResource(R.string.common_account_template_add))
                            }
                        }
                    }
                }
            } else if (filteredTemplates.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.no_results),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(items = filteredTemplates, key = { it.id }) { template ->
                    CommonAccountTemplateCard(
                        template = template,
                        emailType = emailType,
                        accountType = accountType,
                        phoneType = phoneType,
                        passwordType = passwordType,
                        nameType = nameType,
                        onEdit = { openEditEditor(template) },
                        onDelete = { templateToDelete = template }
                    )
                }
            }
        }
    }

    if (showBillingAddressEditor) {
        BillingAddressEditorDialog(
            billingAddress = billingAddress,
            isResolvingLocation = isResolvingLocation,
            onDismiss = { showBillingAddressEditor = false },
            onUseLocation = { applyAddress ->
                requestBillingAddressFromLocation(applyAddress)
            },
            onSave = { address ->
                scope.launch {
                    preferences.setBillingAddress(address)
                    Toast.makeText(
                        context,
                        if (address.isEmpty()) {
                            context.getString(R.string.billing_address_removed)
                        } else {
                            context.getString(R.string.billing_address_saved)
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    showBillingAddressEditor = false
                }
            }
        )
    }

    if (showEditor) {
        val isEditMode = editingId != null
        val isValid = editingContent.isNotBlank()

        ModalBottomSheet(
            onDismissRequest = { showEditor = false },
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            val style = templateTypeStyle(
                type = editingType,
                emailType = emailType,
                phoneType = phoneType,
                passwordType = passwordType,
                nameType = nameType
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(style.containerColor, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = style.icon,
                            contentDescription = null,
                            tint = style.contentColor
                        )
                    }
                    Column {
                        Text(
                            text = if (isEditMode) {
                                stringResource(R.string.common_account_template_edit)
                            } else {
                                stringResource(R.string.common_account_template_add)
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.common_account_templates_create_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.common_account_template_type),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    typeOptions.forEach { option ->
                        FilterChip(
                            selected = editingType == option,
                            onClick = { editingType = option },
                            label = { Text(option) },
                            leadingIcon = {
                                Icon(
                                    imageVector = when (option) {
                                        emailType -> Icons.Default.Email
                                        phoneType -> Icons.Default.Phone
                                        passwordType -> Icons.Default.Lock
                                        else -> Icons.Default.Person
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = editingContent,
                    onValueChange = { editingContent = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    label = { Text(stringResource(R.string.common_account_template_content)) },
                    isError = editingContent.isBlank()
                )

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.common_account_template_preview),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = editingType,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = editingContent.ifBlank { stringResource(R.string.common_account_template_content) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        onClick = { showEditor = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        enabled = isValid,
                        onClick = {
                            scope.launch {
                                val id = editingId
                                if (id == null) {
                                    preferences.addTemplate(
                                        type = normalizeType(editingType),
                                        content = editingContent
                                    )
                                } else {
                                    preferences.upsertTemplate(
                                        CommonAccountTemplate(
                                            id = id,
                                            type = normalizeType(editingType),
                                            content = editingContent
                                        )
                                    )
                                }
                                showEditor = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }

    templateToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { templateToDelete = null },
            title = { Text(stringResource(R.string.common_account_template_delete_title)) },
            text = { Text(target.content.ifBlank { stringResource(R.string.common_account_template_delete_message) }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            preferences.deleteTemplate(target.id)
                            templateToDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { templateToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun BillingAddressSummaryCard(
    billingAddress: BillingAddress,
    isResolvingLocation: Boolean,
    onEdit: () -> Unit,
    onUseLocation: () -> Unit,
    onRemove: () -> Unit
) {
    val hasAddress = !billingAddress.isEmpty()

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.common_account_billing_address_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.common_account_billing_address_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = if (hasAddress) {
                    billingAddress.formatForDisplay()
                } else {
                    stringResource(R.string.billing_address_empty)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasAddress) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasAddress) {
                            stringResource(R.string.edit_billing_address)
                        } else {
                            stringResource(R.string.add_billing_address)
                        }
                    )
                }
                OutlinedButton(
                    onClick = onUseLocation,
                    enabled = !isResolvingLocation
                ) {
                    if (isResolvingLocation) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.common_account_billing_use_location))
                }
                if (hasAddress) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.remove_billing_address))
                    }
                }
            }
        }
    }
}

@Composable
private fun BillingAddressEditorDialog(
    billingAddress: BillingAddress,
    isResolvingLocation: Boolean,
    onDismiss: () -> Unit,
    onUseLocation: ((BillingAddress) -> Unit) -> Unit,
    onSave: (BillingAddress) -> Unit
) {
    var streetAddress by remember(billingAddress) { mutableStateOf(billingAddress.streetAddress) }
    var apartment by remember(billingAddress) { mutableStateOf(billingAddress.apartment) }
    var city by remember(billingAddress) { mutableStateOf(billingAddress.city) }
    var stateProvince by remember(billingAddress) { mutableStateOf(billingAddress.stateProvince) }
    var postalCode by remember(billingAddress) { mutableStateOf(billingAddress.postalCode) }
    var country by remember(billingAddress) { mutableStateOf(billingAddress.country) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.billing_address)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onUseLocation { resolved ->
                            streetAddress = resolved.streetAddress
                            apartment = resolved.apartment
                            city = resolved.city
                            stateProvince = resolved.stateProvince
                            postalCode = resolved.postalCode
                            country = resolved.country
                        }
                    },
                    enabled = !isResolvingLocation,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isResolvingLocation) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.common_account_billing_use_location))
                }
                OutlinedTextField(
                    value = streetAddress,
                    onValueChange = { streetAddress = it },
                    label = { Text(stringResource(R.string.street_address)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apartment,
                    onValueChange = { apartment = it },
                    label = { Text(stringResource(R.string.apartment)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = city,
                        onValueChange = { city = it },
                        label = { Text(stringResource(R.string.city)) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = stateProvince,
                        onValueChange = { stateProvince = it },
                        label = { Text(stringResource(R.string.state_province)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = postalCode,
                        onValueChange = { postalCode = it },
                        label = { Text(stringResource(R.string.postal_code)) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = country,
                        onValueChange = { country = it },
                        label = { Text(stringResource(R.string.country)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        BillingAddress(
                            streetAddress = streetAddress.trim(),
                            apartment = apartment.trim(),
                            city = city.trim(),
                            stateProvince = stateProvince.trim(),
                            postalCode = postalCode.trim(),
                            country = country.trim()
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun CommonAccountTemplateCard(
    template: CommonAccountTemplate,
    emailType: String,
    accountType: String,
    phoneType: String,
    passwordType: String,
    nameType: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val normalizedType = when (template.type.trim().lowercase(Locale.ROOT)) {
        emailType.lowercase(Locale.ROOT), "email", "邮箱" -> emailType
        accountType.lowercase(Locale.ROOT), "account", "账号" -> accountType
        phoneType.lowercase(Locale.ROOT), "phone", "手机号", "电话" -> phoneType
        passwordType.lowercase(Locale.ROOT), "password", "密码" -> passwordType
        nameType.lowercase(Locale.ROOT), "name", "姓名" -> nameType
        else -> accountType
    }
    val style = templateTypeStyle(
        type = normalizedType,
        emailType = emailType,
        phoneType = phoneType,
        passwordType = passwordType,
        nameType = nameType
    )

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(),
        elevation = CardDefaults.cardElevation(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(style.containerColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = normalizedType,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Text(
                    text = template.content,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun templateTypeStyle(
    type: String,
    emailType: String,
    phoneType: String,
    passwordType: String,
    nameType: String
): TemplateTypeStyle {
    return when (type) {
        emailType -> TemplateTypeStyle(
            icon = Icons.Default.Email,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
        phoneType -> TemplateTypeStyle(
            icon = Icons.Default.Phone,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
        passwordType -> TemplateTypeStyle(
            icon = Icons.Default.Lock,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
        nameType -> TemplateTypeStyle(
            icon = Icons.Default.Person,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
        else -> TemplateTypeStyle(
            icon = Icons.Default.Person,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@SuppressLint("MissingPermission")
private suspend fun resolveBillingAddressFromCurrentLocation(context: Context): BillingAddress? {
    if (!hasAnyLocationPermission(context)) return null

    val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = runCatching {
        locationManager.getProviders(true)
            .filter { provider ->
                provider == LocationManager.GPS_PROVIDER ||
                    provider == LocationManager.NETWORK_PROVIDER ||
                    provider == LocationManager.PASSIVE_PROVIDER
            }
    }.getOrDefault(emptyList())

    if (providers.isEmpty()) return null

    val lastKnown = providers
        .mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }
        .maxByOrNull { it.time }

    var freshLocation: Location? = null
    if (lastKnown == null) {
        for (provider in providers) {
            freshLocation = requestSingleLocation(locationManager, provider)
            if (freshLocation != null) break
        }
    }
    val location = lastKnown ?: freshLocation ?: return null

    return reverseGeocodeBillingAddress(context, location)
}

private fun hasAnyLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
private suspend fun requestSingleLocation(
    locationManager: LocationManager,
    provider: String
): Location? = withTimeoutOrNull(8_000L) {
    suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (continuation.isActive) {
                    continuation.resume(location)
                }
                runCatching { locationManager.removeUpdates(this) }
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        runCatching {
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.onFailure {
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }

        continuation.invokeOnCancellation {
            runCatching { locationManager.removeUpdates(listener) }
        }
    }
}

private suspend fun reverseGeocodeBillingAddress(
    context: Context,
    location: Location
): BillingAddress? = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) return@withContext null
    val geocoder = Geocoder(context, Locale.getDefault())
    val address = runCatching {
        @Suppress("DEPRECATION")
        geocoder.getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
    }.getOrNull() ?: return@withContext null

    address.toBillingAddress().takeUnless { it.isEmpty() }
}

private fun Address.toBillingAddress(): BillingAddress {
    val streetParts = listOfNotNull(
        subThoroughfare?.trim()?.takeIf { it.isNotBlank() },
        thoroughfare?.trim()?.takeIf { it.isNotBlank() }
    )
    val streetAddress = when {
        streetParts.isNotEmpty() -> streetParts.joinToString(" ")
        !featureName.isNullOrBlank() && featureName != locality -> featureName
        else -> getAddressLine(0)
            ?.substringBefore(',')
            ?.trim()
            .orEmpty()
    }

    return BillingAddress(
        streetAddress = streetAddress,
        apartment = subLocality.orEmpty(),
        city = locality ?: subAdminArea.orEmpty(),
        stateProvince = adminArea.orEmpty(),
        postalCode = postalCode.orEmpty(),
        country = countryName.orEmpty()
    )
}
