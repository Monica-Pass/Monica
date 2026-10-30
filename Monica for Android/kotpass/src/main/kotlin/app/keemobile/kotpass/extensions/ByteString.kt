package app.keemobile.kotpass.extensions

import okio.ByteString
import java.nio.ByteOrder
import java.util.UUID

internal fun ByteString.asIntLe() = asByteBuffer()
    .apply { order(ByteOrder.LITTLE_ENDIAN) }
    .int

internal fun ByteString.asLongLe() = asByteBuffer()
    .apply { order(ByteOrder.LITTLE_ENDIAN) }
    .long

internal fun ByteString.asUuid() = asByteBuffer()
    .let { buffer ->
        val mostSigBits = buffer.long
        val leastSigBits = buffer.long
        UUID(mostSigBits, leastSigBits)
    }
