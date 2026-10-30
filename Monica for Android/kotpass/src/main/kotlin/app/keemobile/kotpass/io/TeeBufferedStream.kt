package app.keemobile.kotpass.io

import app.keemobile.kotpass.extensions.bufferStream
import okio.Buffer
import okio.ByteString
import okio.Sink
import okio.Source
import okio.buffer
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * [BufferedStream] implementation that writes a copy of all bytes
 * read from [Source] to a mirror [Buffer].
 */
internal class TeeBufferedStream(
    source: Source,
    private val mirrorBuffer: Buffer
) : BufferedStream {
    private val bufferedSource = source.buffer()

    override val buffer: Buffer = bufferedSource.buffer

    override fun isOpen() = bufferedSource.isOpen

    override fun close() = bufferedSource.close()

    override fun exhausted() = bufferedSource.exhausted()

    override fun indexOf(b: Byte, fromIndex: Long) = bufferedSource
        .indexOf(b, fromIndex)

    override fun indexOf(bytes: ByteString, fromIndex: Long) = bufferedSource
        .indexOf(bytes, fromIndex)

    override fun rangeEquals(offset: Long, bytes: ByteString) = bufferedSource
        .rangeEquals(offset, bytes)

    override fun read(sink: ByteArray, offset: Int, byteCount: Int) = bufferedSource
        .read(sink, offset, byteCount)
        .also { if (it > 0) mirrorBuffer.write(sink) }

    override fun read(sink: Buffer, byteCount: Long) = bufferedSource
        .read(sink, byteCount).also {
            if (it > 0) sink.copyTo(mirrorBuffer, sink.size - byteCount, byteCount)
        }

    override fun read(dst: ByteBuffer?) = bufferedSource
        .read(dst)
        .also {
            if (dst != null && it > 0) {
                val len = dst.array().size
                mirrorBuffer.write(dst.array().sliceArray(len - it until len))
            }
        }

    override fun readAll(sink: Sink): Long {
        val temp = Buffer()
        val byteCount = bufferedSource.readAll(temp)
        if (byteCount > 0) {
            sink.write(temp, byteCount)
            mirrorBuffer.write(temp, byteCount)
        }
        return byteCount
    }

    override fun readByte() = bufferedSource
        .readByte()
        .also { mirrorBuffer.writeByte(it.toInt()) }

    override fun readByteArray() = bufferedSource
        .readByteArray()
        .also(mirrorBuffer::write)

    override fun readByteArray(byteCount: Long) = bufferedSource
        .readByteArray(byteCount)
        .also(mirrorBuffer::write)

    override fun readByteString() = bufferedSource
        .readByteString()
        .also(mirrorBuffer::write)

    override fun readByteString(byteCount: Long) = bufferedSource
        .readByteString(byteCount)
        .also(mirrorBuffer::write)

    override fun readFully(sink: ByteArray) = bufferedSource
        .readFully(sink)
        .also { mirrorBuffer.write(sink) }

    override fun readFully(sink: Buffer, byteCount: Long) = bufferedSource
        .readFully(sink, byteCount)
        .also { sink.copyTo(mirrorBuffer, sink.size - byteCount, byteCount) }

    override fun readInt() = bufferedSource
        .readInt()
        .also(mirrorBuffer::writeInt)

    override fun readIntLe() = bufferedSource
        .readIntLe()
        .also(mirrorBuffer::writeIntLe)

    override fun readLong() = bufferedSource
        .readLong()
        .also(mirrorBuffer::writeLong)

    override fun readLongLe() = bufferedSource
        .readLongLe()
        .also(mirrorBuffer::writeLongLe)

    override fun readShort() = bufferedSource
        .readShort()
        .also { mirrorBuffer.writeShort(it.toInt()) }

    override fun readShortLe() = bufferedSource
        .readShortLe()
        .also { mirrorBuffer.writeShortLe(it.toInt()) }

    override fun readString(charset: Charset) = bufferedSource
        .readString(charset)
        .also { mirrorBuffer.writeString(it, charset) }

    override fun readString(byteCount: Long, charset: Charset) = bufferedSource
        .readString(byteCount, charset)
        .also { mirrorBuffer.writeString(it, charset) }

    override fun request(byteCount: Long) = bufferedSource.request(byteCount)

    override fun require(byteCount: Long) = bufferedSource.require(byteCount)

    override fun skip(byteCount: Long) = bufferedSource.skip(byteCount)

    override fun peek() = bufferedSource.peek().bufferStream()

    override fun timeout() = bufferedSource.timeout()
}
