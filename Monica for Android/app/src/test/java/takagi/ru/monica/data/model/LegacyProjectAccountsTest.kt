package takagi.ru.monica.data.model

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry

class LegacyProjectAccountsTest {
    private fun entry(id: Long, username: String, otp: String = "", password: String = "synthetic-$id") =
        PasswordEntry(id = id, title = "Synthetic legacy project", website = "", username = username,
            password = password, authenticatorKey = otp, passwordGroupId = "legacy-project")

    @Test fun threeLegacyAccountsKeepEveryUsernamePasswordAndOriginalId() {
        val entries = listOf(entry(1, "alice-example"), entry(2, "bob-example"), entry(3, "carol-example"))
        val restored = ProjectCredentialGroup.restoreForEditing(entries, emptyMap())
        assertEquals(entries.map { it.username }, restored.map { it.username })
        assertEquals(entries.map { it.password }, restored.flatMap { it.passwords }.map { it.value })
        assertEquals(entries.map { it.id }, restored.flatMap { it.passwords }.map { it.originalEntryId })
        assertEquals(3, restored.map { it.id }.distinct().size)
        assertEquals(restored.map { it.id }, ProjectCredentialGroup.restore(entries.reversed(), emptyMap()).map { it.id })
    }

    @Test fun multiplePasswordsOfOneLegacyAccountRemainOneGroupWithoutValueDeduplication() {
        val entries = listOf(entry(1, "alice", password = "same-secret"), entry(2, "alice", password = "same-secret"))
        val restored = ProjectCredentialGroup.restore(entries, emptyMap())
        assertEquals(1, restored.size)
        assertEquals(listOf(1L, 2L), restored.single().passwords.map { it.originalEntryId })
        assertEquals(listOf("same-secret", "same-secret"), restored.single().passwords.map { it.value })
    }

    @Test fun legacyOtpDifferencesAndExactUsernamesNeverGetMerged() {
        val entries = listOf(entry(1, "alice", ""), entry(2, "alice", "otp-A"), entry(3, "alice", "otp-B"),
            entry(4, " alice", "otp-A"), entry(5, "ALICE", "otp-A"), entry(6, "", ""))
        val restored = ProjectCredentialGroup.restoreForEditing(entries, emptyMap())
        assertEquals(entries.map { it.username to it.authenticatorKey }, restored.map { it.username to it.otp })
    }

    @Test fun absentMetadataDoesNotSilentlyJoinAnExplicitAccount() {
        val metadata = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(username = "alice"))).single().metadata
        val fields = mapOf(1L to listOf(CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = metadata.raw.toString())))
        val error = assertThrows(ProjectCredentialGroup.RestoreException::class.java) {
            ProjectCredentialGroup.restore(listOf(entry(1, "alice"), entry(2, "bob"), entry(3, "alice")), fields)
        }
        assertEquals(ProjectCredentialGroup.RestoreFailure.UNSUPPORTED_METADATA, error.reason)
    }

    @Test fun conflictingExplicitAccountStillRefusesToSave() {
        val rows = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(username = "alice",
            passwords = listOf(ProjectCredentialGroup.Password(), ProjectCredentialGroup.Password()))))
        val fields = rows.mapIndexed { i, row -> i + 1L to listOf(CustomFieldDraft(
            title = ProjectCredentialGroup.FIELD, value = row.metadata.raw.toString())) }.toMap()
        val error = assertThrows(ProjectCredentialGroup.RestoreException::class.java) {
            ProjectCredentialGroup.restoreForEditing(listOf(entry(1, "alice"), entry(2, "bob")), fields)
        }
        assertEquals(ProjectCredentialGroup.RestoreFailure.USERNAME_CONFLICT, error.reason)
    }

    @Test fun malformedMetadataIsRetainedAndNotTreatedAsLegacy() {
        val field = CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = "{broken", isProtected = true)
        val error = assertThrows(ProjectCredentialGroup.RestoreException::class.java) {
            ProjectCredentialGroup.restoreForEditing(listOf(entry(1, "alice")), mapOf(1L to listOf(field)))
        }
        assertEquals(ProjectCredentialGroup.RestoreFailure.UNSUPPORTED_METADATA, error.reason)
        assertEquals("{broken", field.value)
    }
}
