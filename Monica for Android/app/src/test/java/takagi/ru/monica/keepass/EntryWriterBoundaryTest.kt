package takagi.ru.monica.keepass

import android.app.Application
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.data.model.TotpData
import java.time.Instant
import java.util.UUID

/** In-memory writer contracts; no Android Keystore, file saving, or user vault is exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class EntryWriterBoundaryTest {
    private val nativeMutation = KeePassNativeMutation { Instant.parse("2026-11-10T00:00:00Z") }

    @Test
    fun blankPasswordOtpSkipsDecoderAndPreservesNativeCredentialsAndOpaqueFields() {
        val original = mixedEntry()
        val before = fieldSnapshot(original)
        val writer = KeePassPasswordEntryWriter(nativeMutation) {
            throw AssertionError("Blank authenticator data must not invoke the decoder")
        }

        val updated = writer.buildUpdatedEntry(
            original,
            passwordRow().copy(authenticatorKey = " \t\n"),
            plainPassword = "new password"
        )

        assertEquals("new password", updated.fields.getValue("Password").content)
        assertTrue(updated.fields.getValue("Password") is EntryValue.Encrypted)
        assertRetainedFields(original, updated, "otp", "HOTP Counter", "HmacOtp-Secret-Hex",
            "MonicaPasskeyData", KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY)
        assertNativeEnvelopeRetained(original, updated)
        assertEquals(before, fieldSnapshot(original))
    }

    @Test
    fun passwordAndSecureItemDecoderFailuresPropagateWithoutMutatingSource() {
        val original = mixedEntry()
        val before = fieldSnapshot(original)
        val failure = IllegalStateException("synthetic decoder failure")
        val passwordInputs = mutableListOf<String>()
        val writer = KeePassPasswordEntryWriter(nativeMutation) { encrypted ->
            passwordInputs += encrypted
            throw failure
        }
        val row = passwordRow().copy(authenticatorKey = "local-encrypted-otp")

        assertSameFailure(failure) {
            writer.buildPasswordEntryFieldPatch(row, "new password", existingEntry = original)
        }
        assertEquals(listOf("local-encrypted-otp"), passwordInputs)

        val secureInputs = mutableListOf<SecureItem>()
        val codec = KeePassSecureItemFieldCodec(nativeMutation) { item ->
            secureInputs += item
            throw failure
        }
        // Both the native-login OTP path and the general secure-item path must fail closed.
        listOf(ItemType.TOTP, ItemType.NOTE).forEach { type ->
            val item = SecureItem(itemType = type, title = "Synthetic item", itemData = "local-ciphertext")
            assertSameFailure(failure) { codec.buildSecureItemEntryFieldPatch(item, original) }
            assertSame(item, secureInputs.last())
        }
        assertEquals(2, secureInputs.size)
        assertEquals(before, fieldSnapshot(original))
    }

    @Test
    fun editingTotpOnMixedLoginReplacesOtpAliasesWithoutConvertingTheEntry() {
        val original = mixedEntry()
        val before = fieldSnapshot(original)
        val totp = TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = "Example", accountName = "alice")
        val portable = Json.encodeToString(TotpData.serializer(), totp)
        val item = SecureItem(itemType = ItemType.TOTP, title = "Edited authenticator", itemData = "local-ciphertext")
        val inputs = mutableListOf<SecureItem>()
        val codec = KeePassSecureItemFieldCodec(nativeMutation) {
            inputs += it
            portable
        }

        val updated = codec.buildSecureItemEntryFieldPatch(item, original).applyTo(original)

        assertEquals(1, inputs.size)
        assertSame(item, inputs.single())
        assertRetainedFields(original, updated, "Title", "UserName", "Password", "URL", "Notes",
            "MonicaPasskeyData", KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY)
        assertFalse(updated.fields.containsKey("MonicaItemType"))
        assertFalse(updated.fields.containsKey("MonicaItemData"))
        assertFalse(updated.fields.containsKey("HOTP Counter"))
        assertFalse(updated.fields.containsKey("HmacOtp-Secret-Hex"))
        assertEquals(portable, updated.fields.getValue("MonicaTotpData").content)
        assertTrue(updated.fields.getValue("MonicaTotpData") is EntryValue.Encrypted)
        assertTrue(updated.fields.getValue("otp") is EntryValue.Encrypted)
        assertTrue(updated.fields.getValue("TOTP Seed") is EntryValue.Encrypted)
        val parsed = requireNotNull(KeePassTotpCodec.parseFields({ updated.fields[it]?.content.orEmpty() }))
        assertEquals(totp.secret, parsed.secret)
        assertEquals(totp.issuer, parsed.issuer)
        assertEquals(totp.accountName, parsed.accountName)
        assertEquals(totp.otpType, parsed.otpType)
        assertNativeEnvelopeRetained(original, updated)
        assertEquals(before, fieldSnapshot(original))
    }

    @Test
    fun notePatchKeepsUnresolvedMetadataReferencesAndLoginAlongsideSeparateNotes() {
        val original = mixedEntry(
            "MonicaIsFavorite" to protectedValue(UNRESOLVED_REF),
            "MonicaImagePaths" to protectedValue(UNRESOLVED_REF)
        )
        val before = fieldSnapshot(original)
        val portable = Json.encodeToString(NoteData.serializer(), NoteData(content = "Portable note body"))
        val item = SecureItem(
            itemType = ItemType.NOTE,
            title = "Edited note",
            itemData = "local-encrypted-note",
            notes = "Independent Monica notes",
            isFavorite = false,
            imagePaths = ""
        )
        val inputs = mutableListOf<SecureItem>()
        val codec = KeePassSecureItemFieldCodec(nativeMutation) {
            inputs += it
            portable
        }

        val updated = codec.buildUpdatedSecureItemEntry(original, item)

        assertEquals(1, inputs.size)
        assertSame(item, inputs.single())
        assertRetainedFields(original, updated, "UserName", "Password", "URL", "otp",
            "MonicaPasskeyData", KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY,
            "MonicaIsFavorite", "MonicaImagePaths")
        assertEquals("Portable note body", updated.fields.getValue("Notes").content)
        assertEquals("Independent Monica notes", updated.fields.getValue("MonicaItemNotes").content)
        assertTrue(updated.fields.getValue("MonicaItemNotes") is EntryValue.Encrypted)
        assertEquals(portable, updated.fields.getValue("MonicaItemData").content)
        assertTrue(updated.fields.getValue("MonicaItemData") is EntryValue.Encrypted)
        assertNativeEnvelopeRetained(original, updated)
        assertEquals(before, fieldSnapshot(original))
    }

    @Test
    fun passkeyResolutionPrecedesPemExportAndBothReceiveOriginalAlias() {
        val original = mixedEntry()
        val before = fieldSnapshot(original)
        val passkey = passkeyRow()
        val calls = mutableListOf<Pair<String, String?>>()
        val writer = KeePassPasskeyEntryWriter(
            resolvePrivateKey = { alias ->
                calls += "resolve" to alias
                "resolved-portable-material"
            },
            exportPrivateKeyPem = { alias ->
                calls += "export" to alias
                "synthetic-exported-pem"
            }
        )

        val updated = writer.buildUpdatedPasskeyEntry(original, passkey)

        assertEquals(listOf("resolve" to passkey.privateKeyAlias, "export" to passkey.privateKeyAlias), calls)
        val portable = requireNotNull(KeePassPasskeySyncCodec.decode(
            updated.fields.getValue("MonicaPasskeyData").content, 9L, null, null
        ))
        assertEquals("resolved-portable-material", portable.privateKeyAlias)
        assertEquals("original-local-alias", passkey.privateKeyAlias)
        assertEquals("synthetic-exported-pem", updated.fields.getValue(KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY).content)
        listOf("MonicaPasskeyData", KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY,
            KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID, KeePassDxPasskeyCodec.FIELD_USER_HANDLE).forEach { name ->
            assertTrue("$name must remain protected", updated.fields.getValue(name) is EntryValue.Encrypted)
        }
        assertRetainedFields(original, updated, "Password", "otp")
        assertNativeEnvelopeRetained(original, updated)
        assertEquals(before, fieldSnapshot(original))
    }

    @Test
    fun passkeyFailuresPropagateAndOnlyNullExportUsesExistingProtectedPem() {
        val original = mixedEntry()
        val before = fieldSnapshot(original)
        val passkey = passkeyRow().copy(userName = "", userDisplayName = "", rpId = "")
        val failure = IllegalStateException("synthetic private-key failure")
        val calls = mutableListOf<Pair<String, String?>>()
        val resolveFailure = KeePassPasskeyEntryWriter(
            resolvePrivateKey = { alias -> calls += "resolve" to alias; throw failure },
            exportPrivateKeyPem = { alias -> calls += "export" to alias; "must not be reached" }
        )
        assertSameFailure(failure) { resolveFailure.buildPasskeyEntryFieldPatch(passkey, original) }
        assertEquals(listOf("resolve" to passkey.privateKeyAlias), calls)

        calls.clear()
        val exportFailure = KeePassPasskeyEntryWriter(
            resolvePrivateKey = { alias -> calls += "resolve" to alias; "portable" },
            exportPrivateKeyPem = { alias -> calls += "export" to alias; throw failure }
        )
        assertSameFailure(failure) { exportFailure.buildPasskeyEntryFieldPatch(passkey, original) }
        assertEquals(listOf("resolve" to passkey.privateKeyAlias, "export" to passkey.privateKeyAlias), calls)

        calls.clear()
        val nullResult = KeePassPasskeyEntryWriter(
            resolvePrivateKey = { alias -> calls += "resolve" to alias; null },
            exportPrivateKeyPem = { alias -> calls += "export" to alias; null }
        ).buildPasskeyEntryFieldPatch(passkey, original).applyTo(original)
        assertEquals(listOf("resolve" to passkey.privateKeyAlias, "export" to passkey.privateKeyAlias), calls)
        assertEquals("existing-pem", nullResult.fields.getValue(KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY).content)
        assertTrue(nullResult.fields.getValue(KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY) is EntryValue.Encrypted)
        val portable = requireNotNull(KeePassPasskeySyncCodec.decode(
            nullResult.fields.getValue("MonicaPasskeyData").content, 9L, null, null
        ))
        assertEquals("", portable.privateKeyAlias)
        assertRetainedFields(original, nullResult, "UserName", "Password", "URL", "otp")
        assertNativeEnvelopeRetained(original, nullResult)
        assertEquals(before, fieldSnapshot(original))
    }

    private fun passwordRow() = PasswordEntry(
        title = "Edited login", website = "https://example.test", username = "alice", password = "local-ciphertext"
    )

    private fun passkeyRow() = PasskeyEntry(
        credentialId = "AQIDBA", rpId = "example.test", rpName = "Example", userId = "BQYHCA",
        userName = "alice", userDisplayName = "Alice", publicKey = "synthetic-public-key",
        privateKeyAlias = "original-local-alias", createdAt = 1_700_000_000_000L, lastUsedAt = 1_700_000_001_000L
    )

    private fun mixedEntry(vararg extraFields: Pair<String, EntryValue>): Entry = Entry(
        uuid = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        fields = EntryFields.of(
            "Title" to EntryValue.Plain("Original mixed login"),
            "UserName" to EntryValue.Plain("original-user"),
            "Password" to protectedValue("original-password"),
            "URL" to EntryValue.Plain("https://original.test"),
            "Notes" to EntryValue.Plain("original-notes"),
            "otp" to protectedValue("otpauth://hotp/old?secret=MZXW6YTBOI&counter=7"),
            "HOTP Counter" to EntryValue.Plain("7"),
            "HmacOtp-Secret-Hex" to protectedValue("666f6f626172"),
            "MonicaPasskeyData" to protectedValue("opaque-existing-passkey-payload"),
            KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY to protectedValue("existing-pem"),
            "External protected reference" to protectedValue(UNRESOLVED_REF),
            "_etm_plugin_state" to EntryValue.Plain("opaque-plugin-state"),
            *extraFields
        ),
        times = null,
        binaries = listOf(BinaryReference(
            hash = ByteArray(32) { 7 }.toByteString(), name = "synthetic-attachment.bin"
        )),
        history = listOf(Entry(
            uuid = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            fields = EntryFields.of("Password" to protectedValue("history-password")),
            times = null
        ))
    )

    private fun protectedValue(value: String): EntryValue = EntryValue.Encrypted(EncryptedValue.fromString(value))

    private fun fieldSnapshot(entry: Entry): Map<String, Pair<String, Boolean>> =
        entry.fields.entries.associate { (name, value) -> name to (value.content to (value is EntryValue.Encrypted)) }

    private fun assertRetainedFields(original: Entry, updated: Entry, vararg names: String) {
        names.forEach { name -> assertSame(name, original.fields.getValue(name), updated.fields.getValue(name)) }
    }

    private fun assertNativeEnvelopeRetained(original: Entry, updated: Entry) {
        assertEquals(original.uuid, updated.uuid)
        assertSame(original.times, updated.times)
        assertSame(original.history, updated.history)
        assertSame(original.binaries, updated.binaries)
        assertRetainedFields(original, updated, "External protected reference", "_etm_plugin_state")
        assertEquals(UNRESOLVED_REF, updated.fields.getValue("External protected reference").content)
        assertTrue(updated.fields.getValue("External protected reference") is EntryValue.Encrypted)
    }

    private fun assertSameFailure(expected: Throwable, action: () -> Unit) {
        assertSame("The original failure must propagate", expected, runCatching(action).exceptionOrNull())
    }

    private companion object {
        const val UNRESOLVED_REF = "{REF:P@I:000000000000000000000000000000ff}"
    }
}
