package takagi.ru.monica.steam.data

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import takagi.ru.monica.repository.MdbxRepository
import takagi.ru.monica.repository.MdbxStoredVaultEntry
import takagi.ru.monica.steam.importer.SteamMaFileBackupCodec
import takagi.ru.monica.steam.importer.SteamMaFileParser
import takagi.ru.monica.steam.importer.SteamMaFilePayload

data class SteamMdbxAccountRecord(
    val account: SteamAccount,
    val entryId: String
)

class SteamMdbxAccountStore(
    private val repository: MdbxRepository,
    private val parser: SteamMaFileParser = SteamMaFileParser(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun loadAccounts(databaseId: Long): List<SteamMdbxAccountRecord> {
        return repository.listSteamMaFileEntries(databaseId)
            .mapIndexedNotNull { index, entry ->
                parseEntry(databaseId = databaseId, entry = entry, sortOrder = index)
            }
    }

    suspend fun upsertPayload(
        databaseId: Long,
        payload: SteamMaFilePayload,
        existingEntryId: String? = null
    ): SteamMdbxAccountRecord {
        val provisionalAccount = payload.toSteamAccount(
            id = existingEntryId?.let { runtimeAccountId(databaseId, it) } ?: 0L,
            selected = true,
            sortOrder = 0
        )
        return upsertAccount(
            databaseId = databaseId,
            entryId = existingEntryId,
            account = provisionalAccount
        )
    }

    suspend fun upsertAccount(
        databaseId: Long,
        entryId: String?,
        account: SteamAccount,
        folderId: String? = null,
        relocate: Boolean = false
    ): SteamMdbxAccountRecord {
        val maFileJson = SteamMaFileBackupCodec.encode(account)
        val title = account.displayName
            .ifBlank { account.accountName }
            .ifBlank { account.steamId }
            .ifBlank { "Steam" }
        val resolvedEntryId = if (relocate) repository.upsertSteamMaFileEntryInFolder(
            databaseId, entryId, title, maFileJson, folderId
        ) else repository.upsertSteamMaFileEntry(
            databaseId = databaseId,
            entryId = entryId,
            title = title,
            maFileJson = maFileJson
        )
        return SteamMdbxAccountRecord(
            account = account.copy(id = runtimeAccountId(databaseId, resolvedEntryId)),
            entryId = resolvedEntryId
        )
    }

    suspend fun deleteAccount(databaseId: Long, entryId: String) {
        repository.deleteSteamMaFileEntry(databaseId, entryId)
    }

    private fun parseEntry(
        databaseId: Long,
        entry: MdbxStoredVaultEntry,
        sortOrder: Int
    ): SteamMdbxAccountRecord? {
        val extracted = extractMaFile(entry.payloadJson) ?: return null
        val payload = runCatching {
            parser.parse(
                maFileContent = extracted.maFileJson,
                fileName = "${entry.entryId}.maFile",
                steamIdOverride = extracted.steamIdOverride
            )
        }.getOrNull() ?: return null
        val resolvedPayload = entry.title.trim()
            .takeIf { title ->
                title.isNotBlank() &&
                    payload.displayName == payload.accountName &&
                    title != payload.accountName &&
                    title != payload.steamId
            }
            ?.let { title -> payload.copy(displayName = title) }
            ?: payload
        return SteamMdbxAccountRecord(
            account = resolvedPayload.toSteamAccount(
                id = runtimeAccountId(databaseId, entry.entryId),
                selected = sortOrder == 0,
                sortOrder = sortOrder
            ),
            entryId = entry.entryId
        )
    }

    private fun extractMaFile(payloadJson: String): ExtractedMaFile? {
        val trimmed = payloadJson.trim()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return null
        val wrapperSteamId = root.stringAny(
            "steamid",
            "steam_id",
            "SteamID",
            "steam64",
            "steam_id64",
            "SteamID64"
        )
        val embedded = root.stringAny(
            "mafile_json",
            "maFileJson",
            "mafile",
            "maFile",
            "content",
            "raw_json",
            "rawJson"
        )
        if (!embedded.isNullOrBlank()) {
            return ExtractedMaFile(embedded, wrapperSteamId)
        }
        if (root.looksLikeSteamMaFile()) {
            return ExtractedMaFile(trimmed, wrapperSteamId)
        }
        return null
    }

    private fun JsonObject.looksLikeSteamMaFile(): Boolean {
        return listOf(
            "shared_secret",
            "sharedSecret",
            "identity_secret",
            "identitySecret",
            "uri",
            "otpauth_uri",
            "steam_uri",
            "Session",
            "session"
        ).any { key -> containsKey(key) }
    }

    private fun JsonObject.stringAny(vararg keys: String): String? {
        keys.forEach { key ->
            val value = this[key].stringOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun JsonElement?.stringOrNull(): String? {
        return (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun SteamMaFilePayload.toSteamAccount(
        id: Long,
        selected: Boolean,
        sortOrder: Int
    ): SteamAccount {
        val now = System.currentTimeMillis()
        return SteamAccount(
            id = id,
            steamId = steamId,
            accountName = accountName,
            displayName = displayName,
            deviceId = deviceId,
            sharedSecret = sharedSecret,
            identitySecret = identitySecret,
            revocationCode = revocationCode,
            tokenGid = tokenGid,
            accessToken = accessToken,
            refreshToken = refreshToken,
            steamLoginSecure = steamLoginSecure,
            rawSteamGuardJson = rawJson,
            selected = selected,
            sortOrder = sortOrder,
            createdAt = now,
            updatedAt = now
        )
    }

    private data class ExtractedMaFile(
        val maFileJson: String,
        val steamIdOverride: String?
    )

    companion object {
        fun runtimeAccountId(databaseId: Long, entryId: String): Long {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$databaseId:$entryId".toByteArray(Charsets.UTF_8))
            var value = 0L
            repeat(7) { index ->
                value = (value shl 8) or (digest[index].toLong() and 0xff)
            }
            return -value.coerceAtLeast(1L)
        }
    }
}
