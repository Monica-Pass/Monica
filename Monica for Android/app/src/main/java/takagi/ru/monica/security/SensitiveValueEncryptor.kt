package takagi.ru.monica.security

/** Write dependency; UI and ordering tests must opt into their own explicit substitute. */
internal fun interface SensitiveValueEncryptor {
    fun encrypt(value: String): String
}
