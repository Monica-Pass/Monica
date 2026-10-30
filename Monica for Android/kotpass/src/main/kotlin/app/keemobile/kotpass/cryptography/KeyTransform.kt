package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.cryptography.engines.Argon2Engine
import app.keemobile.kotpass.cryptography.format.AesKdf
import app.keemobile.kotpass.cryptography.format.Argon2Kdf
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters.Aes
import app.keemobile.kotpass.database.header.KdfParameters.Argon2
import app.keemobile.kotpass.extensions.clear
import app.keemobile.kotpass.extensions.sha256
import app.keemobile.kotpass.extensions.sha512

internal object KeyTransform {
    fun compositeKey(credentials: Credentials): ByteArray {
        val items = listOfNotNull(
            credentials.passphrase?.getBinary(),
            credentials.key?.getBinary()
        )
        val composite = when {
            items.isNotEmpty() -> items.reduce { a, b -> a + b }
            else -> ByteArray(0)
        }

        return composite
            .sha256()
            .also { composite.clear() }
    }

    fun transformedKey(header: DatabaseHeader, credentials: Credentials): ByteArray {
        return when (header) {
            is DatabaseHeader.Ver3x -> {
                transformedAesKey(
                    credentials = credentials,
                    seed = header.transformSeed.toByteArray(),
                    rounds = header.transformRounds
                )
            }
            is DatabaseHeader.Ver4x -> {
                when (header.kdfParameters) {
                    is Aes -> {
                        transformedAesKey(
                            credentials = credentials,
                            seed = header.kdfParameters.seed.toByteArray(),
                            rounds = header.kdfParameters.rounds
                        )
                    }
                    is Argon2 -> {
                        val key = compositeKey(credentials)
                        try {
                            KdfTransform.argon2(header.kdfParameters, key) ?: Argon2Kdf.transformKey(
                            variant = when (header.kdfParameters.variant) {
                                Argon2.Variant.Argon2d -> Argon2Engine.Variant.Argon2d
                                Argon2.Variant.Argon2id -> Argon2Engine.Variant.Argon2id
                            },
                            version = Argon2Engine.Version.from(header.kdfParameters.version),
                            password = key,
                            salt = header.kdfParameters.salt.toByteArray(),
                            secretKey = header.kdfParameters.secretKey?.toByteArray(),
                            additional = header.kdfParameters.associatedData?.toByteArray(),
                            iterations = header.kdfParameters.iterations,
                            parallelism = header.kdfParameters.parallelism,
                            memory = header.kdfParameters.memory
                            )
                        } finally {
                            key.fill(0)
                        }
                    }
                }
            }
        }
    }

    fun masterKey(
        masterSeed: ByteArray,
        transformedKey: ByteArray
    ) = (masterSeed + transformedKey).sha256()

    private fun transformedAesKey(credentials: Credentials, seed: ByteArray, rounds: ULong): ByteArray {
        val key = compositeKey(credentials)
        try { return KdfTransform.aes(seed, rounds, key) ?: AesKdf.transformKey(key, seed, rounds) }
        finally { key.fill(0); seed.fill(0) }
    }

    fun hmacKey(
        masterSeed: ByteArray,
        transformedKey: ByteArray
    ): ByteArray {
        val combined = byteArrayOf(*masterSeed, *transformedKey, 0x01)
        return (ByteArray(8) { 0xFF.toByte() } + combined.sha512())
            .sha512()
            .also { combined.clear() }
    }
}
