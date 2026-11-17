package takagi.ru.monica.repository

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.model.PasswordUrlRules
import uniffi.mdbx_ffi.*

class MdbxAutofillReaderTest {
    private fun parse(payload: String, decrypt: (String) -> String = { it }) =
        MdbxAutofillReader.parse(payload, 7, "native-object", "native-folder", "Native title", "revision", decrypt)
    private fun parseLegacy(payload: String, decrypt: (String) -> String) = MdbxAutofillReader.parse(payload, 7,
        mdbx2PhysicalEntryId("11111111-1111-1111-1111-111111111111", "password:42"),
        "folder", "title", "revision", decrypt, "11111111-1111-1111-1111-111111111111")

    @Test fun nativeAndCliPlaintextPrefixesAreNotReinterpretedAsCiphertext() {
        for (prefix in listOf("C2|", "MDK|", "v2:")) {
            val payload = """{"kind":"password","monica_entry_id":"password:cli-id","monica_password_encoding":"plaintext-v1","username":"${prefix}user","password_plain":"${prefix}password"}"""
            val result = checkNotNull(parse(payload) { error("Plaintext must not be decrypted") })
            assertEquals("${prefix}user", result.entry.username)
            assertEquals("${prefix}password", result.entry.password)
            assertEquals(0L, result.entry.id)
            assertEquals(7L, result.entry.mdbxDatabaseId)
            assertEquals("native-object", result.entry.replicaGroupId)
            assertEquals("native-folder", result.entry.mdbxFolderId)
        }
    }

    @Test fun explicitEmptyAndNullNeverResurrectOldPassword() {
        for (value in listOf("\"\"", "null")) {
            assertEquals("", checkNotNull(parse("""{"username":"u","password_plain":$value,"password":"old-secret"}""")).entry.password)
        }
        assertEquals("", checkNotNull(parse("""{"username":"u"}""")).entry.password)
        assertEquals("old", checkNotNull(parse("""{"username":"u","password":"old"}""")).entry.password)
        assertNull(parse("""{"username":"u","password_plain":false}"""))
    }

    @Test fun knownOlderAndroidCiphertextsRequireSuccessfulDecode() {
        val payload = """{"kind":"password","monica_entry_id":"password:42","room_id":42,"username":"C2|user","password_plain":"native-password"}"""
        assertEquals("decoded-user", checkNotNull(parseLegacy(payload) { if (it == "C2|user") "decoded-user" else it }).entry.username)
        assertNull(parseLegacy(payload) { error("unreadable legacy ciphertext") })
        assertEquals("C2|user", checkNotNull(parse(payload) { error("Unproven native object must stay literal") }).entry.username)
    }

    @Test fun cancelledLegacyDecodeIsNeverSuppressed() {
        assertTrue(runCatching { parseLegacy("""{"kind":"password","monica_entry_id":"password:42","room_id":42,"username":"x"}""") {
            throw CancellationException("synthetic cancellation")
        } }.exceptionOrNull() is CancellationException)
    }

    @Test fun duplicatesEscapesAndConflictingAliasesFailClosed() {
        assertNull(parse("""{"username":"u","password_plain":"first","password_plain":"second"}"""))
        assertNull(parse("""{"username":"u","password_plain":"first","password_\u0070lain":"second"}"""))
        assertNull(parse("""{"username":"u","app_package_name":"one","appPackageName":"two"}"""))
        assertNull(parse("""{"username":"u","password_plain":"one","passwordPlain":"two"}"""))
        assertNotNull(parse("""{"username":"u","app_package_name":"one","appPackageName":"one"}"""))
    }

    @Test fun futureUnknownAndNonLoginShapesAreExcluded() {
        for (extra in listOf("\"schema\":\"future-login\"", "\"kind\":\"api-token\"", "\"login_type\":\"WIFI\"",
            "\"login_type\":\"API_KEY\"", "\"login_type\":\"SSO\"", "\"login_type\":\"SSH_KEY\"",
            "\"monica_password_encoding\":\"future\"", "\"username\":42")) {
            assertNull(parse("""{"password_plain":"secret",$extra}"""))
        }
        assertNull(parse("""{"credentials":[{"username":"nested","password":"secret"}]}"""))
    }

    @Test fun connectorReplicasArchivedAndDeletedRecordsAreExcluded() {
        for (extra in listOf("\"bitwarden_mode\":true", "\"keepass_mode\":true", "\"is_archived\":true",
            "\"isDeleted\":true", "\"bitwardenVaultId\":1", "\"keepass_database_id\":2"))
            assertNull(parse("""{"username":"u","password_plain":"secret",$extra}"""))
    }

    @Test fun desktopWrappedLoginRemainsPlaintextAndUsesNativeIdentity() {
        val result = checkNotNull(parse("""{"data":{"entry":{"id":123,"username":"MDK|native","password":"secret","loginType":0,"appPackageName":"app.test","isArchived":false},"customFields":[]}}""") { error("native plaintext") })
        assertEquals("MDK|native", result.entry.username)
        assertEquals("secret", result.entry.password)
        assertEquals("app.test", result.entry.appPackageName)
        assertEquals("native-object", result.objectId)
        assertNull(parse("""{"username":"outer","data":{"entry":{"id":123,"username":"inner"}}}"""))
    }

