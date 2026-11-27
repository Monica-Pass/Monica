package takagi.ru.monica.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.security.SessionManager

internal data class FolderBackupUiState(
    val configured: Boolean = false, val label: String = "", val tree: String = "",
    val automatic: Boolean = false, val afterChanges: Boolean = true, val keep: Int = 10,
    val records: List<FolderBackupRecord> = emptyList(), val busy: Boolean = false, val error: Int = 0,
)

internal class FolderBackupViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(FolderBackupUiState(busy = true))
    val state = mutableState.asStateFlow()
    init { refresh() }

    fun refresh() = operation { check -> load(check) }

    fun save(tree: Uri?, password: String, automatic: Boolean, changes: Boolean, keep: Int) = operation { check ->
        val context = getApplication<Application>()
        val store = FolderBackupStore(context)
        val old = store.config()
        val selected = tree ?: Uri.parse(old.tree)
        val destination = SafBackupDestination(context, selected)
        // Existing settings (especially disabling automation) remain editable if a
        // removable drive or provider is unavailable. A new location must be validated.
        val label = if (tree != null || !old.configured) {
            destination.label().also { destination.list() }
        } else old.label
        val newPassword = password.ifEmpty { old.password }
        require(newPassword.isNotBlank())
        check()
        store.configure(selected.toString(), label, newPassword, automatic, changes, keep)
        FolderBackupScheduler.configure(context, store.config())
        load(check)
    }

    private var operation: kotlinx.coroutines.Job? = null
    private fun operation(block: suspend (() -> Unit) -> Unit) {
        if (FolderBackupRunner.running.value) return
        operation?.cancel()
        operation = viewModelScope.launch {
            val generation = SessionManager.lockGeneration.value
            val visibility = FolderBackupRunner.foregroundEpoch.get()
            val job = kotlinx.coroutines.currentCoroutineContext()
            val check = {
                job.ensureActive()
                if (!FolderBackupRunner.authorized(generation) || FolderBackupRunner.foregroundEpoch.get() != visibility)
                    throw FolderBackupDeferred()
            }
            mutableState.value = mutableState.value.copy(busy = true, error = 0)
            try {
                withContext(Dispatchers.IO) {
                    check()
                    block(check)
                    check()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = mutableState.value.copy(error = if (error is SecurityException)
                    R.string.folder_backup_permission_error else R.string.folder_backup_failed)
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    private fun load(check: () -> Unit) {
        val context = getApplication<Application>()
        val store = FolderBackupStore(context)
        val config = store.config()
        val records = store.records().filter { it.tree == config.tree }.sortedByDescending { it.createdAt }
        check()
        mutableState.value = FolderBackupUiState(config.configured, config.label, config.tree,
            config.automatic, config.afterChanges, config.keep, records)
        if (config.configured) {
            val listed = SafBackupDestination(context, Uri.parse(config.tree)).list().map { it.uri }.toSet()
            check()
            mutableState.value = mutableState.value.copy(records = records.filter { it.uri in listed })
        }
    }
}
