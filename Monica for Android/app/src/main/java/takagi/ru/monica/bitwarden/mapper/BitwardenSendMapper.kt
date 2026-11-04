package takagi.ru.monica.bitwarden.mapper

import android.util.Base64
import takagi.ru.monica.bitwarden.api.SendApiResponse
import takagi.ru.monica.bitwarden.api.SendCreateRequest
import takagi.ru.monica.bitwarden.api.SendFileCreateRequest
import takagi.ru.monica.bitwarden.api.SendTextCreateRequest
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.data.bitwarden.BitwardenSend
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import takagi.ru.monica.bitwarden.service.BitwardenSendDraft
import takagi.ru.monica.bitwarden.service.BitwardenSendEdit
import takagi.ru.monica.bitwarden.service.SendPasswordAction
import takagi.ru.monica.bitwarden.service.SendAuthAction
import takagi.ru.monica.bitwarden.service.BitwardenSendAuth

object BitwardenSendMapper {

    internal fun buildUpdatePayload(source: JsonObject, draft: BitwardenSendDraft,
        vaultKey: SymmetricCryptoKey): JsonObject {
        val encryptedKey = requireNotNull(BitwardenSendEdit.string(source, "key"))
        val raw = BitwardenCrypto.decrypt(encryptedKey, vaultKey)
        val keyMaterial = try { requireNotNull(decodeKeyMaterial(raw)).copyOf() } finally { raw.fill(0) }
        val sendKey = BitwardenCrypto.deriveSendKey(keyMaterial)
        try {
            val isText = BitwardenSendEdit.string(source, "type") == "0"
            return BitwardenSendEdit.patch(source, draft,
                name = BitwardenCrypto.encryptString(draft.title, sendKey),
                notes = draft.notes?.let { BitwardenCrypto.encryptString(it, sendKey) },
                text = if (isText) BitwardenCrypto.encryptString(draft.text, sendKey) else null,
                passwordHash = if (draft.passwordAction == SendPasswordAction.REPLACE)
                    BitwardenCrypto.hashSendPassword(requireNotNull(draft.password), keyMaterial) else null)
        } finally { sendKey.clear(); keyMaterial.fill(0) }
    }

    data class CreateTextSendPayload(
        val request: SendCreateRequest,
        val keyBase64: String,
        val shareUrl: String
    )

    data class CreateFileSendPayload(
        val request: SendCreateRequest,
        val keyBase64: String,
        val encryptedFileName: String,
        val sendKey: SymmetricCryptoKey
    )

