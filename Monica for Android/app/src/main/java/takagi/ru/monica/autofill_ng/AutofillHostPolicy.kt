package takagi.ru.monica.autofill_ng

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Parses browser authorities locally; never resolves a host or guesses an IPv6 port. */
internal object AutofillHostPolicy {
    fun address(value: String?): HttpUrl? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8192 } ?: return null
        if (raw.any { it.isWhitespace() || it.isISOControl() }) return null
        val authority = raw.substringAfter("://", raw).substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return null
        val parsed = when {
            raw.contains("://") -> raw.toHttpUrlOrNull()
            !raw.startsWith('[') && raw.count { it == ':' } > 1 -> runCatching {
                HttpUrl.Builder().scheme("https").host(raw).build()
            }.getOrNull()
            else -> "https://$raw".toHttpUrlOrNull()
        } ?: return null
        val host = parsed.host.trimEnd('.')
        // Browsers can interpret abbreviated/leading-zero numeric hosts differently.
        // Accept only an unambiguous four-octet decimal IPv4 representation.
        if (host.all { it.isDigit() || it == '.' } && !isIpv4(host)) return null
        return if (host == parsed.host) parsed else runCatching { parsed.newBuilder().host(host).build() }.getOrNull()
    }

    fun host(value: String?): String? = address(value)?.host

    fun origin(scheme: String, host: String): String =
        "$scheme://${if (':' in host) "[$host]" else host}"

    fun isIp(host: String): Boolean = ':' in host || isIpv4(host)

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' } &&
                (part.length == 1 || !part.startsWith('0')) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
