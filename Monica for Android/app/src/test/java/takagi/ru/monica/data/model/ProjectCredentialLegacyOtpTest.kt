package takagi.ru.monica.data.model

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry

class ProjectCredentialLegacyOtpTest {
    private fun fixture(otp: List<String>) = ProjectCredentialGroup.rows(listOf(
        ProjectCredentialGroup.Group(username = "synthetic-user", note = "Original note",
            passwords = otp.indices.map { ProjectCredentialGroup.Password(value = "synthetic-$it") })
    )).let { rows ->
        rows.mapIndexed { index, row -> PasswordEntry(id = index + 1L, title = "Synthetic project",
            website = "", username = row.username, password = row.password.value, authenticatorKey = otp[index]) } to
            rows.mapIndexed { index, row -> index + 1L to ProjectCredentialGroup.put(emptyList(), row.metadata) }.toMap()
    }

    @Test fun partialOldOtpSurvivesUnchangedSaveAndPasswordEditInEitherRowOrder() {
        listOf(listOf("", "JBSWY3DPEHPK3PXP"), listOf("JBSWY3DPEHPK3PXP", "")).forEach { otp ->
            val (entries, fields) = fixture(otp)
            val restored = ProjectCredentialGroup.restoreForEditing(entries.reversed(), fields).single()
            assertEquals("JBSWY3DPEHPK3PXP", restored.otp)
            assertEquals("Original note", restored.note)
            val unchanged = ProjectCredentialGroup.rows(listOf(restored))
            assertEquals(otp, unchanged.map { it.otp })
            assertEquals(entries.map { it.password }, unchanged.map { it.password.value })
            assertEquals(entries.map { it.id }, unchanged.map { it.password.originalEntryId })
            val edited = restored.copy(passwords = restored.passwords.map { it.copy(value = it.value + "-edited") })
            assertEquals(otp, ProjectCredentialGroup.rows(listOf(edited)).map { it.otp })
            assertEquals(fields.values.map { ProjectCredentialGroup.read(it)!!.passwordId },
                unchanged.map { it.metadata.passwordId })
        }
    }

    @Test fun explicitOtpChangeAppliesToWholeAccountAndReorderKeepsOriginalRows() {
        val (entries, fields) = fixture(listOf("", "JBSWY3DPEHPK3PXP"))
        val restored = ProjectCredentialGroup.restoreForEditing(entries, fields).single()
        assertEquals(listOf("JBSWY3DPEHPK3PXP", ""),
            ProjectCredentialGroup.rows(listOf(restored.copy(passwords = restored.passwords.reversed()))).map { it.otp })
        assertEquals(listOf("", ""), ProjectCredentialGroup.rows(listOf(restored.copy(otp = ""))).map { it.otp })
        assertEquals(listOf("NEW_OTP", "NEW_OTP"),
            ProjectCredentialGroup.rows(listOf(restored.copy(otp = "NEW_OTP"))).map { it.otp })
    }

    @Test fun genuineConflictsAndMissingAccountMetadataStayBlocked() {
        val (entries, fields) = fixture(listOf("OTP_A", "OTP_B"))
        assertThrows(IllegalArgumentException::class.java) { ProjectCredentialGroup.restoreForEditing(entries, fields) }
        val sparse = entries.mapIndexed { index, entry -> if (index == 0) entry.copy(authenticatorKey = "") else entry }
        assertThrows(IllegalArgumentException::class.java) { ProjectCredentialGroup.restoreForEditing(sparse, fields - 1L) }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectCredentialGroup.restoreForEditing(sparse.mapIndexed { index, row ->
                if (index == 0) row.copy(username = "another-user") else row }, fields)
        }
        // Non-editor callers retain the existing strict contract.
        assertThrows(IllegalArgumentException::class.java) { ProjectCredentialGroup.restore(sparse, fields) }
    }
}
