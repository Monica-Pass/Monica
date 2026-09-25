package takagi.ru.monica.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.ApiKeyDraft
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.security.SessionManager
import java.util.UUID

/** In-memory draft only: no credentials in saved instance state or navigation arguments. */
class ApiKeyEditorViewModel : ViewModel() {
    var draft by mutableStateOf(ApiKeyDraft())
    var targets by mutableStateOf<List<StorageTarget>>(emptyList())
    var favorite by mutableStateOf(false)
    var original by mutableStateOf<PasswordEntry?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var validationAttempted by mutableStateOf(false)
        private set
    var failure by mutableStateOf<Failure?>(null)
        private set
    private var extraFields = emptyList<CustomFieldDraft>()
    private var initialized = false
    private var loadJob: Job? = null
    private var generation = 0
    private var draftReplicaGroupId = UUID.randomUUID().toString()

    enum class Failure { LOAD, SAVE, LOCKED }

    init {
        viewModelScope.launch {
            SessionManager.isUnlocked.drop(1).collect { if (!it) clearSecrets() }
        }
    }

    fun initialize(passwords: PasswordViewModel, id: Long?, initialTarget: StorageTarget) {
        if (initialized) return
        initialized = true
        failure = null
        validationAttempted = false
        targets = listOf(initialTarget)
        if (id == null) { loaded = true; return }
        loadJob = viewModelScope.launch {
            try {
                val entry = requireNotNull(passwords.getPasswordEntryById(id))
                val fields = passwords.getCustomFieldsByEntryIdSync(id)
                original = entry
                extraFields = fields.map(CustomFieldDraft::fromCustomField)
                draft = ApiKeyDraft.from(entry, fields.associate { it.title to it.value })
                favorite = entry.isFavorite
                targets = listOf(entry.toStorageTarget())
                loaded = true
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failure = Failure.LOAD }
        }
    }

    fun save(passwords: PasswordViewModel, onSaved: (Long) -> Unit) {
        if (!loaded || saving) return
        validationAttempted = true
        if (!draft.isValid || targets.isEmpty()) return
        saving = true
        failure = null
        val saveGeneration = generation
        val requestedTargetCount = targets.distinctBy(StorageTarget::stableKey).size
        passwords.savePasswordsAcrossTargets(
            originalIds = listOfNotNull(original?.id),
            commonEntry = draft.toEntry(original).copy(isFavorite = favorite,
                replicaGroupId = original?.replicaGroupId ?: draftReplicaGroupId),
            passwords = listOf(draft.key), targets = targets,
            customFields = draft.customFields(extraFields),
            onCompleteWithIds = { id, savedIds ->
                if (saveGeneration == generation) {
                    saving = false
                    if (id == null || savedIds.size != requestedTargetCount) failure = Failure.SAVE else {
                        clearSecrets()
                        onSaved(id)
                    }
                }
            },
        )
    }

    private fun clearSecrets() {
        generation++
        draftReplicaGroupId = UUID.randomUUID().toString()
        loadJob?.cancel()
        draft = ApiKeyDraft()
        original = null
        extraFields = emptyList()
        initialized = false
        loaded = false
        saving = false
        failure = Failure.LOCKED
    }

    override fun onCleared() { clearSecrets() }
}
