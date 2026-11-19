package takagi.ru.monica.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.timeline.*
import takagi.ru.monica.utils.FieldChange

class TimelineJournalViewModel(application: Application) : AndroidViewModel(application) {
    private val db = PasswordDatabase.getDatabase(application)
    private val strings = takagi.ru.monica.utils.AppLocaleStringResolver(application)
    @Volatile private var scopeNames: Map<String, String> = emptyMap()
    private val reader = TimelineReader(db) { key ->
        timelineFieldResource(key)?.let { strings.get(it) } ?: when (key) {
            "CREATE", "UPDATE", "TRASH", "RESTORE_FIELDS", "RESTORE_TRASH", "PERMANENT_DELETE", "SYNC", "ARCHIVE", "UNARCHIVE", "MOVE", "DELETE" ->
                strings.get(takagi.ru.monica.ui.screens.actionResource(key))
            "local" -> strings.get(takagi.ru.monica.R.string.filter_monica)
            else -> scopeNames[key] ?: key
        }
    }
    data class State(val entries: List<TimelineReader.Entry> = emptyList(), val loading: Boolean = false,
        val more: Boolean = false, val error: String? = null, val detail: TimelineReader.Entry? = null,
        val plan: TimelineJournal.Plan? = null, val busy: Boolean = false, val restored: Boolean = false,
        val scopes: List<String> = emptyList())
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var filter = TimelineReader.Filter()
    private var cursor = TimelineReader.Cursor()
    private var job: Job? = null
    private var detailJob: Job? = null
    private var foreground = false
    private var epoch = 0L

    init {
        viewModelScope.launch {
            SessionManager.isUnlocked.collect { unlocked ->
                if (!unlocked) conceal() else if (foreground) reload()
            }
        }
        viewModelScope.launch {
            db.timelineDao().changes().drop(1).collect { if (foreground && mutableState.value.detail == null) reload() }
        }
    }

    fun foreground(active: Boolean) {
        foreground = active
        if (!active) conceal() else if (SessionManager.isUnlocked.value) reload()
    }
    private fun conceal() {
        epoch++
        job?.cancel(); detailJob?.cancel()
        mutableState.value = State()
        cursor = TimelineReader.Cursor()
    }
    fun filter(value: TimelineReader.Filter) { filter = value; if (foreground) reload() }
    fun scopeNames(value: Map<String, String>) {
        if (scopeNames == value) return
        scopeNames = value
        if (foreground && filter.query.isNotBlank()) reload()
    }
    fun reload() { cursor = TimelineReader.Cursor(); load(reset = true) }
    fun more() { if (!mutableState.value.loading && mutableState.value.more) load(false) }

    private fun authorized(requestEpoch: Long, generation: Long) = foreground && epoch == requestEpoch &&
        SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation

    private fun load(reset: Boolean) {
        job?.cancel()
        val requestEpoch = ++epoch
        val generation = SessionManager.lockGeneration.value
        val requestFilter = filter
        if (reset) mutableState.update { State(scopes = it.scopes, loading = true) }
        else mutableState.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val entries = if (reset) mutableListOf() else mutableState.value.entries.toMutableList()
                val initial = entries.size
                var next = cursor
                var hasMore: Boolean
                do {
                    val page = reader.page(requestFilter, next)
                    entries.addAll(page.entries)
                    next = page.cursor; hasMore = page.more
                } while (hasMore && entries.size - initial < 32)
                val scopes = withContext(Dispatchers.IO) { db.timelineDao().scopes() }
                if (authorized(requestEpoch, generation)) {
                    cursor = next
                    mutableState.update { it.copy(entries = TimelineReader.collapse(entries), scopes = scopes,
                        loading = false, more = hasMore) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (authorized(requestEpoch, generation)) mutableState.update { it.copy(loading = false, error = "UNREADABLE") } }
        }
    }

    fun open(entry: TimelineReader.Entry) {
        detailJob?.cancel(); job?.cancel()
        val requestEpoch = ++epoch
        val generation = SessionManager.lockGeneration.value
        mutableState.update { it.copy(detail = entry, busy = true, error = null, plan = null, restored = false) }
        detailJob = viewModelScope.launch {
            try {
                val full = withContext(Dispatchers.IO) {
                    if (entry.record.origin == "LEGACY") {
                        val log = requireNotNull(db.operationLogDao().getLogById(entry.record.id.removePrefix("l:").toLong()))
                        val snapshot = db.timelineVersionSnapshotDao().getByOperationLogId(log.id)
                        val snapshotValid = snapshot != null && snapshot.createdAt + TimelineJournal.RETENTION_MILLIS > System.currentTimeMillis()
                        val encoded = if (snapshotValid) SecurityManager(getApplication()).decryptTimelineSnapshot(snapshot!!.encryptedChangesJson) else log.changesJson
                        val changes = if (encoded.isBlank()) emptyList() else Json { ignoreUnknownKeys = true }.decodeFromString<List<FieldChange>>(encoded)
                        TimelineDetails(title = log.itemTitle, facts = changes.filter { !it.fieldName.startsWith("__") }.map { TimelineFact(it.fieldName, it.oldValue, it.newValue) })
                    } else db.timelineJournal.read(requireNotNull(db.timelineDao().get(entry.record.id)))
                }
                if (authorized(requestEpoch, generation)) mutableState.update { it.copy(detail = entry.copy(details = full), busy = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (authorized(requestEpoch, generation)) mutableState.update { it.copy(busy = false, error = reason(e)) } }
        }
    }

    fun back() {
        detailJob?.cancel(); epoch++
        val closingDetail = mutableState.value.plan == null
        mutableState.update { if (it.plan != null) it.copy(plan = null, error = null, busy = false)
            else it.copy(detail = null, plan = null, error = null, busy = false, restored = false) }
        if (closingDetail && foreground) reload()
    }
    fun preview() = detailAction { entry ->
        val plan = db.timelineJournal.preview(entry.record.id);
        { state: State -> state.copy(plan = plan) }
    }
    fun confirm() {
        val plan = mutableState.value.plan ?: return
        detailAction { _ ->
            db.timelineJournal.confirm(plan);
            { state: State -> state.copy(plan = null, restored = true) }
        }
    }
    private fun detailAction(block: suspend (TimelineReader.Entry) -> (State) -> State) {
        val entry = mutableState.value.detail ?: return
        if (mutableState.value.busy) return
        val requestEpoch = epoch
        val generation = SessionManager.lockGeneration.value
        mutableState.update { it.copy(busy = true, error = null) }
        detailJob = viewModelScope.launch {
            try {
                val update = block(entry)
                if (authorized(requestEpoch, generation)) mutableState.update(update)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (authorized(requestEpoch, generation)) mutableState.update { it.copy(error = reason(e), plan = null) } }
            finally { if (authorized(requestEpoch, generation)) mutableState.update { it.copy(busy = false) } }
        }
    }
    private fun reason(e: Exception): String = listOf("CONFLICT", "EXPIRED", "MISSING", "UNSUPPORTED", "LOCKED")
        .firstOrNull { e.message?.contains("TIMELINE_$it") == true } ?: "UNREADABLE"
}
