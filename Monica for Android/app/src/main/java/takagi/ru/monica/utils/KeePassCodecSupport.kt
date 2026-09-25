package takagi.ru.monica.utils

import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.xml.XmlContentParser
import takagi.ru.monica.data.KeePassCipherAlgorithm
import takagi.ru.monica.keepass.KeePassXmlContentParser
import java.util.UUID

object KeePassCodecSupport {
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

