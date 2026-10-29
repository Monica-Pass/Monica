package takagi.ru.monica.passkey.hardware

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class HardwareTransportTest {
    private class Peer : HidPackets {
        override val inputSize = 64
        override val outputSize = 64
        val writes = mutableListOf<ByteArray>()
        val replies = ArrayDeque<ByteArray>()
        var closed = false
        override fun write(packet: ByteArray) { writes.add(packet.copyOf()) }
        override fun read(): ByteArray? = if (replies.isEmpty()) null else replies.removeFirst()
        override fun close() { closed = true }
    }
    private fun frame(cid: Int, cmd: Int, data: ByteArray, length: Int = data.size): ByteArray = ByteArray(64).apply {
        CtapHid.putChannel(this, cid); this[4] = (cmd or 128).toByte(); this[5] = (length shr 8).toByte(); this[6] = length.toByte()
        data.copyInto(this, 7, 0, minOf(data.size, 57))
    }

    @Test fun hidFragmentsOutgoingDataAndReassemblesIncomingWithKeepaliveAndOtherChannel() = runBlocking {
        val peer = Peer()
        val response = ByteArray(180) { it.toByte() }
        peer.replies.add(frame(5, 16, byteArrayOf(8))) // another channel must be ignored
        peer.replies.add(frame(-1, 0x3b, byteArrayOf(2)))
        peer.replies.add(frame(-1, 16, response))
        for (seq in 0..2) peer.replies.add(ByteArray(64).apply {
            CtapHid.putChannel(this, -1); this[4] = seq.toByte()
            val offset = 57 + seq * 59
            response.copyInto(this, 5, offset, minOf(response.size, offset + 59))
        })
        val data = ByteArray(160) { (it + 8).toByte() }
        assertArrayEquals(response, CtapHid(peer).command(data))
        assertEquals(3, peer.writes.size)
        assertEquals(0x90, peer.writes[0][4].toInt() and 255)
        assertEquals(0, peer.writes[1][4].toInt())
        assertEquals(1, peer.writes[2][4].toInt())
        assertArrayEquals(data, peer.writes[0].copyOfRange(7, 64) + peer.writes[1].copyOfRange(5, 64) + peer.writes[2].copyOfRange(5, 49))
    }

    @Test fun hidRejectsWrongCommandSequenceLengthAndClosesToPreventStaleResponseReuse() = runBlocking {
        for (bad in listOf("command", "sequence", "length")) {
            val peer = Peer()
            when (bad) {
                "command" -> peer.replies.add(frame(-1, 6, byteArrayOf(0)))
                "length" -> peer.replies.add(frame(-1, 16, byteArrayOf(0), 65535))
                else -> { peer.replies.add(frame(-1, 16, ByteArray(120))); peer.replies.add(ByteArray(64).apply { CtapHid.putChannel(this, -1); this[4] = 5 }) }
            }
            try { CtapHid(peer).command(byteArrayOf(4)); fail() } catch (_: IllegalArgumentException) {}
            assertTrue(peer.closed)
            assertEquals(0x91, peer.writes.last()[4].toInt() and 255)
        }
    }

    @Test fun hidCancellationStopsWaitingAndClosesConnection() = runBlocking {
        val peer = Peer()
        val job = launch(Dispatchers.Default) { CtapHid(peer).command(byteArrayOf(1)) }
        delay(50); job.cancelAndJoin()
        assertTrue(peer.closed)
    }

    @Test fun hidTimeoutClosesEvenWhenPeerNeverReplies() = runBlocking {
        val peer = Peer(); var now = 0L
        try { CtapHid(peer) { now += 31000; now }.command(byteArrayOf(4)); fail() } catch (_: java.io.IOException) {}
        assertTrue(peer.closed)
    }

    @Test fun nfcCommandAndResponseChainingUseShortApdus() = runBlocking {
        val writes = mutableListOf<ByteArray>()
        val data = ByteArray(400) { it.toByte() }
        val apdu = NfcApdu(261) { packet ->
            writes.add(packet)
            when (writes.size) {
                1 -> byteArrayOf(0x90.toByte(), 0)
                2 -> byteArrayOf(0, 1, 2, 0x61, 3)
                else -> byteArrayOf(3, 4, 5, 0x90.toByte(), 0)
            }
        }
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), apdu.command(data))
        assertEquals(0x90, writes[0][0].toInt() and 255)
        assertEquals(0x80, writes[1][0].toInt() and 255)
        assertArrayEquals(data, writes[0].copyOfRange(5, 260) + writes[1].copyOfRange(5, 150))
        assertArrayEquals(byteArrayOf(0, 0xc0.toByte(), 0, 0, 3), writes[2])
    }

    @Test fun nfcKeepaliveUsesCtapGetResponseAndDoesNotPolluteCbor() = runBlocking {
        val writes = mutableListOf<ByteArray>()
        val apdu = NfcApdu(261) { packet ->
            writes.add(packet)
            if (writes.size == 1) byteArrayOf(2, 0x91.toByte(), 0) else byteArrayOf(0, 0xa0.toByte(), 0x90.toByte(), 0)
        }
        assertArrayEquals(byteArrayOf(0, 0xa0.toByte()), apdu.command(byteArrayOf(4)))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x11, 0, 0, 0), writes[1])
    }

    @Test fun nfcRejectsBadStatusAndInfiniteEmptyChaining() = runBlocking {
        for (response in listOf(byteArrayOf(0x69, 0x85.toByte()), byteArrayOf(0x61, 0))) {
            try { NfcApdu(261) { response }.command(byteArrayOf(4)); fail() } catch (_: Exception) { }
        }
    }
}
