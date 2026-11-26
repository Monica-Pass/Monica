package takagi.ru.monica.security

/** Recognizes explicit formats only; ordinary text and ambiguous unprefixed V1 remain untouched. */
internal object LocalCipherEnvelope {
    enum class Format(val prefix: String, val deviceBound: Boolean) {
        MDK("MDK|", false), COMPAT("C2|", true), AUTHENTICATED_DEVICE("V2|", true)
    }

    fun format(value: String): Format? {
        val start = value.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return null
        return Format.entries.firstOrNull { value.startsWith(it.prefix, start) }
    }

    fun isDeviceBound(value: String): Boolean = format(value)?.deviceBound == true
}
