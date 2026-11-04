package takagi.ru.monica.data.bitwarden

/** Read-only projection. Never loads operation payloads, passwords, tokens, or raw error bodies. */
data class BitwardenSyncQueueRecord(
    val queueKey: String,
    val itemId: String,
    val title: String?,
    val kind: String,
    val operation: String,
    val status: String,
)
