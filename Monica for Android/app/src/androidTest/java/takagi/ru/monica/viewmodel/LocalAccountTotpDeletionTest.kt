package takagi.ru.monica.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.ProjectCredentialGroup
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import java.util.UUID

/** Real local deletion transactions, with synthetic accounts and original ciphertext sentinels. */
class LocalAccountTotpDeletionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val secret = "JBSWY3DPEHPK3PXP"
    private val differentSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    private inner class Fixture {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = PasswordRepository(db.passwordEntryDao())
        val items = SecureItemRepository(db.secureItemDao())
        val model = TotpViewModel(items, passwords, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        suspend fun rows() = db.passwordEntryDao().getAllPasswordEntriesSync().sortedBy { it.id }
        suspend fun account(project: String = UUID.randomUUID().toString(), metadata: Boolean = true,
                            otp: String = secret, keepassId: Long? = null): List<Long> {
            val group = ProjectCredentialGroup.Group(username = "same-user", note = "Account note",
                passwords = listOf(ProjectCredentialGroup.Password(value = "ciphertext-one"),
                    ProjectCredentialGroup.Password(value = "ciphertext-two")))
            return ProjectCredentialGroup.rows(listOf(group)).map { row ->
                val id = passwords.insertPasswordEntry(PasswordEntry(title = "Same title", username = row.username,
                    password = row.password.value, website = "", authenticatorKey = otp,
                    replicaGroupId = project, keepassDatabaseId = keepassId))
                if (metadata) CustomFieldRepository(db.customFieldDao()).saveFieldsForEntry(id,
                    ProjectCredentialGroup.put(emptyList(), row.metadata.forProject(project)))
                id
            }
        }
        suspend fun bind(passwordId: Long, otp: String = secret): SecureItem {
            val id = items.insertItem(SecureItem(title = "Bound OTP", itemType = ItemType.TOTP,
                itemData = Json.encodeToString(TotpData(secret = otp, boundPasswordId = passwordId))))
            return requireNotNull(items.getItemById(id))
        }
        suspend fun delete(item: SecureItem) {
            val previousJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            model.deleteTotpItem(item)
            withTimeout(10000) { model.viewModelScope.coroutineContext[Job]!!.children.filterNot { it in previousJobs }.toList().joinAll() }
        }
        suspend fun close() { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); db.close() }
    }

    @Test fun deletionStaysWithinExactLocalAccountEvenWhenNamesAndOtpMatch() = runBlocking {
        val f = Fixture()
        try {
            val project = UUID.randomUUID().toString()
            val selected = f.account(project)
            f.account(project) // An independent account in the same project, same username and secret.
            f.account() // Same visible content in another local project.
            f.account(project, keepassId = 77L) // An external Room owner must never join the local account.
            val bound = f.bind(selected.first())
            val before = f.rows()
            val fields = f.db.customFieldDao().getAllFieldsSync()
            f.delete(bound)
            assertEquals(true, f.items.getItemById(bound.id)?.isDeleted)
            val after = f.rows()
            after.forEach { row ->
                val original = before.single { it.id == row.id }
                if (row.id in selected) {
                    assertEquals("", row.authenticatorKey)
                    assertEquals(original, row.copy(authenticatorKey = original.authenticatorKey, updatedAt = original.updatedAt))
                } else assertEquals(original, row)
            }
            assertEquals(fields, f.db.customFieldDao().getAllFieldsSync())
        } finally { f.close() }
    }

    @Test fun siblingBindingRetainsSharedOtpUntilLastStoredAuthenticatorIsDeleted() = runBlocking {
        val f = Fixture()
        try {
            val ids = f.account()
            val first = f.bind(ids[0])
            val last = f.bind(ids[1])
            val before = f.rows()
            f.delete(first)
            assertEquals("Another live binding still owns this account OTP", before, f.rows())
            f.delete(last)
            assertTrue(f.rows().all { it.authenticatorKey.isEmpty() })
            assertEquals(before.map { it.password }, f.rows().map { it.password })
        } finally { f.close() }
    }

    @Test fun batchDeleteClearsAllPasswordsAfterExcludingEverySelectedBinding() = runBlocking {
        val f = Fixture()
        try {
            val ids = f.account()
            val bindings = ids.map { f.bind(it) }
            val previousJobs = f.model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            f.model.deleteTotpItems(bindings)
            withTimeout(10000) { f.model.viewModelScope.coroutineContext[Job]!!.children.filterNot { it in previousJobs }.toList().joinAll() }
            assertTrue(f.rows().all { it.authenticatorKey.isEmpty() })
            assertTrue(bindings.all { f.items.getItemById(it.id)?.isDeleted == true })
        } finally { f.close() }
    }

    @Test fun legacyExplicitGroupWithoutCredentialMetadataClearsTogether() = runBlocking {
        val f = Fixture()
        try {
            val ids = f.account(metadata = false)
            f.delete(f.bind(ids.first()))
            assertTrue(f.rows().all { it.authenticatorKey.isEmpty() })
        } finally { f.close() }
    }

    @Test fun deletingResidualOtpAllowsAnAlreadyPartlyClearedAccountToRestore() = runBlocking {
        val f = Fixture()
        try {
            val ids = f.account()
            // Prior-version partial deletion: explicit repair deletes the remaining OTP.
            f.passwords.updateAuthenticatorKey(ids.first(), "")
            val before = f.rows()
            f.delete(f.bind(ids.last()))
            val after = f.rows()
            assertTrue(after.all { it.authenticatorKey.isEmpty() })
            assertEquals(before.map { it.password }, after.map { it.password })
            val fields = f.db.customFieldDao().getFieldsByEntryIds(ids).groupBy { it.entryId }
                .mapValues { (_, values) -> values.map(CustomFieldDraft::fromCustomField) }
            assertEquals(2, ProjectCredentialGroup.restore(after, fields).single().passwords.size)
        } finally { f.close() }
    }

    @Test fun aDifferentOtpOnASiblingIsNeverDeleted() = runBlocking {
        val f = Fixture()
        try {
            val ids = f.account()
            f.passwords.updateAuthenticatorKey(ids.last(), differentSecret)
            val protected = requireNotNull(f.passwords.getPasswordEntryById(ids.last()))
            f.delete(f.bind(ids.first()))
            assertEquals("", f.passwords.getPasswordEntryById(ids.first())?.authenticatorKey)
            assertEquals(protected, f.passwords.getPasswordEntryById(ids.last()))
        } finally { f.close() }
    }

    @Test fun unsupportedAccountMetadataPreservesBothPasswordAndAuthenticatorData() = runBlocking {
        val f = Fixture()
        try {
            val ids = f.account()
            CustomFieldRepository(f.db.customFieldDao()).saveFieldsForEntry(ids.last(),
                listOf(CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = "{}")))
            val bound = f.bind(ids.first())
            val before = f.rows()
            f.delete(bound)
            assertEquals(before, f.rows())
            assertEquals(bound, f.items.getItemById(bound.id))
        } finally { f.close() }
    }

    @Test fun deletingAStaleBindingDoesNotClearAReplacementOrItsSiblings() = runBlocking {
        val f = Fixture()
        try {
            val ids = f.account()
            val stale = f.bind(ids.first())
            f.passwords.updateAuthenticatorKey(ids.first(), differentSecret)
            val before = f.rows()
            f.delete(stale)
            assertEquals(before, f.rows())
            assertEquals(true, f.items.getItemById(stale.id)?.isDeleted)
        } finally { f.close() }
    }
}
