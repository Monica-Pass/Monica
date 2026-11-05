package takagi.ru.monica.data.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.CustomFieldDraft

class ProjectCredentialGroupTest {
    @Test fun identicalMetadataCopiesRestoreAndSaveWithoutLosingUnknownFieldsOrProtection() {
        val rows = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(username = "synthetic",
            passwords = listOf(ProjectCredentialGroup.Password(value = "one"), ProjectCredentialGroup.Password(value = "two")))))
        val entries = rows.mapIndexed { i, row -> PasswordEntry(id = i + 1L, title = "Fixture", website = "",
            username = row.username, password = row.password.value) }
        val fields = rows.mapIndexed { i, row ->
            val raw = JsonObject(row.metadata.raw + ("extension" to buildJsonObject { put("large", 9007199254740993L) }))
            i + 1L to listOf(CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = raw.toString()),
                CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = JsonObject(raw.entries.reversed().associate { it.toPair() }).toString(), isProtected = true),
                CustomFieldDraft(title = "User field", value = "first"), CustomFieldDraft(title = "User field", value = "second"))
        }.toMap()
        val restored = ProjectCredentialGroup.restoreForEditing(entries, fields)
        assertEquals(listOf("one", "two"), restored.single().passwords.map { it.value })
        ProjectCredentialGroup.rows(restored).forEachIndexed { i, row ->
            val saved = ProjectCredentialGroup.put(fields.getValue(i + 1L), row.metadata)
            assertEquals(1, saved.count { it.title == ProjectCredentialGroup.FIELD })
            assertTrue(saved.single { it.title == ProjectCredentialGroup.FIELD }.isProtected)
            assertEquals(listOf("first", "second"), saved.filter { it.title == "User field" }.map { it.value })
            assertEquals(ProjectCredentialGroup.read(fields.getValue(i + 1L))?.raw, ProjectCredentialGroup.read(saved)?.raw)
            assertEquals(rows[i].metadata.passwordId, ProjectCredentialGroup.read(saved)?.passwordId)
        }
    }

    @Test fun conflictingDamagedAndUnsupportedCopiesRemainUnreadableWithoutMutation() {
        val metadata = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group())).single().metadata
        val field = CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = metadata.raw.toString())
        val variants = listOf("{broken", JsonObject(metadata.raw + ("version" to JsonPrimitive(2))).toString(),
            JsonObject(metadata.raw + ("extension" to JsonPrimitive("different"))).toString(),
            JsonObject(metadata.raw + ("label" to JsonPrimitive("different"))).toString())
        for (value in variants) for (reverse in listOf(false, true)) {
            val fields = listOf(field, field.copy(value = value)).let { if (reverse) it.reversed() else it }
            val before = fields.toList()
            assertNull(ProjectCredentialGroup.read(fields))
            assertThrows(IllegalArgumentException::class.java) { ProjectCredentialGroup.put(fields, metadata) }
            assertEquals(before, fields)
        }
    }

    @Test fun repeatedOrderKeepsAdditionalAccountTokensAndStrongestProtection() {
        val token = ProjectCredentialGroup.token(java.util.UUID.randomUUID().toString())
        val fields = listOf(CustomFieldDraft(title = EntryContentFields.ORDER, value = token),
            CustomFieldDraft(title = EntryContentFields.ORDER, value = token, isProtected = true),
            CustomFieldDraft(title = "ordinary", value = "same"), CustomFieldDraft(title = "ordinary", value = "same"))
        val saved = EntryContentFields.withOrder(fields, emptyList())
        assertEquals(listOf(token), EntryContentFields.order(saved))
        assertEquals(1, saved.count { it.title == EntryContentFields.ORDER })
        assertTrue(saved.single { it.title == EntryContentFields.ORDER }.isProtected)
        assertEquals(2, saved.count { it.title == "ordinary" })
    }
    @Test fun notesBelongToTheAccountAndSurvivePasswordRemovalAndLegacyMetadata() {
        val groups = listOf(ProjectCredentialGroup.Group(username = "personal", note = "Personal note\nSecond line"),
            ProjectCredentialGroup.Group(username = "work", note = "Work note",
                passwords = listOf(ProjectCredentialGroup.Password(value = "one"), ProjectCredentialGroup.Password(value = "two"))))
        val rows = ProjectCredentialGroup.rows(groups)
        val entries = rows.mapIndexed { index, row -> PasswordEntry(id = index + 1L, title = "Notes", website = "",
            username = row.username, password = row.password.value) }
        val fields = rows.mapIndexed { index, row -> index + 1L to ProjectCredentialGroup.put(emptyList(), row.metadata) }.toMap()
        val restored = ProjectCredentialGroup.restore(entries.reversed(), fields)
        assertEquals(groups.map { it.note }, restored.map { it.note })
        val removed = ProjectCredentialGroup.restore(entries.filterNot { it.id == 2L }, fields)
        assertEquals("Work note", removed.last().note)
        val cleared = ProjectCredentialGroup.rows(restored.map { it.copy(note = "") })
        assertTrue(cleared.all { it.metadata.note.isEmpty() })
        assertTrue(cleared.all { "note" !in it.metadata.raw })
        val spaces = ProjectCredentialGroup.rows(restored.map { it.copy(note = "  ") })
        assertTrue(spaces.all { it.metadata.note == "  " })
        val old = JsonObject(rows.first().metadata.raw - "note")
        assertEquals("", ProjectCredentialGroup.parse(old.toString())?.note)
        assertNull(ProjectCredentialGroup.parse(JsonObject(old + ("note" to JsonPrimitive(42))).toString()))
        val conflict = fields + (3L to ProjectCredentialGroup.put(emptyList(), rows.last().metadata.copy(
            raw = JsonObject(rows.last().metadata.raw + ("note" to JsonPrimitive("External edit"))))))
        assertThrows(IllegalArgumentException::class.java) { ProjectCredentialGroup.restore(entries, conflict) }
    }
    @Test fun metadataContainsNoSecretsAndRestoresIndependentAccounts() {
        val groups = listOf(ProjectCredentialGroup.Group(username = "alpha", passwords = listOf(ProjectCredentialGroup.Password(value = "alphaSecret")), otp = "ALPHA_OTP"),
            ProjectCredentialGroup.Group(label = "Work", username = "beta", passwords = listOf(ProjectCredentialGroup.Password(value = "betaSecret"), ProjectCredentialGroup.Password(value = "otherSecret"))))
        val rows = ProjectCredentialGroup.rows(groups)
        assertTrue(rows.all { !it.metadata.raw.toString().contains("Secret") && !it.metadata.raw.toString().contains("OTP") })
        val entries = rows.mapIndexed { index, row -> PasswordEntry(id = index + 1L, title = "Shared", website = "", username = row.username, password = row.password.value, authenticatorKey = row.otp) }
        val fields = rows.mapIndexed { index, row -> index + 1L to ProjectCredentialGroup.put(emptyList(), row.metadata) }.toMap()
        val restored = ProjectCredentialGroup.restore(entries.reversed(), fields)
        assertEquals(listOf("alpha", "beta"), restored.map { it.username })
        assertEquals(listOf("betaSecret", "otherSecret"), restored[1].passwords.map { it.value })
        assertEquals("", restored[1].otp)
        assertEquals(groups.map { it.id }, restored.map { it.id })
    }
    @Test fun reorderAndEditRetainPasswordIdentitiesAndUnknownMetadata() {
        val original = ProjectCredentialGroup.Group(passwords = listOf(ProjectCredentialGroup.Password(value = "a"), ProjectCredentialGroup.Password(value = "b")))
        val rows = ProjectCredentialGroup.rows(listOf(original))
        val future = JsonPrimitive(9007199254740993L)
        val changed = original.copy(passwords = rows.map { it.password.copy(metadata = it.metadata.copy(raw = JsonObject(it.metadata.raw + ("future" to future)))) }.reversed())
        val updated = ProjectCredentialGroup.rows(listOf(changed))
        assertEquals(rows.map { it.metadata.passwordId }.reversed(), updated.map { it.metadata.passwordId })
        assertTrue(updated.all { it.metadata.raw["future"] == future })
    }
    @Test fun legacyProjectRemainsOneAccountAndUnsupportedMetadataIsRejected() {
        val entries = listOf(PasswordEntry(id = 1, title = "Old", website = "", username = "a", password = "one"), PasswordEntry(id = 2, title = "Old", website = "", username = "a", password = "two"))
        assertEquals(2, ProjectCredentialGroup.restore(entries, emptyMap()).single().passwords.size)
        val bad = listOf(takagi.ru.monica.data.CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = "{}"))
        assertThrows(IllegalArgumentException::class.java) { ProjectCredentialGroup.restore(entries, mapOf(1L to bad)) }
    }
    @Test fun duplicateIdsAndConflictingAccountFieldsAreRejected() {
        val password = ProjectCredentialGroup.Password(value = "secret")
        assertThrows(IllegalArgumentException::class.java) {
            ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(passwords = listOf(password)),
                ProjectCredentialGroup.Group(passwords = listOf(password))))
        }
        val rows = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(username = "original",
            passwords = listOf(password, ProjectCredentialGroup.Password(value = "other")))))
        val fields = rows.mapIndexed { i, row -> i + 1L to ProjectCredentialGroup.put(emptyList(), row.metadata) }.toMap()
        val entries = rows.mapIndexed { i, row -> PasswordEntry(id = i + 1L, title = "Project", website = "",
            username = row.username, password = row.password.value) }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectCredentialGroup.restore(listOf(entries[0], entries[1].copy(username = "externally changed")), fields)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectCredentialGroup.restore(listOf(entries[0], entries[1].copy(authenticatorKey = "different OTP")), fields)
        }
        val projectId = java.util.UUID.randomUUID().toString()
        assertEquals(projectId, ProjectCredentialGroup.parse(rows[0].metadata.forProject(projectId).raw.toString())?.projectId)
    }

}
