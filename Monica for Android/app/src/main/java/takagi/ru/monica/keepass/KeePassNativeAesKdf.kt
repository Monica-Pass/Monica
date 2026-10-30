package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.AesKeyTransformer
import takagi.ru.monica.rustcore.RustKeePassKdfCore

internal object KeePassNativeAesKdf : AesKeyTransformer {
    override fun transform(seed: ByteArray, rounds: ULong, compositeKey: ByteArray): ByteArray? {
        if (seed.size != 32 || compositeKey.size != 32 || rounds > Long.MAX_VALUE.toULong()) return null
        return RustKeePassKdfCore.deriveAes(compositeKey, seed, rounds.toLong())
    }
}
