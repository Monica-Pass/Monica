package app.keemobile.kotpass.extensions

import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Random

internal fun Random.nextByteString(length: Int): ByteString {
    return ByteArray(length)
        .apply { nextBytes(this) }
        .toByteString()
}
