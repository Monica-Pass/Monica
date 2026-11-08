package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.api.*

/** Synthetic wire shapes from Vaultwarden 1.30.5 and 1.36.0 Cipher::to_json. */
class BitwardenVaultwardenDataTest {
    private val revision = "2026-11-08T00:00:00Z"
    private val sections = listOf("login", "secureNote", "card", "identity", "sshKey")

    private fun source(type: Int = 1, pascal: Boolean = false): JsonObject {
        val detail = when (type) {
            1 -> buildJsonObject { put("username", "sealed-user"); put("password", "sealed-password"); put("uri", JsonNull) }
            2 -> buildJsonObject { put("type", 0) }
            3 -> buildJsonObject { put("number", "sealed-number") }
            4 -> buildJsonObject { put("firstName", "sealed-first") }
            else -> buildJsonObject { put("privateKey", "sealed-private") }
        }
        val common = buildJsonObject {
            put("name", "sealed-title"); put("notes", JsonNull)
            put("fields", JsonArray(emptyList())); put("passwordHistory", JsonArray(emptyList()))
        }
        val raw = buildJsonObject {
            put("id", "synthetic-$type"); put("type", type); put("revisionDate", revision)
            put("favorite", false); put("reprompt", 0)
            common.forEach { (k, v) -> put(k, v) }
            put(sections[type - 1], detail)
            put("data", JsonObject(detail + common))
        }
        fun uppercase(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapKeys { it.key.replaceFirstChar(Char::uppercaseChar) }.mapValues { uppercase(it.value) })
            is JsonArray -> JsonArray(value.map(::uppercase))
            else -> value
        }
        return if (pascal) uppercase(raw).jsonObject else raw
    }

    @Test fun oldVaultwardenObjectMirrorsProjectAllSupportedTypesAndCasings() {
        for (pascal in listOf(false, true)) for (type in 1..5) {
            val raw = source(type, pascal)
            val cipher = BitwardenCipherDocument.decode(BitwardenWireJson.parseObject(raw.toString()))
            assertEquals(raw, cipher.sourceDocument)
            assertTrue("type=$type pascal=$pascal", BitwardenCipherDocument.canProject(cipher))
        }
    }

    @Test fun entireLegacyVaultPassesTheProductionSyncDecoderAndProjectionGate() {
        val ciphers = (1..262).map { JsonObject(source() + ("id" to JsonPrimitive("synthetic-$it"))) }
        val sync = BitwardenCipherDocument.decodeSync(buildJsonObject {
            put("profile", buildJsonObject { put("id", "synthetic-account") })
            put("ciphers", JsonArray(ciphers))
        })
        assertEquals(262, sync.ciphers.count(BitwardenCipherDocument::canProject))
    }

    @Test fun editingKeepsObjectRepresentationAndUpdatesBothMirrors() {
        for (pascal in listOf(false, true)) {
            val raw = source(pascal = pascal)
            val updated = BitwardenCipherDocument.update(raw, CipherUpdateRequest(type = 1,
                name = "new-title", login = CipherLoginApiData(username = "new-user", password = "new-password"),
                lastKnownRevisionDate = revision))
            val data = updated.getValue("data")
            assertTrue(data is JsonObject)
            assertEquals(updated["name"], data.jsonObject["name"])
            assertEquals(updated["login"]!!.jsonObject["username"], data.jsonObject["username"])
            assertEquals(updated["login"]!!.jsonObject["password"], data.jsonObject["password"])
            val ack = JsonObject(updated + mapOf("id" to JsonPrimitive("synthetic-1"), "revisionDate" to JsonPrimitive(revision)))
            assertTrue(BitwardenCipherDocument.canProject(BitwardenCipherDocument.decode(ack)))
            assertTrue(BitwardenCipherDocument.confirms(updated, ack))
        }
    }

    @Test fun conflictingAndAmbiguousMirrorsStillCannotProjectOrEdit() {
        val raw = source()
        val data = raw.getValue("data").jsonObject
        for (bad in listOf(
            JsonObject(data + ("name" to JsonPrimitive("other-title"))),
            JsonObject(data + ("password" to JsonPrimitive("other-password"))),
            JsonObject(data + ("Name" to JsonPrimitive("sealed-title"))),
            JsonObject(mapOf("v" to JsonPrimitive(2))),
            JsonArray(listOf(data)), JsonPrimitive("2.encrypted-blob")
        )) {
            val cipher = BitwardenCipherDocument.decode(JsonObject(raw + ("data" to bad)))
            assertFalse(BitwardenCipherDocument.canProject(cipher))
        }
        assertFalse(BitwardenCipherDocument.canProject(BitwardenCipherDocument.decode(JsonObject(raw + ("partialData" to data)))))
    }

    @Test fun unknownMirrorFieldsArePreservedAndTheirLossCannotConfirmAnUpload() {
        val raw = source()
        val opaque = buildJsonObject { put("A", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1)))) }
        val data = JsonObject(raw.getValue("data").jsonObject + ("FutureData" to opaque))
        val original = JsonObject(raw + ("data" to data))
        val updated = BitwardenCipherDocument.update(original, CipherUpdateRequest(type = 1,
            name = "new-title", login = CipherLoginApiData(username = "new-user", password = "new-password"),
            lastKnownRevisionDate = revision))
        assertEquals(opaque, updated.getValue("data").jsonObject["FutureData"])
        val missing = JsonObject(updated + ("data" to JsonObject(updated.getValue("data").jsonObject - "FutureData")))
        assertFalse(BitwardenCipherDocument.confirms(updated, missing))
        assertFalse(BitwardenCipherDocument.confirms(updated, JsonObject(updated - "data")))
    }

    @Test fun confirmationAcceptsEquivalentObjectOrSerializedMirrorsButRejectsContentChanges() {
        val raw = source()
        val data = raw.getValue("data").jsonObject
        val serialized = JsonObject(raw + ("data" to JsonPrimitive(data.toString())))
        assertTrue(BitwardenCipherDocument.confirms(raw, serialized))
        assertTrue(BitwardenCipherDocument.confirms(serialized, raw))
        val changed = JsonObject(raw + ("data" to JsonObject(data + ("password" to JsonPrimitive("other")))))
        assertFalse(BitwardenCipherDocument.confirms(raw, changed))
    }

    @Test fun projectionDiagnosticsUseFixedStructuralCodesWithoutCredentialValues() {
        val raw = source()
        fun reason(value: JsonObject) = BitwardenCipherDocument.projectionRejection(BitwardenCipherDocument.decode(value))
        assertNull(reason(raw))
        assertEquals(BitwardenCipherDocument.ProjectionRejection.LEGACY_SHAPE,
            reason(JsonObject(raw + ("data" to JsonPrimitive("private-unrecognized-blob")))))
        assertEquals(BitwardenCipherDocument.ProjectionRejection.LEGACY_MIRROR,
            reason(JsonObject(raw + ("data" to JsonObject(raw["data"]!!.jsonObject + ("name" to JsonPrimitive("private-conflict")))))))
        assertEquals(BitwardenCipherDocument.ProjectionRejection.PARTIAL,
            reason(JsonObject(raw + ("partialData" to JsonObject(emptyMap())))))
        assertEquals(BitwardenCipherDocument.ProjectionRejection.TYPE_SECTION, reason(JsonObject(raw - "data" - "login")))
    }
}
