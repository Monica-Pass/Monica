package takagi.ru.monica.bitwarden

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.SendApiResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.mapper.BitwardenSendMapper
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.repository.BitwardenRepository.SendMutationResult
import takagi.ru.monica.bitwarden.service.BitwardenSendDraft
import takagi.ru.monica.bitwarden.service.SendPasswordAction
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.screens.AddEditSendScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper

/**
 * Real production Repository/Room and AddEditSendScreen, backed by synthetic local HTTP.
 * No real accounts, global settings, migrations, or other vault rows are changed.
 * Optional: -e sendManagementScreenshots true. Images remain in this UUID's external-files folder.
 * Pending review/compilation/device execution; source alone does not establish a passing result.
 */
@RunWith(AndroidJUnit4::class)
class BitwardenSendManagementInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val localized get() = android.view.ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
        applyOverrideConfiguration(Configuration(compose.activity.resources.configuration).apply {
            setLocale(java.util.Locale.ENGLISH)
        })
    }
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun textEditRoundTripsExactContentPasswordThreeStatesAndDatesThroughHttpAndRoom() = runBlocking {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try {
                fixture.initialize()
                val original = fixture.seed(BitwardenSend.TYPE_TEXT)
                val originalDocument = fixture.document(original)
                val originalPassword = originalDocument["password"]
                val keep = fixture.draft(original).copy(
                    title = "  Edited synthetic text  ", text = "  text\n\t ", notes = "  notes\n ",
                    maxAccessCount = 9, hideEmail = true, hiddenText = true, disabled = true,
                    expirationMillis = null,
                )
                var saved = success(fixture.repository.updateSend(original, keep))
                fixture.assertStored(saved, keep, original)
                assertEquals(originalPassword, fixture.document(saved)["password"])
                assertEquals(JsonNull, fixture.putBodies.last()["password"])
                assertEquals(0, fixture.passwordRemovals.get())
                assertOpaqueIdentity(originalDocument, fixture.document(saved))

                val replace = keep.copy(
                    passwordAction = SendPasswordAction.REPLACE, password = "  new synthetic password  ",
                    disabled = false, expirationMillis = keep.deletionMillis - 86_400_000L,
                )
                saved = success(fixture.repository.updateSend(saved, replace))
                fixture.assertStored(saved, replace, original)
                assertTrue(saved.hasPassword)
                val passwordHash = fixture.document(saved)["password"]!!.jsonPrimitive.content
                assertEquals(BitwardenCrypto.hashSendPassword(replace.password!!, fixture.material), passwordHash)
                assertNotEquals(BitwardenCrypto.hashSendPassword(replace.password!!.trim(), fixture.material), passwordHash)
                assertFalse(fixture.putBodies.last().toString().contains(replace.password!!))
                assertEquals(JsonPrimitive(1), fixture.document(saved)["authType"])

                val remove = replace.copy(passwordAction = SendPasswordAction.REMOVE, password = null,
                    notes = " ", expirationMillis = null)
                saved = success(fixture.repository.updateSend(saved, remove))
                fixture.assertStored(saved, remove, original)
                assertFalse(saved.hasPassword)
                assertEquals(JsonNull, fixture.document(saved)["password"])
                assertEquals(1, fixture.passwordRemovals.get())
                assertEquals(3, fixture.putBodies.size)
                assertOpaqueIdentity(originalDocument, fixture.document(saved))
                fixture.assertNoUnexpectedRequests()
            } finally { fixture.close() }
        }
    }

    @Test fun fileEditPreservesIdKeyAndCiphertextRejectsRemoteRevisionsAndReadsBackLostResponse() = runBlocking {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try {
                fixture.initialize()
                val original = fixture.seed(BitwardenSend.TYPE_FILE)
                val originalDocument = fixture.document(original)
                val bytesBefore = fixture.fetchStoredFileBytes()
                assertArrayEquals(fixture.fileCiphertext, bytesBefore)
                assertArrayEquals(fixture.filePlaintext, fixture.decryptFile(bytesBefore))
                val edit = fixture.draft(original).copy(title = "Edited synthetic file", notes = " file note ", disabled = true)
                var saved = success(fixture.repository.updateSend(original, edit))
                fixture.assertStored(saved, edit, original)
                assertEquals(originalDocument["file"], fixture.document(saved)["file"])
                assertOpaqueIdentity(originalDocument, fixture.document(saved))
                assertArrayEquals(bytesBefore, fixture.fetchStoredFileBytes())

                // Existing editor baseline is stale before the first server read: no replacement PUT.
                fixture.changeRevision(saved)
                val putCount = fixture.putBodies.size
                val localBefore = fixture.row(saved)
                assertTrue(fixture.repository.updateSend(saved, edit) is SendMutationResult.Error)
                assertEquals(putCount, fixture.putBodies.size)
                assertEquals(localBefore, fixture.row(saved))
                saved = fixture.reloadLocal(saved)

                // A second reader/writer changes the remote revision between the two GETs.
                fixture.changeOnRead.set(fixture.reads.get() + 2)
                val secondBaseline = fixture.row(saved)
                assertTrue(fixture.repository.updateSend(saved, edit) is SendMutationResult.Error)
                assertEquals(putCount, fixture.putBodies.size)
                assertEquals(secondBaseline, fixture.row(saved))
                saved = fixture.reloadLocal(saved)

                // Commit before dropping the response. Assert the actual network PUT count, including
                // any automatic HTTP-client retries; never weaken this to count only Kotlin calls.
                fixture.loseNextPutResponse.set(true)
                val recoveredDraft = edit.copy(title = "Recovered after lost response", disabled = false,
                    passwordAction = SendPasswordAction.REPLACE, password = " file password ")
                val recovered = success(fixture.repository.updateSend(saved, recoveredDraft))
                fixture.assertStored(recovered, recoveredDraft, original)
                assertEquals(putCount + 1, fixture.putBodies.size)
                assertFalse(fixture.loseNextPutResponse.get())
                assertTrue(fixture.paths.any { it == "GET /api/sends/${saved.bitwardenSendId}" })
                assertEquals(originalDocument["file"], fixture.document(recovered)["file"])
                assertArrayEquals(bytesBefore, fixture.fetchStoredFileBytes())
                assertArrayEquals(fixture.filePlaintext, fixture.decryptFile(fixture.fetchStoredFileBytes()))
                fixture.assertNoUnexpectedRequests()
            } finally { fixture.close() }
        }
    }

    @Test fun nativeTextAndFileEditorsSaveRealRowsWithPasswordRemovalDatePickerAndEnableSwitch() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try {
                val seeded = runBlocking {
                    fixture.initialize()
                    fixture.seed(BitwardenSend.TYPE_TEXT) to fixture.seed(BitwardenSend.TYPE_FILE)
                }
                val current = mutableStateOf<BitwardenSend?>(seeded.first)
                val state = mutableStateOf<BitwardenViewModel.SendState>(BitwardenViewModel.SendState.Idle)
                val successVersion = mutableStateOf(0)
                val outcome = AtomicReference<SendMutationResult?>()
                val capturedDraft = AtomicReference<BitwardenSendDraft?>()
                val english = localized
                val configuration = Configuration(english.resources.configuration)
                compose.setContent {
                    val scope = rememberCoroutineScope()
                    CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides configuration,
                        LocalActivityResultRegistryOwner provides compose.activity) {
                        MonicaTheme {
                            Surface(Modifier.fillMaxSize()) {
                                val editing = current.value
                                if (editing == null) Text("Synthetic Send edit complete")
                                else key(editing.id) {
                                    AddEditSendScreen(
                                        sendState = state.value, sendCreateSuccessVersion = successVersion.value,
                                        vaults = listOf(fixture.vault), activeVault = fixture.vault,
                                        unlockStateByVault = mapOf(fixture.vaultId to BitwardenViewModel.UnlockState.Unlocked),
                                        existingSend = editing, onNavigateBack = { current.value = null },
                                        onCreate = { _, _, _, _, _, _, _, _, _ -> error("Unexpected create-text fallback") },
                                        onCreateFile = { _, _, _, _, _, _, _, _, _ -> error("Unexpected create-file fallback") },
                                        onSaveDraft = { vaultId, draft, uri, fileName ->
                                            check(vaultId == fixture.vaultId && uri == null && fileName == null)
                                            capturedDraft.set(draft)
                                            state.value = BitwardenViewModel.SendState.Creating
                                            scope.launch {
                                                val result = fixture.repository.updateSend(editing, draft)
                                                outcome.set(result)
                                                if (result is SendMutationResult.Success) {
                                                    successVersion.value++
                                                    state.value = BitwardenViewModel.SendState.Idle
                                                } else state.value = BitwardenViewModel.SendState.Error("Synthetic Save failed")
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                val originalDeletion = Instant.parse(seeded.first.deletionDate!!).toEpochMilli()
                field(R.string.title).performScrollTo().performTextReplacement("  Native edited text  ")
                field(R.string.send_content_label).performScrollTo().performTextReplacement("  native text\n ")
                field(R.string.notes_optional).performScrollTo().performTextReplacement(" native notes ")
                toggle(R.string.send_remove_password).performScrollTo().performClick().assertIsOn()
                compose.onNodeWithText(localized.getString(R.string.send_new_password)).assertIsNotEnabled()
                toggle(R.string.enabled).performScrollTo().performClick().assertIsOff()
                toggle(R.string.send_no_expiration).performScrollTo().performClick().assertIsOn()
                compose.onNode(hasText("${english.getString(R.string.send_expiration_date)}:", substring = true)).assertDoesNotExist()
                val deletionLabel = dateLabel(R.string.send_deletion_date, originalDeletion)
                compose.onNodeWithText(deletionLabel).performScrollTo().assertIsDisplayed()
                screenshotIfRequested(fixture, "text-options")
                compose.onNodeWithContentDescription(english.getString(R.string.save)).performClick()
                awaitSaved(outcome)
                val textDraft = requireNotNull(capturedDraft.get())
                assertEquals(originalDeletion, textDraft.deletionMillis)
                assertNull(textDraft.expirationMillis)
                assertEquals(SendPasswordAction.REMOVE, textDraft.passwordAction)
                val textSaved = success(requireNotNull(outcome.get()))
                runBlocking { fixture.assertStored(textSaved, textDraft, seeded.first) }
                assertFalse(textSaved.hasPassword)

                compose.runOnIdle {
                    outcome.set(null)
                    capturedDraft.set(null)
                    state.value = BitwardenViewModel.SendState.Idle
                    current.value = seeded.second
                }
                compose.waitForIdle()
                field(R.string.title).performScrollTo().performTextReplacement("Native edited file")
                compose.onNode(hasSetTextAction() and hasText(english.getString(R.string.send_content_label))).assertDoesNotExist()
                field(R.string.notes_optional).performScrollTo().performTextReplacement(" file UI note ")
                field(R.string.send_new_password).performScrollTo().performTextInput(" native file password ")
                toggle(R.string.enabled).performScrollTo().performClick().assertIsOff()
                // Exercise the production native DatePicker -> TimePicker, confirming an exact minute.
                val newDeletion = ((System.currentTimeMillis() + 6 * 86_400_000L) / 60_000L) * 60_000L
                selectNativeDateTime(R.string.send_deletion_date, newDeletion)
                compose.onNodeWithText(dateLabel(R.string.send_deletion_date, newDeletion)).performScrollTo().assertIsDisplayed()
                screenshotIfRequested(fixture, "file-options")
                compose.onNodeWithContentDescription(english.getString(R.string.save)).performClick()
                awaitSaved(outcome)
                val fileDraft = requireNotNull(capturedDraft.get())
                assertEquals(newDeletion, fileDraft.deletionMillis)
                assertEquals(SendPasswordAction.REPLACE, fileDraft.passwordAction)
                assertEquals(" native file password ", fileDraft.password)
                val fileSaved = success(requireNotNull(outcome.get()))
                runBlocking { fixture.assertStored(fileSaved, fileDraft, seeded.second) }
                assertTrue(fileSaved.hasPassword)
                assertArrayEquals(fixture.fileCiphertext, fixture.fetchStoredFileBytes())
                fixture.assertNoUnexpectedRequests()
                screenshotIfRequested(fixture, "completed")
            } finally {
                compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
                runBlocking { fixture.close() }
            }
        }
    }

    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(localized.getString(label)))
    private fun toggle(label: Int) = compose.onNode(
        isToggleable() and hasContentDescription(localized.getString(label)), useUnmergedTree = true)

    private fun dateLabel(label: Int, millis: Long) = "${localized.getString(label)}: " +
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(millis))

    private fun selectNativeDateTime(label: Int, millis: Long) {
        val selected = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        compose.onNode(hasText("${localized.getString(label)}:", substring = true)).performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
            override fun getDescription() = "Select the synthetic Send deletion date in the native DatePicker"
            override fun perform(controller: UiController, view: View) {
                (view as DatePicker).updateDate(selected.year, selected.monthValue - 1, selected.dayOfMonth)
                controller.loopMainThreadUntilIdle()
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(TimePicker::class.java)
            override fun getDescription() = "Select the synthetic Send deletion time in the native TimePicker"
            override fun perform(controller: UiController, view: View) {
                (view as TimePicker).apply { hour = selected.hour; minute = selected.minute }
                controller.loopMainThreadUntilIdle()
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
        compose.waitForIdle()
    }

    private fun awaitSaved(outcome: AtomicReference<SendMutationResult?>) {
        compose.waitUntil(30_000) { outcome.get() != null }
        success(requireNotNull(outcome.get()))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Synthetic Send edit complete").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun screenshotIfRequested(fixture: Fixture, name: String) {
        if (InstrumentationRegistry.getArguments().getString("sendManagementScreenshots") != "true") return
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(context.getExternalFilesDir(null), "send-management-${fixture.uuid}").apply { mkdirs() }
        try { File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun success(result: SendMutationResult): BitwardenSend {
        assertTrue("Expected Send success, got $result", result is SendMutationResult.Success)
        return (result as SendMutationResult.Success).send
    }

    private fun assertOpaqueIdentity(before: JsonObject, after: JsonObject) {
        listOf("id", "accessId", "key", "type", "future", "file").forEach { assertEquals(it, before[it], after[it]) }
        if (before["text"] != null && before["text"] != JsonNull) {
            assertEquals(before["text"]!!.jsonObject["futureText"], after["text"]!!.jsonObject["futureText"])
        }
    }

    private inner class Fixture(val server: MockWebServer) {
        val uuid = UUID.randomUUID().toString()
        private val accountKey = "send-management-$uuid"
        private val email = "$accountKey@example.invalid"
        private val token = "synthetic-send-token-$uuid"
        val repository = BitwardenRepository.getInstance(context)
        val database = PasswordDatabase.getDatabase(context)
        val material = ByteArray(16) { (it + 21).toByte() }
        private val vaultKey = SymmetricCryptoKey(ByteArray(32) { 7 }, ByteArray(32) { 11 })
        private val sendKey = BitwardenCrypto.deriveSendKey(material)
        val filePlaintext = "Synthetic file bytes only\n\u0000\tend".toByteArray()
        val fileCiphertext = encryptedFile(filePlaintext)
        private val fileId = "file-$uuid"
        private val documents = ConcurrentHashMap<String, JsonObject>()
        val paths = CopyOnWriteArrayList<String>()
        val putBodies = CopyOnWriteArrayList<JsonObject>()
        val reads = AtomicInteger()
        val changeOnRead = AtomicInteger(-1)
        val passwordRemovals = AtomicInteger()
        val loseNextPutResponse = AtomicBoolean()
        private val revision = AtomicInteger()
        private val unexpected = CopyOnWriteArrayList<String>()
        var vaultId = 0L
        lateinit var vault: BitwardenVault

        suspend fun initialize() {
            val endpoint = server.url("/").toString().trimEnd('/')
            vaultId = database.bitwardenVaultDao().insert(BitwardenVault(
                email = email, canonicalEmail = email, userId = uuid, accountKey = accountKey,
                serverUrl = endpoint, identityUrl = "$endpoint/identity", apiUrl = "$endpoint/api/",
                accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000L,
                isLocked = false, isConnected = true, syncEnabled = false,
            ))
            vault = requireNotNull(database.bitwardenVaultDao().getVaultById(vaultId))
            cache<SymmetricCryptoKey>("symmetricKeyCache")[vaultId] = vaultKey
            cache<String>("accessTokenCache")[vaultId] = token
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    paths += "${request.method} $path"
                    if (request.getHeader("Authorization") != "Bearer $token") {
                        unexpected += "Unexpected authorization at $path"
                        return MockResponse().setResponseCode(401)
                    }
                    // This route is a synthetic stored blob, not a recipient or OTP API.
                    if (path == "/synthetic-blob/$fileId" && request.method == "GET") {
                        return MockResponse().setBody(Buffer().write(fileCiphertext))
                    }
                    val sendId = path.removePrefix("/api/sends/").substringBefore('/')
                    val existing = documents[sendId]
                    if (existing == null) {
                        unexpected += "Unexpected request ${request.method} $path"
                        return MockResponse().setResponseCode(404)
                    }
                    if (path == "/api/sends/$sendId" && request.method == "GET") {
                        if (reads.incrementAndGet() == changeOnRead.get()) changeRevisionDocument(sendId)
                        return ok(requireNotNull(documents[sendId]))
                    }
                    if (path == "/api/sends/$sendId" && request.method == "PUT") {
                        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        putBodies += body
                        val fields = body.toMutableMap()
                        if (body["password"] == JsonNull || !body.containsKey("password")) fields["password"] = existing["password"] ?: JsonNull
                        fields["revisionDate"] = nextRevision()
                        documents[sendId] = JsonObject(fields)
                        if (loseNextPutResponse.compareAndSet(true, false)) {
                            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                        return ok(requireNotNull(documents[sendId]))
                    }
                    if (path == "/api/sends/$sendId/remove-password" && request.method == "PUT") {
                        passwordRemovals.incrementAndGet()
                        documents[sendId] = JsonObject(existing + mapOf("password" to JsonNull,
                            "authType" to JsonPrimitive(2), "revisionDate" to nextRevision()))
                        return ok(requireNotNull(documents[sendId]))
                    }
                    unexpected += "Unexpected request ${request.method} $path"
                    return MockResponse().setResponseCode(405)
                }
            }
        }

        suspend fun seed(type: Int): BitwardenSend {
            val sendId = "send-${UUID.randomUUID()}"
            val now = (System.currentTimeMillis() / 60_000L) * 60_000L
            documents[sendId] = buildJsonObject {
                put("id", sendId); put("accessId", "access-$sendId"); put("type", type)
                put("key", BitwardenCrypto.encrypt(material, vaultKey))
                put("name", BitwardenCrypto.encryptString(if (type == 0) "Synthetic original text" else "Synthetic original file", sendKey))
                put("notes", BitwardenCrypto.encryptString("Original synthetic notes", sendKey))
                put("password", "server-password-marker"); put("authType", 1); put("emails", JsonNull)
                put("accessCount", 1); put("maxAccessCount", 5); put("disabled", false); put("hideEmail", false)
                put("revisionDate", nextRevision())
                put("deletionDate", Instant.ofEpochMilli(now + 8 * 86_400_000L).toString())
                put("expirationDate", Instant.ofEpochMilli(now + 3 * 86_400_000L).toString())
                put("future", buildJsonObject { put("nested", "Preserve synthetic opaque metadata") })
                if (type == BitwardenSend.TYPE_TEXT) put("text", buildJsonObject {
                    put("text", BitwardenCrypto.encryptString("Original synthetic content", sendKey))
                    put("hidden", false); put("futureText", 42)
                }) else put("file", buildJsonObject {
                    put("id", fileId); put("fileName", BitwardenCrypto.encryptString("synthetic-only.txt", sendKey))
                    put("key", BitwardenCrypto.encrypt(ByteArray(64) { (it + 9).toByte() }, sendKey))
                    put("size", fileCiphertext.size.toString()); put("futureFile", "must remain")
                })
            }
            val entity = map(requireNotNull(documents[sendId]))
            database.bitwardenSendDao().upsert(entity)
            return requireNotNull(database.bitwardenSendDao().getBySendId(vaultId, sendId))
        }

        fun document(send: BitwardenSend) = requireNotNull(documents[send.bitwardenSendId])
        fun draft(send: BitwardenSend) = BitwardenSendDraft(send.name, send.textContent.orEmpty(), send.notes,
            maxAccessCount = send.maxAccessCount, hideEmail = send.hideEmail, hiddenText = send.isTextHidden,
            disabled = send.disabled, deletionMillis = Instant.parse(send.deletionDate!!).toEpochMilli(),
            expirationMillis = send.expirationDate?.let { Instant.parse(it).toEpochMilli() })
        suspend fun row(send: BitwardenSend) = requireNotNull(database.bitwardenSendDao().getBySendId(vaultId, send.bitwardenSendId))
        private fun map(document: JsonObject) = requireNotNull(BitwardenSendMapper.mapApiToEntity(vaultId,
            vault.serverUrl, json.decodeFromString<SendApiResponse>(document.toString()), vaultKey))
        suspend fun reloadLocal(send: BitwardenSend): BitwardenSend {
            database.bitwardenSendDao().upsert(map(document(send)).copy(id = send.id, createdAt = send.createdAt))
            return row(send)
        }
        fun changeRevision(send: BitwardenSend) = changeRevisionDocument(send.bitwardenSendId)
        private fun changeRevisionDocument(sendId: String) {
            documents[sendId] = JsonObject(requireNotNull(documents[sendId]) + ("revisionDate" to nextRevision()))
        }
        private fun nextRevision() = JsonPrimitive(Instant.ofEpochMilli(1_800_000_000_000L + revision.incrementAndGet() * 1000L).toString())

        suspend fun assertStored(saved: BitwardenSend, draft: BitwardenSendDraft, original: BitwardenSend) {
            val stored = row(saved)
            assertEquals(saved, stored)
            assertEquals(original.id, stored.id); assertEquals(original.createdAt, stored.createdAt)
            assertEquals(original.accessId, stored.accessId); assertEquals(original.keyBase64, stored.keyBase64)
            assertEquals(original.type, stored.type); assertEquals(original.fileName, stored.fileName)
            assertEquals(original.fileSize, stored.fileSize); assertFalse(stored.isDirty)
            assertEquals(draft.title, stored.name); assertEquals(draft.notes.orEmpty(), stored.notes)
            assertEquals(draft.disabled, stored.disabled); assertEquals(draft.hideEmail, stored.hideEmail)
            assertEquals(draft.maxAccessCount, stored.maxAccessCount)
            assertEquals(draft.deletionMillis, Instant.parse(stored.deletionDate!!).toEpochMilli())
            assertEquals(draft.expirationMillis, stored.expirationDate?.let { Instant.parse(it).toEpochMilli() })
            if (stored.isTextType) { assertEquals(draft.text, stored.textContent); assertEquals(draft.hiddenText, stored.isTextHidden) }
        }

        fun fetchStoredFileBytes(): ByteArray = OkHttpClient().newCall(Request.Builder()
            .url(server.url("/synthetic-blob/$fileId")).header("Authorization", "Bearer $token").build())
            .execute().use { response -> check(response.isSuccessful); requireNotNull(response.body).bytes() }
        private fun encryptedFile(plain: ByteArray): ByteArray {
            val iv = ByteArray(16) { (it + 31).toByte() }
            val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey.encKey, "AES"), IvParameterSpec(iv))
            }.doFinal(plain)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(sendKey.macKey, "HmacSHA256")) }.doFinal(iv + encrypted)
            return byteArrayOf(BitwardenCrypto.CIPHER_TYPE_AES_CBC_HMAC.toByte()) + iv + mac + encrypted
        }
        fun decryptFile(bytes: ByteArray): ByteArray {
            assertEquals(BitwardenCrypto.CIPHER_TYPE_AES_CBC_HMAC.toByte(), bytes[0])
            val iv = bytes.copyOfRange(1, 17)
            val encrypted = bytes.copyOfRange(49, bytes.size)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(sendKey.macKey, "HmacSHA256")) }.doFinal(iv + encrypted)
            assertArrayEquals(mac, bytes.copyOfRange(17, 49))
            return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(sendKey.encKey, "AES"), IvParameterSpec(iv))
            }.doFinal(encrypted)
        }
        fun assertNoUnexpectedRequests() = assertTrue("Unexpected synthetic HTTP requests: $unexpected", unexpected.isEmpty())
        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String): MutableMap<Long, T> = BitwardenRepository::class.java
            .getDeclaredField(name).apply { isAccessible = true }.get(repository) as MutableMap<Long, T>

        suspend fun close() {
            if (vaultId != 0L) {
                val owned = database.bitwardenVaultDao().getVaultById(vaultId)
                if (owned != null) {
                    check(owned.accountKey == accountKey && owned.email == email && owned.userId == uuid)
                    cache<SymmetricCryptoKey>("symmetricKeyCache").remove(vaultId)?.clear()
                    cache<String>("accessTokenCache").remove(vaultId)
                    database.bitwardenSendDao().getSendsByVault(vaultId).forEach { row ->
                        check(documents.containsKey(row.bitwardenSendId))
                        database.bitwardenSendDao().deleteBySendId(vaultId, row.bitwardenSendId)
                    }
                    database.bitwardenVaultDao().deleteById(vaultId)
                }
            }
            vaultKey.clear(); sendKey.clear(); material.fill(0)
            filePlaintext.fill(0); fileCiphertext.fill(0); documents.clear()
        }
    }

    private fun ok(body: JsonObject) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body.toString())
}
