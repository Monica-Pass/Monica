package takagi.ru.monica.bitwarden.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.api.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BitwardenSendReceiverTest {
    @Test fun acceptsBothCloudFormsAndRetainsCustomDeploymentPrefix() {
        listOf("https://send.bitwarden.com/#$ID/$KEY", "https://vault.bitwarden.com/#/send/$ID/$KEY",
            "https://send.bitwarden.com/#/send/$ID/$KEY").forEach { raw ->
            BitwardenSendReceiverLink.parse(raw).use { link ->
                assertFalse(link.requiresTargetReview)
                assertEquals("https://api.bitwarden.com/", link.target().apiBase.toString())
                assertEquals("https://identity.bitwarden.com/", link.target().identityBase.toString())
                assertFalse(link.toString().contains(KEY))
            }
        }
        BitwardenSendReceiverLink.parse("https://send.bitwarden.eu/#$ID/$KEY").use { link ->
            assertEquals("https://identity.bitwarden.eu/", link.target().identityBase.toString())
        }
        BitwardenSendReceiverLink.parse("https://own.example/team/vault/#/send/$ID/$KEY").use { link ->
            assertTrue(link.requiresTargetReview)
            assertEquals(BitwardenSendReceiverFailure.TARGET_REVIEW_REQUIRED, failure { link.target() })
            assertEquals("https://own.example/team/vault/api/", link.target(reviewed = true).apiBase.toString())
            assertEquals("https://id.example/private/", link.target(identityBase = "https://id.example/private/", reviewed = true).identityBase.toString())
        }
    }

    @Test fun malformedOrAmbiguousLinksCannotBecomeCredentialDestinations() {
        val invalid = listOf(
            "https://send.bitwarden.com:444/#$ID/$KEY", "http://send.bitwarden.com/#$ID/$KEY",
            "https://vault.bitwarden.com/path/#/send/$ID/$KEY", "https://user:password@own.example/#/send/$ID/$KEY",
            "https://own.example/?key=x#/send/$ID/$KEY", "https://own.example/a/../#/send/$ID/$KEY",
            "https://own.example/%2e%2e/#/send/$ID/$KEY", "https://own.example/%252f/#/send/$ID/$KEY",
            "https://own.example/a%2fb/#/send/$ID/$KEY", "https://own.example/a\\b/#/send/$ID/$KEY",
            "https://own.example/a//b/#/send/$ID/$KEY", "https://own.example/#/send/$ID/$KEY/",
            "https://own.example/#/send/$ID/${KEY}==", "https://own.example/#/send/$ID/AAAA",
            "https://own.example/#/send/invalid/$KEY", "https://own.example/#/send/$ID/$KEY?x=y",
        )
        invalid.forEach { assertEquals(BitwardenSendReceiverFailure.INVALID_LINK, failure { BitwardenSendReceiverLink.parse(it) }) }
        BitwardenSendReceiverLink.parse("https://send.bitwarden.com.evil.test/#/send/$ID/$KEY").use {
            assertTrue(it.requiresTargetReview)
            assertEquals("send.bitwarden.com.evil.test", it.target(reviewed = true).identityBase.host)
        }
    }

    @Test fun derivesUpstreamSendKeyAndExactPasswordBytesAgainstIndependentKnownVectors() {
        BitwardenSendReceiverCrypto(Base64.getUrlDecoder().decode(KEY)).use { crypto ->
            val password = "  hunter2  ".toCharArray()
            // Independently generated with Python hashlib/hmac following the pinned SDK formula.
            assertEquals("1WUoJITNPbJlcInZJWNh+vOo3sujVx+6p45yd1NKClk=", crypto.passwordHash(password))
            assertTrue(password.all { it == '\u0000' })
            assertEquals("unicode 密码 😀", crypto.decryptText(encrypted("unicode 密码 😀".toByteArray())))
            assertEquals("", crypto.decryptText(encrypted(byteArrayOf())))
        }
    }

    @Test fun refusesUnauthenticatedDowngradesExtraFieldsAndInvalidUtf8() {
        BitwardenSendReceiverCrypto(Base64.getUrlDecoder().decode(KEY)).use { crypto ->
            val valid = encrypted("hello".toByteArray())
            val parts = valid.substring(2).split('|')
            val invalid = listOf("0.${parts[0]}|${parts[1]}", "$valid|ignored", "2.${parts[0]}|${parts[1]}|AA==",
                valid.replaceFirst("2.", "8."), encrypted(byteArrayOf(0xc3.toByte(), 0x28)))
            invalid.forEach { assertEquals(BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED, failure { crypto.decryptText(it) }) }
            BitwardenSendReceiverCrypto(ByteArray(16)).use { wrong ->
                assertEquals(BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED, failure { wrong.decryptText(valid) })
            }
        }
    }

    @Test fun authenticatesWholeFileAndPaddingBeforeOpeningExternalOutput() {
        val directory = Files.createTempDirectory("send-receiver-crypto").toFile()
        val source = ByteArray(180_123) { (it % 251).toByte() }
        try {
            BitwardenSendReceiverCrypto(Base64.getUrlDecoder().decode(KEY)).use { crypto ->
                val valid = frame(source)
                val file = File(directory, "cipher").apply { writeBytes(valid) }
                val output = ByteArrayOutputStream()
                RandomAccessFile(file, "r").use { handle ->
                    assertEquals(source.size.toLong(), crypto.decryptFile(handle, valid.size.toLong(), { output }, {}))
                }
                assertArrayEquals(source, output.toByteArray())
                val badMac = valid.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
                val badPadding = frame(ByteArray(16), padding = false)
                listOf(badMac, badPadding, valid.copyOf(valid.size - 1), valid.copyOf().apply { this[0] = 0 }).forEach { invalid ->
                    file.writeBytes(invalid)
                    var opened = false
                    RandomAccessFile(file, "r").use { handle ->
                        assertEquals(BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED, failure {
                            crypto.decryptFile(handle, invalid.size.toLong(), { opened = true; ByteArrayOutputStream() }, {})
                        })
                    }
                    assertFalse(opened)
                }
            }
        } finally { directory.listFiles()?.forEach(File::delete); directory.delete() }
    }

    @Test fun passwordsAreRequestedOnlyAfterTypedChallengeAndNoAccessIsAutomaticallyReplayed(): Unit = runBlocking {
        val fake = Fake().apply {
            tokens += BitwardenSendReceiverTokenReply.Challenge(BitwardenSendReceiverChallenge.PASSWORD)
            tokens += BitwardenSendReceiverTokenReply.Challenge(BitwardenSendReceiverChallenge.INVALID_PASSWORD)
            tokens += granted()
        }
        session(fake).use { session ->
            assertTrue(session.open() is BitwardenSendReceiverState.PasswordRequired)
            assertEquals(0, fake.accessCount)
            assertTrue((session.submitPassword("wrong".toCharArray()) as BitwardenSendReceiverState.PasswordRequired).rejected)
            val view = session.submitPassword("  hunter2  ".toCharArray()) as BitwardenSendReceiverState.TextReady
            assertEquals("received text", view.text)
            assertEquals("1WUoJITNPbJlcInZJWNh+vOo3sujVx+6p45yd1NKClk=", fake.credentials.last()["password_hash_b64"])
            assertEquals(1, fake.accessCount)
            assertFalse(view.toString().contains("received text"))
        }
        val uncertain = Fake().apply { accessFailure = BitwardenSendReceiverException(BitwardenSendReceiverFailure.OUTCOME_UNKNOWN) }
        session(uncertain).use {
            val result = it.open() as BitwardenSendReceiverState.Failure
            assertTrue(result.accessMayHaveBeenCounted)
            assertEquals(BitwardenSendReceiverFailure.OUTCOME_UNKNOWN, result.code)
            assertEquals(1, uncertain.accessCount)
            assertEquals(1, uncertain.credentials.size)
        }
    }

    @Test fun emailRequiresExplicitRequestHasCooldownAndPreservesMaskedRejections(): Unit = runBlocking {
        var clock = 1_000L
        val fake = Fake().apply {
            tokens += BitwardenSendReceiverTokenReply.Challenge(BitwardenSendReceiverChallenge.EMAIL)
            tokens += BitwardenSendReceiverTokenReply.Challenge(BitwardenSendReceiverChallenge.EMAIL_CODE)
            tokens += BitwardenSendReceiverTokenReply.Challenge(BitwardenSendReceiverChallenge.EMAIL_CODE)
            tokens += granted()
        }
        session(fake, now = { clock }).use { session ->
            assertSame(BitwardenSendReceiverState.EmailRequired, session.open())
            assertEquals(1, fake.credentials.size)
            session.requestEmailCode("recipient@example.test")
            session.requestEmailCode("recipient@example.test")
            assertEquals(2, fake.credentials.size)
            val code = "bad-code".toCharArray()
            val rejected = session.submitEmailCode(code) as BitwardenSendReceiverState.EmailCodeRequired
            assertTrue(rejected.rejected)
            assertTrue(code.all { it == '\u0000' })
            assertEquals(mapOf("email" to "recipient@example.test", "otp" to "bad-code"), fake.credentials.last())
            assertEquals(0, fake.accessCount)
            clock += 1_000
            assertTrue(session.submitEmailCode("correct-code".toCharArray()) is BitwardenSendReceiverState.TextReady)
        }
        val unexpected = Fake().apply { tokens += BitwardenSendReceiverTokenReply.Challenge(BitwardenSendReceiverChallenge.EMAIL); tokens += granted() }
        session(unexpected).use { session ->
            session.open()
            assertEquals(BitwardenSendReceiverFailure.INVALID_RESPONSE,
                (session.requestEmailCode("recipient@example.test") as BitwardenSendReceiverState.Failure).code)
            assertEquals(0, unexpected.accessCount)
        }
    }

    @Test fun fileSaveUsesOneCountedAuthorizationAndWritesOnlyVerifiedOriginalBytes(): Unit = runBlocking {
        val directory = Files.createTempDirectory("send-receiver-session").toFile()
        try {
            val bytes = "file contents\u0000 preserved".toByteArray()
            val fake = Fake().apply { fileBytes = frame(bytes); response = fileResponse(fileBytes!!.size) }
            session(fake).use { session ->
                assertTrue(session.open() is BitwardenSendReceiverState.FileReady)
                val output = ByteArrayOutputStream()
                assertTrue(session.saveFile(directory, { output }))
                assertArrayEquals(bytes, output.toByteArray())
                assertEquals(1, fake.fileCount)
                assertEquals(0, directory.listFiles()!!.size)
            }
            val damaged = Fake().apply { fileBytes = frame(bytes).apply { this[18] = (this[18].toInt() xor 1).toByte() }; response = fileResponse(fileBytes!!.size) }
            session(damaged).use { session ->
                session.open(); var opened = false
                assertFalse(session.saveFile(directory, { opened = true; ByteArrayOutputStream() }))
                assertFalse(opened)
                assertEquals(1, damaged.fileCount)
                assertTrue((session.state.value as BitwardenSendReceiverState.Failure).accessMayHaveBeenCounted)
                assertEquals(0, directory.listFiles()!!.size)
            }
        } finally { directory.listFiles()?.forEach(File::delete); directory.delete() }
    }

    @Test fun closeRevokesAnUncooperativeLateResponseAndCannotRestoreSecretState(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = object : Fake() {
            override suspend fun access(target: BitwardenSendReceiverTarget, token: String): BitwardenSendReceiverAccess =
                withContext(NonCancellable) { entered.complete(Unit); release.await(); response }
        }
        val session = session(fake)
        val pending = async { session.open() }
        entered.await(); session.close(); release.complete(Unit)
        runCatching { pending.await() }
        assertSame(BitwardenSendReceiverState.Closed, session.state.value)
        assertTrue(fake.closed)
        assertSame(BitwardenSendReceiverState.Closed, session.open())
    }

    @Test fun expiryAndOwnerRevocationBlockRequestsWithoutAnAccountOrRefreshFallback(): Unit = runBlocking {
        var time = 0L
        val fake = Fake()
        val session = session(fake, now = { time })
        time = 5 * 60_000L
        runCatching { session.open() }
        assertEquals(0, fake.credentials.size)
        assertSame(BitwardenSendReceiverState.Closed, session.state.value)
        val link = BitwardenSendReceiverLink.parse("https://send.bitwarden.com/#$ID/$KEY")
        link.close()
        assertEquals(BitwardenSendReceiverFailure.CLOSED, failure {
            BitwardenSendReceiverSession(link, link.target(), Fake(), {})
        })
    }

    private fun session(fake: Fake, now: () -> Long = { 1_000L }): BitwardenSendReceiverSession {
        val link = BitwardenSendReceiverLink.parse("https://send.bitwarden.com/#$ID/$KEY")
        return BitwardenSendReceiverSession(link, link.target(), fake, {}, now, { 1_800_000_000_000L })
    }

    private open class Fake : BitwardenSendReceiverTransport {
        val tokens = ArrayDeque<BitwardenSendReceiverTokenReply>()
        val credentials = mutableListOf<Map<String, String>>()
        var accessCount = 0
        var fileCount = 0
        var closed = false
        var accessFailure: Exception? = null
        var fileBytes: ByteArray? = null
        var response = BitwardenSendReceiverAccess(ID, 0, encrypted("title".toByteArray()), encrypted("received text".toByteArray()),
            true, null, null, null, null, null)
        override suspend fun token(target: BitwardenSendReceiverTarget, accessId: String, credential: BitwardenSendReceiverCredential): BitwardenSendReceiverTokenReply {
            assertEquals(ID, accessId)
            credentials += credential.fields.toMap()
            return if (tokens.isEmpty()) granted() else tokens.removeFirst()
        }
        override suspend fun access(target: BitwardenSendReceiverTarget, token: String): BitwardenSendReceiverAccess {
            accessCount++; accessFailure?.let { throw it }; return response
        }
        override suspend fun authorizeFile(target: BitwardenSendReceiverTarget, token: String, fileId: String): BitwardenSendReceiverDownload {
            fileCount++; return BitwardenSendReceiverDownload(fileId, "https://storage.example/file?capability=synthetic".toHttpUrl())
        }
        override suspend fun download(target: BitwardenSendReceiverDownload, expectedBytes: Long?, sink: OutputStream,
                                      requireCurrent: () -> Unit, progress: (Long, Long?) -> Unit) {
            requireCurrent(); sink.write(fileBytes!!); requireCurrent(); progress(fileBytes!!.size.toLong(), expectedBytes)
        }
        override fun close() { closed = true }
    }

    companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val KEY = "Pgui0FK85cNhBGWHAlBHBw"
        // Independent HKDF known vector: raw URL key -> salt bitwarden-send, info send.
        private val derived = Base64.getDecoder().decode("gJXa6PSjhQutc1fmNfQPA+eHRH7M/ngkEwgBf0tXMJYCK/en4/7RmwVhzvWUfIFcauXrTvEWvZwwG+JPK2I0gA==")
        private val fixtureIv = ByteArray(16) { it.toByte() }
        private fun granted() = BitwardenSendReceiverTokenReply.Granted("synthetic-send-token", 120)
        private fun fileResponse(size: Int) = BitwardenSendReceiverAccess(ID, 1, encrypted("file title".toByteArray()), null,
            false, "file-id", encrypted("received.txt".toByteArray()), size.toLong(), null, null)
        private fun failure(block: () -> Unit): BitwardenSendReceiverFailure? =
            (runCatching(block).exceptionOrNull() as? BitwardenSendReceiverException)?.code
        private fun frame(bytes: ByteArray, padding: Boolean = true): ByteArray {
            val cipher = Cipher.getInstance(if (padding) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, 32), "AES"), IvParameterSpec(fixtureIv))
            }.doFinal(bytes)
            val mac = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(derived.copyOfRange(32, 64), "HmacSHA256")); update(fixtureIv); doFinal(cipher)
            }
            return byteArrayOf(2) + fixtureIv + mac + cipher
        }
        private fun encrypted(bytes: ByteArray): String {
            val frame = frame(bytes)
            val b64 = Base64.getEncoder()
            return "2.${b64.encodeToString(frame.copyOfRange(1, 17))}|${b64.encodeToString(frame.copyOfRange(49, frame.size))}|${b64.encodeToString(frame.copyOfRange(17, 49))}"
        }
    }
}
