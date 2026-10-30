package takagi.ru.monica.rustcore

/** No string conversion or parameter normalization is allowed at this boundary. */
internal object RustKeePassKdfCore {
    @Volatile private var loadAttempted = false
    @Volatile private var available = false

    fun derive(key: ByteArray, salt: ByteArray, iterations: Int, memoryKiB: Int,
               parallelism: Int, variant: Int, version: Int): ByteArray? {
        if (!loadAttempted) synchronized(this) {
            if (!loadAttempted) {
                available = runCatching { System.loadLibrary("monica_rust_jni"); true }.getOrDefault(false)
                loadAttempted = true
            }
        }
        if (!available) return null
        return runCatching {
            nativeDerive(key, salt, iterations, memoryKiB, parallelism, variant, version)
        }.getOrNull()
    }

    @JvmStatic private external fun nativeDerive(
        key: ByteArray, salt: ByteArray, iterations: Int, memoryKiB: Int,
        parallelism: Int, variant: Int, version: Int,
    ): ByteArray?

    fun deriveAes(key: ByteArray, seed: ByteArray, rounds: Long): ByteArray? {
        if (!loadAttempted) synchronized(this) {
            if (!loadAttempted) {
                available = runCatching { System.loadLibrary("monica_rust_jni"); true }.getOrDefault(false)
                loadAttempted = true
            }
        }
        if (!available) return null
        return runCatching { nativeDeriveAes(key, seed, rounds) }.getOrNull()
    }

    @JvmStatic private external fun nativeDeriveAes(key: ByteArray, seed: ByteArray, rounds: Long): ByteArray?
}
