package takagi.ru.monica.keepass

import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

object KeePassTotpCodec {
    const val FIELD_OTP = "otp"
    const val FIELD_TOTP_SEED = "TOTP Seed"
    const val FIELD_TOTP_SETTINGS = "TOTP Settings"
    const val FIELD_TOTP_PERIOD = "TOTP Period"
    const val FIELD_TOTP_DIGITS = "TOTP Digits"
    const val FIELD_TOTP_ALGORITHM = "TOTP Algorithm"
    const val FIELD_OTP_TYPE = "OTP Type"
    const val FIELD_HOTP_COUNTER = "HOTP Counter"

    private val legacyFieldNames = setOf(
        FIELD_OTP, FIELD_TOTP_SEED, FIELD_TOTP_SETTINGS, FIELD_TOTP_PERIOD, FIELD_TOTP_DIGITS,
        FIELD_TOTP_ALGORITHM, FIELD_OTP_TYPE, FIELD_HOTP_COUNTER,
        "TOTPSeed", "TOTPSettings", "TOTPPeriod", "TOTPDigits", "TOTPAlgorithm",
        "OTPType", "TOTP Type", "TOTPType", "HOTPCounter"
    )
    private val secretSuffixes = listOf("Secret", "Secret-Hex", "Secret-Base32", "Secret-Base64")
    val fieldNames: Set<String> = legacyFieldNames +
        secretSuffixes.flatMap { listOf("TimeOtp-$it", "HmacOtp-$it") } +
        setOf("TimeOtp-Length", "TimeOtp-Period", "TimeOtp-Algorithm", "HmacOtp-Counter")
    private val normalizedFieldNames = fieldNames.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }

    fun isOtpField(name: String): Boolean = name.lowercase(Locale.ROOT) in normalizedFieldNames

    fun isSecretField(name: String): Boolean = name.equals(FIELD_OTP, true) ||
        name.equals(FIELD_TOTP_SEED, true) || name.equals("TOTPSeed", true) ||
        secretSuffixes.any { name.equals("TimeOtp-$it", true) || name.equals("HmacOtp-$it", true) }

    /** KeePass, KeePassXC/KeePassDX, KeeOtp and Tray TOTP share this reader. */
    fun parseFields(
        getField: (String) -> String,
        issuer: String = "",
        accountName: String = "",
        link: String = ""
    ): TotpData? {
        fun value(vararg names: String) = names.firstNotNullOfOrNull { getField(it).takeIf(String::isNotBlank) }.orEmpty()
        parseOtpAuthUri(getField(FIELD_OTP), issuer, accountName, link)?.let { return it }
        parseNativeFields(getField, "TimeOtp", issuer, accountName, link)?.let { return it }
        parse(Fields(
            otp = getField(FIELD_OTP), seed = value(FIELD_TOTP_SEED, "TOTPSeed"),
            settings = value(FIELD_TOTP_SETTINGS, "TOTPSettings"), period = value(FIELD_TOTP_PERIOD, "TOTPPeriod"),
            digits = value(FIELD_TOTP_DIGITS, "TOTPDigits"), algorithm = value(FIELD_TOTP_ALGORITHM, "TOTPAlgorithm"),
            counter = value(FIELD_HOTP_COUNTER, "HOTPCounter"), type = value(FIELD_OTP_TYPE, "OTPType", "TOTP Type", "TOTPType"),
            issuer = issuer, accountName = accountName, link = link
        ))?.let { return it }
        return parseNativeFields(getField, "HmacOtp", issuer, accountName, link)
    }

    private fun parseNativeFields(
        getField: (String) -> String, prefix: String, issuer: String, accountName: String, link: String
    ): TotpData? = runCatching {
        val secrets = secretSuffixes.map { it to getField("$prefix-$it") }.filter { it.second.isNotEmpty() }
        // KeePass requires exactly one encoding. Do not silently choose between different secrets.
        val (encoding, value) = secrets.singleOrNull() ?: return null
        val secret = when (encoding) {
            "Secret" -> encodeBase32(value.toByteArray(Charsets.UTF_8))
            "Secret-Hex" -> {
                val hex = value.filterNot(Char::isWhitespace)
                require(hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null })
                encodeBase32(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            "Secret-Base64" -> encodeBase32(Base64.getDecoder().decode(value.filterNot(Char::isWhitespace)))
            else -> normalizeSecret(value)
        }
        if (!isValidSecret(secret)) return null
        val hotp = prefix == "HmacOtp"
        val counterText = getField("HmacOtp-Counter").trim()
        val counter = if (hotp && counterText.isNotEmpty()) {
            counterText.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        } else 0L
        TotpData(
            secret = secret, issuer = issuer, accountName = accountName, link = link,
            otpType = if (hotp) OtpType.HOTP else OtpType.TOTP,
            counter = counter,
            period = getField("TimeOtp-Period").trim().toIntOrNull()?.takeIf { it > 0 } ?: 30,
            digits = if (hotp) 6 else getField("TimeOtp-Length").trim().toIntOrNull()?.takeIf { it in 1..8 } ?: 6,
            algorithm = if (hotp) "SHA1" else normalizeAlgorithm(getField("TimeOtp-Algorithm")) ?: return null
        )
    }.getOrNull()

    data class Fields(
        val otp: String = "",
        val seed: String = "",
        val settings: String = "",
        val period: String = "",
        val digits: String = "",
        val algorithm: String = "",
        val counter: String = "",
        val type: String = "",
        val issuer: String = "",
        val accountName: String = "",
        val link: String = ""
    )

    fun parse(fields: Fields): TotpData? {
        parseOtpAuthUri(fields.otp, fields.issuer, fields.accountName, fields.link)?.let { return it }

        if (fields.otp.contains("=") && !fields.otp.contains("://")) {
            val query = runCatching { parseQuery(fields.otp) }.getOrNull().orEmpty()
            val key = query["key"]
            if (key != null) return parse(fields.copy(
                otp = "", seed = key,
                period = query["step"] ?: fields.period,
                digits = query["size"] ?: fields.digits,
                algorithm = query["algorithm"] ?: fields.algorithm,
                type = query["type"] ?: fields.type,
                counter = query["counter"] ?: fields.counter
            ))
        }

        val secret = normalizeSecret(
            when {
                fields.seed.isNotBlank() -> fields.seed
                fields.otp.isNotBlank() && !fields.otp.contains("://") -> fields.otp
                else -> ""
            }
        )
        if (!isValidSecret(secret)) return null

        if (fields.counter.isNotBlank() && fields.counter.trim().toLongOrNull()?.takeIf { it >= 0 } == null) return null
        val settings = parseSettings(fields)
        if (settings.counter < 0) return null
        return TotpData(
            secret = secret,
            issuer = fields.issuer,
            accountName = fields.accountName,
            period = settings.period.takeIf { it > 0 } ?: 30,
            digits = if (settings.otpType == OtpType.STEAM) 5 else settings.digits.takeIf { it in 1..10 } ?: 6,
            algorithm = normalizeAlgorithm(settings.algorithm) ?: return null,
            otpType = settings.otpType,
            counter = settings.counter.coerceAtLeast(0),
            link = fields.link
        )
    }

    fun toKeePassFields(data: TotpData, title: String): Map<String, String> {
        if (data.otpType !in setOf(OtpType.TOTP, OtpType.HOTP, OtpType.STEAM)) return emptyMap()
        val normalized = data.copy(
            secret = normalizeSecret(data.secret),
            algorithm = normalizeAlgorithm(data.algorithm) ?: return emptyMap(),
            period = data.period.takeIf { it > 0 } ?: 30,
            digits = if (data.otpType == OtpType.STEAM) 5 else data.digits.takeIf { it in 1..10 } ?: 6,
            counter = data.counter.coerceAtLeast(0L)
        )
        if (!isValidSecret(normalized.secret)) return emptyMap()

        return buildMap {
            put(FIELD_OTP, buildOtpAuthUri(normalized, title))
            if (normalized.otpType == OtpType.HOTP) {
                put(FIELD_OTP_TYPE, "HOTP")
                put(FIELD_HOTP_COUNTER, normalized.counter.toString())
                // KeePass's native HOTP generator supports RFC 4226 (SHA-1, six digits).
                if (normalized.algorithm == "SHA1" && normalized.digits == 6) {
                    put("HmacOtp-Secret-Base32", normalized.secret)
                    put("HmacOtp-Counter", normalized.counter.toString())
                }
            } else {
                put(FIELD_OTP_TYPE, normalized.otpType.name)
                put(FIELD_TOTP_SEED, normalized.secret)
                put(FIELD_TOTP_SETTINGS, "${normalized.period};${if (normalized.otpType == OtpType.STEAM) "S" else normalized.digits}")
                put(FIELD_TOTP_PERIOD, normalized.period.toString())
                put(FIELD_TOTP_DIGITS, normalized.digits.toString())
                put(FIELD_TOTP_ALGORITHM, normalized.algorithm)
                if (normalized.otpType == OtpType.TOTP && normalized.digits <= 8) {
                    put("TimeOtp-Secret-Base32", normalized.secret)
                    put("TimeOtp-Length", normalized.digits.toString())
                    put("TimeOtp-Period", normalized.period.toString())
                    put("TimeOtp-Algorithm", "HMAC-SHA-${normalized.algorithm.removePrefix("SHA")}")
                }
            }
        }
    }

    fun normalizeSecret(value: String): String {
        return value
            .replace(Regex("[\\s\\-]"), "")
            .uppercase(Locale.ROOT)
            .trimEnd('=')
    }

    fun isValidSecret(secret: String): Boolean = secret.isNotEmpty() &&
        secret.all { it in 'A'..'Z' || it in '2'..'7' } && secret.length % 8 !in setOf(1, 3, 6)

    private fun normalizeAlgorithm(value: String): String? {
        val normalized = value.trim().uppercase(Locale.ROOT).removePrefix("HMAC").replace("-", "").replace("_", "")
        return normalized.ifBlank { "SHA1" }.takeIf { it in setOf("SHA1", "SHA256", "SHA512") }
    }

    private fun encodeBase32(bytes: ByteArray): String = buildString {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(alphabet[(buffer ushr bits) and 31])
            }
        }
        if (bits > 0) append(alphabet[(buffer shl (5 - bits)) and 31])
    }

    private data class ParsedSettings(
        val period: Int = 30,
        val digits: Int = 6,
        val algorithm: String = "SHA1",
        val otpType: OtpType = OtpType.TOTP,
        val counter: Long = 0L
    )

    private fun parseSettings(fields: Fields): ParsedSettings {
        var period = 30
        var digits = 6
        var algorithm = "SHA1"
        var otpType = OtpType.TOTP
        var counter = 0L

        val tokens = fields.settings.split(";", ",", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var positionalIndex = 0
        tokens.forEach { token ->
            if (token.contains("=")) {
                val parts = token.split("=", limit = 2)
                val key = parts[0].trim().lowercase(Locale.ROOT)
                val value = parts.getOrNull(1)?.trim().orEmpty()
                when (key) {
                    "period", "step", "time_step" -> value.toIntOrNull()?.let { period = it }
                    "digits", "length" -> value.toIntOrNull()?.let { digits = it }
                    "algorithm", "algo", "digest" -> if (value.isNotBlank()) algorithm = value.uppercase(Locale.ROOT)
                    "counter" -> value.toLongOrNull()?.let {
                        counter = it
                        otpType = OtpType.HOTP
                    }
                    "type", "otp_type", "encoder" -> when (value.lowercase(Locale.ROOT)) {
                        "hotp" -> otpType = OtpType.HOTP
                        "steam", "s" -> otpType = OtpType.STEAM
                    }
                }
            } else {
                token.toIntOrNull()?.let { number ->
                    when (positionalIndex++) {
                        0 -> period = number
                        1 -> digits = number
                    }
                }
                if (token.startsWith("SHA", ignoreCase = true)) {
                    algorithm = token.uppercase(Locale.ROOT)
                }
                if (token.equals("HOTP", ignoreCase = true)) {
                    otpType = OtpType.HOTP
                }
                if (token.equals("S", ignoreCase = true) || token.equals("STEAM", ignoreCase = true)) {
                    otpType = OtpType.STEAM
                }
            }
        }

        fields.period.toIntOrNull()?.let { period = it }
        fields.digits.toIntOrNull()?.let { digits = it }
        if (fields.algorithm.isNotBlank()) {
            algorithm = fields.algorithm.uppercase(Locale.ROOT)
        }
        fields.counter.toLongOrNull()?.let {
            counter = it
            otpType = OtpType.HOTP
        }
        when (fields.type.trim().uppercase(Locale.ROOT)) {
            "HOTP" -> otpType = OtpType.HOTP
            "TOTP" -> otpType = OtpType.TOTP
            "STEAM", "S" -> otpType = OtpType.STEAM
        }

        return ParsedSettings(
            period = period,
            digits = digits,
            algorithm = algorithm,
            otpType = otpType,
            counter = counter
        )
    }

    private fun parseOtpAuthUri(
        uri: String,
        fallbackIssuer: String,
        fallbackAccount: String,
        fallbackLink: String
    ): TotpData? {
        if (!uri.trim().startsWith("otpauth://", ignoreCase = true)) return null
        return runCatching {
            val parsed = URI(uri.trim().replace(" ", "%20"))
            val typeRaw = parsed.host?.lowercase(Locale.ROOT).orEmpty()
            if (typeRaw !in setOf("totp", "hotp", "steam")) return null
            val params = parseQuery(parsed.rawQuery.orEmpty())
            val otpType = when {
                typeRaw == "hotp" -> OtpType.HOTP
                typeRaw == "steam" || params["encoder"].equals("steam", true) -> OtpType.STEAM
                else -> OtpType.TOTP
            }

            val decodedLabel = URLDecoder.decode(parsed.rawPath.orEmpty().trimStart('/').replace("+", "%2B"), "UTF-8")
            val (labelIssuer, labelAccount) = if (decodedLabel.contains(":")) {
                val parts = decodedLabel.split(":", limit = 2)
                parts[0] to parts[1]
            } else {
                "" to decodedLabel
            }

            val secret = normalizeSecret(params["secret"].orEmpty())
            if (!isValidSecret(secret)) return null

            val issuer = params["issuer"].orEmpty().ifBlank { labelIssuer }.ifBlank { fallbackIssuer }
            val account = labelAccount.ifBlank { fallbackAccount }
            val algorithm = normalizeAlgorithm(params["algorithm"].orEmpty()) ?: return null
            val digits = if (otpType == OtpType.STEAM) 5 else params["digits"]?.toIntOrNull()?.takeIf { it in 1..10 } ?: 6
            val period = params["period"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30
            val counter = params["counter"]?.let { raw ->
                raw.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            } ?: 0L

            TotpData(
                secret = secret,
                issuer = issuer,
                accountName = account,
                period = period,
                digits = digits,
                algorithm = algorithm,
                otpType = otpType,
                counter = counter,
                link = fallbackLink
            )
        }.getOrNull()
    }

    private fun buildOtpAuthUri(data: TotpData, title: String): String {
        val type = if (data.otpType == OtpType.HOTP) "hotp" else "totp"
        val label = encodeUriComponent(
            when {
                data.issuer.isNotBlank() && data.accountName.isNotBlank() -> "${data.issuer}:${data.accountName}"
                data.accountName.isNotBlank() -> data.accountName
                data.issuer.isNotBlank() -> data.issuer
                title.isNotBlank() -> title
                else -> "Authenticator"
            }
        )
        val query = buildList {
            add("secret=${encodeUriComponent(data.secret)}")
            if (data.otpType == OtpType.STEAM) add("encoder=steam")
            if (data.issuer.isNotBlank()) {
                add("issuer=${encodeUriComponent(data.issuer)}")
            }
            if (!data.algorithm.equals("SHA1", ignoreCase = true)) {
                add("algorithm=${encodeUriComponent(data.algorithm.uppercase(Locale.ROOT))}")
            }
            if (data.digits != 6) {
                add("digits=${data.digits}")
            }
            if (data.period != 30) {
                add("period=${data.period}")
            }
            if (data.otpType == OtpType.HOTP) {
                add("counter=${data.counter.coerceAtLeast(0L)}")
            }
        }.joinToString("&")
        return "otpauth://$type/$label?$query"
    }

    private fun encodeUriComponent(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').mapNotNull { pair ->
        val parts = pair.split('=', limit = 2)
        if (parts.size != 2) null else URLDecoder.decode(parts[0], "UTF-8").lowercase(Locale.ROOT) to
            URLDecoder.decode(parts[1], "UTF-8")
    }.toMap()
}
