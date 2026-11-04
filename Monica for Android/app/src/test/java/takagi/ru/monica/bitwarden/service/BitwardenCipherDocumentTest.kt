package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.api.*

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], manifest = org.robolectric.annotation.Config.NONE)
class BitwardenCipherDocumentTest {
    private val revision = "2026-11-03T00:00:00Z"
    @Test fun repeatedIdentityUpdatesNormalizeSerializedAddressAliasesAndKeepOpaqueData() {
        val original = Json.parseToJsonElement("""{"id":"identity","type":4,"name":"sealed-name","revisionDate":"$revision","identity":{"firstName":"old","FutureIdentity":{"keep":42}}}""").jsonObject
        val update = CipherUpdateRequest(type = 4, name = "sealed-name", lastKnownRevisionDate = revision,
            identity = CipherIdentityApiData(firstName = "new", address1 = "sealed-1", address2 = "sealed-2", address3 = "sealed-3"))
        val first = BitwardenCipherDocument.update(original, update, archiveIntent = JsonNull)
        val nextRevision = "2026-11-04T00:00:00Z"
        val ack = JsonObject(first + mapOf("id" to JsonPrimitive("identity"), "revisionDate" to JsonPrimitive(nextRevision)))
        assertTrue(BitwardenCipherDocument.confirms(first, ack))
        val second = BitwardenCipherDocument.update(ack, update.copy(lastKnownRevisionDate = nextRevision),
            archiveIntent = JsonPrimitive("2026-11-04T01:00:00Z"))
        for (document in listOf(first, second)) {
            val identity = document.getValue("identity").jsonObject
            for (i in 1..3) {
                assertEquals(1, identity.keys.count { it.equals("address$i", ignoreCase = true) })
                assertEquals(JsonPrimitive("sealed-$i"), identity["address$i"])
            }
            assertEquals(original["identity"]!!.jsonObject["FutureIdentity"], identity["FutureIdentity"])
            BitwardenCipherDocument.decode(JsonObject(document + mapOf("id" to JsonPrimitive("identity"), "revisionDate" to JsonPrimitive(nextRevision))))
        }
        val identity = second.getValue("identity").jsonObject
        val ambiguous = JsonObject(ack + ("identity" to JsonObject(identity + ("Address1" to JsonPrimitive("different")))))
        reject { BitwardenCipherDocument.decode(ambiguous) }
        assertFalse(BitwardenCipherDocument.confirms(second, ambiguous))
    }

    @Test fun officialSerializedLegacyDataProjectsAllFiveTypesButNotBlobOrPartialData() {
        for ((type, section) in listOf(1 to "login", 2 to "secureNote", 3 to "card", 4 to "identity", 5 to "sshKey")) {
            val detail = if (type == 2) "{\"type\":0}" else "{}"
            val raw = Json.parseToJsonElement("""{"id":"cipher","type":$type,"name":"sealed-name","revisionDate":"$revision","$section":$detail}""").jsonObject
            val legacy = buildJsonObject { put("Name", "sealed-name"); if (type == 2) put("Type", 0) }
            val source = JsonObject(raw + ("data" to JsonPrimitive(legacy.toString())))
            assertTrue("type $type", BitwardenCipherDocument.canProject(BitwardenCipherDocument.decode(source)))
            assertFalse(BitwardenCipherDocument.canProject(BitwardenCipherDocument.decode(JsonObject(source + ("data" to JsonPrimitive("2.encrypted-blob"))))))
            assertFalse(BitwardenCipherDocument.canProject(BitwardenCipherDocument.decode(JsonObject(source + ("partialData" to JsonPrimitive(legacy.toString()))))))
            assertFalse(BitwardenCipherDocument.canProject(BitwardenCipherDocument.decode(JsonObject(source + ("data" to JsonPrimitive("{\"Name\":\"different\"}"))))))
        }
    }