    @Test fun wrappedLoginRejectsOuterSecretAliasesAndUnknownInnerEncoding() {
        for (outerKey in listOf("password", "password_plain", "passwordPlain")) {
            assertNull(parse("""{"$outerKey":"outer-secret","data":{"entry":{"id":123,"username":"inner","password":"inner-secret"}}}"""))
        }
        for (entry in listOf(
            """{"entry":{"id":123,"username":"inner","password":"secret","monica_password_encoding":"future-v2"}}""",
            """{"id":123,"username":"inner","password":"secret","monica_password_encoding":"future-v2"}""",
        )) {
            assertNull(parse("""{"monica_password_encoding":"plaintext-v1","data":$entry}"""))
        }
        assertEquals("MDK|literal", checkNotNull(parse(
            """{"data":{"entry":{"id":123,"username":"inner","password":"MDK|literal","monica_password_encoding":"plaintext-v1"}}}"""
        ) { error("Known native plaintext must stay literal") }).entry.password)
    }

    @Test fun urlRulesDistinguishMissingFromInvalidAndDuplicateMetadata() {
        assertNull(checkNotNull(parse("""{"username":"u"}""")).urlRules)
        val rule = PasswordUrlRules.encode(listOf(PasswordUrlRules.Rule("https://only.test", PasswordUrlRules.Mode.EXACT)))
        fun payload(values: List<String>) = buildJsonObject {
            put("username", "u")
            put("custom_fields", buildJsonArray { values.forEach { value -> add(buildJsonObject {
                put("title", PasswordUrlRules.FIELD); put("value", value); put("is_protected", false)
            }) } })
        }.toString()
        assertEquals(PasswordUrlRules.Mode.EXACT, checkNotNull(parse(payload(listOf(rule)))).urlRules!!.single().mode)
        assertEquals(emptyList<PasswordUrlRules.Rule>(), checkNotNull(parse(payload(listOf("future")))).urlRules)
        assertEquals(emptyList<PasswordUrlRules.Rule>(), checkNotNull(parse(payload(listOf(rule, rule)))).urlRules)
        val invalidDuplicate = """{"version":1,"urls":[],"native":true,"native":false}"""
        assertTrue(checkNotNull(parse(payload(listOf(invalidDuplicate)))).urlRules!!.isEmpty())
    }

    @Test fun embeddedFieldsAndLegacyWalletValuesStayRequestOwned() {
        val result = checkNotNull(parse("""{"username":"u","credit_card_number_plain":"4111111111111111","credit_card_cvv_plain":"123","address_line":"Synthetic street","custom_fields":[{"title":"monica.content.wallet.bank_card","value":"opaque-future-wallet","is_protected":true}]}"""))
        assertEquals("4111111111111111", result.entry.creditCardNumber)
        assertEquals("123", result.entry.creditCardCVV)
        assertEquals("Synthetic street", result.entry.addressLine)
        assertEquals("opaque-future-wallet", result.customFields.single().value)
    }

    @Test fun nativeAuthorizationOutcomesAndAllUnknownClientConstraintsAreRejected() {
        fun decision(outcome: MdbxAuthorizationOutcome, constraints: List<MdbxAuthorizationConstraint> = emptyList()) =
            MdbxAuthorizationDecision(outcome, emptyList(), constraints, true)
        assertTrue(MdbxAutofillReader.accepts(decision(MdbxAuthorizationOutcome.ALLOW)))
        assertTrue(MdbxAutofillReader.accepts(decision(MdbxAuthorizationOutcome.ALLOW_WITH_CONSTRAINTS)))
        for (outcome in listOf(MdbxAuthorizationOutcome.DENY, MdbxAuthorizationOutcome.REQUIRE_FRESH_AUTHENTICATION,
            MdbxAuthorizationOutcome.REQUIRE_ADDITIONAL_FACTOR)) assertFalse(MdbxAutofillReader.accepts(decision(outcome)))
        for (kind in MdbxAuthorizationConstraintKind.entries) assertFalse(MdbxAutofillReader.accepts(
            decision(MdbxAuthorizationOutcome.ALLOW_WITH_CONSTRAINTS, listOf(MdbxAuthorizationConstraint(kind, 10u)))))
    }

