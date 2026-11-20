package takagi.ru.monica.ui.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.zxing.BarcodeFormat
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.QrScannerScreen

/** A null authenticator callback keeps dedicated passkey shortcuts limited to FIDO. */
@Composable
internal fun UnifiedQrScannerScreen(
    onNavigateBack: () -> Unit,
    onAuthenticatorScanned: ((String) -> Unit)? = null,
    diagnosticLabel: String? = null,
    onDiagnostic: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    QrScannerScreen(
        onQrCodeScanned = { value ->
            when (UnifiedQrRouter.classify(value)) {
                CredentialQrKind.AUTHENTICATOR -> onAuthenticatorScanned?.invoke(value)
                CredentialQrKind.PASSKEY -> {
                    launchSystemFidoQrIntent(context, value)?.let {
                        Toast.makeText(context, context.getString(it), Toast.LENGTH_LONG).show()
                    }
                    onNavigateBack()
                }
                CredentialQrKind.UNSUPPORTED -> Unit
            }
        },
        onNavigateBack = onNavigateBack,
        subtitle = stringResource(if (onAuthenticatorScanned != null) R.string.qr_credentials_hint else R.string.passkey_scan_qr_menu_title),
        allowedFormats = listOf(BarcodeFormat.QR_CODE),
        resultValidator = { value ->
            when (UnifiedQrRouter.classify(value)) {
                CredentialQrKind.AUTHENTICATOR -> onAuthenticatorScanned != null
                CredentialQrKind.PASSKEY -> true
                CredentialQrKind.UNSUPPORTED -> false
            }
        },
        invalidResultMessage = stringResource(if (onAuthenticatorScanned != null) R.string.qr_credentials_invalid else R.string.passkey_qr_invalid),
        requireSingleResult = true,
        diagnosticLabel = diagnosticLabel,
        onDiagnostic = onDiagnostic
    )
}

internal fun launchSystemFidoQrIntent(context: Context, raw: String): Int? {
    val value = FidoQrCode.normalizedOrNull(raw) ?: return R.string.passkey_qr_invalid
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        null
    } catch (_: Exception) {
        R.string.passkey_qr_open_failed
    }
}
