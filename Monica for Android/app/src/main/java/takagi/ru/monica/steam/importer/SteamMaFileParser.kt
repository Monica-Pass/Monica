package takagi.ru.monica.steam.importer

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SteamMaFilePayload(
    val steamId: String,
    val accountName: String,
    val displayName: String,
    val deviceId: String,
    val sharedSecret: String,
    val identitySecret: String?,
    val revocationCode: String?,
    val tokenGid: String?,
    val accessToken: String?,
    val refreshToken: String?,
    val steamLoginSecure: String?,
    val rawJson: String
) {
    val hasRealSteamId: Boolean
        get() = steamId.isSteamId64Value()
}

data class SteamMaFileManifestEntry(
    val filename: String,
    val steamId: String?,
    val salt: String?,
    val iv: String?
)

class SteamMaFileParser(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private enum class SharedSecretSource {
        StandardField,
        OtpAuthUri,
        SteamUri
    }

    fun parseSteamGuardJson(
        steamId: String,
        deviceId: String,
        steamGuardJson: String,
        accessToken: String?,
        refreshToken: String?,
        displayNameOverride: String? = null
    ): SteamMaFilePayload {
        val root = json.parseToJsonElement(steamGuardJson).jsonObject
        val accountName = root.stringAny("account_name", "accountName", "AccountName") ?: steamId
        val sharedSecret = root.stringAny("shared_secret", "sharedSecret")
            ?: error("Steam Guard payload missing shared_secret")
        val normalizedSharedSecret = normalizeSteamSharedSecret(
            sharedSecret = sharedSecret,
            source = SharedSecretSource.StandardField
        )
        return SteamMaFilePayload(
            steamId = steamId,
            accountName = accountName,
            displayName = displayNameOverride?.trim()?.takeIf { it.isNotBlank() }
                ?: root.stringAny("monica_display_name", "monicaDisplayName")
                ?: accountName,
            deviceId = deviceId.ifBlank { root.stringAny("device_id", "deviceId").orEmpty() },
            sharedSecret = normalizedSharedSecret,
            identitySecret = root.stringAny("identity_secret", "identitySecret"),
            revocationCode = root.stringAny("revocation_code", "revocationCode"),
            tokenGid = root.stringAny("token_gid", "tokenGid"),
            accessToken = accessToken,
            refreshToken = refreshToken,
            steamLoginSecure = accessToken?.let { "$steamId||$it" },
            rawJson = steamGuardJson
        )
    }

    fun parse(
        maFileContent: String,
        fileName: String? = null,
        manifestContent: String? = null,
        password: String? = null,
        displayNameOverride: String? = null,
        steamIdOverride: String? = null,
        allowMissingSteamId: Boolean = false
    ): SteamMaFilePayload {
        val trimmed = maFileContent.trim()
        val plainJson = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            decryptEncryptedMaFile(trimmed, fileName, manifestContent, password)
        }

        val root = json.parseToJsonElement(plainJson).jsonObject
        val session = root.objectAny("Session", "session")
        val embeddedSteamId = root.steamIdAny()
            ?: session?.steamIdAny()
            ?: root.stringAny("steamLoginSecure", "steam_login_secure")
                ?.steamIdFromSteamLoginSecure()
            ?: session?.stringAny("SteamLoginSecure", "steamLoginSecure")
                ?.steamIdFromSteamLoginSecure()
            ?: fileName?.steamIdFromFileName()
        val accountName = root.stringAny("account_name", "accountName", "AccountName")
            ?: session?.stringAny("AccountName", "account_name")
            ?: embeddedSteamId
            ?: fileName?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() && it != fileName }
            ?: "Steam"
        val (rawSharedSecret, sharedSecretSource) = root.preferredSharedSecret()
            ?: error("maFile missing shared_secret")
        val sharedSecret = normalizeSteamSharedSecret(rawSharedSecret, sharedSecretSource)
        val steamId = steamIdOverride.normalizedSteamIdOverride()
            ?: embeddedSteamId
            ?: root.localSteamIdAny()
            ?: if (allowMissingSteamId || root.boolAny("monica_missing_steamid", "monicaMissingSteamId") == true) {
                generateLocalSteamId(
                    accountName = accountName,
                    sharedSecret = sharedSecret,
                    identitySecret = root.stringAny("identity_secret", "identitySecret"),
                    tokenGid = root.stringAny("token_gid", "tokenGid"),
                    revocationCode = root.stringAny("revocation_code", "revocationCode")
                )
            } else {
                error("maFile missing steamid")
            }
        val deviceId = root.stringAny("device_id", "deviceId")
            ?: session?.stringAny("DeviceID", "device_id", "deviceId")
            ?: ""
        val steamLoginSecure = session?.stringAny("SteamLoginSecure", "steamLoginSecure")
            ?: root.stringAny("steamLoginSecure", "steam_login_secure")
        val accessToken = root.stringAny("access_token", "accessToken", "oauth_token", "OAuthToken")
            ?: session?.stringAny("AccessToken", "access_token", "OAuthToken", "oauth_token")
            ?: steamLoginSecure?.substringAfter("||", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }

        return SteamMaFilePayload(
            steamId = steamId,
            accountName = accountName,
            displayName = displayNameOverride?.trim()?.takeIf { it.isNotBlank() }
                ?: root.stringAny("monica_display_name", "monicaDisplayName")
                ?: accountName,
            deviceId = deviceId,
            sharedSecret = sharedSecret,
            identitySecret = root.stringAny("identity_secret", "identitySecret"),
            revocationCode = root.stringAny("revocation_code", "revocationCode"),
            tokenGid = root.stringAny("token_gid", "tokenGid"),
            accessToken = accessToken,
            refreshToken = root.stringAny("refresh_token", "refreshToken")
                ?: session?.stringAny("RefreshToken", "refresh_token"),
            steamLoginSecure = steamLoginSecure,
            rawJson = root.rawJsonForSteamId(plainJson, steamId)
        )
    }

    fun parseManifest(manifestContent: String): List<SteamMaFileManifestEntry> {
        val root = json.parseToJsonElement(manifestContent).jsonObject
        val entries = root["entries"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val filename = obj.stringAny("filename", "Filename") ?: return@mapNotNull null
            SteamMaFileManifestEntry(
                filename = filename,
                steamId = obj.stringAny("steamid", "SteamID", "steam_id"),
                salt = obj.stringAny("encryption_salt", "Salt", "salt"),
                iv = obj.stringAny("encryption_iv", "IV", "iv")
            )
        }
    }

    private fun decryptEncryptedMaFile(
        encryptedContent: String,
        fileName: String?,
        manifestContent: String?,
        password: String?
    ): String {
        val passKey = password?.takeIf { it.isNotEmpty() }
            ?: error("Encrypted maFile requires password")
        val manifest = manifestContent?.takeIf { it.isNotBlank() }
            ?: error("Encrypted maFile requires manifest.json")
        val entries = parseManifest(manifest)
        val normalizedFileName = fileName?.substringAfterLast('/')?.substringAfterLast('\\')
        val entry = entries.firstOrNull { it.filename == normalizedFileName }
            ?: entries.singleOrNull()
            ?: error("Cannot find matching maFile entry in manifest.json")
        val salt = entry.salt ?: error("Manifest entry missing encryption_salt")
        val iv = entry.iv ?: error("Manifest entry missing encryption_iv")
        return SteamMaFileCrypto.decrypt(passKey, salt, iv, encryptedContent)
            ?: error("Cannot decrypt maFile. Check password and manifest.json")
    }

    private fun JsonObject.objectAny(vararg keys: String): JsonObject? {
        keys.forEach { key ->
            (this[key] as? JsonObject)?.let { return it }
        }
        return null
    }

    private fun JsonObject.stringAny(vararg keys: String): String? {
        keys.forEach { key ->
            val value = this[key].stringOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun JsonObject.boolAny(vararg keys: String): Boolean? {
        keys.forEach { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@forEach
            primitive.contentOrNull?.lowercase()?.let { value ->
                when (value) {
                    "true", "1" -> return true
                    "false", "0" -> return false
                }
            }
        }
        return null
    }

    private fun JsonElement?.stringOrNull(): String? {
        return (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.steamIdAny(): String? {
        return stringAny(
            "steamid",
            "steam_id",
            "SteamID",
            "steam64",
            "steam_id64",
            "steamID64",
            "SteamID64",
            "sbeamid"
        )?.takeIf { it.isSteamId64() }
    }

    private fun JsonObject.localSteamIdAny(): String? {
        return stringAny("monica_local_steamid", "monicaLocalSteamId")
            ?.takeIf { it.isMonicaLocalSteamId() }
    }

    private fun JsonObject.preferredSharedSecret(): Pair<String, SharedSecretSource>? {
        stringAny(
            "uri",
            "Uri",
            "otp_uri",
            "otpUri",
            "otpauth_uri",
            "otpauthUri",
            "steam_uri",
            "steamUri",
            "url",
            "URL"
        )?.extractSharedSecretFromUri()?.let { return it }

        val sharedSecret = stringAny("shared_secret", "sharedSecret") ?: return null
        return sharedSecret.extractSharedSecretFromUri()
            ?: (sharedSecret to SharedSecretSource.StandardField)
    }

    private fun String.extractSharedSecretFromUri(): Pair<String, SharedSecretSource>? {
        val normalized = trim()
        if (normalized.startsWith("steam://", ignoreCase = true)) {
            // Preserve Base64 '/' characters; this payload is a key, not a path.
            val secret = normalized
                .substringAfter("://", "")
                .substringBefore("?")
                .substringBefore("#")
                .trim()
                .decodeUriComponent()
                .takeIf { it.isNotBlank() }
            return secret?.let { it to SharedSecretSource.SteamUri }
        }
        if (normalized.startsWith("otpauth://", ignoreCase = true)) {
            val secret = normalized.queryParameter("secret")
                ?.decodeUriComponent()
                ?.takeIf { it.isNotBlank() }
            return secret?.let { it to SharedSecretSource.OtpAuthUri }
        }
        return null
    }

    private fun normalizeSteamSharedSecret(
        sharedSecret: String,
        source: SharedSecretSource
    ): String {
        val compact = sharedSecret.filterNot { it.isWhitespace() }
        val base64Bytes = decodeBase64OrNull(compact)
        val base32Bytes = decodeBase32OrNull(compact)
        val selected = when (source) {
            SharedSecretSource.OtpAuthUri -> base32Bytes ?: base64Bytes
            SharedSecretSource.SteamUri -> base64Bytes ?: base32Bytes
            SharedSecretSource.StandardField -> {
                when {
                    base64Bytes?.size == STEAM_SECRET_BYTES -> base64Bytes
                    base32Bytes?.size == STEAM_SECRET_BYTES -> base32Bytes
                    base64Bytes != null -> base64Bytes
                    else -> base32Bytes
                }
            }
        } ?: error("maFile shared_secret is not valid base64 or base32")
        return Base64.getEncoder().encodeToString(selected)
    }

    private fun decodeBase64OrNull(value: String): ByteArray? {
        if (value.isBlank()) return null
        return runCatching {
            Base64.getDecoder().decode(value.withBase64Padding())
        }.getOrNull()
            ?: runCatching {
                Base64.getUrlDecoder().decode(value.withBase64Padding())
            }.getOrNull()
    }

    private fun decodeBase32OrNull(value: String): ByteArray? {
        val normalized = value
            .uppercase()
            .replace("=", "")
            .filterNot { it == ' ' || it == '-' }
        if (normalized.isBlank() || normalized.any { it !in BASE32_ALPHABET }) return null

        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        normalized.forEach { char ->
            buffer = (buffer shl 5) or BASE32_ALPHABET.indexOf(char)
            bitsLeft += 5
            while (bitsLeft >= 8) {
                output += ((buffer shr (bitsLeft - 8)) and 0xff).toByte()
                bitsLeft -= 8
            }
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun String.withBase64Padding(): String {
        val remainder = length % 4
        return if (remainder == 0) this else this + "=".repeat(4 - remainder)
    }

    private fun String.decodeUriComponent(): String {
        return runCatching {
            URLDecoder.decode(replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(this)
    }

    private fun String.queryParameter(name: String): String? {
        val query = substringAfter("?", missingDelimiterValue = "")
            .substringBefore("#")
            .takeIf { it.isNotBlank() }
            ?: return null
        return query.split('&')
            .asSequence()
            .mapNotNull { part ->
                val key = part.substringBefore("=", "")
                val value = part.substringAfter("=", "")
                if (key.equals(name, ignoreCase = true)) value else null
            }
            .firstOrNull()
    }

    private fun String.steamIdFromFileName(): String? {
        return Regex("""(?<!\d)(7656119\d{10})(?!\d)""")
            .find(substringAfterLast('/').substringAfterLast('\\'))
            ?.value
    }

    private fun String.steamIdFromSteamLoginSecure(): String? {
        return substringBefore("||", "")
            .takeIf { it.isSteamId64() }
    }

    private fun String.isSteamId64(): Boolean {
        return isSteamId64Value()
    }

    private fun String.isMonicaLocalSteamId(): Boolean {
        return startsWith(MONICA_LOCAL_STEAM_ID_PREFIX) &&
            removePrefix(MONICA_LOCAL_STEAM_ID_PREFIX).matches(Regex("""[0-9a-f]{16,64}"""))
    }

    private fun String?.normalizedSteamIdOverride(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.isSteamId64()) return value
        val accountId = value
            .takeIf { it.matches(Regex("""\d{1,10}""")) }
            ?.toLongOrNull()
            ?.takeIf { it in 1L..STEAM_ACCOUNT_ID32_MAX }
        return accountId?.let { (STEAM_ID64_BASE + it).toString() }
            ?: error("Invalid SteamID")
    }

    private fun JsonObject.rawJsonForSteamId(originalJson: String, steamId: String): String {
        val hasRealSteamId = steamId.isSteamId64()
        val alreadyHasRealSteamId = steamIdAny() != null
        val hasMonicaMissingMarker =
            containsKey("monica_missing_steamid") || containsKey("monicaMissingSteamId") ||
                containsKey("monica_local_steamid") || containsKey("monicaLocalSteamId")
        if (hasRealSteamId && alreadyHasRealSteamId && !hasMonicaMissingMarker) {
            return originalJson
        }

        val enriched = JsonObject(toMutableMap().apply {
            if (hasRealSteamId) {
                removeMissingSteamIdMarkers()
                if (!alreadyHasRealSteamId) {
                    put("steamid", JsonPrimitive(steamId))
                }
            } else {
                removeSteamIdFields()
                put("monica_missing_steamid", JsonPrimitive(true))
                put("monica_local_steamid", JsonPrimitive(steamId))
            }
        })
        return json.encodeToString(enriched)
    }

    private fun MutableMap<String, JsonElement>.removeMissingSteamIdMarkers() {
        remove("monica_missing_steamid")
        remove("monicaMissingSteamId")
        remove("monica_local_steamid")
        remove("monicaLocalSteamId")
    }

    private fun MutableMap<String, JsonElement>.removeSteamIdFields() {
        STEAM_ID_KEYS.forEach(::remove)
        removeMissingSteamIdMarkers()
        val session = (this["Session"] as? JsonObject)?.toMutableMap()
            ?: (this["session"] as? JsonObject)?.toMutableMap()
        if (session != null) {
            STEAM_ID_KEYS.forEach(session::remove)
            if (containsKey("Session")) {
                this["Session"] = JsonObject(session)
            } else {
                this["session"] = JsonObject(session)
            }
        }
    }

    private fun generateLocalSteamId(
        accountName: String,
        sharedSecret: String,
        identitySecret: String?,
        tokenGid: String?,
        revocationCode: String?
    ): String {
        val material = listOf(
            accountName,
            sharedSecret,
            identitySecret.orEmpty(),
            tokenGid.orEmpty(),
            revocationCode.orEmpty()
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MONICA_LOCAL_STEAM_ID_PREFIX + digest.take(24)
    }

    private companion object {
        private const val STEAM_SECRET_BYTES = 20
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val STEAM_ID64_BASE = 76561197960265728L
        private const val STEAM_ACCOUNT_ID32_MAX = 4_294_967_295L
        private const val MONICA_LOCAL_STEAM_ID_PREFIX = "monica-missing-steamid-"
        private val STEAM_ID_KEYS = listOf(
            "steamid",
            "steam_id",
            "SteamID",
            "steam64",
            "steam_id64",
            "steamID64",
            "SteamID64",
            "sbeamid"
        )
    }
}

private fun String.isSteamId64Value(): Boolean {
    return matches(Regex("""7656119\d{10}"""))
}
