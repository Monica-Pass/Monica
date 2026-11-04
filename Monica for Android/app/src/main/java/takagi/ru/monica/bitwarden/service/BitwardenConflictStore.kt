package takagi.ru.monica.bitwarden.service

import android.content.Context
import java.util.Date
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordHistoryEntry
import takagi.ru.monica.data.bitwarden.BitwardenConflictBackup
import takagi.ru.monica.security.SecurityManager

/** Conflict material is independent of the live row and protected by the local MDK. */
internal class BitwardenConflictStore(context: Context) {
    private val database = PasswordDatabase.getDatabase(context)
    private val security = SecurityManager(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun snapshot(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Date -> JsonPrimitive(value.time)
        is Enum<*> -> JsonPrimitive(value.name)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to snapshot(it.value) })
        is Iterable<*> -> kotlinx.serialization.json.JsonArray(value.map(::snapshot))
        else -> error("Unsupported conflict snapshot value")
    }

    private fun passwordEntrySnapshot(entry: PasswordEntry): JsonObject = JsonObject(mapOf(
        "id" to snapshot(entry.id),
        "title" to snapshot(entry.title),
        "website" to snapshot(entry.website),
        "username" to snapshot(entry.username),
        "password" to snapshot(entry.password),
        "notes" to snapshot(entry.notes),
        "createdAt" to snapshot(entry.createdAt),
        "updatedAt" to snapshot(entry.updatedAt),
        "isFavorite" to snapshot(entry.isFavorite),
        "sortOrder" to snapshot(entry.sortOrder),
        "isGroupCover" to snapshot(entry.isGroupCover),
        "appPackageName" to snapshot(entry.appPackageName),
        "appName" to snapshot(entry.appName),
        "email" to snapshot(entry.email),
        "phone" to snapshot(entry.phone),
        "addressLine" to snapshot(entry.addressLine),
        "city" to snapshot(entry.city),
        "state" to snapshot(entry.state),
        "zipCode" to snapshot(entry.zipCode),
        "country" to snapshot(entry.country),
        "creditCardNumber" to snapshot(entry.creditCardNumber),
        "creditCardHolder" to snapshot(entry.creditCardHolder),
        "creditCardExpiry" to snapshot(entry.creditCardExpiry),
        "creditCardCVV" to snapshot(entry.creditCardCVV),
        "categoryId" to snapshot(entry.categoryId),
        "boundNoteId" to snapshot(entry.boundNoteId),
        "keepassDatabaseId" to snapshot(entry.keepassDatabaseId),
        "keepassGroupPath" to snapshot(entry.keepassGroupPath),
        "keepassEntryUuid" to snapshot(entry.keepassEntryUuid),
        "keepassGroupUuid" to snapshot(entry.keepassGroupUuid),
        "mdbxDatabaseId" to snapshot(entry.mdbxDatabaseId),
        "mdbxFolderId" to snapshot(entry.mdbxFolderId),
        "authenticatorKey" to snapshot(entry.authenticatorKey),
        "passkeyBindings" to snapshot(entry.passkeyBindings),
        "sshKeyData" to snapshot(entry.sshKeyData),
        "loginType" to snapshot(entry.loginType),
        "ssoProvider" to snapshot(entry.ssoProvider),
        "ssoRefEntryId" to snapshot(entry.ssoRefEntryId),
        "wifiMetadata" to snapshot(entry.wifiMetadata),
        "customIconType" to snapshot(entry.customIconType),
        "customIconValue" to snapshot(entry.customIconValue),
        "customIconUpdatedAt" to snapshot(entry.customIconUpdatedAt),
        "isDeleted" to snapshot(entry.isDeleted),
        "deletedAt" to snapshot(entry.deletedAt),
        "isArchived" to snapshot(entry.isArchived),
        "archivedAt" to snapshot(entry.archivedAt),
        "replicaGroupId" to snapshot(entry.replicaGroupId),
        "passwordGroupId" to snapshot(entry.passwordGroupId),
        "bitwardenVaultId" to snapshot(entry.bitwardenVaultId),
        "bitwardenCipherId" to snapshot(entry.bitwardenCipherId),
        "bitwardenFolderId" to snapshot(entry.bitwardenFolderId),
        "bitwardenRevisionDate" to snapshot(entry.bitwardenRevisionDate),
        "bitwardenCipherType" to snapshot(entry.bitwardenCipherType),
        "bitwardenLocalModified" to snapshot(entry.bitwardenLocalModified)
    ))

    private fun secureItemSnapshot(entry: SecureItem): JsonObject = JsonObject(mapOf(
        "id" to snapshot(entry.id),
        "itemType" to snapshot(entry.itemType),
        "title" to snapshot(entry.title),
        "notes" to snapshot(entry.notes),
        "isFavorite" to snapshot(entry.isFavorite),
        "sortOrder" to snapshot(entry.sortOrder),
        "createdAt" to snapshot(entry.createdAt),
        "updatedAt" to snapshot(entry.updatedAt),
        "itemData" to snapshot(entry.itemData),
        "imagePaths" to snapshot(entry.imagePaths),
        "categoryId" to snapshot(entry.categoryId),
        "keepassDatabaseId" to snapshot(entry.keepassDatabaseId),
        "keepassGroupPath" to snapshot(entry.keepassGroupPath),
        "keepassEntryUuid" to snapshot(entry.keepassEntryUuid),
        "keepassGroupUuid" to snapshot(entry.keepassGroupUuid),
        "mdbxDatabaseId" to snapshot(entry.mdbxDatabaseId),
        "mdbxFolderId" to snapshot(entry.mdbxFolderId),
        "isDeleted" to snapshot(entry.isDeleted),
        "deletedAt" to snapshot(entry.deletedAt),
        "replicaGroupId" to snapshot(entry.replicaGroupId),
        "bitwardenVaultId" to snapshot(entry.bitwardenVaultId),
        "bitwardenCipherId" to snapshot(entry.bitwardenCipherId),
        "bitwardenFolderId" to snapshot(entry.bitwardenFolderId),
        "bitwardenRevisionDate" to snapshot(entry.bitwardenRevisionDate),
        "bitwardenLocalModified" to snapshot(entry.bitwardenLocalModified),
        "syncStatus" to snapshot(entry.syncStatus)
    ))

    private fun customFieldSnapshot(entry: CustomField): JsonObject = JsonObject(mapOf(
        "id" to snapshot(entry.id),
        "entryId" to snapshot(entry.entryId),
        "title" to snapshot(entry.title),
        "value" to snapshot(entry.value),
        "isProtected" to snapshot(entry.isProtected),
        "sortOrder" to snapshot(entry.sortOrder)
    ))

    private fun passwordHistoryEntrySnapshot(entry: PasswordHistoryEntry): JsonObject = JsonObject(mapOf(
        "id" to snapshot(entry.id),
        "entryId" to snapshot(entry.entryId),
        "password" to snapshot(entry.password),
        "lastUsedAt" to snapshot(entry.lastUsedAt)
    ))

    suspend fun record(entry: PasswordEntry, remote: CipherApiResponse) {
        val local = snapshot(mapOf(
            "kind" to "password", "entry" to passwordEntrySnapshot(entry),
            "customFields" to database.customFieldDao().getFieldsByEntryIdSync(entry.id).map(::customFieldSnapshot),
            "passwordHistory" to database.passwordHistoryDao().getHistoryByEntryIdSync(entry.id).map(::passwordHistoryEntrySnapshot)
        )).toString()
        record(entry.bitwardenVaultId ?: error("Missing vault"), entry.id, entry.title,
            entry.bitwardenRevisionDate, remote, local)
    }

    suspend fun record(item: SecureItem, remote: CipherApiResponse) {
        record(item.bitwardenVaultId ?: error("Missing vault"), null, item.title,
            item.bitwardenRevisionDate, remote, snapshot(mapOf("kind" to "secure", "entry" to secureItemSnapshot(item))).toString())
    }

    private suspend fun record(vaultId: Long, entryId: Long?, title: String, revision: String?,
                               remote: CipherApiResponse, local: String) {
        val dao = database.bitwardenConflictBackupDao()
        // Repeated sync of the same versions must not fill the conflict list with duplicates.
        val duplicate = dao.getUnresolvedConflictsByVault(vaultId).any {
            it.bitwardenCipherId == remote.id && it.localRevisionDate == revision &&
                it.serverRevisionDate == remote.revisionDate &&
                runCatching { unseal(it.localDataJson) == local }.getOrDefault(false)
        }
        if (duplicate) return
        dao.insert(BitwardenConflictBackup(
            vaultId = vaultId, entryId = entryId, bitwardenCipherId = remote.id,
            conflictType = BitwardenConflictBackup.TYPE_CONCURRENT_EDIT,
            localDataJson = seal(local), serverDataJson = seal(remote.sourceDocument?.toString() ?: json.encodeToString(remote)),
            localRevisionDate = revision, serverRevisionDate = remote.revisionDate,
            entryTitle = title
        ))
    }

    private fun seal(payload: String): String = JsonObject(mapOf(
        "format" to JsonPrimitive("monica-bitwarden-conflict-v1"),
        "sealed" to JsonPrimitive(security.encryptData(payload))
    )).toString()

    private fun unseal(value: String): String {
        val wrapper = json.parseToJsonElement(value) as? JsonObject ?: error("Invalid conflict material")
        check(wrapper["format"]?.jsonPrimitive?.content == "monica-bitwarden-conflict-v1")
        return security.decryptData(wrapper["sealed"]?.jsonPrimitive?.content ?: error("Missing snapshot"))
    }

    fun server(conflict: BitwardenConflictBackup): CipherApiResponse =
        json.decodeFromString(unseal(conflict.serverDataJson ?: error("Missing server snapshot")))
}
