package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.intToLittleEndian
import app.keemobile.kotpass.cryptography.longToLittleEndian
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.constantTimeEquals
import app.keemobile.kotpass.extensions.sha256
import app.keemobile.kotpass.extensions.sha512
import app.keemobile.kotpass.io.BufferedStream
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.toByteString
import java.lang.Integer.min

internal object ContentBlocks {
    private const val BlockSplitRate = 1048576

    private class Block(
        val index: Long,
        val length: Int,
        val data: ByteArray
    )

    fun readContentBlocksVer3x(source: BufferedSource): ByteArray {
        val contentData = Buffer()

        while (true) {
            val index = source.readIntLe()
            val hash = source.readByteArray(32)
            val length = source.readIntLe()

            if (length > 0) {
                val data = source.readByteArray(length.toLong())
                if (!data.sha256().contentEquals(hash)) {
                    throw FormatError.InvalidContent("Hash for block $index does not match.")
                }
                contentData.write(data)
            } else {
                break
            }
        }

        return contentData.readByteArray()
    }

    fun readContentBlocksVer4x(
        source: BufferedStream,
        masterSeed: ByteArray,
        transformedKey: ByteArray
    ): ByteArray {
        val contentData = Buffer()
        val hmacKey = createBlockHmacKey(masterSeed, transformedKey)
        var index = 0L

        while (true) {
            val hash = source.readByteArray(32)
            val length = source.readIntLe()

            if (length > 0) {
                val data = source.readByteArray(length.toLong())
                if (!createBlockHmac(hmacKey, index, length, data).constantTimeEquals(hash)) {
                    throw FormatError.InvalidContent("HMAC for block $index does not match.")
                }
                contentData.write(data)
                index++
            } else {
                break
            }
        }

        return contentData.readByteArray()
    }

    internal fun writeContentBlocksVer3x(
        sink: BufferedSink,
        contentData: ByteArray
    ) = writeContentBlocks(sink, contentData, true) {
        if (data.isNotEmpty()) {
            data.sha256()
        } else {
            ByteArray(32) { 0x0 }
        }
    }

    internal fun writeContentBlocksVer4x(
        sink: BufferedSink,
        contentData: ByteArray,
        masterSeed: ByteArray,
        transformedKey: ByteArray
    ) {
        val hmacKey = createBlockHmacKey(masterSeed, transformedKey)

        writeContentBlocks(sink, contentData, false) {
            createBlockHmac(hmacKey, index, length, data)
        }
    }

    private fun writeContentBlocks(
        sink: BufferedSink,
        contentData: ByteArray,
        writeIndexes: Boolean,
        hashFunc: Block.() -> ByteArray
    ): Unit = with(sink) {
        var index = 0L
        var offset = 0

        while (offset < contentData.size) {
            val length = min(contentData.size - offset, BlockSplitRate)
            val data = contentData.sliceArray(offset until offset + length)
            val hash = hashFunc(Block(index, length, data))

            if (writeIndexes) {
                writeIntLe(index.toInt())
            }
            write(hash)
            writeIntLe(length)
            write(data)
            index++
            offset += length
        }
        if (writeIndexes) {
            writeIntLe(index.toInt())
        }
        write(hashFunc(Block(index, 0, ByteArray(0))))
        writeIntLe(0)
    }

    private fun createBlockHmacKey(
        masterSeed: ByteArray,
        transformedKey: ByteArray
    ) = byteArrayOf(*masterSeed, *transformedKey, 0x01).sha512()

    private fun createBlockHmac(
        hmacKey: ByteArray,
        index: Long,
        length: Int,
        data: ByteArray
    ): ByteArray {
        val indexBytes = longToLittleEndian(index)
        val blockKey = (indexBytes + hmacKey)
            .toByteString()
            .sha512()

        return (indexBytes + intToLittleEndian(length) + data)
            .toByteString()
            .hmacSha256(blockKey)
            .toByteArray()
    }
}
