package takagi.ru.monica.rustcore

/**
 * Byte-oriented JNI facade for Bitwarden KDF primitives.
 *
 * Protocol-level normalization stays in Kotlin. This boundary receives the exact
 * password/salt bytes chosen by BitwardenCrypto and never logs or stringifies them.
 * A null result means callers must use the existing, security-reviewed fallback.
 */
object RustBitwardenKdfCore {
    @Volatile
    private var loadAttempted = false

    @Volatile
    private var nativeAvailable = false

    private fun ensureLoaded(): Boolean {
        if (!loadAttempted) {
            synchronized(this) {
                if (!loadAttempted) {
                    nativeAvailable = runCatching {
                        System.loadLibrary("monica_rust_jni")
                        true
                    }.getOrDefault(false)
                    loadAttempted = true
                }
            }
        }
        return nativeAvailable
    }

    fun derivePbkdf2Sha256(
        passwordBytes: ByteArray,
        saltBytes: ByteArray,
        iterations: Int,
    ): ByteArray? {
        require(iterations > 0) { "PBKDF2 iterations must be positive: $iterations" }
        if (!ensureLoaded()) return null
        return runCatching {
            nativeDerivePbkdf2Sha256(passwordBytes, saltBytes, iterations)
        }.getOrNull()
    }

    fun deriveArgon2id(
        passwordBytes: ByteArray,
        saltBytes: ByteArray,
        iterations: Int,
        memoryKiB: Int,
        parallelism: Int,
    ): ByteArray? {
        require(iterations > 0) { "Argon2 iterations must be positive: $iterations" }
        require(memoryKiB > 0) { "Argon2 memory must be positive: ${memoryKiB}KiB" }
        require(parallelism > 0) { "Argon2 parallelism must be positive: $parallelism" }
        if (!ensureLoaded()) return null
        return runCatching {
            nativeDeriveArgon2id(
                passwordBytes,
                saltBytes,
                iterations,
                memoryKiB,
                parallelism,
            )
        }.getOrNull()
    }

    @JvmStatic
    private external fun nativeDerivePbkdf2Sha256(
        passwordBytes: ByteArray,
        saltBytes: ByteArray,
        iterations: Int,
    ): ByteArray?

    @JvmStatic
    private external fun nativeDeriveArgon2id(
        passwordBytes: ByteArray,
        saltBytes: ByteArray,
        iterations: Int,
        memoryKiB: Int,
        parallelism: Int,
    ): ByteArray?
}
