package takagi.ru.monica.data.dedup

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.BitwardenCipherDocument

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SingleVaultDedupNativeDocumentTest {
    private val key = SymmetricCryptoKey(ByteArray(32) { 9 }, ByteArray(32) { 7 })

    @Test fun bitwardenFullTypedFieldsUseSingleKeeperContainment() {
        val projects = listOf(project("A", fields(1, 2, 3)), project("B", fields(1, 2)), project("C", fields(2, 3, 4)))
        assertTrue(projects.all { it.blockers.isEmpty() })
        val result = SingleVaultDedupComparator.analyze(projects)
        assertEquals(listOf("bitwarden:1:B"), result.candidates.map { it.candidateKey })
        assertEquals("bitwarden:1:A", result.candidates.single().keeperKey)
    }

    @Test fun bitwardenPasswordAndUriMatchBehaviorPreventFalseDuplicates() {
        val baseline = project("A", fields(1))
        val changedPassword = project("B", fields(1), login = login("other"))
        val changedMatch = project("C", fields(1), login = JsonObject(login() + ("uris" to buildJsonArray {
            add(buildJsonObject { put("uri", encrypted("https://example.invalid")); put("match", 3) }) })))
        assertTrue(SingleVaultDedupComparator.analyze(listOf(baseline, changedPassword, changedMatch)).candidates.isEmpty())
    }

    @Test fun bitwardenRepeatedFieldsAndProtectionTypeRemainSignificant() {
        val one = project("A", fields(1))
        val two = project("B", fields(1, 1))
        val protected = project("C", buildJsonArray { add(buildJsonObject {
            put("name", encrypted("Field")); put("value", encrypted("1")); put("type", 1)
        }) })
        assertFalse(SingleVaultDedupComparator.covers(one, two))
        assertTrue(SingleVaultDedupComparator.covers(two, one))
        assertFalse(SingleVaultDedupComparator.covers(one, protected))
    }

    @Test fun bitwardenUnknownAttachmentAndHistoryContentIsNotDropped() {
        val unknown = project("A", fields(1), extra = mapOf("futureNativeData" to buildJsonObject { put("secret", "opaque") }))
        assertTrue(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT in unknown.blockers)
        val attachment = project("B", fields(1), extra = mapOf("attachments" to buildJsonArray {
            add(buildJsonObject { put("id", "asset"); put("fileName", encrypted("file")); put("size", "3") }) }))
        assertTrue(SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT in attachment.blockers)
        val history = project("C", fields(1), extra = mapOf("passwordHistory" to buildJsonArray {
            add(buildJsonObject { put("password", encrypted("old")); put("lastUsedDate", "2026-11-01T00:00:00Z") }) }))
        assertFalse(SingleVaultDedupComparator.covers(project("D", fields(1)), history))
    }

    @Test fun bitwardenNativePasskeyAndBindingFieldsAreExplicitlyPreserved() {
        val fido = JsonObject(login() + ("fido2Credentials" to buildJsonArray {
            add(buildJsonObject { put("credentialId", encrypted("synthetic-id")); put("keyValue", encrypted("synthetic-key")) })
        }))
        val a = project("A", fields(1), login = fido)
        val b = project("B", fields(1), login = fido)
        assertTrue(SingleVaultDedupBlockReason.PASSKEY_PRESERVED in a.blockers)
        assertTrue(SingleVaultDedupComparator.analyze(listOf(a, b)).candidates.isEmpty())
        val binding = project("C", buildJsonArray { add(buildJsonObject {
            put("name", encrypted("passkeyBindings")); put("value", encrypted("synthetic-reference")); put("type", 1)
        }) })
        assertTrue(SingleVaultDedupBlockReason.PASSKEY_REFERENCE in binding.blockers)
    }

    @Test fun bitwardenUnreadableFieldNeverFallsBackToCiphertextEquality() {
        val broken = buildJsonArray { add(buildJsonObject { put("name", encrypted("Field")); put("value", "broken"); put("type", 0) }) }
        val a = project("A", broken)
        val b = project("B", broken)
        assertTrue(SingleVaultDedupBlockReason.UNREADABLE_CONTENT in a.blockers)
        assertTrue(SingleVaultDedupComparator.analyze(listOf(a, b)).candidates.isEmpty())
    }

    @Test fun bitwardenAccountGroupAndLinkedFieldsNeedSeparateStructuralProof() {
        val fields = buildJsonArray { add(buildJsonObject {
            put("name", encrypted("monica.content.credential")); put("value", encrypted("{}")); put("type", 0)
        }) }
        assertTrue(SingleVaultDedupBlockReason.INCONSISTENT_PROJECT in project("A", fields).blockers)
        val linked = buildJsonArray { add(buildJsonObject {
            put("name", encrypted("Linked")); put("value", JsonNull); put("type", 3); put("linkedId", 100)
        }) }
        assertTrue(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE in project("B", linked).blockers)
    }

    @Test fun mdbxNativeRowsRetainUnknownPayloadAndDoNotClaimHistoryCompleteness() {
        val raw = buildJsonObject { put("futureNativeObject", buildJsonObject { put("opaque", "retained") }) }
        val node = SingleVaultDedupProjectBuilder.exactJsonNode("native", raw)
        val rows = listOf(SingleVaultDedupMdbxDocument.Row("A", "Example", raw, node,
            setOf(SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT, SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)))
        val projects = SingleVaultDedupMdbxDocument.projects("mdbx:1", rows)
        assertEquals(1, projects.size)
        assertEquals(2, projects.single().blockers.size)
        assertTrue(projects.single().content!!.children.single().attributes.getValue("json").contains("futureNativeObject"))
        assertTrue(SingleVaultDedupComparator.analyze(projects).candidates.isEmpty())
    }

    @Test fun mdbxExplicitGroupIsCountedAsOneBlockedProject() {
        val payload = buildJsonObject { put("password_group_id", "same-project") }
        val rows = listOf("A", "B").map { SingleVaultDedupMdbxDocument.Row(it, "Example", payload,
            SingleVaultDedupProjectBuilder.exactJsonNode("native", payload), setOf(SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT)) }
        val project = SingleVaultDedupMdbxDocument.projects("mdbx:1", rows).single()
        assertEquals(2, project.summary.contentCount)
        assertTrue(SingleVaultDedupBlockReason.INCONSISTENT_PROJECT in project.blockers)
    }

    private fun project(id: String, fields: JsonArray, login: JsonObject = login(), extra: Map<String, JsonElement> = emptyMap()): SingleVaultDedupProject {
        val raw = buildJsonObject {
            put("id", id); put("type", 1); put("name", encrypted("Example")); put("revisionDate", "2026-11-06T00:00:00Z")
            put("login", login); put("fields", fields); put("favorite", false); put("reprompt", 0)
            extra.forEach { (name, value) -> put(name, value) }
        }
        return SingleVaultDedupBitwardenDocument.project("bitwarden:1", BitwardenCipherDocument.decode(raw), key)
    }

    private fun fields(vararg values: Int) = buildJsonArray { values.forEach { value -> add(buildJsonObject {
        put("name", encrypted("Field")); put("value", encrypted(value.toString())); put("type", 0)
    }) } }
    private fun login(password: String = "same") = buildJsonObject {
        put("username", encrypted("alice")); put("password", encrypted(password))
        put("uris", buildJsonArray { add(buildJsonObject { put("uri", encrypted("https://example.invalid")); put("match", 0) }) })
    }
    private fun encrypted(value: String) = BitwardenCrypto.encryptString(value, key)
}