    fun mapApiToEntity(
        vaultId: Long,
        serverUrl: String,
        api: SendApiResponse,
        vaultKey: SymmetricCryptoKey
    ): BitwardenSend? {
        return try {
            val encryptedKeyBytes = BitwardenCrypto.decrypt(api.key, vaultKey)
            val keyMaterial = decodeKeyMaterial(encryptedKeyBytes) ?: return null
            val keyBase64 = Base64.encodeToString(keyMaterial, Base64.NO_WRAP)
            val sendKey = BitwardenCrypto.deriveSendKey(keyMaterial)
            try {
                require(api.type == 0 && api.text != null || api.type == 1 && api.file != null) { "Unsupported or incomplete Send" }
                val decryptedName = decryptString(api.name, sendKey) ?: "Untitled Send"
                val decryptedNotes = decryptString(api.notes, sendKey).orEmpty()
                val decryptedText = decryptString(api.text?.text, sendKey)
                val decryptedFileName = api.file?.fileName?.let { fileName ->
                    decryptString(fileName, sendKey) ?: fileName
                }

                val now = System.currentTimeMillis()
                BitwardenSend(
                    vaultId = vaultId,
                    bitwardenSendId = api.id,
                    accessId = api.accessId,
                    keyBase64 = keyBase64,
                    type = api.type,
                    name = decryptedName,
                    notes = decryptedNotes,
                    textContent = decryptedText,
                    isTextHidden = api.text?.hidden ?: false,
                    fileName = decryptedFileName,
                    fileSize = api.file?.size,
                    accessCount = api.accessCount,
                    maxAccessCount = api.maxAccessCount,
                    hasPassword = api.password != null,
                    authType = api.authType,
                    recipientEmails = api.emails,
                    disabled = api.disabled,
                    hideEmail = api.hideEmail ?: false,
                    revisionDate = api.revisionDate,
                    expirationDate = api.expirationDate,
                    deletionDate = api.deletionDate,
                    shareUrl = buildShareUrl(
                        serverUrl = serverUrl,
                        accessId = api.accessId,
                        keyMaterial = keyMaterial,
                        urlB64Key = api.urlB64Key
                    ),
                    lastSyncedAt = now,
                    createdAt = now,
                    updatedAt = now
                )
            } finally {
                sendKey.clear()
                keyMaterial.fill(0)
                encryptedKeyBytes.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun buildCreateTextSendPayload(
        serverUrl: String,
        vaultKey: SymmetricCryptoKey,
        title: String,
        text: String,
        notes: String?,
        password: String?,
        maxAccessCount: Int?,
        hideEmail: Boolean,
        hiddenText: Boolean,
        deletionMillis: Long,
        expirationMillis: Long?,
        authAction: SendAuthAction = SendAuthAction.KEEP,
        recipientEmails: List<String> = emptyList(),
        disabled: Boolean = false
    ): CreateTextSendPayload {
        val keyMaterial = BitwardenCrypto.generateSendKeyMaterial()
        val keyBase64 = Base64.encodeToString(keyMaterial, Base64.NO_WRAP)
        val sendKey = BitwardenCrypto.deriveSendKey(keyMaterial)

        try {
        // Bitwarden Send 协议要求 Key 字段加密原始 16 字节密钥，而不是 Base64 文本。
        val encryptedKey = BitwardenCrypto.encrypt(keyMaterial, vaultKey)
        val encryptedName = BitwardenCrypto.encryptString(title, sendKey)
        val encryptedNotes = notes
            ?.takeIf { it.isNotEmpty() }
            ?.let { BitwardenCrypto.encryptString(it, sendKey) }
        val encryptedText = BitwardenCrypto.encryptString(text, sendKey)

        val passwordHash = password
            ?.takeIf { it.isNotEmpty() && authAction !in setOf(SendAuthAction.NONE, SendAuthAction.EMAIL) }
            ?.let { BitwardenCrypto.hashSendPassword(it, keyMaterial) }

        val request = SendCreateRequest(
            key = encryptedKey,
            type = BitwardenSend.TYPE_TEXT,
            name = encryptedName,
            notes = encryptedNotes,
            password = passwordHash,
            disabled = disabled,
            authType = (if (authAction == SendAuthAction.KEEP) {
                if (password.isNullOrEmpty()) SendAuthAction.NONE else SendAuthAction.PASSWORD
            } else authAction).wireValue,
            emails = if (authAction == SendAuthAction.EMAIL) BitwardenSendAuth.recipients(recipientEmails).joinToString(",") else null,
            hideEmail = hideEmail,
            deletionDate = Instant.ofEpochMilli(deletionMillis).toString(),
            expirationDate = expirationMillis?.let { Instant.ofEpochMilli(it).toString() },
            maxAccessCount = maxAccessCount?.takeIf { it > 0 },
            text = SendTextCreateRequest(
                text = encryptedText,
                hidden = hiddenText
            )
        )

        return CreateTextSendPayload(
            request = request,
            keyBase64 = keyBase64,
            shareUrl = buildShareUrl(
                serverUrl = serverUrl,
                accessId = "",
                keyMaterial = keyMaterial
            )
        )
        } finally { sendKey.clear(); keyMaterial.fill(0) }
    }

    fun buildCreateFileSendPayload(
        vaultKey: SymmetricCryptoKey,
        keyMaterial: ByteArray,
        title: String,
        fileName: String,
        encryptedFileLength: Long,
        notes: String?,
        password: String?,
        maxAccessCount: Int?,
        hideEmail: Boolean,
        deletionMillis: Long,
        expirationMillis: Long?,
        authAction: SendAuthAction = SendAuthAction.KEEP,
        recipientEmails: List<String> = emptyList(),
        disabled: Boolean = false
    ): CreateFileSendPayload {
        val keyBase64 = Base64.encodeToString(keyMaterial, Base64.NO_WRAP)
        val sendKey = BitwardenCrypto.deriveSendKey(keyMaterial)

        val encryptedKey = BitwardenCrypto.encrypt(keyMaterial, vaultKey)
        val encryptedName = BitwardenCrypto.encryptString(title, sendKey)
        val encryptedFileName = BitwardenCrypto.encryptString(fileName, sendKey)
        val encryptedNotes = notes
            ?.takeIf { it.isNotEmpty() }
            ?.let { BitwardenCrypto.encryptString(it, sendKey) }
        val passwordHash = password
            ?.takeIf { it.isNotEmpty() && authAction !in setOf(SendAuthAction.NONE, SendAuthAction.EMAIL) }
            ?.let { BitwardenCrypto.hashSendPassword(it, keyMaterial) }

        val request = SendCreateRequest(
            key = encryptedKey,
            type = BitwardenSend.TYPE_FILE,
            fileLength = encryptedFileLength,
            name = encryptedName,
            notes = encryptedNotes,
            password = passwordHash,
            disabled = disabled,
            authType = (if (authAction == SendAuthAction.KEEP) {
                if (password.isNullOrEmpty()) SendAuthAction.NONE else SendAuthAction.PASSWORD
            } else authAction).wireValue,
            emails = if (authAction == SendAuthAction.EMAIL) BitwardenSendAuth.recipients(recipientEmails).joinToString(",") else null,
            hideEmail = hideEmail,
            deletionDate = Instant.ofEpochMilli(deletionMillis).toString(),
            expirationDate = expirationMillis?.let { Instant.ofEpochMilli(it).toString() },
            maxAccessCount = maxAccessCount?.takeIf { it > 0 },
            file = SendFileCreateRequest(fileName = encryptedFileName)
        )

        return CreateFileSendPayload(
            request = request,
            keyBase64 = keyBase64,
            encryptedFileName = encryptedFileName,
            sendKey = sendKey
        )
    }

    fun buildShareUrl(
        serverUrl: String,
        accessId: String,
        keyMaterial: ByteArray,
        urlB64Key: String? = null
    ): String {
        val baseUrl = buildSendBaseUrl(serverUrl)
        val key = urlB64Key?.takeIf { it.isNotEmpty() } ?: Base64.encodeToString(
            keyMaterial,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return if (accessId.isBlank()) "$baseUrl/$key" else "$baseUrl$accessId/$key"
    }

    private fun buildSendBaseUrl(serverUrl: String): String {
        val normalized = serverUrl.trimEnd('/')

        return when {
            takagi.ru.monica.bitwarden.api.BitwardenApiFactory.isOfficialEuServer(normalized) -> "https://send.bitwarden.eu/#/send/"
            takagi.ru.monica.bitwarden.api.BitwardenApiFactory.isOfficialServer(normalized) -> "https://send.bitwarden.com/#/send/"
            else -> "$normalized/#/send/"
        }
    }

    private fun decryptString(value: String?, key: SymmetricCryptoKey): String? {
        if (value.isNullOrBlank()) return null
        return BitwardenCrypto.decryptToString(value, key)
    }

    private fun decodeKeyMaterial(raw: ByteArray): ByteArray? {
        // 正常格式：直接是 16 字节随机密钥（Bitwarden 官方实现）
        if (raw.size == 16) return raw

        // 兼容早期错误格式：将 Base64 文本作为字符串加密
        val asText = runCatching { String(raw, Charsets.UTF_8).trim() }.getOrNull().orEmpty()
        if (asText.isBlank()) return null

        val standard = runCatching { Base64.decode(asText, Base64.NO_WRAP) }.getOrNull()
        if (standard?.size == 16) return standard

        val urlSafe = runCatching {
            Base64.decode(asText, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()
        if (urlSafe?.size == 16) return urlSafe

        return null
    }
}