    @Test fun knownWalletShapesRequireTypeKindAndValidNestedDocument() {
        fun wallet(type: String, kind: String, data: String) = MdbxAutofillReader.parseWallet(buildJsonObject {
            put("kind", kind); put("item_data", data)
        }.toString(), type, 7, "wallet", "folder", "Card", "revision")
        val card = """{"cardNumber":"4111111111111111","cardholderName":"Synthetic","expiryMonth":"12","expiryYear":"2030","cvv":"123","future":{"big":9007199254740993}}"""
        assertEquals(card, checkNotNull(wallet("card", "bank_card", card)).item.itemData)
        assertNotNull(wallet("document-ref", "document", """{"documentType":"PASSPORT","documentNumber":"synthetic","fullName":"Test"}"""))
        assertNotNull(wallet("billing-address", "billing_address", """{"streetAddress":"Test"}"""))
        assertNull(wallet("identity", "document", card))
        assertNull(wallet("card", "document", card))
        assertNull(wallet("card", "bank_card", """{"unknown":"data"}"""))
        assertNull(wallet("billing-address", "billing_address", """{"schema":"future","streetAddress":"Test"}"""))
        assertNull(wallet("billing-address", "billing_address", """{"streetAddress":"one","streetAddress":"two"}"""))
    }

    @Test fun oversizedAndOvernestedPayloadsAreRejected() {
        assertNull(parse("""{"username":"${"x".repeat(MdbxAutofillReader.MAX_PAYLOAD_BYTES)}"}"""))
        assertNull(parse("""{"username":"u","unknown":${"[".repeat(70)}0${"]".repeat(70)}}"""))
    }

    @Test fun sourceHashIsUnambiguousAndDoesNotExposeInputs() {
        val one = mdbxAutofillDigest(listOf("private/path", "credential", null))
        assertEquals(64, one.length)
        assertFalse(one.contains("credential"))
        assertNotEquals(mdbxAutofillDigest(listOf("a", "bc")), mdbxAutofillDigest(listOf("ab", "c")))
        assertNotEquals(mdbxAutofillDigest(listOf(null)), mdbxAutofillDigest(listOf("")))
    }

    @Test fun logicalLoginIdentityRequiresTheActualVaultAndPhysicalObject() {
        val vaultId = "11111111-1111-1111-1111-111111111111"
        val logical = "password:42"
        val objectId = mdbx2PhysicalEntryId(vaultId, logical)
        fun read(nativeId: String, vault: String?, payloadLogical: String = logical) =
            MdbxAutofillReader.parse("""{"username":"u","password_plain":"secret","room_id":42,"monica_entry_id":"$payloadLogical"}""",
                7, nativeId, "folder", "title", "revision", { it }, vault)
        assertEquals(logical, checkNotNull(read(objectId, vaultId)).logicalEntryId)
        assertNull(checkNotNull(read("22222222-2222-2222-2222-222222222222", vaultId)).logicalEntryId)
        assertNull(checkNotNull(read(objectId, "33333333-3333-3333-3333-333333333333")).logicalEntryId)
        assertNull(checkNotNull(read(objectId, vaultId, "password:43")).logicalEntryId)
        assertNull(checkNotNull(read(objectId, null)).logicalEntryId)
        assertNull(checkNotNull(parse("""{"username":"u","room_id":42}""")).logicalEntryId)
    }

    @Test fun logicalWalletIdentityCannotBeForgedWithSameRoomNumber() {
        val vaultId = "11111111-1111-1111-1111-111111111111"
        val logical = "billing-address:42"
        val payload = """{"kind":"billing_address","monica_entry_id":"$logical","room_id":42,"item_data":"{\"streetAddress\":\"Synthetic\"}"}"""
        fun read(objectId: String, vault: String?) = MdbxAutofillReader.parseWallet(payload,
            "billing-address", 7, objectId, "folder", "title", "revision", vault)
        assertEquals(logical, checkNotNull(read(mdbx2PhysicalEntryId(vaultId, logical), vaultId)).logicalEntryId)
        assertNull(checkNotNull(read("22222222-2222-2222-2222-222222222222", vaultId)).logicalEntryId)
        assertNull(checkNotNull(read(mdbx2PhysicalEntryId(vaultId, logical), null)).logicalEntryId)
    }

    @Test fun actualVaultIdentityDoesNotDependOnMutableLogicalMetadata() {
        val vaultId = "11111111-1111-1111-1111-111111111111"
        val objectId = "22222222-2222-2222-2222-222222222222"
        for (metadata in listOf("", ",\"monica_entry_id\":\"password:forged\",\"nativeVaultId\":\"forged-vault\"")) {
            val login = checkNotNull(MdbxAutofillReader.parse(
                """{"username":"MDK|literal","password_plain":"secret"$metadata}""",
                7, objectId, "folder", "title", "revision", { error("Unproven native data must stay literal") }, vaultId))
            assertEquals(vaultId, login.nativeVaultId)
            assertNull(login.logicalEntryId)
            assertEquals("MDK|literal", login.entry.username)
            val wallet = checkNotNull(MdbxAutofillReader.parseWallet(
                """{"kind":"billing_address","item_data":"{\"streetAddress\":\"Synthetic\"}"$metadata}""",
                "billing-address", 7, objectId, "folder", "title", "revision", vaultId))
            assertEquals(vaultId, wallet.nativeVaultId)
            assertNull(wallet.logicalEntryId)
        }
    }
}
