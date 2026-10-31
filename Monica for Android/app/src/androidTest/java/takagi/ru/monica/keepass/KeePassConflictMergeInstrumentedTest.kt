package takagi.ru.monica.keepass

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassKdbxService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real Room, KDBX codec and WebDAV HTTP requests; all records and endpoints are synthetic. */
@RunWith(AndroidJUnit4::class)
class KeePassConflictMergeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val room = PasswordDatabase.getDatabase(context)
    private val dao = room.localKeePassDatabaseDao()
    private val pending = room.keepassPendingChangeDao()

    @Test fun currentMergeReadsRemoteAndKeepsBothClientsEntries() = runBlocking {
        scenario {
            takagi.ru.monica.security.SessionManager.markUnlocked()
            val localId = UUID.randomUUID()
            val remoteId = UUID.randomUUID()
            working.writeBytes(encode(base.modifyParentGroup { copy(entries = entries + entry("local-extra", "").copy(uuid = localId)) }))
            remote.bytes = encode(base.modifyParentGroup { copy(entries = entries + entry("remote-extra", "").copy(uuid = remoteId)) })
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            store.mergeCurrentDatabase(id)
            val saved = decode(remote.bytes)
            assertEquals(setOf(localId, remoteId) + base.content.group.entries.single().uuid,
                saved.content.group.entries.map { it.uuid }.toSet())
            assertArrayEquals(remote.bytes, working.readBytes())
            assertArrayEquals(remote.bytes, baseFile.readBytes())
            assertEquals(listOf("\"revision-1\""), remote.putConditions)
            assertEquals(KeePassSyncStatus.IN_SYNC, dao.getDatabaseById(id)!!.lastSyncStatus)
            assertTrue(pending.getUnfinishedChangesByDatabase(id).isEmpty())
        }
    }

    @Test fun currentMergeRejectsAThirdClientWriteAndPreservesLocalCopy() = runBlocking {
        scenario {
            takagi.ru.monica.security.SessionManager.markUnlocked()
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            val before = working.readBytes()
            val remoteBefore = remote.bytes.copyOf()
            remote.raceOnPut = true
            assertTrue(runCatching { store.mergeCurrentDatabase(id) }.isFailure)
            assertArrayEquals(before, working.readBytes())
            assertArrayEquals(remoteBefore, remote.bytes)
            assertFalse(pending.getUnfinishedChangesByDatabase(id).isEmpty())
            assertTrue(store.isDatabaseAvailable(id))
        }
    }

    @Test fun independentFieldsMergeAndSyncStatusAndPendingQueueFinishTogether() = runBlocking {
        scenario {
            val review = service.inspectCurrentRemoteConflict(id).getOrThrow()
            assertEquals(0, review.snapshot.ambiguousCount)
            val result = resolve(review).getOrThrow()
            assertEquals(0, result.conflictCopyCount)
            assertEquals(2, result.retainedRecoveryCopies)
            val merged = decode(working.readBytes()).content.group.entries.single()
            assertEquals("local-user", merged.fields.getValue("UserName").content)
            assertEquals("remote-note", merged.fields.getValue("Notes").content)
            assertEquals(PASSKEY_METADATA, merged.fields.getValue("MonicaPasskey").content)
            assertTrue(merged.fields.getValue("MonicaPasskey") is EntryValue.Encrypted)
            assertArrayEquals(working.readBytes(), baseFile.readBytes())
            assertArrayEquals(working.readBytes(), remote.bytes)
            assertEquals(listOf("\"revision-1\""), remote.putConditions)
            val registration = dao.getDatabaseById(id)!!
            assertEquals(KeePassSyncStatus.IN_SYNC, registration.lastSyncStatus)
            assertNull(registration.lastSyncError)
            assertNotNull(registration.lastSyncedAt)
            val state = room.keepassRemoteSyncStateDao().getState(id)!!
            assertFalse(state.hasLocalChanges)
            assertFalse(state.hasRemoteChanges)
            assertEquals(KeePassSourceSafety.revisionOf(working).sha256, state.baseHash)
            assertTrue(pending.getUnfinishedChangesByDatabase(id).isEmpty())
            assertEquals(KeePassPendingChange.STATUS_COMPLETED, pending.getByChangeId(id, "fixture-change")!!.status)
            assertTrue(service.listRecoveryCopies(id).all { it.verified })
            assertTrue(service.syncRemoteDatabase(id).isSuccess)
            assertEquals(1, remote.putConditions.size)
        }
    }

    @Test fun disputedFieldsCannotBypassReviewWithAnEmptySelection() = runBlocking {
        scenario {
            remote.bytes = encode(base.modifyParentGroup { copy(entries = listOf(entry("remote-user", "remote-note"))) })
            val review = service.inspectCurrentRemoteConflict(id).getOrThrow()
            assertTrue(resolve(review).isFailure)
            assertTrue(remote.putConditions.isEmpty())
            val disputedField = review.snapshot.items.flatMap { it.details }.single { it.label == "UserName" }
            service.resolveCurrentRemoteConflict(id, KeePassConflictDecision.MERGE,
                review.localRevision.sha256, review.remoteRevision.sha256,
                mapOf(disputedField.id to KeePassConflictResolutionSide.REMOTE)).getOrThrow()
            val merged = decode(working.readBytes()).content.group.entries.single()
            assertEquals("remote-user", merged.fields.getValue("UserName").content)
            assertEquals("remote-note", merged.fields.getValue("Notes").content)
        }
    }

    @Test fun aLocalEditAfterReviewCannotBeOverwritten() = runBlocking {
        scenario {
            val review = service.inspectCurrentRemoteConflict(id).getOrThrow()
            val newer = encode(base.modifyParentGroup { copy(entries = listOf(entry("new-local-user", "base-note"))) })
            working.writeBytes(newer)
            KeePassKdbxService.invalidateProcessCache(id)
            assertTrue(resolve(review).isFailure)
            assertArrayEquals(newer, working.readBytes())
            assertTrue(remote.putConditions.isEmpty())
        }
    }

    @Test fun aRemoteEditAfterReviewPreventsAllWritesAndRetainsPendingChanges() = runBlocking {
        scenario {
            val review = service.inspectCurrentRemoteConflict(id).getOrThrow()
            remote.bytes = encode(base.modifyParentGroup { copy(entries = listOf(entry("new-remote-user", "new-note"))) })
            remote.etag = "\"revision-2\""
            val original = working.readBytes()
            assertTrue(resolve(review).isFailure)
            assertArrayEquals(original, working.readBytes())
            assertEquals(0, remote.putConditions.size)
            assertEquals(KeePassPendingChange.STATUS_BLOCKED, pending.getByChangeId(id, "fixture-change")!!.status)
        }
    }

    @Test fun aChangeBetweenVersionCheckAndPutIsRejectedByTheServer() = runBlocking {
        scenario {
            val review = service.inspectCurrentRemoteConflict(id).getOrThrow()
            val original = working.readBytes()
            val originalBase = baseFile.readBytes()
            remote.raceOnPut = true
            assertTrue(resolve(review).isFailure)
            assertEquals(listOf("\"revision-1\""), remote.putConditions)
            assertEquals("\"racing-device\"", remote.etag)
            assertArrayEquals(original, working.readBytes())
            assertArrayEquals(originalBase, baseFile.readBytes())
            assertEquals(KeePassSyncStatus.CONFLICT, dao.getDatabaseById(id)!!.lastSyncStatus)
            assertEquals(KeePassPendingChange.STATUS_BLOCKED, pending.getByChangeId(id, "fixture-change")!!.status)
            assertEquals(2, service.listRecoveryCopies(id).size)
        }
    }

    @Test fun aConditionalFailureDuringNormalSyncAlsoBecomesAnActionableConflict() = runBlocking {
        scenario {
            remote.bytes = baseFile.readBytes()
            val state = room.keepassRemoteSyncStateDao().getState(id)!!
            room.keepassRemoteSyncStateDao().insertState(state.copy(remoteEtag = remote.etag))
            remote.raceOnPut = true
            assertTrue(service.syncRemoteDatabase(id).isFailure)
            assertEquals(KeePassSyncStatus.CONFLICT, dao.getDatabaseById(id)!!.lastSyncStatus)
        }
    }

    @Test fun lostWriteVerificationKeepsLocalCopiesAndCanBeReviewedAgain() = runBlocking {
        scenario {
            val review = service.inspectCurrentRemoteConflict(id).getOrThrow()
            val original = working.readBytes()
            val originalBase = baseFile.readBytes()
            remote.failReadAfterWrite = true
            assertTrue(resolve(review).isFailure)
            assertArrayEquals(original, working.readBytes())
            assertArrayEquals(originalBase, baseFile.readBytes())
            assertEquals(KeePassPendingChange.STATUS_BLOCKED, pending.getByChangeId(id, "fixture-change")!!.status)
            remote.failReadAfterWrite = false
            val refreshed = service.inspectCurrentRemoteConflict(id).getOrThrow()
            assertNotEquals(review.remoteRevision, refreshed.remoteRevision)
            assertTrue(resolve(refreshed).isSuccess)
            assertTrue(pending.getUnfinishedChangesByDatabase(id).isEmpty())
        }
    }

    @Test fun wholeRemoteChoiceDoesNotUploadAndCancelsOnlyThisDatabasesReviewedChanges() = runBlocking {
        scenario {
            val review = service.inspectCurrentRemoteConflict(id).getOrThrow()
            assertTrue(resolve(review, KeePassConflictDecision.USE_REMOTE).isSuccess)
            assertArrayEquals(remote.bytes, working.readBytes())
            assertTrue(remote.putConditions.isEmpty())
            assertEquals(KeePassPendingChange.STATUS_CANCELLED, pending.getByChangeId(id, "fixture-change")!!.status)
            assertEquals(KeePassSyncStatus.IN_SYNC, dao.getDatabaseById(id)!!.lastSyncStatus)
        }
    }

    @Test fun settlementDoesNotConsumeOtherDatabasesOrNewChanges() = runBlocking {
        scenario {
            val otherId = dao.insertDatabase(LocalKeePassDatabase(name = "Other synthetic database", filePath = "unused"))
            try {
                listOf(KeePassPendingChange.STATUS_PENDING, KeePassPendingChange.STATUS_IN_PROGRESS, KeePassPendingChange.STATUS_FAILED)
                    .forEach { status -> pending.insert(change(id, status).copy(status = status, lastError = "old failure", nextAttemptAt = 999999)) }
                val foreignId = pending.insert(change(otherId, "foreign"))
                val reviewedIds = pending.getUnfinishedChangesByDatabase(id).map { it.id }
                pending.insert(change(id, "created-later"))
                pending.settleConflictChanges(id, reviewedIds + foreignId, false, 12345)
                assertEquals(KeePassPendingChange.STATUS_COMPLETED, pending.getByChangeId(id, "fixture-change")!!.status)
                assertEquals(KeePassPendingChange.STATUS_BLOCKED, pending.getByChangeId(otherId, "foreign")!!.status)
                assertEquals(KeePassPendingChange.STATUS_BLOCKED, pending.getByChangeId(id, "created-later")!!.status)
                listOf(KeePassPendingChange.STATUS_PENDING, KeePassPendingChange.STATUS_IN_PROGRESS, KeePassPendingChange.STATUS_FAILED)
                    .forEach { status ->
                        val completed = pending.getByChangeId(id, status)!!
                        assertEquals(KeePassPendingChange.STATUS_COMPLETED, completed.status)
                        assertEquals(12345L, completed.completedAt)
                        assertNull(completed.lastError)
                        assertNull(completed.nextAttemptAt)
                    }
                pending.settleConflictChanges(id, reviewedIds, true, 23456)
                assertEquals(KeePassPendingChange.STATUS_COMPLETED, pending.getByChangeId(id, "fixture-change")!!.status)
            } finally {
                pending.deleteByDatabase(otherId)
                dao.deleteDatabaseById(otherId)
            }
        }
    }

    @Test fun largePendingQueuesCanBeSettledWithoutExceedingSqlParameterLimits() = runBlocking {
        scenario {
            room.withTransaction {
                repeat(1100) { pending.insert(change(id, "batch-$it")) }
                val repository = KeePassPendingChangeRepository(pending)
                val reviewed = repository.getUnfinishedChangesByDatabase(id).map { it.id }
                repository.settleConflictChanges(id, reviewed, false, 12345)
            }
            assertTrue(pending.getUnfinishedChangesByDatabase(id).isEmpty())
            assertEquals(KeePassPendingChange.STATUS_COMPLETED, pending.getByChangeId(id, "batch-1099")!!.status)
        }
    }

    private suspend fun scenario(block: suspend Fixture.() -> Unit) {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try { fixture.initialize(); fixture.block() } finally { fixture.close() }
        }
    }

    private inner class Fixture(server: MockWebServer) {
        val root = File(context.filesDir, "keepass-conflict-test-${UUID.randomUUID()}").apply { mkdirs() }
        val working = File(root, "working.kdbx")
        val baseFile = File(root, "base.kdbx")
        private val security = SecurityManager(context)
        val service = KeePassKdbxService(context, dao, security)
        private val credentials = Credentials.from(EncryptedValue.fromString("Synthetic conflict test password"))
        private val entryId = UUID.randomUUID()
        val base: KeePassDatabase.Ver4x = KeePassDatabase.Ver4x.create(
            rootName = "Root", meta = Meta(generator = "Conflict fixture", name = "Conflict fixture"), credentials = credentials
        ).let { created ->
            val seed = when (val kdf = created.header.kdfParameters) {
                is KdfParameters.Aes -> kdf.seed
                is KdfParameters.Argon2 -> kdf.salt
            }
            created.copy(header = created.header.copy(kdfParameters = KdfParameters.Aes(rounds = 100U, seed = seed)))
        }.modifyParentGroup { copy(entries = listOf(entry("base-user", "base-note"))) } as KeePassDatabase.Ver4x
        val remote = Remote(encode(base.modifyParentGroup { copy(entries = listOf(entry("base-user", "remote-note"))) }))
        private val endpoint = server.url("/").toString()
        var id = 0L
        private var sourceId = 0L

        init { server.dispatcher = remote }

        suspend fun initialize() {
            working.writeBytes(encode(base.modifyParentGroup { copy(entries = listOf(entry("local-user", "base-note"))) }))
            baseFile.writeBytes(encode(base))
            sourceId = room.keepassRemoteSourceDao().insertSource(KeepassRemoteSource(
                providerType = KeePassRemoteProviderType.WEBDAV, displayName = "Synthetic WebDAV",
                remotePath = "fixture.kdbx", baseUrl = endpoint
            ))
            id = dao.insertDatabase(LocalKeePassDatabase(
                name = "Conflict fixture", filePath = working.relativeTo(context.filesDir).path,
                sourceType = KeePassDatabaseSourceType.REMOTE_WEBDAV, sourceId = sourceId,
                openMode = KeePassOpenMode.WORKING_COPY,
                workingCopyPath = working.relativeTo(context.filesDir).path,
                cacheCopyPath = baseFile.relativeTo(context.filesDir).path,
                encryptedPassword = security.encryptData("Synthetic conflict test password"),
                lastSyncStatus = KeePassSyncStatus.CONFLICT, lastSyncError = "Synthetic conflict"
            ))
            room.keepassRemoteSyncStateDao().insertState(KeepassRemoteSyncState(
                databaseId = id, remoteEtag = "\"base-revision\"",
                baseHash = KeePassSourceSafety.revisionOf(baseFile).sha256,
                workingHash = KeePassSourceSafety.revisionOf(working).sha256,
                hasLocalChanges = true, hasRemoteChanges = true, syncPhase = KeePassSyncPhase.CONFLICT
            ))
            pending.insert(change(id, "fixture-change"))
        }

        fun entry(user: String, note: String) = Entry(uuid = entryId, fields = EntryFields.of(
            "Title" to EntryValue.Plain("Account"), "UserName" to EntryValue.Plain(user),
            "Notes" to EntryValue.Plain(note), "Password" to EntryValue.Encrypted(EncryptedValue.fromString("fixture-password")),
            "MonicaPasskey" to EntryValue.Encrypted(EncryptedValue.fromString(PASSKEY_METADATA))
        ))
        fun encode(database: KeePassDatabase): ByteArray = ByteArrayOutputStream().use { out -> database.encode(out); out.toByteArray() }
        fun decode(bytes: ByteArray): KeePassDatabase = bytes.inputStream().use { KeePassDatabase.decode(it, credentials) }
        suspend fun resolve(preview: KeePassRemoteConflictPreview, decision: KeePassConflictDecision = KeePassConflictDecision.MERGE) =
            service.resolveCurrentRemoteConflict(id, decision, preview.localRevision.sha256, preview.remoteRevision.sha256)

        suspend fun close() {
            if (id != 0L) {
                service.listRecoveryCopies(id).forEach { service.deleteRecoveryCopy(it).getOrThrow() }
                KeePassKdbxService.invalidateProcessCache(id)
                pending.deleteByDatabase(id)
                dao.deleteDatabaseById(id)
            }
            if (sourceId != 0L) room.keepassRemoteSourceDao().deleteSourceById(sourceId)
            check(root.canonicalFile.parentFile == context.filesDir.canonicalFile && root.name.startsWith("keepass-conflict-test-"))
            root.deleteRecursively()
        }
    }

    private fun change(databaseId: Long, changeId: String) = KeePassPendingChange.fromChangeSet(
        KeePassChangeSet(changeId = changeId, databaseId = databaseId, entryUuid = "fixture-entry",
            target = KeePassChangeTarget.PASSWORD, operation = KeePassChangeOperation.FIELD_PATCH,
            baseFingerprint = "fixture-fingerprint",
            fieldPatch = KeePassFieldChangePatch(managedScope = KeePassManagedFieldScope.PASSWORD,
                replacementFields = listOf(KeePassFieldChange("UserName", "local-user")),
                baseFields = listOf(KeePassFieldBaseValue("UserName", "base-user")))) ,
        status = KeePassPendingChange.STATUS_BLOCKED
    )

    private class Remote(@Volatile var bytes: ByteArray) : Dispatcher() {
        @Volatile var etag = "\"revision-1\""
        @Volatile var raceOnPut = false
        @Volatile var failReadAfterWrite = false
        val putConditions = mutableListOf<String?>()
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
            "HEAD" -> MockResponse().setResponseCode(200)
            "PROPFIND" -> MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(
                """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:"><d:response>
                    <d:href>/fixture.kdbx</d:href><d:propstat><d:prop><d:displayname>fixture.kdbx</d:displayname>
                    <d:getetag>${etag.replace("\"", "&quot;")}</d:getetag><d:getcontentlength>${bytes.size}</d:getcontentlength>
                    <d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response></d:multistatus>""".trimIndent()
            )
            "GET" -> if (failReadAfterWrite && putConditions.isNotEmpty()) MockResponse().setResponseCode(503)
                else MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
            "PUT" -> {
                putConditions += request.getHeader("If-Match")
                if (raceOnPut) etag = "\"racing-device\""
                if (request.getHeader("If-Match") != etag) MockResponse().setResponseCode(412)
                else {
                    bytes = request.body.readByteArray()
                    etag = "\"revision-${putConditions.size + 1}\""
                    MockResponse().setResponseCode(204)
                }
            }
            else -> MockResponse().setResponseCode(405)
        }
    }

    private companion object {
        const val PASSKEY_METADATA = "{\"credentialId\":\"synthetic\",\"signCount\":0,\"BE\":true,\"BS\":true}"
    }
}
