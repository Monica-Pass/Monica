package app.keemobile.kotpass.extensions

import java.security.MessageDigest
import kotlin.experimental.inv
import kotlin.experimental.xor

private const val Sha256 = "SHA-256"
private const val Sha512 = "SHA-512"

internal fun ByteArray.sha256(): ByteArray = MessageDigest
    .getInstance(Sha256)
    .digest(this)

internal fun ByteArray.sha512(): ByteArray = MessageDigest
    .getInstance(Sha512)
    .digest(this)

internal fun ByteArray.clear() {
    for (i in indices) this[i] = 0x0
}

internal fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
    if (this === other) {
        return true
    }
    val length = if (other.size < this.size) other.size else this.size
    var notEqual = other.size xor this.size
    for (i in 0 until length) {
        notEqual = notEqual or (other[i] xor this[i]).toInt()
    }
    for (i in length until this.size) {
        notEqual = notEqual or (this[i] xor this[i].inv()).toInt()
    }
    return notEqual == 0
}
