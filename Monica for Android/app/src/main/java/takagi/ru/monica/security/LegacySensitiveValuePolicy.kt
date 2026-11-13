package takagi.ru.monica.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.model.CardWalletDataCodec
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

/**
 * Positive identification of readable historical plaintext, never a decryption fallback.
 * A true result only permits wrapping the ORIGINAL string; do not serialize the parsed view.
 * Unknown fields, whitespace, field order and escape spelling belong to the original payload.
 */
internal object LegacySensitiveValuePolicy {
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_DEPTH = 32
    private const val MAX_SECRET_BYTES = 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private val documentTypes = setOf("id_card", "passport", "driver_license", "driverlicense", "license",
        "social_security", "socialsecurity", "ssn", "other")

    fun acceptsItem(type: ItemType, raw: String): Boolean {
        if (!bounded(raw)) return false
        return when (type) {
            ItemType.TOTP -> acceptsOtp(raw)
            ItemType.BANK_CARD -> objectOrNull(raw)?.let { obj ->
                val number = obj.string("cardNumber", "number") ?: return@let false
                val hasShape = "cardNumber" in obj || listOf("cardholderName", "expiryMonth", "expMonth", "bankName").any { it in obj }
                hasShape && number.any { it in '0'..'9' } && number.length <= 64 &&
                    number.all { it in '0'..'9' || it in " -*" } &&
                    obj.stringsHaveExpectedShape("cardNumber", "number", "cardholderName", "expiryMonth", "expiryYear",
                        "expMonth", "expYear", "cvv", "code", "pin") &&
                    CardWalletDataCodec.parseBankCardData(raw)?.cardNumber == number
            } ?: false
            ItemType.DOCUMENT -> objectOrNull(raw)?.let { obj ->
                val number = obj.string("documentNumber", "number") ?: return@let false
                val typeName = obj.string("documentType", "type") ?: return@let false
                number.isNotBlank() && number.length <= 256 && !hasEnvelopeMarker(number) &&
                    typeName.lowercase(Locale.ROOT) in documentTypes &&
                    obj.stringsHaveExpectedShape("documentNumber", "number", "documentType", "type", "fullName",
                        "firstName", "lastName", "ssn", "passportNumber", "licenseNumber") &&
                    CardWalletDataCodec.parseDocumentData(raw)?.documentNumber == number
            } ?: false
            else -> false
        }
    }

    // Password readers accept URI/Base32 only; their fromAuthenticatorKey rejects JSON.
    fun acceptsAuthenticatorKey(raw: String): Boolean = bounded(raw) && !raw.trim().startsWith('{') && acceptsOtp(raw)

    private fun acceptsOtp(raw: String): Boolean {
        val value = raw.trim()
        if (value.startsWith('{')) return acceptsOtpObject(objectOrNull(raw) ?: return false)
        if (value.contains("://")) return acceptsOtpUri(value)
        // V1 has no prefix: Base64(12-byte IV + ciphertext + 16-byte tag).
        // The alphabets overlap; retain long bare Base32-looking values when V1 is possible.
        val compact = compactBase32(raw)
        return validBase32(raw) && !couldBeLegacyCiphertext(compact)
    }

