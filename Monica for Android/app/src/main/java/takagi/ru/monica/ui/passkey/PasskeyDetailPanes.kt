package takagi.ru.monica.ui

import takagi.ru.monica.ui.components.formatRelativeTime

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.ui.rememberFavicon
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.ui.components.InfoField
import takagi.ru.monica.ui.components.InfoFieldWithCopy
import takagi.ru.monica.ui.icons.UnmatchedIconFallback
import takagi.ru.monica.ui.icons.rememberAutoMatchedSimpleIcon
import java.text.DateFormat
import java.util.Date

@Composable
internal fun PasskeyOverviewPane(
    totalPasskeys: Int,
    boundPasskeys: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(14.dp)
                            .size(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.passkey_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.passkey_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.passkey_count, totalPasskeys)) }
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text("${stringResource(R.string.passkey_bound_label)} $boundPasskeys") }
                    )
                }
            }
        }
    }
}

@Composable
internal fun PasskeyDetailPane(
    passkey: PasskeyEntry,
    boundPasswordTitle: String?,
    onBindPassword: (() -> Unit)? = null,
    onChangeBinding: (() -> Unit)? = null,
    onOpenBoundPassword: (() -> Unit)?,
    onUnbindPassword: (() -> Unit)?,
    onDeletePasskey: () -> Unit,
    onEditRemark: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val portability = takagi.ru.monica.ui.components.rememberPasskeyPortability(passkey)
    val createdTime = remember(passkey.createdAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(passkey.createdAt))
    }
    val lastUsedTime = formatRelativeTime(passkey.lastUsedAt)
    val transports = remember(passkey.transports) {
        passkey.getTransportsList().joinToString(", ").ifBlank { "-" }
    }
    val displayTitle = remember(passkey.notes, passkey.userDisplayName, passkey.userName, passkey.rpName, passkey.rpId) {
        passkey.displayTitle()
    }
    val bindingLabel = boundPasswordTitle ?: stringResource(R.string.common_account_not_configured)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PasskeyHeroCard(
            passkey = passkey,
            title = displayTitle,
            hasBoundPassword = boundPasswordTitle != null,
            onEditRemark = onEditRemark
        )

        PasskeySectionCard(
            title = stringResource(R.string.passkey_detail_activity),
            icon = Icons.Default.AccessTime
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoField(
                    label = stringResource(R.string.passkey_detail_created),
                    value = createdTime
                )
                InfoField(
                    label = stringResource(R.string.passkey_detail_last_used),
                    value = lastUsedTime
                )
                InfoField(
                    label = stringResource(R.string.passkey_detail_use_count),
                    value = passkey.useCount.toString()
                )
            }
        }

        PasskeyBindingCard(
            boundPasswordTitle = bindingLabel,
            hasBoundPassword = boundPasswordTitle != null,
            onBindPassword = onBindPassword,
            onChangeBinding = onChangeBinding,
            onOpenBoundPassword = onOpenBoundPassword,
            onUnbindPassword = onUnbindPassword
        )

        PasskeySectionCard(
            title = stringResource(R.string.passkey_detail_security),
            icon = Icons.Default.Security
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoField(
                    label = stringResource(R.string.passkey_discoverable),
                    value = if (passkey.isDiscoverable) stringResource(R.string.yes) else stringResource(R.string.no)
                )
                InfoField(
                    label = stringResource(R.string.passkey_detail_user_verification),
                    value = if (passkey.isUserVerificationRequired) stringResource(R.string.yes) else stringResource(R.string.no)
                )
                InfoField(
                    label = stringResource(R.string.passkey_detail_storage),
                    value = passkey.passkeyMode.toReadableModeLabel()
                )
                takagi.ru.monica.ui.components.PasskeyPortabilityDetails(portability)
            }
        }

        PasskeySectionCard(
            title = stringResource(R.string.passkey_detail_technical),
            icon = Icons.Default.Info
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoField(label = "RP ID", value = passkey.rpId)
                InfoField(label = stringResource(R.string.passkey_detail_algorithm), value = passkey.getAlgorithmName())
                InfoField(label = stringResource(R.string.passkey_detail_transports), value = transports)
                InfoFieldWithCopy(
                    label = stringResource(R.string.legacy_ui_passkey_user_id),
                    value = passkey.userId.ifBlank { "-" },
                    context = context
                )
                InfoFieldWithCopy(
                    label = stringResource(R.string.legacy_ui_passkey_credential_id),
                    value = passkey.credentialId,
                    context = context
                )
                if (passkey.aaguid.isNotBlank()) {
                    InfoFieldWithCopy(
                        label = "AAGUID",
                        value = passkey.aaguid,
                        context = context
                    )
                }
                if (passkey.privateKeyAlias.isNotBlank()) {
                    InfoField(
                        label = stringResource(R.string.legacy_ui_passkey_private_key),
                        value = if (PasskeyPrivateKeyStore.isProtectedReference(passkey.privateKeyAlias)) {
                            stringResource(R.string.legacy_ui_passkey_protected_storage)
                        } else {
                            stringResource(R.string.legacy_ui_passkey_legacy_material)
                        }
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = stringResource(
                        R.string.passkey_delete_message,
                        displayTitle,
                        passkey.userName.ifBlank { "-" }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Button(
                    onClick = onDeletePasskey,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.passkey_delete_button))
                }
            }
        }
    }
}

