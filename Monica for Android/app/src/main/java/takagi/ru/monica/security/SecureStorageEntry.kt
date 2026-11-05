package takagi.ru.monica.security

import android.content.ClipData
import android.content.ClipboardManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import takagi.ru.monica.ui.screens.SecureStorageRecoveryScreen
import takagi.ru.monica.ui.theme.MonicaTheme

/** The same bounded gate for every UI entry, including the independent input-method process. */
internal fun ComponentActivity.launchWithSecureStorage(onReady: () -> Unit) {
    lifecycleScope.launch {
        when (val result = SecureStorageStartup.prepareForEntry(this@launchWithSecureStorage)) {
            is SecureStartupResult.Ready -> {
                if (!isFinishing && !isDestroyed) {
                    try { onReady() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: SecureStorageUnavailableException) {
                        showSecureStorageBlocked(SecureStartupResult.Blocked(failure))
                    }
                }
            }
            is SecureStartupResult.Blocked -> showSecureStorageBlocked(result)
        }
    }
}

internal fun ComponentActivity.showSecureStorageBlocked(result: SecureStartupResult.Blocked) {
    if (isFinishing || isDestroyed) return
    SessionManager.markLocked()
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    val diagnostic = SecureStorageStartup.diagnostic(result.failure, result.attempts)
    setContent {
        MonicaTheme {
            SecureStorageRecoveryScreen(
                onRetry = { recreate() },
                onCopyDiagnostic = {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Monica diagnostic", diagnostic))
                },
                onExit = { finish() },
                onRecover = if (result.recoveryAvailable) { password ->
                    val recovered = ConfirmedStorageRecovery.recover(this, password)
                    if (recovered) recreate()
                    recovered
                } else null,
            )
        }
    }
}
