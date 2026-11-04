package takagi.ru.monica.bitwarden.service

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BitwardenPasswordHistoryTest {
    private val key = SymmetricCryptoKey(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 33).toByte() })
    private val before = "2026-11-04T00:00:00Z"
    private val now = Instant.parse("2026-11-04T01:00:00Z")
    @After fun clearKey() { key.clear() }
    private fun enc(value: String) = BitwardenCrypto.encryptString(value, key)
    private fun wire(value: String, date: String = before, upper: Boolean = false) = buildJsonObject {
        put(if (upper) "Password" else "password", enc(value))
        put(if (upper) "LastUsedDate" else "lastUsedDate", date)
        put("future", buildJsonObject { put("keep", "opaque") })
    }
    private fun source(password: String? = "old", history: JsonElement? = null,
        fields: List<CipherFieldApiData>? = null) = CipherApiResponse(id = "synthetic-history", type = 1,
        name = enc("Synthetic"), revisionDate = before,
        login = CipherLoginApiData(password = password?.let(::enc), passwordRevisionDate = before),
        passwordHistory = history, fields = fields)
    private fun request(password: String? = "new", fields: List<CipherFieldApiData>? = null) =
        CipherUpdateRequest(type = 1, name = enc("Changed title"),
            login = CipherLoginApiData(password = password?.let(::enc)), fields = fields)
    private fun plain(prepared: BitwardenPasswordHistory.Prepared) =
        BitwardenPasswordHistory.decode(prepared.request.passwordHistory, key).map { it.password }
    private fun desired(password: String, id: Long = 1L) = BitwardenPasswordHistory.DesiredRecord(id, null, password, before)
    private fun field(name: String, value: String, type: Int = 1) = CipherFieldApiData(enc(name), enc(value), type)

    @Test fun passwordChangeAddsOldSecretWithCorrectTimestampAndRevision() {
        val original = wire("older")
        val result = BitwardenPasswordHistory.prepare(request(), source(history = JsonArray(listOf(original))), key, now = now)
        assertEquals(listOf("older", "old"), plain(result))
        assertEquals(now.toString(), result.request.login!!.passwordRevisionDate)
        assertEquals(now.toString(), BitwardenPasswordHistory.decode(result.request.passwordHistory, key).last().lastUsedDate)
        assertEquals(original, result.request.passwordHistory!!.jsonArray.first())
        assertFalse(result.request.passwordHistory.toString().contains("\"old\""))
    }

    @Test fun unchangedTitleKeepsHistoryBytesUnknownShapesDuplicatesAndRevision() {
        val duplicate = wire("same", upper = true)
        val unknown = JsonObject(mapOf("newSchema" to JsonPrimitive("keep")))
        val raw = JsonArray(listOf(duplicate, duplicate, unknown, JsonPrimitive(123)))
        val result = BitwardenPasswordHistory.prepare(request("old"), source(history = raw), key, now = now)
        assertSame(raw, result.request.passwordHistory)
        assertEquals(before, result.request.login!!.passwordRevisionDate)
        assertEquals(listOf("same", "same", null, null), plain(result))
    }

    @Test fun emptyOldAddsNothingWhileWhitespaceAndOldToEmptyAreRealChanges() {
        assertEquals(emptyList<String>(), plain(BitwardenPasswordHistory.prepare(request(), source(""), key, now = now)))
        assertEquals(listOf("  \t"), plain(BitwardenPasswordHistory.prepare(request("new"), source("  \t"), key, now = now)))
        val cleared = BitwardenPasswordHistory.prepare(request(null), source("old"), key, now = now)
        assertEquals(listOf("old"), plain(cleared))
        assertEquals(now.toString(), cleared.request.login!!.passwordRevisionDate)
        val unchanged = BitwardenPasswordHistory.prepare(request(null), source(null), key, now = now)
        assertEquals(before, unchanged.request.login!!.passwordRevisionDate)
    }

    @Test fun explicitClearKeepsEmptyHistoryEvenAfterOfflinePasswordEdit() {
        val result = BitwardenPasswordHistory.prepare(request(), source(history = JsonArray(listOf(wire("older")))), key,
            desired = emptyList(), initialized = true, clearRemote = true, now = now)
        assertEquals(JsonArray(emptyList()), result.request.passwordHistory)
        assertEquals(now.toString(), result.request.login!!.passwordRevisionDate)
    }

    @Test fun deleteTargetsOneIdenticalOccurrenceAndPreservesUnknownWire() {
        val same = wire("same")
        val unknown = JsonObject(mapOf("future" to JsonPrimitive("keep")))
        val result = BitwardenPasswordHistory.prepare(request("old"), source(history = JsonArray(listOf(same, same, unknown))), key,
            desired = listOf(BitwardenPasswordHistory.DesiredRecord(2L, same, "same", before),
                BitwardenPasswordHistory.DesiredRecord(null, unknown, null, null)), initialized = true, now = now)
        assertEquals(JsonArray(listOf(same, unknown)), result.request.passwordHistory)
        assertEquals(listOf(2L, null), result.records.map { it.localId })
    }

    @Test fun multipleOfflineChangesCaptureOriginalOnlyOnceAndKeepOccurrenceOrder() {
        val result = BitwardenPasswordHistory.prepare(request("C"), source("A", JsonArray(listOf(wire("older")))), key,
            desired = listOf(BitwardenPasswordHistory.DesiredRecord(3L, wire("older"), "older", before),
                desired("A", 1L), desired("B", 2L)), initialized = true, now = now)
        assertEquals(listOf("older", "A", "B"), plain(result))
        assertEquals(listOf(3L, 1L, 2L), result.records.map { it.localId })
    }

    @Test fun originalRemovedByDeleteOrLimitIsNotResurrectedOnUpload() {
        val result = BitwardenPasswordHistory.prepare(request("final"), source("A"), key,
            desired = listOf(desired("E", 5L), desired("D", 4L), desired("C", 3L), desired("B", 2L), desired("Z", 9L)),
            initialized = true, suppressOriginalPassword = true, now = now)
        assertEquals(listOf("E", "D", "C", "B", "Z"), plain(result))
    }

    @Test fun passwordChangeAfterClearCanKeepItsExplicitNewSnapshot() {
        val result = BitwardenPasswordHistory.prepare(request(), source(), key,
            desired = listOf(desired("old")), initialized = true, clearRemote = true, now = now)
        assertEquals(listOf("old"), plain(result))
    }

    @Test fun initialMappingMatchesByExactValueAndDateWithoutCollapsingDuplicates() {
        val one = wire("same")
        val two = wire("same", upper = true)
        val result = BitwardenPasswordHistory.prepare(request("old"), source(history = JsonArray(listOf(one, two))), key,
            desired = listOf(desired("same")), initialized = false, now = now)
        assertEquals(JsonArray(listOf(one, two)), result.request.passwordHistory)
        assertEquals(listOf(1L, null), result.records.map { it.localId })
    }

    @Test fun hiddenFieldChangeDeletionAndWhitespaceUseOfficialHistoryText() {
        val old = listOf(field("hidden", "previous"), field("deleted", "value"), field("empty", ""),
            field("whitespace", " "), field("public", "visible", 0), field("unchanged", "same"))
        val changed = listOf(field("hidden", "changed"), field("whitespace", ""), field("unchanged", "same"))
        val result = BitwardenPasswordHistory.prepare(request("old", changed), source(fields = old), key, now = now)
        assertEquals(setOf("hidden: previous", "deleted: value", "whitespace:  "), plain(result).toSet())
        assertEquals(before, result.request.login!!.passwordRevisionDate)
    }

    @Test fun hiddenFieldsOnNonLoginTypesStillGenerateHistory() {
        val original = source(fields = listOf(field("secret", "old"))).copy(type = 2, login = null)
        val next = request(fields = emptyList()).copy(type = 2, login = null)
        val result = BitwardenPasswordHistory.prepare(next, original, key, now = now)
        assertEquals(listOf("secret: old"), plain(result))
        assertNull(result.request.login)
    }

    @Test fun newRecordsApplyOfficialLimitFiveButUnchangedFutureListsArePreserved() {
        val list = JsonArray((1..7).map { JsonObject(wire("history-$it") - "future") })
        assertEquals(7, plain(BitwardenPasswordHistory.prepare(request("old"), source(history = list), key, now = now)).size)
        assertEquals(listOf("history-4", "history-5", "history-6", "history-7", "old"),
            plain(BitwardenPasswordHistory.prepare(request(), source(history = list), key, now = now)))
    }

    @Test fun wrongKeyCorruptDateAmbiguousFieldsAndMalformedHistoryFailClosed() {
        val wrong = SymmetricCryptoKey(ByteArray(32) { (it + 12).toByte() }, ByteArray(32) { (it + 44).toByte() })
        try {
            assertTrue(runCatching { BitwardenPasswordHistory.decode(JsonArray(listOf(wire("secret"))), wrong) }.isFailure)
            val invalidDate = buildJsonObject { put("password", enc("secret")); put("lastUsedDate", "bad-date") }
            assertTrue(runCatching { BitwardenPasswordHistory.decode(JsonArray(listOf(invalidDate)), key) }.isFailure)
            val ambiguous = buildJsonObject { put("password", enc("a")); put("Password", enc("b")); put("lastUsedDate", before) }
            assertTrue(runCatching { BitwardenPasswordHistory.decode(JsonArray(listOf(ambiguous)), key) }.isFailure)
            assertTrue(runCatching { BitwardenPasswordHistory.decode(JsonPrimitive("invalid"), key) }.isFailure)
            assertTrue(runCatching { BitwardenPasswordHistory.decode(JsonArray(List(513) { JsonNull }), key) }.isFailure)
        } finally { wrong.clear() }
    }

    @Test fun corruptedSourceHistoryCannotBeBypassedByExplicitClear() {
        val raw = JsonArray(listOf(buildJsonObject { put("password", "2.corrupt"); put("lastUsedDate", before) }))
        assertTrue(runCatching { BitwardenPasswordHistory.prepare(request("old"), source(history = raw), key,
            desired = emptyList(), initialized = true, clearRemote = true, now = now) }.isFailure)
    }

    @Test fun unknownRecordsAndExtraMetadataSurviveNewHistoryBeyondFive() {
        val opaque = JsonPrimitive(9)
        val future = wire("future-history")
        val older = (1..7).map { JsonObject(wire("history-$it") - "future") }
        val raw = JsonArray(listOf(opaque, future) + older)
        val result = BitwardenPasswordHistory.prepare(request(), source(history = raw), key, now = now)
        assertEquals(listOf(opaque, future), result.request.passwordHistory!!.jsonArray.take(2))
        assertEquals(listOf(null, "future-history", "history-4", "history-5", "history-6", "history-7", "old"), plain(result))
    }

    @Test fun repeatedHiddenFieldsAreCountedByOccurrenceRatherThanByName() {
        val before = listOf(field("duplicate", "one"), field("duplicate", "two"), field("duplicate", "two"))
        val after = listOf(field("duplicate", "two"))
        val result = BitwardenPasswordHistory.prepare(request("old", after), source(fields = before), key, now = now)
        assertEquals(listOf("duplicate: one", "duplicate: two"), plain(result))
    }

    @Test fun missingDateDoesNotBypassAuthenticationOfRecognizedPassword() {
        assertTrue(runCatching { BitwardenPasswordHistory.decode(JsonArray(listOf(
            buildJsonObject { put("password", "2.corrupt") })), key) }.isFailure)
    }

    @Test fun canonicalExplicitHistoryUpdatesLegacyDataAndPasswordRevision() {
        val source = source(history = JsonArray(listOf(wire("earlier"))))
        val raw = Json { encodeDefaults = true }.encodeToJsonElement(CipherApiResponse.serializer(), source).jsonObject
        val decoded = BitwardenCipherDocument.decode(raw)
        val prepared = BitwardenPasswordHistory.prepare(request(), decoded, key, now = now)
        val update = BitwardenCipherDocument.update(raw,
            prepared.request.copy(lastKnownRevisionDate = before), key,
            historyIntent = prepared.request.passwordHistory, passwordRevisionDateIntent = prepared.request.login?.passwordRevisionDate)
        assertEquals(prepared.request.passwordHistory, update["passwordHistory"])
        assertEquals(JsonPrimitive(now.toString()), update.getValue("login").jsonObject["passwordRevisionDate"])
        assertTrue(BitwardenCipherDocument.confirms(update, JsonObject(update + ("id" to JsonPrimitive(source.id)) +
            ("revisionDate" to JsonPrimitive(now.toString())))))
    }
}