    private fun acceptsOtpObject(obj: JsonObject): Boolean {
        if (!obj.stringsHaveExpectedShape("secret", "key", "issuer", "serviceName", "account", "accountName",
                "algorithm", "otpType", "pin")) return false
        val secret = obj.string("secret", "key") ?: return false
        if (obj.string("secret") != null && obj.string("key") != null && obj.string("secret") != obj.string("key")) return false
        val type = obj.string("otpType") ?: "TOTP"
        if (type !in setOf("TOTP", "HOTP", "STEAM", "YANDEX", "MOTP")) return false
        // The legacy "key" fallback does not preserve non-TOTP type metadata.
        if ("secret" !in obj && type != "TOTP") return false
        if (!validOtpParameters(obj.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull })) return false
        if (secret.contains("://")) return acceptsOtpUri(secret)
        return if (type == "MOTP") validMotp(secret, obj.string("pin")) else validBase32(secret)
    }

    private fun acceptsOtpUri(raw: String): Boolean {
        return try {
            when {
                raw.startsWith("steam://", ignoreCase = true) -> {
                    val payload = raw.substringAfter("://")
                    if (payload.isBlank() || payload.any { it == '?' || it == '#' }) false
                    else {
                        val secret = decodeComponent(payload)
                        validBase32(secret) || decodeBase64(secret)?.size?.let { it in 10..MAX_SECRET_BYTES } == true
                    }
                }
                else -> {
                    val uri = URI(raw)
                    val scheme = uri.scheme?.lowercase(Locale.ROOT)
                    if (scheme !in setOf("otpauth", "motp") || uri.rawFragment != null || uri.isOpaque) false
                    else {
                        val parameters = queryParameters(uri.rawQuery) ?: return false
                        val secret = parameters["secret"] ?: return false
                        if (!validOtpParameters(parameters)) false
                        else if (scheme == "motp") {
                            !uri.rawAuthority.isNullOrBlank() && uri.rawAuthority.contains(':') &&
                                validMotp(secret, parameters["pin"])
                        } else {
                            val authority = uri.rawAuthority?.lowercase(Locale.ROOT)
                            authority in setOf("totp", "hotp", "yaotp") && !uri.rawPath.isNullOrBlank() &&
                                uri.rawPath != "/" && validBase32(secret) &&
                                (authority != "hotp" || parameters["counter"]?.toLongOrNull()?.let { it >= 0 } == true) &&
                                (parameters["encoder"] == null || parameters["encoder"].equals("steam", ignoreCase = true))
                        }
                    }
                }
            }
        } catch (_: Exception) { false }
    }

    private fun validOtpParameters(values: Map<String, String?>): Boolean {
        if ("algorithm" in values && values["algorithm"]?.uppercase(Locale.ROOT) !in setOf("SHA1", "SHA256", "SHA512")) return false
        if ("period" in values && values["period"]?.toIntOrNull()?.let { it in 1..86400 } != true) return false
        if ("digits" in values && values["digits"]?.toIntOrNull()?.let { it in 4..10 } != true) return false
        if ("counter" in values && values["counter"]?.toLongOrNull()?.let { it >= 0 } != true) return false
        return true
    }

    private fun queryParameters(query: String?): Map<String, String>? {
        if (query.isNullOrBlank()) return null
        val parts = query.split('&')
        if (parts.size > 64) return null
        val result = LinkedHashMap<String, String>()
        for (part in parts) {
            if ('=' !in part) return null
            val key = decodeComponent(part.substringBefore('='))
            val value = decodeComponent(part.substringAfter('='))
            if (key.isEmpty() || result.put(key, value) != null) return null
        }
        return result
    }

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name()).also {
            require('\uFFFD' !in it && it.none { c -> c < ' ' })
        }

    private fun validMotp(secret: String, pin: String?): Boolean =
        secret.length in 16..64 && secret.length % 2 == 0 && secret.all { it in "0123456789abcdefABCDEF" } &&
            pin != null && pin.length in 4..10 && pin.all { it in '0'..'9' }

    private fun compactBase32(raw: String): String = raw.trim().replace(" ", "").replace("-", "").uppercase(Locale.ROOT)

    private fun validBase32(raw: String): Boolean {
        val value = compactBase32(raw)
        val symbols = value.trimEnd('=')
        if (symbols.length * 5 / 8 !in 10..MAX_SECRET_BYTES || symbols.any { it !in base32Alphabet }) return false
        val padding = when (symbols.length % 8) { 0 -> 0; 2 -> 6; 4 -> 4; 5 -> 3; 7 -> 1; else -> return false }
        if (value.length != symbols.length && value.length - symbols.length != padding) return false
        val unusedBits = symbols.length * 5 % 8
        return unusedBits == 0 || (base32Alphabet.indexOf(symbols.last()) and ((1 shl unusedBits) - 1)) == 0
    }

    private fun couldBeLegacyCiphertext(value: String): Boolean = decodeBase64(value)?.size?.let { it >= 28 } == true

    private fun decodeBase64(value: String): ByteArray? {
        if (value.isEmpty() || value.any { it !in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=_-" }) return null
        return try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) {
            try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) { null }
        }
    }

    private fun hasEnvelopeMarker(value: String): Boolean = '|' in value

    private fun JsonObject.string(vararg names: String): String? =
        names.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }

    private fun JsonObject.stringsHaveExpectedShape(vararg names: String): Boolean = names.all { name ->
        val value = this[name] ?: return@all true
        value is JsonPrimitive && value.isString && !hasEnvelopeMarker(value.content)
    }

    private fun bounded(raw: String): Boolean = raw.length <= MAX_BYTES && raw.isNotBlank() && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    private fun objectOrNull(raw: String): JsonObject? {
        val value = raw.trim()
        if (!value.startsWith('{') || !value.endsWith('}') || !boundedJsonStructure(value)) return null
        return try { (json.parseToJsonElement(raw) as? JsonObject)?.takeIf(::hasStrictJsonValues) } catch (_: Exception) { null }
    }

    private fun hasStrictJsonValues(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.values.all(::hasStrictJsonValues)
        is JsonArray -> value.all(::hasStrictJsonValues)
        is JsonPrimitive -> value === JsonNull || value.isString || value.content in setOf("true", "false") || jsonNumber.matches(value.content)
    }

    /** Bound nesting before the library parser, and reject duplicate (including escaped) keys. */
    private fun boundedJsonStructure(raw: String): Boolean {
        val stack = ArrayList<MutableSet<String>?>()
        val closers = ArrayList<Char>()
        var inString = false
        var escaped = false
        var stringStart = 0
        for (i in raw.indices) {
            val c = raw[i]
            if (inString) {
                if (c < ' ') return false
                if (escaped) { escaped = false; continue }
                if (c == '\\') { escaped = true; continue }
                if (c == '"') {
                    inString = false
                    var next = i + 1
                    while (next < raw.length && raw[next] in " \t\r\n") next++
                    if (next < raw.length && raw[next] == ':') {
                        val keys = stack.lastOrNull() ?: return false
                        val key = try { (json.parseToJsonElement(raw.substring(stringStart, i + 1)) as JsonPrimitive).content }
                        catch (_: Exception) { return false }
                        if (!keys.add(key)) return false
                    }
                }
            } else when (c) {
                '"' -> { inString = true; stringStart = i }
                '{', '[' -> {
                    if (stack.size >= MAX_DEPTH) return false
                    stack.add(if (c == '{') HashSet() else null)
                    closers.add(if (c == '{') '}' else ']')
                }
                '}', ']' -> {
                    if (closers.lastOrNull() != c) return false
                    closers.removeAt(closers.lastIndex)
                    stack.removeAt(stack.lastIndex)
                }
            }
        }
        return !inString && stack.isEmpty()
    }
}
