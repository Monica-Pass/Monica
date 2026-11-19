package takagi.ru.monica.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.data.OperationType
import takagi.ru.monica.data.PasswordDatabase

/**
 * 操作日志记录工具类。
 *
 * Startup rule: [init] must remain cheap. Room access, Android ID reads,
 * SecurityManager construction, redaction and JSON serialization are deferred to
 * the existing IO logger scope and therefore never compete with the first frame.
 */
object OperationLogger {

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var database: PasswordDatabase? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Only publish the application context. Expensive dependencies are created
     * lazily on the logger's IO dispatcher when the first operation is emitted.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun logCreate(
        itemType: OperationLogItemType,
        itemId: Long,
        itemTitle: String,
        details: List<FieldChange> = emptyList()
    ) {
        log(itemType, itemId, itemTitle, OperationType.CREATE, details)
    }

    fun logUpdate(
        itemType: OperationLogItemType,
        itemId: Long,
        itemTitle: String,
        changes: List<FieldChange>,
        snapshotChanges: List<FieldChange> = changes
    ) {
        if (changes.isEmpty()) return
        log(
            itemType = itemType,
            itemId = itemId,
            itemTitle = itemTitle,
            operationType = OperationType.UPDATE,
            changes = changes,
            snapshotChanges = snapshotChanges
        )
    }

    fun logDelete(
        itemType: OperationLogItemType,
        itemId: Long,
        itemTitle: String,
        detail: String? = null
    ) {
        log(
            itemType = itemType,
            itemId = itemId,
            itemTitle = if (detail != null) "$itemTitle ($detail)" else itemTitle,
            operationType = OperationType.DELETE,
            changes = emptyList()
        )
    }

    fun logWebDavUpload(
        isAutomatic: Boolean,
        isPermanent: Boolean,
        details: List<FieldChange> = emptyList()
    ) {
        val triggerType = if (isAutomatic) "自动" else "手动"
        val backupType = if (isPermanent) "永久" else "临时"
        log(
            itemType = OperationLogItemType.WEBDAV_UPLOAD,
            itemId = System.currentTimeMillis(),
            itemTitle = "${triggerType}上传 · $backupType",
            operationType = OperationType.SYNC,
            changes = details
        )
    }

    fun logWebDavDownload(addedItems: List<FieldChange> = emptyList()) {
        log(
            itemType = OperationLogItemType.WEBDAV_DOWNLOAD,
            itemId = System.currentTimeMillis(),
            itemTitle = "同步下载",
            operationType = OperationType.SYNC,
            changes = addedItems
        )
    }

    fun logSync(
        itemType: OperationLogItemType,
        itemId: Long,
        itemTitle: String,
        details: List<FieldChange> = emptyList(),
        scopeKey: String? = null,
        automaticEmptySync: Boolean = false
    ) {
        log(itemType, itemId, itemTitle, OperationType.SYNC, details, scopeKey = scopeKey, automaticEmptySync = automaticEmptySync)
    }

    private fun log(
        itemType: OperationLogItemType,
        itemId: Long,
        itemTitle: String,
        operationType: OperationType,
        changes: List<FieldChange>,
        snapshotChanges: List<FieldChange> = changes,
        scopeKey: String? = null,
        automaticEmptySync: Boolean = false
    ) {
        val occurredAt = System.currentTimeMillis()
        val generation = takagi.ru.monica.security.SessionManager.lockGeneration.value
            .takeIf { takagi.ru.monica.security.SessionManager.isUnlocked.value }
        val immutableSnapshotChanges = snapshotChanges.toList()

        scope.launch {
            val db = databaseOrNull()
            if (db == null) {
                android.util.Log.e("OperationLogger", "Logger context is unavailable; init() was not called")
                return@launch
            }

            try {
                db.timelineJournal.observe(
                    kind = itemType.name, itemId = itemId, title = itemTitle,
                    action = operationType.name,
                    facts = immutableSnapshotChanges.map {
                        takagi.ru.monica.timeline.TimelineFact(it.fieldName, it.oldValue, it.newValue)
                    },
                    occurredAt = occurredAt, explicitScope = scopeKey,
                    automaticEmptySync = automaticEmptySync, observedGeneration = generation
                )
            } catch (e: Exception) {
                android.util.Log.e("OperationLogger", "Failed to log operation", e)
            }
        }
    }

    private fun databaseOrNull(): PasswordDatabase? {
        database?.let { return it }
        val context = appContext ?: return null
        return synchronized(this) {
            database ?: PasswordDatabase.getDatabase(context).also { database = it }
        }
    }

    fun <T> compareAndGetChanges(
        old: T?,
        new: T,
        fields: List<Pair<String, (T) -> String?>>
    ): List<FieldChange> {
        if (old == null) return emptyList()
        return fields.mapNotNull { (fieldName, getter) ->
            val oldValue = getter(old) ?: ""
            val newValue = getter(new) ?: ""
            if (oldValue != newValue) {
                FieldChange(fieldName, oldValue, newValue)
            } else {
                null
            }
        }
    }
}

@Serializable
data class FieldChange(
    val fieldName: String,
    val oldValue: String,
    val newValue: String
)
