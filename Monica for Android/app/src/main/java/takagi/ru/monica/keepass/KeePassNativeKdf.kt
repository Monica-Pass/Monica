package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.Argon2KeyTransformer
import app.keemobile.kotpass.database.header.KdfParameters
import takagi.ru.monica.rustcore.RustKeePassKdfCore

/** Same transform, same work factors. Unsupported parameters use unmodified Kotpass. */
internal object KeePassNativeKdf : Argon2KeyTransformer {
    override fun transform(parameters: KdfParameters.Argon2, compositeKey: ByteArray): ByteArray? {
        if (parameters.secretKey != null || parameters.associatedData != null) return null
        if (parameters.version != 0x10U && parameters.version != 0x13U) return null
        // Kotpass 0.10 converts bytes to a signed Int before dividing by 1024.
        // Do not change its semantics for oversized or unusual input headers.
        if (parameters.memory > Int.MAX_VALUE.toULong() || parameters.iterations > Int.MAX_VALUE.toULong()
            || parameters.parallelism > Int.MAX_VALUE.toUInt()) return null
        val memoryKiB = (parameters.memory / 1024U).toInt()
        val iterations = parameters.iterations.toInt()
        val lanes = parameters.parallelism.toInt()
        if (iterations <= 0 || lanes <= 0 || memoryKiB.toLong() < 8L * lanes) return null
        val salt = parameters.salt.toByteArray()
        try {
            if (salt.size < 8) return null
            return RustKeePassKdfCore.derive(compositeKey, salt, iterations, memoryKiB, lanes,
                if (parameters.variant == KdfParameters.Argon2.Variant.Argon2d) 0 else 2,
                parameters.version.toInt())
        } finally { salt.fill(0) }
    }
}
