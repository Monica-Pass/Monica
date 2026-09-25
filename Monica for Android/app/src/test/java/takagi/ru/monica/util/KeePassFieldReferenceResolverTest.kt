package takagi.ru.monica.util

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.utils.KeePassFieldReferenceResolver as Resolver

class KeePassFieldReferenceResolverTest {
    @Test fun localPlaceholdersResolveWithoutAWholeDatabaseAndKeepExactCustomNames() {
        val entry = entry("Title" to "Mail", "UserName" to "alice", "Email" to "upper", "email" to "lower")
        assertEquals("Mail/alice/upper/lower", Resolver.resolveValue("{TITLE}/{USERNAME}/{S:Email}/{S:email}", entry))
        assertEquals("{S:missing}", Resolver.resolveValue("{S:missing}", entry))
    }

    @Test fun referencesSupportContainmentAndLocalCustomFieldRedirection() {
        val source = entry("Title" to "Example Website", "Password" to "{S:Shared}", "Shared" to "secret-value")
        val consumer = entry("Password" to "{REF:P@T:Website}")
        val context = Resolver.buildContext(listOf(source, consumer))
        assertEquals("secret-value", Resolver.getFieldValue(consumer, "Password", context))
        assertEquals("{REF:P@T:Website}", consumer.fields.getValue("Password").content)
    }

    @Test fun customSearchCodeCannotBeUsedAsTheRetrievedField() {
        val source = entry("Title" to "Source", "Notes" to "matched-note", "Custom" to "searchable-value")
        val context = Resolver.buildContext(listOf(source))
        assertEquals("matched-note", Resolver.resolveValue("{REF:N@O:searchable}", source, context))
        assertEquals("{REF:O@T:Source}", Resolver.resolveValue("{REF:O@T:Source}", source, context))
        assertEquals(source.uuid.toString().replace("-", "").uppercase(), Resolver.resolveValue("{REF:I@T:Source}", source, context))
    }

    @Test fun unresolvedAndCyclicReferencesStayVisibleWithoutChangingStoredStrings() {
        val a = entry("Title" to "A", "Password" to "{REF:P@T:B}")
        val b = entry("Title" to "B", "Password" to "{REF:P@T:A}")
        val context = Resolver.buildContext(listOf(a, b))
        assertTrue(Resolver.getFieldValue(a, "Password", context).startsWith("{REF:"))
        assertEquals("{REF:P@I:00000000000000000000000000000000}", Resolver.resolveValue(
            "{REF:P@I:00000000000000000000000000000000}", a, context))
        assertEquals("{REF:P@T:B}", a.fields.getValue("Password").content)
        val local = entry("A" to "{S:B}", "B" to "{S:A}")
        assertTrue(Resolver.resolveValue("{S:A}", local).startsWith("{S:"))
    }

    private fun entry(vararg fields: Pair<String, String>) = Entry(UUID.randomUUID(),
        fields = EntryFields.of(*fields.map { it.first to EntryValue.Plain(it.second) }.toTypedArray()))
}
