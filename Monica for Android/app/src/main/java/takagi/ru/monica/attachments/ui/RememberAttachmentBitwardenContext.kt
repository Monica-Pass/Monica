package takagi.ru.monica.attachments.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SessionManager

/** Preparing an authenticated client reads Room; never do it in composition/remember. */
@Composable
internal fun rememberAttachmentBitwardenContext(
    vault: BitwardenVault?,
    cipherId: String?,
): AttachmentFacade.BitwardenContext? {
    val app = LocalContext.current.applicationContext
    val repository = remember(app) { BitwardenRepository.getInstance(app) }
    return rememberAttachmentBitwardenContext(vault, cipherId, repository)
}

@Composable
internal fun rememberAttachmentBitwardenContext(
    vault: BitwardenVault?,
    cipherId: String?,
    repository: BitwardenRepository,
): AttachmentFacade.BitwardenContext? {
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val appGeneration by SessionManager.lockGeneration.collectAsState()
    if (vault == null || cipherId.isNullOrBlank() || !unlocked || vault.isLocked) return null
    val vaultGeneration by repository.getVaultSessionGenerationFlow(vault.id).collectAsState()
    // A new owner/session gets an empty state immediately, before its effect starts.
    return key(repository, vault, cipherId, appGeneration, vaultGeneration) {
        var value by remember { mutableStateOf<AttachmentFacade.BitwardenContext?>(null) }
        LaunchedEffect(Unit) {
            var prepared: AttachmentFacade.BitwardenContext? = null
            try {
                prepared = repository.getAttachmentBitwardenContext(vault, cipherId)
                currentCoroutineContext().ensureActive()
                if (!SessionManager.isUnlocked.value ||
                    SessionManager.lockGeneration.value != appGeneration ||
                    repository.getVaultSessionGeneration(vault.id) != vaultGeneration ||
                    !repository.isVaultUnlocked(vault.id)) return@LaunchedEffect
                value = prepared
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Attachment actions retain their existing unavailable/locked handling.
                Log.w("BitwardenAttachmentContext", "Context unavailable: ${error.javaClass.simpleName}")
            } finally {
                value = null
                prepared?.wrappingKey?.clear()
            }
        }
        value
    }
}