@Composable
private fun PasskeyHeroCard(
    passkey: PasskeyEntry,
    title: String,
    hasBoundPassword: Boolean,
    onEditRemark: (() -> Unit)?
) {
    val website = remember(passkey.rpId) {
        passkey.rpId.trim().let { rpId ->
            when {
                rpId.isBlank() -> ""
                "://" in rpId -> rpId
                else -> "https://$rpId"
            }
        }
    }
    val simpleIcon = rememberAutoMatchedSimpleIcon(
        website = website,
        title = passkey.rpName,
        appPackageName = null,
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = true
    )
    val favicon = rememberFavicon(
        url = website,
        enabled = website.isNotBlank() && simpleIcon.resolved && simpleIcon.slug == null
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    when {
                        simpleIcon.bitmap != null -> Image(
                            bitmap = simpleIcon.bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(48.dp)
                        )
                        favicon != null -> Image(
                            bitmap = favicon,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(48.dp)
                        )
                        else -> UnmatchedIconFallback(
                            strategy = takagi.ru.monica.data.UnmatchedIconHandlingStrategy.WEBSITE_OR_TITLE_INITIAL,
                            primaryText = passkey.rpId,
                            secondaryText = passkey.rpName,
                            defaultIcon = Icons.Default.VpnKey,
                            iconSize = 42.dp
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = passkey.userDisplayName.ifBlank { passkey.userName },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = passkey.rpName.ifBlank { passkey.rpId },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = passkey.rpId,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onEditRemark != null) {
                    TextButton(onClick = onEditRemark) {
                        Text(stringResource(R.string.passkey_edit_remark))
                    }
                }
            }
        }
    }
}

@Composable
private fun PasskeyBindingCard(
    boundPasswordTitle: String,
    hasBoundPassword: Boolean,
    onBindPassword: (() -> Unit)?,
    onChangeBinding: (() -> Unit)?,
    onOpenBoundPassword: (() -> Unit)?,
    onUnbindPassword: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.bind_password),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.passkey_binding_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = boundPasswordTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!hasBoundPassword) {
                    if (onBindPassword != null) {
                        FilledTonalButton(
                            onClick = onBindPassword
                        ) {
                            Text(stringResource(R.string.bind_password))
                        }
                    }
                } else {
                    if (onOpenBoundPassword != null) {
                        FilledTonalButton(
                            onClick = onOpenBoundPassword
                        ) {
                            Text(stringResource(R.string.passkey_view_details))
                        }
                    }
                    if (onChangeBinding != null) {
                        OutlinedButton(
                            onClick = onChangeBinding
                        ) {
                            Text(stringResource(R.string.bound_password_change))
                        }
                    }
                    if (onUnbindPassword != null) {
                        OutlinedButton(
                            onClick = onUnbindPassword
                        ) {
                            Text(stringResource(R.string.unbind))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasskeySectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
private fun PasskeyMetaPill(label: String) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun String.toReadableSyncLabel(): String = when (this) {
    "SYNCED" -> stringResource(R.string.sync_status_synced_short)
    "SYNCING" -> stringResource(R.string.sync_status_syncing_short)
    "PENDING" -> stringResource(R.string.sync_status_pending_short)
    "FAILED" -> stringResource(R.string.sync_status_failed_short)
    "CONFLICT" -> stringResource(R.string.sync_conflict)
    "REFERENCE" -> stringResource(R.string.legacy_ui_passkey_reference)
    else -> stringResource(R.string.sync_status_local_short)
}

@Composable
private fun String.toReadableModeLabel(): String = when (this) {
    PasskeyEntry.MODE_BW_COMPAT -> "Bitwarden"
    PasskeyEntry.MODE_KEEPASS_COMPAT -> "KeePass"
    else -> stringResource(R.string.legacy_ui_local)
}
