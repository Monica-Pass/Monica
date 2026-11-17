package takagi.ru.monica.util

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * 统一处理 Base32 secret 与 otpauth URI 两种验证器密钥格式。
 */
object TotpDataResolver {
    private const val DEFAULT_PERIOD = 30
    private const val DEFAULT_DIGITS = 6
    private const val DEFAULT_ALGORITHM = "SHA1"
    private val json = Json { ignoreUnknownKeys = true }

    fun fromAuthenticatorKey(
        rawKey: String,
        fallbackIssuer: String = "",
        fallbackAccountName: String = "",
        depth: Int = 0
    ): TotpData? {
        val normalizedKey = rawKey.trim()
        if (normalizedKey.isBlank() || isStoredCiphertext(normalizedKey)) return null
        if (normalizedKey.startsWith('{') || normalizedKey.startsWith('[')) return null

        val parsedFromUri = parseUriTotpData(normalizedKey)
        val initialData = if (parsedFromUri != null) {
            parsedFromUri.copy(
                issuer = parsedFromUri.issuer.ifBlank { fallbackIssuer.trim() },
                accountName = parsedFromUri.accountName.ifBlank { fallbackAccountName.trim() }
            )
        } else {
            TotpData(
                secret = if (normalizedKey.contains("://")) "" else normalizedKey,
                issuer = fallbackIssuer.trim(),
                accountName = fallbackAccountName.trim()
            )
        }

        return normalizeTotpData(initialData, depth)
    }

    fun normalizeTotpData(data: TotpData, depth: Int = 0): TotpData {
        // 修复历史损坏数据：secret 字段误存了完整 URI（如 "steam://BASE64..."）
        if (data.secret.contains("://") && depth < 3) {
            reparseCorruptedSecret(data)?.let { return normalizeTotpData(it, depth + 1) }
        }

        val mergedData = parseUriTotpData(data.secret)?.let { parsed ->
            mergeParsedData(data, parsed)
        } ?: data

        val contextAwareData = applyContextualOtpType(mergedData)

        val safePeriod = when (contextAwareData.otpType) {
            OtpType.STEAM -> DEFAULT_PERIOD
            else -> contextAwareData.period.takeIf { it > 0 } ?: DEFAULT_PERIOD
        }
        val safeDigits = when (contextAwareData.otpType) {
            OtpType.STEAM -> 5
            else -> contextAwareData.digits.coerceIn(4, 10)
        }
        val normalizedAlgorithm = when (contextAwareData.otpType) {
            OtpType.STEAM -> DEFAULT_ALGORITHM
            else -> normalizeAlgorithm(contextAwareData.algorithm)
        }
        val normalizedSecret = when (contextAwareData.otpType) {
            OtpType.MOTP -> contextAwareData.secret.trim()
            else -> normalizeBase32Secret(contextAwareData.secret)
        }

        return contextAwareData.copy(
            secret = normalizedSecret,
            issuer = contextAwareData.issuer.trim(),
            accountName = contextAwareData.accountName.trim(),
            period = safePeriod,
            digits = safeDigits,
            algorithm = normalizedAlgorithm,
            counter = contextAwareData.counter.coerceAtLeast(0L)
        )
    }

    fun normalizeBase32Secret(secret: String): String {
        return secret
            .trim()
            .replace(" ", "")
            .replace("-", "")
            .uppercase()
    }

    fun parseStoredItemData(
        itemData: String,
        fallbackIssuer: String = "",
        fallbackAccountName: String = "",
        decryptIfNeeded: ((String) -> String)? = null
    ): TotpData? {
        val resolvedItemData = decryptIfNeeded?.let { decrypt ->
            try { decrypt(itemData) } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: Exception) { return null }
        } ?: itemData
        if (isStoredCiphertext(resolvedItemData.trim())) return null

        runCatching {
            json.decodeFromString<TotpData>(resolvedItemData)
        }.getOrNull()?.let { decoded ->
            return normalizeTotpData(
                decoded.copy(
                    issuer = decoded.issuer.ifBlank { fallbackIssuer.trim() },
                    accountName = decoded.accountName.ifBlank { fallbackAccountName.trim() }
                )
            )
        }

