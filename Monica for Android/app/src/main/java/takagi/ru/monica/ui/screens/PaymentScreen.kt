package takagi.ru.monica.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.ImageDialog
import takagi.ru.monica.ui.components.rememberDonationQrBitmap
import takagi.ru.monica.ui.components.rememberDonationQrSaver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentScreen(
    onNavigateBack: () -> Unit,
    onActivatePlus: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var isFreeDonation by remember { mutableStateOf(false) }
    var showUnlockConfirmation by rememberSaveable { mutableStateOf(false) }
    val qrResource = if (isFreeDonation) R.drawable.support_author_qr_free else R.drawable.support_author_qr
    val qrBitmap = rememberDonationQrBitmap(qrResource)
    val saveQrImage = rememberDonationQrSaver()
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    previewBitmap?.let { bitmap ->
        ImageDialog(bitmap = bitmap, onDismiss = { previewBitmap = null }, onDownload = { saveQrImage(bitmap) })
    }
    
    if (showUnlockConfirmation) {
        AlertDialog(
            onDismissRequest = { showUnlockConfirmation = false },
            title = { Text(stringResource(R.string.monica_plus_title)) },
            text = { Text(stringResource(R.string.payment_unlock_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    if (showUnlockConfirmation) {
                        showUnlockConfirmation = false
                        onActivatePlus()
                        onNavigateBack()
                    }
                }) {
                    Text(stringResource(R.string.payment_unlock_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.payment_page_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Thank you text
            Text(
                text = stringResource(R.string.plus_thank_you),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            // QR Code Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.payment_qr_code_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Free Donation Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.free_donation_toggle),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = isFreeDonation,
                            onCheckedChange = { isFreeDonation = it }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // QR Code Image
                    qrBitmap?.let { bitmap -> Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.payment_qr_code_title),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .clickable { previewBitmap = bitmap },
                        contentScale = ContentScale.Fit
                    ) } ?: Box(Modifier.fillMaxWidth().aspectRatio(2f))
                }
            }
            
            // Payment Links Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.plus_payment_links_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Ko-fi Link
                    PaymentLinkButton(
                        platform = "Ko-fi",
                        url = "https://ko-fi.com/joyinjoester",
                        icon = Icons.Default.Coffee,
                        context = context,
                        snackbarHostState = snackbarHostState
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Afdian Link
                    PaymentLinkButton(
                        platform = stringResource(R.string.payment_platform_afdian),
                        url = "https://afdian.com/a/JoyinJoester/plan",
                        icon = Icons.Default.Favorite,
                        context = context,
                        snackbarHostState = snackbarHostState
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // PayPal Link
                    PaymentLinkButton(
                        platform = stringResource(R.string.payment_platform_paypal),
                        url = "https://www.paypal.com/ncp/payment/BHSYWK73CA8FW",
                        icon = Icons.Default.AccountBalanceWallet,
                        context = context,
                        snackbarHostState = snackbarHostState
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    PaymentLinkButton(
                        platform = stringResource(R.string.payment_platform_liberapay),
                        url = "https://liberapay.com/JoyinJoester",
                        icon = Icons.Default.Euro,
                        context = context,
                        snackbarHostState = snackbarHostState
                    )
                }
            }
            
            // Activation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.plus_activation_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showUnlockConfirmation = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.payment_paid_button))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PaymentLinkButton(
    platform: String,
    url: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    context: Context,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    
    FilledTonalButton(
        onClick = {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            } catch (e: Exception) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(platform, url)
                clipboard.setPrimaryClip(clip)
                
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.error_opening_link),
                        duration = SnackbarDuration.Short
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(platform, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
    }
}
