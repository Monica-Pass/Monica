package takagi.ru.monica.bitwarden

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.ui.rememberAttachmentBitwardenContext
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenVaultDao
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.PasswordDetailScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel

/** Synthetic TLS account and real Room; no allowMainThreadQueries or user data. */
class BitwardenAttachmentContextUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BitwardenAuthenticatedRepositoryInstrumentedTest()

    @Test fun realPasswordDetailCanOpenReturnAndReopenWithoutDownloadingAttachments(): Unit = runBlocking {
        fixture.withFixture { f ->
            val vault = f.loginSuccessfully()
            val db = PasswordDatabase.getDatabase(context)
            val security = SecurityManager(context)
            val entry = PasswordEntry(title = "Synthetic detail", username = "detail@example.invalid",
                password = security.encryptData("synthetic-detail-password"), website = "",
                bitwardenVaultId = vault.id, bitwardenCipherId = "synthetic-detail-cipher")
            val id = db.passwordEntryDao().insertPasswordEntry(entry)
            val attachmentId = db.attachmentDao().insert(Attachment(parentPasswordId = id,
                source = "BITWARDEN", fileName = "synthetic-detail.pdf", mimeType = "application/pdf",
                sizeBytes = 128, downloadState = "PENDING", createdAt = 1, updatedAt = 1))
            val model = PasswordViewModel(PasswordRepository(db.passwordEntryDao()), security,
                customFieldRepository = CustomFieldRepository(db.customFieldDao()), context = context,
                strings = AppLocaleStringResolver(context))
            val open = mutableStateOf(false)
            try {
                compose.setContent { MaterialTheme {
                    if (open.value) PasswordDetailScreen(model, passwordId = id, biometricEnabled = false,
                        onNavigateBack = { open.value = false }, onEditPassword = {})
                    else Button(onClick = { open.value = true }) { Text("Open synthetic result") }
                } }
                repeat(3) {
                    compose.onNodeWithText("Open synthetic result").performClick()
                    compose.waitUntil(20_000) {
                        compose.onAllNodesWithText("detail@example.invalid").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("synthetic-detail.pdf"))
                    compose.onAllNodesWithText("synthetic-detail.pdf").onFirst().assertIsDisplayed()
                    compose.onNodeWithText("synthetic-detail-password").assertDoesNotExist()
                    if (it == 0) {
                        val file = java.io.File(context.getExternalFilesDir(null), "bitwarden-detail-crash.png")
                        file.outputStream().use { output -> compose.onRoot().captureToImage().asAndroidBitmap()
                            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                    }
                    takagi.ru.monica.ui.pressFocusedBack()
                    compose.onNodeWithText("Open synthetic result").assertIsDisplayed()
                }
                assertEquals(0, f.oldCipherRequests.get())
                assertEquals(0, f.refreshRequests.get())
                assertEquals(entry.copy(id = id), db.passwordEntryDao().getPasswordEntryById(id))
                assertEquals("PENDING", db.attachmentDao().getById(attachmentId)?.downloadState)
            } finally {
                compose.runOnIdle { open.value = false }
                compose.waitForIdle()
                model.viewModelScope.cancel()
                db.attachmentDao().deleteById(attachmentId)
                db.passwordEntryDao().deletePasswordEntry(entry.copy(id = id))
            }
        }
    }

    @Test fun switchingCipherStartsEmptyAndDisposalClearsTheOwnedKey(): Unit = runBlocking {
        fixture.withFixture { f ->
            val vault = f.loginSuccessfully()
            val cipher = mutableStateOf("first-cipher")
            val visible = mutableStateOf(true)
            val observed = AtomicReference<AttachmentFacade.BitwardenContext?>()
            compose.setContent { if (visible.value) {
                val value = rememberAttachmentBitwardenContext(vault, cipher.value, f.repository)
                SideEffect { observed.set(value) }
                Text(value?.cipherId ?: "Preparing")
            } }
            compose.waitUntil(15_000) { observed.get()?.cipherId == "first-cipher" }
            val old = requireNotNull(observed.get())
            TransportReadGate(f.repository).use { gate ->
                compose.runOnIdle { cipher.value = "second-cipher" }
                compose.waitUntil(15_000) { gate.entered.count == 0L }
                compose.onNodeWithText("Preparing").assertExists()
                assertNull(observed.get())
                assertTrue(old.wrappingKey.encKey.all { it == 0.toByte() })
                gate.release.countDown()
                compose.waitUntil(15_000) { observed.get()?.cipherId == "second-cipher" }
            }
            val current = requireNotNull(observed.get())
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            assertTrue(current.wrappingKey.encKey.all { it == 0.toByte() })
            assertEquals(vault, f.row())
            f.assertUserKey(vault.id)
        }
    }

    @Test fun accountLockRemovesReadyContext(): Unit = runBlocking {
        fixture.withFixture { f ->
            val vault = f.loginSuccessfully()
            val observed = AtomicReference<AttachmentFacade.BitwardenContext?>()
            compose.setContent {
                val value = rememberAttachmentBitwardenContext(vault, "cipher", f.repository)
                SideEffect { observed.set(value) }
            }
            compose.waitUntil(15_000) { observed.get() != null }
            val old = requireNotNull(observed.get())
            f.repository.lock(vault.id)
            compose.waitUntil(15_000) { observed.get() == null }
            assertTrue(old.wrappingKey.encKey.all { it == 0.toByte() })
            assertTrue(f.row().isLocked)
            assertEquals(vault.encryptedEncKey, f.row().encryptedEncKey)
        }
    }

    @Test fun globalLockAndUnlockWhileRoomReadIsPendingRejectsLateContext(): Unit = runBlocking {
        fixture.withFixture { f ->
            val vault = f.loginSuccessfully()
            TransportReadGate(f.repository).use { gate ->
                val pending = async(Dispatchers.Main) {
                    runCatching { f.repository.getAttachmentBitwardenContext(vault, "cipher") }
                }
                gate.awaitEntered()
                compose.runOnIdle { SessionManager.markLocked(); SessionManager.markUnlocked() }
                gate.release.countDown()
                // Await the actual factory completion, not merely Compose becoming idle.
                val failure = pending.await().exceptionOrNull()
                assertTrue("Late context must fail after a global lock cycle", failure is IllegalStateException)
                assertFalse(failure?.message.orEmpty().contains("main thread"))
            }
            assertEquals(vault, f.row())
            assertEquals(0, f.oldCipherRequests.get())
        }
    }

    @Test fun cancelledPreparationPreservesAccountAndCanBeRetried(): Unit = runBlocking {
        fixture.withFixture { f ->
            val vault = f.loginSuccessfully()
            TransportReadGate(f.repository).use { gate ->
                val pending = async(Dispatchers.Main) { f.repository.getAttachmentBitwardenContext(vault, "cipher") }
                gate.awaitEntered()
                pending.cancel()
                gate.release.countDown()
                pending.join()
                assertTrue(pending.isCancelled)
            }
            val value = withContext(Dispatchers.Main) {
                requireNotNull(f.repository.getAttachmentBitwardenContext(vault, "cipher"))
            }
            try { value.requireCurrent(); assertEquals(vault, f.row()); f.assertUserKey(vault.id) }
            finally { value.wrappingKey.clear() }
        }
    }

    private class TransportReadGate(repository: BitwardenRepository) : AutoCloseable {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val first = AtomicBoolean(true)
        private val field = BitwardenRepository::class.java.getDeclaredField("vaultDao").apply { isAccessible = true }
        private val original = field.get(repository)
        private val owner = repository
        init {
            field.set(repository, Proxy.newProxyInstance(BitwardenVaultDao::class.java.classLoader,
                arrayOf(BitwardenVaultDao::class.java)) { _, method, args ->
                if (method.name == "getTransportState" && first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "Synthetic Room gate was not released" }
                }
                try { method.invoke(original, *(args ?: emptyArray())) }
                catch (error: InvocationTargetException) { throw error.targetException }
            })
        }
        fun awaitEntered() = assertTrue("Preparation did not reach Room", entered.await(15, TimeUnit.SECONDS))
        override fun close() { release.countDown(); field.set(owner, original) }
    }
}