        runCatching {
            json.parseToJsonElement(resolvedItemData) as? JsonObject
        }.getOrNull()?.let { obj ->
            // A malformed record must not terminate the shared authenticator list flow.
            if (listOf("secret", "key", "issuer", "serviceName", "account", "accountName", "period", "digits", "algorithm")
                    .any { obj[it] != null && obj[it] !is JsonPrimitive }) return null
            val secret = obj["secret"]?.jsonPrimitive?.content
                ?: obj["key"]?.jsonPrimitive?.content
                ?: ""
            val issuer = obj["issuer"]?.jsonPrimitive?.content
                ?: obj["serviceName"]?.jsonPrimitive?.content
                ?: fallbackIssuer
            val accountName = obj["account"]?.jsonPrimitive?.content
                ?: obj["accountName"]?.jsonPrimitive?.content
                ?: fallbackAccountName
            val period = obj["period"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_PERIOD
            val digits = obj["digits"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_DIGITS
            val algorithm = obj["algorithm"]?.jsonPrimitive?.content ?: DEFAULT_ALGORITHM

            if (secret.isNotBlank() || issuer.isNotBlank() || accountName.isNotBlank()) {
                return normalizeTotpData(
                    TotpData(
                        secret = secret,
                        issuer = issuer,
                        accountName = accountName,
                        period = period,
                        digits = digits,
                        algorithm = algorithm
                    )
                )
            }
        }

        return fromAuthenticatorKey(
            rawKey = resolvedItemData,
            fallbackIssuer = fallbackIssuer,
            fallbackAccountName = fallbackAccountName
        )
    }

    private fun isStoredCiphertext(value: String): Boolean =
        value.startsWith("MDK|") || value.startsWith("V2|") || value.startsWith("C2|")

    fun toBitwardenPayload(title: String, data: TotpData): String {
        val rawSteamSharedSecret = data.steamSharedSecretBase64.trim()
        val normalized = normalizeTotpData(data)
        if (normalized.otpType == OtpType.STEAM && rawSteamSharedSecret.isNotBlank()) {
            return "steam://$rawSteamSharedSecret"
        }
        if (normalized.otpType == OtpType.STEAM) {
            return buildSteamOtpAuthPayload(title, normalized)
        }

        val shouldUseOtpAuthPayload =
            normalized.otpType != OtpType.TOTP ||
                !normalized.algorithm.equals(DEFAULT_ALGORITHM, ignoreCase = true) ||
                normalized.digits != DEFAULT_DIGITS ||
                normalized.period != DEFAULT_PERIOD

        if (!shouldUseOtpAuthPayload) {
            return normalized.secret
        }

        val label = buildTotpLabel(
            title = title,
            issuer = normalized.issuer,
            accountName = normalized.accountName
        )
        // Stored password payloads need the PIN as well as the secret. Public QR exports
        // keep generateUri's default of omitting the PIN.
        return TotpUriParser.generateUri(label, normalized, includePin = true)
    }

    fun hasEquivalentOtpParameters(left: TotpData, right: TotpData): Boolean {
        val normalizedLeft = normalizeTotpData(left)
        val normalizedRight = normalizeTotpData(right)
        return normalizedLeft.secret == normalizedRight.secret &&
            normalizedLeft.issuer == normalizedRight.issuer &&
            normalizedLeft.accountName == normalizedRight.accountName &&
            normalizedLeft.algorithm == normalizedRight.algorithm &&
            normalizedLeft.digits == normalizedRight.digits &&
            normalizedLeft.period == normalizedRight.period &&
            normalizedLeft.otpType == normalizedRight.otpType &&
            normalizedLeft.counter == normalizedRight.counter &&
            normalizedLeft.pin == normalizedRight.pin
    }

    fun hasNonDefaultOtpSettings(data: TotpData): Boolean {
        val normalized = normalizeTotpData(data)
        return normalized.otpType != OtpType.TOTP ||
            !normalized.algorithm.equals(DEFAULT_ALGORITHM, ignoreCase = true) ||
            normalized.digits != DEFAULT_DIGITS ||
            normalized.period != DEFAULT_PERIOD ||
            normalized.counter > 0L ||
            normalized.pin.isNotBlank()
    }

    private fun parseUriTotpData(raw: String): TotpData? {
        if (!raw.contains("://")) return null
        parseBitwardenSteamUri(raw)?.let { return it }
        return TotpUriParser.parseUri(raw.trim())?.totpData
            ?: parseOtpAuthUriFallback(raw)
    }

    private fun parseOtpAuthUriFallback(raw: String): TotpData? {
        val normalized = raw.trim()
        if (!normalized.startsWith("otpauth://", ignoreCase = true)) return null

        val withoutScheme = normalized.substringAfter("://", missingDelimiterValue = "")
        val authority = withoutScheme.substringBefore("/", missingDelimiterValue = "").lowercase()
        if (authority != "totp" && authority != "hotp" && authority != "yaotp") return null

        val pathAndQuery = withoutScheme.substringAfter("/", missingDelimiterValue = "")
        val label = decodeUriComponent(pathAndQuery.substringBefore("?", missingDelimiterValue = ""))
        val query = pathAndQuery.substringAfter("?", missingDelimiterValue = "")
        val queryParams = query.split("&")
            .filter { it.isNotBlank() }
            .mapNotNull { segment ->
                val key = decodeUriComponent(segment.substringBefore("=")).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                key to decodeUriComponent(segment.substringAfter("=", missingDelimiterValue = ""))
            }
            .toMap()

        val secret = queryParams["secret"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val queryIssuer = queryParams["issuer"]?.trim().orEmpty()
        val labelIssuer = label.substringBefore(":", missingDelimiterValue = "").trim()
        val issuer = queryIssuer.ifBlank { labelIssuer }
        val accountName = if (issuer.isNotBlank() && label.startsWith("$issuer:")) {
            label.removePrefix("$issuer:").trim()
        } else if (label.contains(":")) {
            label.substringAfter(":").trim()
        } else {
            label.trim()
        }
        val algorithm = queryParams["algorithm"]?.trim()?.uppercase().orEmpty().ifBlank { DEFAULT_ALGORITHM }
        val period = queryParams["period"]?.toIntOrNull() ?: DEFAULT_PERIOD
        val requestedDigits = queryParams["digits"]?.toIntOrNull() ?: DEFAULT_DIGITS
        val counter = queryParams["counter"]?.toLongOrNull() ?: 0L
        val encoder = queryParams["encoder"]?.lowercase().orEmpty()
        val issuerLower = issuer.lowercase()
        val otpType = when {
            authority == "hotp" -> OtpType.HOTP
            authority == "yaotp" -> OtpType.YANDEX
            encoder == "steam" || issuerLower.contains("steam") -> OtpType.STEAM
            issuerLower.contains("yandex") -> OtpType.YANDEX
            else -> OtpType.TOTP
        }

        return TotpData(
            secret = secret,
            issuer = issuer,
            accountName = accountName,
            period = period,
            digits = if (otpType == OtpType.STEAM) 5 else requestedDigits,
            algorithm = algorithm,
            otpType = otpType,
            counter = counter,
            pin = queryParams["pin"].orEmpty()
        )
    }

    private fun parseBitwardenSteamUri(raw: String): TotpData? {
        val normalized = raw.trim()
        if (!normalized.startsWith("steam://", ignoreCase = true)) return null
        // '/' belongs to the Base64 key, including immediately after the scheme.
        val secretPart = normalized
            .substringAfter("://", "")
            .substringBefore("?")
            .substringBefore("#")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return null
        val decodedSecret = runCatching {
            URLDecoder.decode(secretPart.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(secretPart)
        val base64Bytes = decodeSteamSharedSecret(decodedSecret)
        val secretBase32 = base64Bytes?.let(::base32Encode)
            ?: normalizeBase32Secret(decodedSecret)

        return TotpData(
            secret = secretBase32,
            issuer = "Steam",
            accountName = "",
            period = DEFAULT_PERIOD,
            digits = 5,
            algorithm = DEFAULT_ALGORITHM,
            otpType = OtpType.STEAM,
            steamSharedSecretBase64 = decodedSecret.takeIf { base64Bytes != null }.orEmpty()
        )
    }

    private fun applyContextualOtpType(source: TotpData): TotpData {
        if (source.otpType != OtpType.TOTP) return source

        val context = buildString {
            append(source.issuer)
            append(' ')
            append(source.accountName)
            append(' ')
            append(source.associatedApp)
            append(' ')
            append(source.link)
        }.lowercase()

        val looksLikeSteam =
            context.contains("steamcommunity") ||
                context.contains("steampowered") ||
                context.contains("steam")

        if (!looksLikeSteam) return source

        return source.copy(
            otpType = OtpType.STEAM,
            digits = 5,
            period = DEFAULT_PERIOD,
            algorithm = DEFAULT_ALGORITHM
        )
    }

    private fun mergeParsedData(source: TotpData, parsed: TotpData): TotpData {
        val sourceAlgorithm = normalizeAlgorithm(source.algorithm)
        val parsedAlgorithm = normalizeAlgorithm(parsed.algorithm)

        val shouldUseParsedPeriod = source.period <= 0 || source.period == DEFAULT_PERIOD
        val shouldUseParsedDigits = source.digits <= 0 || source.digits == DEFAULT_DIGITS
        val shouldUseParsedAlgorithm = sourceAlgorithm == DEFAULT_ALGORITHM
        val shouldUseParsedType = source.otpType == OtpType.TOTP
        val shouldUseParsedCounter = source.counter <= 0L

        return source.copy(
            secret = parsed.secret,
            issuer = source.issuer.ifBlank { parsed.issuer },
            accountName = source.accountName.ifBlank { parsed.accountName },
            period = if (shouldUseParsedPeriod) parsed.period else source.period,
            digits = if (shouldUseParsedDigits) parsed.digits else source.digits,
            algorithm = if (shouldUseParsedAlgorithm) parsedAlgorithm else sourceAlgorithm,
            otpType = if (shouldUseParsedType) parsed.otpType else source.otpType,
            counter = if (shouldUseParsedCounter) parsed.counter else source.counter,
            pin = source.pin.ifBlank { parsed.pin }
        )
    }

    /**
     * 修复历史损坏数据：旧代码无法解析 steam:// 等 URI，
     * 导致整个 URI 字符串被误存为 Base32 secret。
     * 尝试从 secret 中重新解析正确的 TotpData。
     */
    private fun reparseCorruptedSecret(data: TotpData): TotpData? {
        val raw = data.secret.trim()
        if (!raw.contains("://")) return null

        val reparsed = fromAuthenticatorKey(
            rawKey = raw,
            fallbackIssuer = data.issuer.ifBlank { data.accountName },
            fallbackAccountName = data.accountName,
            depth = 1
        ) ?: return null

        // 防止递归：修复后 secret 仍含 "://" 则放弃
        if (reparsed.secret.contains("://")) return null

        // 仅当解析结果与原数据有实质差异时才采用
        if (reparsed.otpType == data.otpType &&
            reparsed.secret == data.secret
        ) return null

        return data.copy(
            secret = reparsed.secret,
            otpType = reparsed.otpType,
            digits = reparsed.digits,
            period = reparsed.period,
            algorithm = reparsed.algorithm,
            issuer = data.issuer.ifBlank { reparsed.issuer },
            accountName = data.accountName.ifBlank { reparsed.accountName },
            steamSharedSecretBase64 = reparsed.steamSharedSecretBase64
                .ifBlank { data.steamSharedSecretBase64 }
        )
    }

    private fun normalizeAlgorithm(algorithm: String): String {
        return algorithm.trim().uppercase().ifBlank { DEFAULT_ALGORITHM }
    }

    private fun decodeSteamSharedSecret(secret: String): ByteArray? {
        val compact = secret.trim().replace("\\s".toRegex(), "")
        if (compact.isBlank()) return null
        val hasBase64OnlyChars = compact.all {
            it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_'
        }
        val hasNonBase32Marker = compact.any {
            val upper = it.uppercaseChar()
            !(upper in 'A'..'Z' || upper in '2'..'7')
        }
        if (!hasBase64OnlyChars || !hasNonBase32Marker) return null

        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return sequenceOf(
            { Base64.getDecoder().decode(padded) },
            { Base64.getUrlDecoder().decode(padded) }
        ).firstNotNullOfOrNull { decode ->
            runCatching { decode() }
                .getOrNull()
                ?.takeIf { it.size >= 10 }
        }
    }

    private fun base32Encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0

        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                output.append(alphabet[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }

        if (bitsLeft > 0) {
            output.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1F])
        }

        return output.toString()
    }

    private fun buildSteamOtpAuthPayload(title: String, data: TotpData): String {
        val label = encodeUriComponent(
            buildTotpLabel(
                title = title,
                issuer = data.issuer,
                accountName = data.accountName
            )
        )
        val query = buildList {
            add("secret=${encodeUriComponent(data.secret)}")
            if (data.issuer.isNotBlank()) {
                add("issuer=${encodeUriComponent(data.issuer)}")
            }
            add("digits=5")
            add("encoder=steam")
        }.joinToString("&")
        return "otpauth://totp/$label?$query"
    }

    private fun encodeUriComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decodeUriComponent(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name()) }
            .getOrDefault(value)

    private fun buildTotpLabel(title: String, issuer: String, accountName: String): String {
        val normalizedIssuer = issuer.trim()
        val normalizedAccount = accountName.trim()
        return when {
            normalizedIssuer.isNotBlank() && normalizedAccount.isNotBlank() -> {
                "$normalizedIssuer:$normalizedAccount"
            }
            title.isNotBlank() -> title.trim()
            normalizedIssuer.isNotBlank() -> normalizedIssuer
            normalizedAccount.isNotBlank() -> normalizedAccount
            else -> "Authenticator"
        }
    }
}
