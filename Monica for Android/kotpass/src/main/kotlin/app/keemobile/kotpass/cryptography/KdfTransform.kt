package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.database.header.KdfParameters

/** Return null to use the original transform. The caller owns the borrowed input. */
fun interface Argon2KeyTransformer {
    fun transform(parameters: KdfParameters.Argon2, compositeKey: ByteArray): ByteArray?
}

fun interface AesKeyTransformer {
    fun transform(seed: ByteArray, rounds: ULong, compositeKey: ByteArray): ByteArray?
}

/** An explicit codec-call scope, never a global provider or a derived-key cache. */
object KdfTransform {
    private val transformer = ThreadLocal<Argon2KeyTransformer>()
    private val aesTransformer = ThreadLocal<AesKeyTransformer>()

    fun <T> withAes(provider: AesKeyTransformer, operation: () -> T): T {
        val previous = aesTransformer.get()
        aesTransformer.set(provider)
        try { return operation() }
        finally { if (previous == null) aesTransformer.remove() else aesTransformer.set(previous) }
    }

    internal fun aes(seed: ByteArray, rounds: ULong, key: ByteArray): ByteArray? =
        aesTransformer.get()?.transform(seed, rounds, key)?.also {
            require(it.size == 32) { "AES provider must return 32 bytes" }
        }

    fun <T> withArgon2(provider: Argon2KeyTransformer, operation: () -> T): T {
        val previous = transformer.get()
        transformer.set(provider)
        try { return operation() }
        finally {
            if (previous == null) transformer.remove() else transformer.set(previous)
        }
    }

    internal fun argon2(parameters: KdfParameters.Argon2, key: ByteArray): ByteArray? {
        return transformer.get()?.transform(parameters, key)?.also {
            require(it.size == 32) { "Argon2 provider must return 32 bytes" }
        }
    }
}