    @Test fun officialDataIsPatchedAlongsideTypedFieldsAndPreservesUnknownDataAndHistory() {
        val oldData = Json.parseToJsonElement("""{"Name":"old","Username":"old","Password":"old","FutureData":{"opaque":[1,2]},"PasswordHistory":[{"Password":"ciphertext","LastUsedDate":"date","FutureHistory":true}]}""").jsonObject
        val original = JsonObject(source + ("data" to JsonPrimitive(oldData.toString())))
        val updated = BitwardenCipherDocument.update(original, request())
        val data = Json.parseToJsonElement(updated.getValue("data").jsonPrimitive.content).jsonObject
        assertEquals(updated["name"], data["Name"])
        assertEquals(updated["login"]!!.jsonObject["username"], data["Username"])
        assertEquals(updated["login"]!!.jsonObject["password"], data["Password"])
        assertEquals(oldData["FutureData"], data["FutureData"])
        assertEquals(oldData["PasswordHistory"], data["PasswordHistory"])
        assertTrue(BitwardenCipherDocument.canProject(BitwardenCipherDocument.decode(acknowledgment(updated))))
    }

    @Test fun officialDataConfirmationComparesJsonSemanticsAndRejectsChangedUnknownData() {
        val raw = Json.parseToJsonElement("""{"id":"cipher","type":1,"name":"same","favorite":false,"reprompt":0,"revisionDate":"$revision","login":{},"data":"{\"Name\":\"same\",\"Future\":{\"A\":1}}"}""").jsonObject
        val normalizedData = JsonPrimitive("{ \"Future\" : { \"A\" : 1 }, \"Name\" : \"same\" }")
        assertTrue(BitwardenCipherDocument.confirms(raw, JsonObject(raw + ("data" to normalizedData))))
        assertFalse(BitwardenCipherDocument.confirms(raw, JsonObject(raw + ("data" to JsonPrimitive("{\"Name\":\"same\",\"Future\":{\"A\":2}}")))))
        assertFalse(BitwardenCipherDocument.confirms(raw, JsonObject(raw + ("data" to JsonPrimitive("{\"Name\":\"different\",\"Future\":{\"A\":1}}")))))
    }
    @Test fun serverNormalizingAbsentCustomFieldsToEmptyArrayConfirmsTheSameContent() {
        val expected = Json.parseToJsonElement("""{"type":1,"name":"same","favorite":false,"reprompt":0,"fields":null}""").jsonObject
        assertTrue(BitwardenCipherDocument.confirms(expected, JsonObject(expected + ("fields" to JsonArray(emptyList())))))
        assertFalse(BitwardenCipherDocument.confirms(expected, JsonObject(expected + ("fields" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive(0)))))))))
        assertFalse(BitwardenCipherDocument.confirms(JsonObject(expected + ("future" to JsonNull)), JsonObject(expected + ("future" to JsonArray(emptyList())))))
    }

    @Test fun reencryptedUriUpdatesTheLegacyPrimaryAliasAndStillConfirmsEveryUri() {
        val value = Json.parseToJsonElement("""{"id":"cipher","type":1,"name":"old","revisionDate":"$revision","login":{"uri":"old-encoding","uris":[{"uri":"old-encoding"}],"futureLogin":"keep"}}""").jsonObject
        val updated = BitwardenCipherDocument.update(value, request(CipherLoginApiData(uris = listOf(CipherUriApiData(uri = "new-encoding")))))
        assertEquals(JsonPrimitive("new-encoding"), updated["login"]!!.jsonObject["uri"])
        assertEquals(JsonPrimitive("keep"), updated["login"]!!.jsonObject["futureLogin"])
        assertTrue(BitwardenCipherDocument.confirms(updated, updated))
        val tampered = JsonObject(updated + ("login" to JsonObject(updated["login"]!!.jsonObject + ("uris" to JsonArray(emptyList())))))
        assertFalse(BitwardenCipherDocument.confirms(updated, tampered))
    }

    @Test fun legacyPrimaryUriWithoutModernArrayIsProjectedBeforeEditing() {
        val value = Json.parseToJsonElement("""{"id":"cipher","type":1,"name":"old","revisionDate":"$revision","login":{"uri":"legacy-uri"}}""").jsonObject
        assertEquals("legacy-uri", BitwardenCipherDocument.decode(value).login?.uris?.single()?.uri)
    }
    @Test fun legacyLoginWithOfficialWrappingKeyIdRemainsProjectable() {
        val value = document("\"encryptedByKeyId\":\"ac4ecffb2e087a59180d1dd19950b8e9\"")
        assertTrue(BitwardenCipherDocument.canProject(BitwardenCipherDocument.decode(value)))
    }
    @Test fun legacyWrappingKeyIdMatchesOfficialSdkVectorAndUsesBothKeyHalves() {
        val key = SymmetricCryptoKey(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })
        try {
            assertEquals("ac4ecffb2e087a59180d1dd19950b8e9", BitwardenCipherKeyResolver.legacyWrappingKeyId(key))
            val cipher = BitwardenCipherDocument.decode(document("\"encryptedByKeyId\":\"ac4ecffb2e087a59180d1dd19950b8e9\""))
            BitwardenCipherKeyResolver.resolveCipherKey(cipher, key, "test").clear()
            key.macKey[0] = (key.macKey[0].toInt() xor 1).toByte()
            reject { BitwardenCipherKeyResolver.resolveCipherKey(cipher, key, "test") }
        } finally { key.clear() }
    }
    private fun request(login: CipherLoginApiData = CipherLoginApiData(username = "new", password = "new")) =
        CipherUpdateRequest(type = 1, name = "new", login = login, lastKnownRevisionDate = revision)
    private fun reject(block: () -> Unit) {
        try { block(); fail("Unsafe source accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    private fun document(extra: String) = BitwardenWireJson.parseObject(
        """{"id":"cipher","type":1,"name":"old","revisionDate":"$revision","login":{"username":"old"},$extra}""")
    private fun api(server: MockWebServer) = Retrofit.Builder().baseUrl(server.url("/"))
        .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
        .build().create(BitwardenVaultApi::class.java)

    private val source = Json.parseToJsonElement("""{
      "Id":"cipher", "Type":1, "Name":"old", "RevisionDate":"2026-11-03T00:00:00Z",
      "ArchivedDate":"2026-11-02T00:00:00Z", "FutureEnvelope":{"version":99,"opaque":[1,{"A":"B"}]},
      "Login":{"username":"old","password":"old","futureLogin":"opaque"},
      "PasswordHistory":[{"password":"ciphertext","lastUsedDate":"date","future":"opaque"}],
      "Attachments":[{"id":"attachment","key":"ciphertext","futureMetadata":{"x":1}}]
    }""").jsonObject

    private fun acknowledgment(request: JsonObject) = JsonObject(request + mapOf(
        "id" to JsonPrimitive("cipher"), "revisionDate" to JsonPrimitive("2026-11-04T00:00:00Z")))

    @Test fun acknowledgmentRejectsMissingUnknownEnvelopeAndNestedContent() {
        val sent = BitwardenCipherDocument.update(source, request())
        val ack = acknowledgment(sent)
        assertTrue(BitwardenCipherDocument.confirms(sent, ack))
        for (field in listOf("FutureEnvelope", "passwordHistory", "attachments", "archivedDate", "name"))
            assertFalse(field, BitwardenCipherDocument.confirms(sent, JsonObject(ack - field)))
        val login = ack["login"]!!.jsonObject
        assertFalse(BitwardenCipherDocument.confirms(sent, JsonObject(ack +
            ("login" to JsonObject(login - "futureLogin")))))
    }

    @Test fun acknowledgmentAcceptsKnownNullOmissionAndCasingButKeepsUnknownNullsExact() {
        val sent = BitwardenCipherDocument.update(source, request(CipherLoginApiData()))
        val ack = acknowledgment(sent)
        val login = ack["login"]!!.jsonObject
        val normalized = JsonObject((ack - "login" - "notes") + ("Login" to JsonObject(login - "password" - "username" - "totp")))
        assertTrue(BitwardenCipherDocument.confirms(sent, normalized))
        val unknownNull = JsonObject(sent + ("futureNull" to JsonNull))
        assertFalse(BitwardenCipherDocument.confirms(unknownNull, ack))
        val changedUnknown = JsonObject(ack + ("FutureEnvelope" to JsonObject(mapOf("VERSION" to JsonPrimitive(99)))))
        assertFalse(BitwardenCipherDocument.confirms(sent, changedUnknown))
    }

    @Test fun acknowledgmentAllowsEquivalentDatesAndServerOnlyTransportFields() {
        val sent = BitwardenCipherDocument.update(source, request())
        val ack = JsonObject((acknowledgment(sent) - "encryptedFor" - "lastKnownRevisionDate") +
            ("archivedDate" to JsonPrimitive("2026-11-02T00:00:00.000+00:00")))
        assertTrue(BitwardenCipherDocument.confirms(sent, ack))
        assertFalse(BitwardenCipherDocument.confirms(sent, JsonObject(ack +
            ("archivedDate" to JsonPrimitive("2026-11-03T00:00:00Z")))))
    }

    @Test fun acknowledgmentRetainsArrayOrderOccurrenceAndOpaqueHistoryShape() {
        val history = JsonArray(listOf(JsonPrimitive("opaque"), JsonObject(mapOf("x" to JsonPrimitive(2)))))
        val sent = JsonObject(BitwardenCipherDocument.update(source, request()) + ("passwordHistory" to history))
        val ack = acknowledgment(sent)
        assertFalse(BitwardenCipherDocument.confirms(sent, JsonObject(ack + ("passwordHistory" to JsonArray(history.reversed())))))
        assertFalse(BitwardenCipherDocument.confirms(sent, JsonObject(ack + ("passwordHistory" to JsonArray(history.drop(1))))))
        assertTrue(BitwardenCipherDocument.confirms(sent, ack))
    }

    @Test fun acknowledgmentAllowsRefreshedAttachmentUrlsButRequiresStableMetadataAndKey() {
        val attachment = JsonObject(mapOf("id" to JsonPrimitive("a"), "key" to JsonPrimitive("sealed-key"),
            "fileName" to JsonPrimitive("sealed-name"), "size" to JsonPrimitive("42"), "url" to JsonPrimitive("old"),
            "future" to JsonObject(mapOf("A" to JsonPrimitive(1)))))
        val sent = JsonObject(BitwardenCipherDocument.update(source, request()) + ("attachments" to JsonArray(listOf(attachment))))
        fun ack(file: JsonObject) = JsonObject(acknowledgment(sent) + ("Attachments" to JsonArray(listOf(file))) - "attachments")
        assertTrue(BitwardenCipherDocument.confirms(sent, ack(JsonObject(attachment + ("url" to JsonPrimitive("new"))))))
        for (field in listOf("id", "key", "fileName", "size", "future"))
            assertFalse(field, BitwardenCipherDocument.confirms(sent, ack(JsonObject(attachment - field))))
    }

    @Test fun acknowledgmentRejectsChangedTypeOwnershipAndNullClearedKnownValues() {
        val sent = BitwardenCipherDocument.update(source, request())
        val ack = acknowledgment(sent)
        for (change in listOf("type" to JsonPrimitive(2), "name" to JsonNull, "key" to JsonPrimitive("other-key"),
            "organizationId" to JsonPrimitive("other-team"), "favorite" to JsonPrimitive(true)))
            assertFalse(change.first, BitwardenCipherDocument.confirms(sent, JsonObject(ack + change)))
    }

    @Test fun ordinaryEditPreservesArchiveState() {
        val result = BitwardenCipherDocument.update(source, request())
        assertEquals(source["ArchivedDate"], result["archivedDate"])
    }

    @Test fun ordinaryEditPreservesUnknownEnvelopeAndNestedLoginData() {
        val result = BitwardenCipherDocument.update(source,
            request())
        assertEquals(source["FutureEnvelope"], result["FutureEnvelope"])
        assertEquals(JsonPrimitive("opaque"), result["login"]!!.jsonObject["futureLogin"])
        assertEquals(source["Attachments"], result["attachments"])
        assertEquals(source["PasswordHistory"], result["passwordHistory"])
    }

    @Test fun duplicateKeysIncludingEscapedSpellingAreRejectedBeforeParsing() {
        for (raw in listOf("""{"id":"first","id":"second"}""", """{"id":"first","\u0069d":"second"}""",
            """{"items":[{"secret":1,"secret":2}]}""")) reject { BitwardenWireJson.parseObject(raw) }
    }

    @Test fun ambiguousRecognizedCasingInNestedSourcesIsRejected() {
        for (raw in listOf(
            """{"Id":"a","id":"b","Type":1}""",
            """{"id":"a","type":1,"login":{"Username":"a","username":"b"}}""",
            """{"id":"a","type":1,"login":{"uris":[{"Uri":"a","uri":"b"}]}}""",
            """{"id":"a","type":1,"fields":[{"Name":"a","name":"b"}]}""",
            """{"id":"a","type":1,"login":{"fido2Credentials":[{"CredentialId":"a","credentialId":"b"}]}}""",
            """{"id":"a","type":1,"attachments":[{"Key":"a","key":"b"}]}"""
        )) reject { BitwardenCipherDocument.decode(BitwardenWireJson.parseObject(raw)) }
    }

    @Test fun unknownObjectKeyCaseAndArrayOrderArePreservedExactly() {
        val value = document(""""Future":{"A":1,"a":2,"list":[3,2,1]}""")
        assertEquals(value["Future"], BitwardenCipherDocument.update(value, request())["Future"])
    }

    @Test fun staleOrAbsentRevisionIsNeverReauthorizedByFreshSource() {
        for (rev in listOf(null, "", "2026-11-04T00:00:00Z")) {
            reject { BitwardenCipherDocument.update(source, request().copy(lastKnownRevisionDate = rev)) }
        }
    }

    @Test fun deletionReadOnlyAndTypeChangeAreRejected() {
        for (extra in listOf(""""deletedDate":"date"""", """"edit":false""",
            """"organizationId":"team","edit":true,"viewPassword":false""")) {
            reject { BitwardenCipherDocument.update(document(extra), request()) }
        }
        reject { BitwardenCipherDocument.update(source, request().copy(type = 2)) }
    }

    @Test fun ownershipArchiveHistoryAndItemKeyRemainUntouched() {
        val value = document(""""organizationId":"team","edit":true,"collectionIds":["c1","c2"],
            "key":"item-key","reprompt":1,"archivedDate":"date","passwordHistory":["opaque",{"X":1}]""")
        val updated = BitwardenCipherDocument.update(value, request().copy(organizationId = "other", key = "wrong",
            archivedDate = null, reprompt = 0, collectionIds = emptyList(), passwordHistory = JsonArray(emptyList())))
        for (field in listOf("organizationId","collectionIds","key","reprompt","archivedDate","passwordHistory"))
            assertEquals(field, value[field], updated[field])
    }

    @Test fun passwordChangeDoesNotReencodeOrTrimOpaqueFidoAndHistory() {
        val fido = Json.parseToJsonElement("""[{"credentialId":"credential","future":{"x":1}}]""")
        val login = JsonObject(mapOf("username" to JsonPrimitive("old"), "fido2Credentials" to fido,
            "passwordRevisionDate" to JsonPrimitive("date")))
        val value = JsonObject(source + ("Login" to login))
        val updated = BitwardenCipherDocument.update(value, request())
        assertEquals(fido, updated["login"]!!.jsonObject["fido2Credentials"])
        assertEquals(JsonPrimitive("date"), updated["login"]!!.jsonObject["passwordRevisionDate"])
        assertEquals(source["PasswordHistory"], updated["passwordHistory"])
    }

    @Test fun typedNullsCanClearKnownFieldsWithoutClearingUnknownOnes() {
        val updated = BitwardenCipherDocument.update(source, request(CipherLoginApiData()))
        assertEquals(JsonNull, updated["login"]!!.jsonObject["username"])
        assertEquals(JsonNull, updated["login"]!!.jsonObject["password"])
        assertEquals(JsonPrimitive("opaque"), updated["login"]!!.jsonObject["futureLogin"])
    }

    @Test fun unsupportedBlobPartialAndKeyIdFormatsCannotBecomeEmptyEditableRecords() {
        for (extra in listOf(""""data":"ciphertext"""", """"data":{"v":2}""",
            """"partialData":{"name":"ciphertext"}""", """"encryptedByKeyId":"new-key-id"""")) {
            val value = document(extra)
            val decoded = BitwardenCipherDocument.decode(value)
            assertEquals(value, decoded.sourceDocument)
            assertFalse(BitwardenCipherDocument.canProject(decoded))
            reject { BitwardenCipherDocument.update(value, request()) }
        }
    }

    @Test fun incompleteAndFutureTypesCannotEnterLegacyProjection() {
        assertFalse(BitwardenCipherDocument.canProject(CipherApiResponse(type = 1, name = "encrypted")))
        assertFalse(BitwardenCipherDocument.canProject(CipherApiResponse(type = 2, name = null, secureNote = CipherSecureNoteApiData(type = 0))))
        for (type in 6..9) assertFalse(BitwardenCipherDocument.canProject(CipherApiResponse(type = type, name = "encrypted")))
        reject { BitwardenCipherDocument.decode(BitwardenWireJson.parseObject("""{"id":"cipher"}""")) }
    }

    @Test fun syncRetainsEveryCipherBeforeProjectionAndRejectsDuplicateIds() {
        val value = BitwardenWireJson.parseObject("""{"Profile":{"Id":"account"},"Ciphers":[$source],
            "Folders":[],"FutureSync":{"opaque":true}}""")
        val decoded = BitwardenCipherDocument.decodeSync(value)
        assertEquals(source, decoded.ciphers.single().sourceDocument)
        reject { BitwardenCipherDocument.decodeSync(BitwardenWireJson.parseObject(
            """{"profile":{"id":"account"},"ciphers":[$source,$source]}""")) }
        reject { BitwardenCipherDocument.decodeSync(BitwardenWireJson.parseObject("""{"profile":{"id":"account"}}""")) }
    }

    @Test fun reencryptedUrisAndDuplicateCustomNamesRetainTheirOwnUnknownMetadata() {
        val key = SymmetricCryptoKey(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })
        try {
            fun enc(s: String) = JsonPrimitive(BitwardenCrypto.encryptString(s, key))
            fun field(value: String, marker: Int) = JsonObject(mapOf("name" to enc("duplicate"), "type" to JsonPrimitive(1),
                "value" to enc(value), "FutureField" to JsonPrimitive(marker)))
            val fields = JsonArray(listOf(field("first", 1), field("second", 2)))
            val uri = JsonObject(mapOf("uri" to enc("https://synthetic.invalid:8443/path"), "match" to JsonPrimitive(3),
                "FutureUri" to JsonPrimitive("keep")))
            val value = JsonObject(source + ("Fields" to fields) + ("Login" to JsonObject(mapOf("uris" to JsonArray(listOf(uri))))))
            val req = request(CipherLoginApiData(uris = listOf(CipherUriApiData(uri = enc("https://synthetic.invalid:8443/path").content,
                match = 3, uriChecksum = enc("checksum").content)))).copy(fields = listOf(
                CipherFieldApiData(name = enc("duplicate").content, value = enc("edited-first").content, type = 1),
                CipherFieldApiData(name = enc("duplicate").content, value = enc("edited-second").content, type = 1)))
            val updated = BitwardenCipherDocument.update(value, req, key)
            assertEquals(listOf(JsonPrimitive(1), JsonPrimitive(2)), updated["fields"]!!.jsonArray.map { it.jsonObject["FutureField"] })
            assertEquals(JsonPrimitive("keep"), updated["login"]!!.jsonObject["uris"]!!.jsonArray.single().jsonObject["FutureUri"])
            val renamed = req.copy(fields = req.fields!!.map { it.copy(name = enc("different").content) })
            reject { BitwardenCipherDocument.update(value, renamed, key) }
        } finally { key.clear() }
    }

    @Test fun officialDataArraysRetainMetadataAcrossReencryptionAndUseServerFieldNames() {
        val key = SymmetricCryptoKey(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })
        try {
            fun enc(s: String) = JsonPrimitive(BitwardenCrypto.encryptString(s, key))
            val fields = JsonArray((1..2).map { JsonObject(mapOf("name" to enc("duplicate"),
                "value" to enc("old-$it"), "type" to JsonPrimitive(1), "FutureField" to JsonPrimitive(it))) })
            val uris = JsonArray(listOf(JsonObject(mapOf("uri" to enc("https://synthetic.invalid:8443/path"),
                "match" to JsonPrimitive(3), "FutureUri" to JsonPrimitive("keep")))))
            val oldData = JsonObject(mapOf("Name" to JsonPrimitive("old"), "Fields" to fields, "Uris" to uris))
            val value = JsonObject(source + ("Fields" to fields) + ("Login" to JsonObject(mapOf("uris" to uris))) +
                ("data" to JsonPrimitive(oldData.toString())))
            val req = request(CipherLoginApiData(uris = listOf(CipherUriApiData(
                uri = enc("https://synthetic.invalid:8443/path").content, match = 3, uriChecksum = enc("checksum").content))))
                .copy(fields = (1..2).map { CipherFieldApiData(name = enc("duplicate").content,
                    value = enc("new-$it").content, type = 1) })
            val updated = BitwardenCipherDocument.update(value, req, key)
            val data = Json.parseToJsonElement(updated.getValue("data").jsonPrimitive.content).jsonObject
            assertEquals(listOf(JsonPrimitive(1), JsonPrimitive(2)), data.getValue("Fields").jsonArray.map { it.jsonObject["FutureField"] })
            assertEquals(listOf("new-1", "new-2"), data.getValue("Fields").jsonArray.map {
                BitwardenCrypto.decryptToString(it.jsonObject.getValue("Value").jsonPrimitive.content, key) })
            val uri = data.getValue("Uris").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive("keep"), uri["FutureUri"])
            assertEquals("checksum", BitwardenCrypto.decryptToString(uri.getValue("UriChecksum").jsonPrimitive.content, key))
            assertTrue(uri.containsKey("Uri") && uri.containsKey("Match"))
            assertTrue(BitwardenCipherDocument.canProject(BitwardenCipherDocument.decode(acknowledgment(updated))))
            assertTrue(BitwardenCipherDocument.confirms(updated, acknowledgment(updated)))
            reject { BitwardenCipherDocument.update(value, req.copy(fields = emptyList()), key) }
        } finally { key.clear() }
    }

    @Test fun rawRetrofitUpdateKeepsUnknownFieldsAndRevisionInActualHttpBody(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(source.toString()).setHeader("Content-Type", "application/json"))
            server.enqueue(MockResponse().setBody(source.toString()).setHeader("Content-Type", "application/json"))
            val service = api(server)
            val original = BitwardenCipherDocument.read(requireNotNull(service.getCipherSource("Bearer synthetic", "cipher").body()))
            val payload = BitwardenCipherDocument.update(original, request())
            val actual = BitwardenCipherDocument.decode(BitwardenCipherDocument.read(
                requireNotNull(service.updateCipherSource("Bearer synthetic", "cipher", payload).body())))
            assertEquals("cipher", actual.id)
            assertEquals("/ciphers/cipher", server.takeRequest().path)
            val wire = server.takeRequest()
            assertEquals("PUT", wire.method)
            val submitted = BitwardenWireJson.parseObject(wire.body.readUtf8())
            assertEquals(original["FutureEnvelope"], submitted["FutureEnvelope"])
            assertEquals(original["ArchivedDate"], submitted["archivedDate"])
            assertEquals(revision, submitted["lastKnownRevisionDate"]!!.jsonPrimitive.content)
            assertFalse(submitted.containsKey("Id"))
            assertFalse(submitted.containsKey("RevisionDate"))
        }
    }


    @Test fun explicitArchiveIntentChangesOnlyArchiveWhileDefaultRetainsIt() {
        assertEquals(source["ArchivedDate"], BitwardenCipherDocument.update(source, request())["archivedDate"])
        assertEquals(JsonNull, BitwardenCipherDocument.update(source, request(), archiveIntent = JsonNull)["archivedDate"])
        val date = JsonPrimitive("2026-11-04T00:00:00Z")
        assertEquals(date, BitwardenCipherDocument.update(source, request(), archiveIntent = date)["archivedDate"])
        reject { BitwardenCipherDocument.update(source, request(), archiveIntent = JsonObject(emptyMap())) }
    }

    @Test fun oversizedAndDeepWireDocumentsAreRejected() {
        reject { BitwardenWireJson.parseObject("""{"x":"too-long"}""", limit = 4) }
        reject { BitwardenWireJson.parseObject("""{"x":""" + "[".repeat(70) + "0" + "]".repeat(70) + "}") }
    }
}
