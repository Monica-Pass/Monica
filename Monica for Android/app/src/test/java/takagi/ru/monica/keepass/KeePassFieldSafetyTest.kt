package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class KeePassFieldSafetyTest {
    private val original = Entry(UUID.randomUUID(), fields = EntryFields.of(
        "Title" to EntryValue.Plain("Login"),
        "Code" to EntryValue.Plain("uppercase"),
        "code" to EntryValue.Plain("lowercase"),
        " Code " to EntryValue.Plain("surrounded"),
    ))

    @Test fun partialEditsAndExplicitRemovalRespectExactNames() {
        val patch = KeePassEntryFieldPatch.fromEntryFields(
            EntryFields.of("Code" to EntryValue.Plain("edited")), { false }, listOf("Code", " Code "),
        )
        val updated = patch.applyTo(original)
        assertEquals("edited", updated.fields.getValue("Code").content)
        assertEquals("lowercase", updated.fields.getValue("code").content)
        assertNull(updated.fields[" Code "])
        assertEquals(setOf("Code", " Code "), patch.toChangePatch(KeePassManagedFieldScope.EXPLICIT_ONLY, original)
            .baseFields.map { it.name }.toSet())
    }

    @Test fun fullReplacementNeverSilentlyErasesARemotelyAddedField() {
        val remote = original.copy(fields = EntryFields.of(*(original.fields.toList() +
            ("Remote recovery" to EntryValue.Plain("keep me"))).toTypedArray()))
        val patch = fullPatch()
        val result = runCatching { KeePassChangeSetApplier().apply(database(remote), patch) }
        assertTrue(result.exceptionOrNull() is KeePassChangeConflictException)
        assertEquals("keep me", remote.fields.getValue("Remote recovery").content)
    }

    @Test fun fullReplacementCanEditOneOfSeveralCaseDistinctKeys() {
        val updated = KeePassChangeSetApplier().apply(database(original), fullPatch()).updatedDatabase.content.group.entries.single()
        assertEquals("edited", updated.fields.getValue("Code").content)
        assertEquals("lowercase", updated.fields.getValue("code").content)
        assertEquals("surrounded", updated.fields.getValue(" Code ").content)
    }

    private fun fullPatch() = KeePassChangeSet(
        databaseId = 42, target = KeePassChangeTarget.UNKNOWN_ENTRY, operation = KeePassChangeOperation.FIELD_PATCH,
        entryUuid = original.uuid.toString(), baseFingerprint = KeePassEntryFingerprint.build(original),
        fieldPatch = KeePassFieldChangePatch(
            managedScope = KeePassManagedFieldScope.EXPLICIT_ONLY,
            replacementFields = original.fields.map { (key, value) ->
                KeePassFieldChange(key, if (key == "Code") "edited" else value.content)
            },
            baseFields = original.fields.map { (key, value) -> KeePassFieldBaseValue(key, value.content) },
            replaceAllFields = true,
        ),
    )

    private fun database(entry: Entry) = KeePassDatabase.Ver4x.create("Root", Meta(),
        Credentials.from(EncryptedValue.fromString("synthetic"))).modifyParentGroup { copy(entries = listOf(entry)) }
}
