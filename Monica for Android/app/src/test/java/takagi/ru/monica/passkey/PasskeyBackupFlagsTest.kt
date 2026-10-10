package takagi.ru.monica.passkey

import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.keepass.KeePassDxPasskeyCodec
import takagi.ru.monica.keepass.KeePassPasskeySyncCodec
import takagi.ru.monica.repository.mergeKeePassImportedPasskeys

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PasskeyBackupFlagsTest {
    private fun entry() = PasskeyEntry(credentialId = "Y3JlZGVudGlhbA", rpId = "example.test", rpName = "RP",
        userId = "dXNlcg", userName = "Account", userDisplayName = "Account", publicKey = "original-public",
        privateKeyAlias = "original-protected-ref", signCount = 41, isBackedUp = false,
        keepassDatabaseId = 1, passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT)

    @Test fun oldRecordsKeepTheirFlagsRegardlessOfLocalBackupStatus() {
        for (backedUp in listOf(false, true)) {
            val row = entry().copy(isBackedUp = backedUp)
            assertEquals(0x1d, PasskeyBackupFlags.authenticatorFlags(row.backupEligible, row.backupState))
            val restored = KeePassPasskeySyncCodec.decode(KeePassPasskeySyncCodec.encode(row), 1, null, null)!!
            assertNull(restored.backupEligible)
            assertNull(restored.backupState)
            assertEquals(row.credentialId, restored.credentialId)
            assertEquals(row.privateKeyAlias, restored.privateKeyAlias)
            assertEquals(41L, restored.signCount)
            assertEquals(0x1d, PasskeyBackupFlags.authenticatorFlags(restored.backupEligible, restored.backupState))
        }
    }

    @Test fun actualAuthenticatorDataUsesExplicitFlagsWithoutChangingKeyOrStoredCounter() {
        val activity = PasskeyAuthActivity()
        val method = PasskeyAuthActivity::class.java.getDeclaredMethod("createAuthenticatorData",
            String::class.java, Int::class.javaPrimitiveType, PasskeyEntry::class.java).apply { isAccessible = true }
        for ((be, bs, expected) in listOf(Triple(false, false, 5), Triple(true, false, 13), Triple(true, true, 29))) {
            val row = entry().copy(backupEligible = be, backupState = bs)
            val data = method.invoke(activity, row.rpId, 0, row) as ByteArray
            assertEquals(37, data.size)
            assertEquals(expected, data[32].toInt())
            assertEquals(0, ByteBuffer.wrap(data, 33, 4).int)
            assertEquals(41L, row.signCount)
            assertEquals("original-protected-ref", row.privateKeyAlias)
            assertEquals("Y3JlZGVudGlhbA", row.credentialId)
        }
        try {
            method.invoke(activity, "example.test", 0, entry().copy(backupEligible = false, backupState = true))
            fail("Inconsistent flags must not be signed")
        } catch (error: InvocationTargetException) { assertTrue(error.cause is IllegalArgumentException) }
    }

    @Test fun keePassPayloadAndNativeFieldsKeepAllValidFlagPairsAndKeyBytes() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val key = Base64.getEncoder().encodeToString(pair.private.encoded)
        for ((be, bs) in listOf(false to false, true to false, true to true)) {
            val row = entry().copy(privateKeyAlias = key, backupEligible = be, backupState = bs)
            val restored = KeePassPasskeySyncCodec.decode(KeePassPasskeySyncCodec.encode(row), 1, null, null)!!
            assertEquals(be, restored.backupEligible)
            assertEquals(bs, restored.backupState)
            assertEquals(key, restored.privateKeyAlias)
            val fields = KeePassDxPasskeyCodec.buildCustomFieldPairs(restored).toMap()
            val native = KeePassDxPasskeyCodec.decode({ fields[it]?.content.orEmpty() }, "RP", "", 1, null, null)!!
            assertEquals(be, native.backupEligible)
            assertEquals(bs, native.backupState)
            assertEquals(row.credentialId, native.credentialId)
            assertArrayEquals(pair.private.encoded, Base64.getDecoder().decode(native.privateKeyAlias))
        }
    }

    @Test fun legacyAndroidPayloadOverridesOldSyncMarkerDerivedNativeFlags() {
        val row = entry()
        val payload = JSONObject(KeePassPasskeySyncCodec.encode(row)).apply {
            remove("backupEligible"); remove("backupState")
        }.toString()
        val restored = KeePassPasskeySyncCodec.decode(payload, 1, null, null)!!
        val fields = KeePassDxPasskeyCodec.buildCustomFieldPairs(restored,
            existingFieldValue = { if (it.endsWith("FLAG_BE") || it.endsWith("FLAG_BS")) "false" else "" },
            exportPrivateKeyPem = { "original-key" }).toMap()
        assertEquals("true", fields.getValue(KeePassDxPasskeyCodec.FIELD_FLAG_BE).content)
        assertEquals("true", fields.getValue(KeePassDxPasskeyCodec.FIELD_FLAG_BS).content)
        assertEquals(row.credentialId, fields.getValue(KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID).content)
    }

    @Test fun refreshPreservesKnownEligibilityAndAcceptsBackupStateChanges() {
        val existing = entry().copy(id = 42, backupEligible = true, backupState = true)
        val incoming = existing.copy(id = 0, backupState = false)
        val merged = mergeKeePassImportedPasskeys(1, listOf(incoming), listOf(existing)).mergedPasskeys.single()
        assertEquals(42L, merged.id)
        assertEquals(false, merged.backupState)
        assertEquals(existing.privateKeyAlias, merged.privateKeyAlias)
        val oldPayload = incoming.copy(backupEligible = null, backupState = null)
        val oldMerge = mergeKeePassImportedPasskeys(1, listOf(oldPayload), listOf(existing)).mergedPasskeys.single()
        assertEquals(true, oldMerge.backupEligible)
        assertEquals(true, oldMerge.backupState)
        assertThrows(IllegalArgumentException::class.java) {
            mergeKeePassImportedPasskeys(1, listOf(incoming.copy(backupEligible = false)), listOf(existing))
        }
    }

    @Test fun bitwardenRefusesChangingImmutableEligibilityDuringTransfer() {
        val mapper = takagi.ru.monica.bitwarden.mapper.PasskeyMapper()
        val original = entry().copy(backupEligible = false, backupState = false)
        assertThrows(IllegalArgumentException::class.java) { mapper.toCreateRequest(original, null) }
        assertEquals(false, original.backupEligible)
        assertEquals("original-protected-ref", original.privateKeyAlias)
        mapper.toCreateRequest(entry(), null)
        mapper.toCreateRequest(entry().copy(backupEligible = true, backupState = false), null)
    }

    @Test fun refreshDoesNotReinterpretLegacyNullAsANewCredential() {
        val existing = entry().copy(id = 42)
        val incoming = existing.copy(id = 0, backupEligible = false, backupState = false)
        assertThrows(IllegalArgumentException::class.java) {
            mergeKeePassImportedPasskeys(1, listOf(incoming), listOf(existing))
        }
        assertEquals(0x1d, PasskeyBackupFlags.authenticatorFlags(existing.backupEligible, existing.backupState))
        assertEquals("original-protected-ref", existing.privateKeyAlias)
        val newImport = mergeKeePassImportedPasskeys(1, listOf(incoming), emptyList()).mergedPasskeys.single()
        assertEquals(0x05, PasskeyBackupFlags.authenticatorFlags(newImport.backupEligible, newImport.backupState))
        assertEquals(false, PasskeyBackupFlags.mergeEligibility(null, false, hasExistingCredential = false))
        assertThrows(IllegalArgumentException::class.java) { PasskeyBackupFlags.mergeEligibility(null, false) }
    }

    @Test fun jsonFlagsDistinguishLegacyMissingValuesAndRejectMalformedValues() {
        assertNull(PasskeyBackupFlags.readBoolean(JSONObject("{}"), "backup_eligible"))
        assertNull(PasskeyBackupFlags.readBoolean(JSONObject("{\"backup_eligible\":null}"), "backup_eligible"))
        assertEquals(false, PasskeyBackupFlags.readBoolean(JSONObject("{\"backup_eligible\":false}"), "backup_eligible"))
        for (value in listOf("\"false\"", "0", "[]", "{}")) {
            assertThrows(IllegalArgumentException::class.java) {
                PasskeyBackupFlags.readBoolean(JSONObject("{\"backup_eligible\":$value}"), "backup_eligible")
            }
        }
    }
}
