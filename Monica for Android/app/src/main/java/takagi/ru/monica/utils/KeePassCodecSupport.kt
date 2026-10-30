package takagi.ru.monica.utils

import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.xml.XmlContentParser
import app.keemobile.kotpass.cryptography.KdfTransform
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.decode
import takagi.ru.monica.keepass.KeePassNativeKdf
import takagi.ru.monica.keepass.KeePassNativeAesKdf
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import takagi.ru.monica.data.KeePassCipherAlgorithm
import takagi.ru.monica.keepass.KeePassXmlContentParser
import java.util.UUID

object KeePassCodecSupport {
    /** The scope remains on the codec worker; no derived key survives this call. */
    fun encode(database: KeePassDatabase, output: OutputStream, random: SecureRandom = SecureRandom()): KeePassDatabase =
        withNativeKdf {
            database.encode(output, contentParser = contentParser, cipherProviders = cipherProviders, random = random)
        }

    fun decode(input: InputStream, credentials: Credentials): KeePassDatabase =
        withNativeKdf {
            KeePassDatabase.decode(input, credentials, contentParser = contentParser, cipherProviders = cipherProviders)
        }

    private fun <T> withNativeKdf(operation: () -> T): T =
        KdfTransform.withAes(KeePassNativeAesKdf) { KdfTransform.withArgon2(KeePassNativeKdf, operation) }

    val contentParser: XmlContentParser = KeePassXmlContentParser

    val cipherProviders: List<CipherProvider> = buildList {
        addAll(BaseCiphers.entries)
        add(TwofishCipher)
    }

    fun resolveCipherUuid(algorithm: KeePassCipherAlgorithm): UUID {
        return when (algorithm) {
            KeePassCipherAlgorithm.AES -> BaseCiphers.Aes.uuid
            KeePassCipherAlgorithm.CHACHA20 -> BaseCiphers.ChaCha20.uuid
            KeePassCipherAlgorithm.TWOFISH -> TwofishCipher.uuid
        }
    }
}

