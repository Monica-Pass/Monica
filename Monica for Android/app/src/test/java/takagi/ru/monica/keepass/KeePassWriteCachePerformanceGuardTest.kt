package takagi.ru.monica.keepass

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassWriteCachePerformanceGuardTest {

    @Test
    fun `normal writes reuse persisted bytes instead of reading the full database again`() {
        val source = serviceSource()
        val writeBody = source
            .substringAfter("private suspend fun writeDatabase(")
            .substringBefore("private fun keePassPendingChangeRepository(")

        assertFalse(writeBody.contains("readDatabaseSnapshot(database)"))
        assertTrue(writeBody.contains("persistedRevision = syncOutcome.finalRevision"))
        assertTrue(writeBody.contains("cachePersistedDatabase("))
    }

    @Test
    fun `raw writes share the same no reread cache path`() {
        val source = serviceSource()
        val writeBody = source
            .substringAfter("private suspend fun writeRawDatabaseBytes(")
            .substringBefore("private fun writeUriCopyVerified(")

        assertFalse(writeBody.contains("readDatabaseSnapshot(database)"))
        assertTrue(writeBody.contains("persistedRevision = outcome.finalRevision"))
        assertTrue(writeBody.contains("cachePersistedDatabase("))
    }

    @Test
    fun `persisted cache state is derived from known bytes and cheap file metadata`() {
        val source = serviceSource()
        val cacheBody = source
            .substringAfter("private fun cachePersistedDatabase(")
            .substringBefore("private fun keePassPendingChangeRepository(")

        assertTrue(cacheBody.contains("sourceRevision: KeePassSourceRevision"))
        assertFalse(cacheBody.contains("persistedBytes: ByteArray"))
        assertFalse(cacheBody.contains("KeePassSourceSafety.revisionOf(persistedBytes)"))
        assertTrue(cacheBody.contains("sourceSignature = currentSourceSignature(database)"))
        assertFalse(cacheBody.contains("readDatabaseSnapshot"))
    }

    @Test
    fun `one computed revision is reused through local write remote verification and cache refresh`() {
        val source = serviceSource()
        val writeBody = source
            .substringAfter("private suspend fun writeDatabase(")
            .substringBefore("private fun keePassPendingChangeRepository(")
        val remoteBody = source
            .substringAfter("private suspend fun syncRemoteWorkingCopy(")
            .substringBefore("private suspend fun markRemoteWritePending(")

        assertTrue(writeBody.contains("val encodedRevision = artifact.revision"))
        assertTrue(writeBody.contains("sourceRevision = persistedRevision"))
        assertTrue(remoteBody.contains("sourceRevision: KeePassSourceRevision"))
        assertTrue(remoteBody.contains("workingHash = sourceRevision.sha256"))
        assertFalse(remoteBody.contains("sha256Hex(syncOutcome.finalBytes)"))
        assertFalse(remoteBody.contains("val workingHash = GoogleDriveKeePassSupport.sha256Hex(bytes)"))
    }

    @Test
    fun `pending remote changes reuse the revision already computed for the write`() {
        val source = serviceSource()
        val enqueueBody = source
            .substringAfter("private suspend fun enqueuePendingChangeSetsIfRemote(")
            .substringBefore("private suspend fun syncRemoteWorkingCopy(")

        assertTrue(enqueueBody.contains("workingRevision: KeePassSourceRevision"))
        assertTrue(enqueueBody.contains("workingHashAtChange = workingRevision.sha256"))
        assertFalse(enqueueBody.contains("readDatabaseSnapshot(database)"))
        assertFalse(enqueueBody.contains("sha256Hex("))
    }

    @Test
    fun `KDBX encoding starts with a bounded capacity estimate`() {
        val source = serviceSource()
        val encodeBody = source
            .substringAfter("private fun encodeDatabase(")
            .substringBefore("private fun writeInternal(")

        assertTrue(encodeBody.contains("KeePassEncodeBufferPolicy.initialCapacity(estimatedSizeBytes)"))
        assertFalse(encodeBody.contains("ByteArrayOutputStream().use"))
    }

    @Test
    fun `unchanged entry counts do not emit Room table updates`() {
        val daoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/LocalKeePassDatabase.kt",
        ).readText()

        assertTrue(
            daoSource.contains(
                "UPDATE local_keepass_databases SET entry_count = :count WHERE id = :id AND entry_count != :count"
            )
        )
    }

    @Test
    fun `field reference indexing avoids a second complete entry list`() {
        val projectionSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassNativeProjectionBundle.kt",
        ).readText()

        assertTrue(projectionSource.contains("entries.value.asSequence()"))
        assertFalse(projectionSource.contains("resolutionContextBuilder(entries.value.map"))
    }

    @Test
    fun `external writes reuse the source revision for conflict and recovery checks`() {
        val source = serviceSource()
        val externalBody = source
            .substringAfter("private fun writeExternal(")
            .substringBefore("private fun writeExternalBytes(")

        assertTrue(externalBody.contains("val originalRevision = openExternalInputStream(uri)?.use(KeePassSourceSafety::revisionOf)"))
        assertTrue(externalBody.contains("recoveryStore.create(database.id, input)"))
        assertFalse(externalBody.contains("originalBytes"))
    }

    @Test
    fun `evicting a decoded database also releases its native indexes`() {
        val source = serviceSource()
        val trimBody = source
            .substringAfter("private fun trimLoadedDatabaseCacheLocked()")
            .substringBefore("private fun consumeProcessCacheInvalidation")

        assertTrue(trimBody.contains("nativeSessionCache.invalidate(evictedDatabaseId)"))
        assertTrue(trimBody.contains("nativeProjectionBundleCache.invalidate(evictedDatabaseId)"))
        assertTrue(trimBody.contains("projectionIndexGate.invalidate(evictedDatabaseId)"))
    }

    @Test
    fun `native browser snapshot is built once per loaded revision`() {
        val source = serviceSource()

        assertTrue(source.contains("val nativeBrowser: Lazy<KeePassNativeBrowserSnapshot>"))
        assertTrue(source.contains("nativeBrowser = lazy(LazyThreadSafetyMode.SYNCHRONIZED)"))
        assertFalse(source.contains("KeePassNativeBrowserBuilder.build(loadDatabase(databaseId).nativeSession.value)"))
    }

    @Test
    fun `native browser hierarchy traversal uses identity indexes instead of rescanning every group`() {
        val browserSource = projectFile(
            "app/src/main/java/takagi/ru/monica/keepass/KeePassNativeBrowser.kt",
        ).readText()

        assertTrue(browserSource.contains("groupsByIdentity[parent].orEmpty()"))
        assertTrue(browserSource.contains("session.groupsByIdentity[parent].orEmpty()"))
        assertFalse(browserSource.contains("filter { group -> group.parentGroup == parent }"))
        assertFalse(browserSource.contains("filter { node -> node.parentGroup == parent }"))
    }

    @Test
    fun `entry counting uses an iterative traversal for deeply nested databases`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/keepass/KeePassTreeNavigation.kt",
        ).readText()
        val start = "fun countEntries(group: Group): Int"
        val end = "fun collectEntriesInGroupPath("
        assertTrue(source.contains(start))
        assertTrue(source.substringAfter(start).contains(end))
        val countBody = source.substringAfter(start).substringBefore(end)

        assertTrue(countBody.contains("ArrayDeque<Group>()"))
        assertFalse(countBody.contains("sumOf(::countEntries)"))
    }

    @Test
    fun `field reference checks traverse entries lazily without a temporary complete list`() {
        val source = serviceSource()
        val start = "private fun buildResolutionContext("
        val end = "private fun containsReferenceToken("
        assertTrue(source.contains(start))
        assertTrue(source.substringAfter(start).contains(end))
        val resolutionBody = source.substringAfter(start).substringBefore(end)

        assertTrue(resolutionBody.contains("entrySequence(keePassDatabase.content.group)"))
        assertFalse(resolutionBody.contains("mutableListOf<Entry>()"))
        val navigation = projectFile(
            "app/src/main/java/takagi/ru/monica/keepass/KeePassTreeNavigation.kt",
        ).readText()
        val sequenceStart = "fun entrySequence(group: Group): Sequence<Entry>"
        val sequenceEnd = "fun buildGroupTraversalContextIndex("
        assertTrue(navigation.contains(sequenceStart))
        assertTrue(navigation.substringAfter(sequenceStart).contains(sequenceEnd))
        val sequenceBody = navigation.substringAfter(sequenceStart).substringBefore(sequenceEnd)
        assertFalse(sequenceBody.contains("mutableListOf<Entry>()"))
        val referenceStart = "private fun containsReferenceToken("
        val referenceEnd = "private fun collectGroups("
        assertTrue(source.contains(referenceStart))
        assertTrue(source.substringAfter(referenceStart).contains(referenceEnd))
        val referenceBody = source.substringAfter(referenceStart).substringBefore(referenceEnd)
        assertFalse(referenceBody.contains("mutableListOf<Entry>()"))
        assertFalse((source + navigation).contains("private fun collectEntries(group: Group, entries: MutableList<Entry>)"))
    }

    @Test
    fun `opening and saving do not eagerly rebuild every compatibility projection`() {
        val source = serviceSource()

        assertTrue(source.contains("val nativeSession: Lazy<KeePassNativeSession>"))
        assertTrue(source.contains("val projectionBundle: Lazy<KeePassNativeProjectionBundle>"))
        assertTrue(source.contains("nativeSessionCache.deferred("))
        assertTrue(source.contains("nativeProjectionBundleCache.deferred(nativeSession)"))
        assertFalse(source.contains("nativeSession = nativeSessionCache.getOrCreate("))
        assertFalse(source.contains("projectionBundle = nativeProjectionBundleCache.getOrCreate(nativeSession)"))
    }

    @Test
    fun `entry count refresh does not allocate a complete temporary entry list`() {
        val source = serviceSource()
        val countBody = source
            .substringAfter("private suspend fun updateStoredEntryCount(")
            .substringBefore("// ================================================================")

        assertFalse(source.contains("collectEntries(writtenDatabase.content.group"))
        assertFalse(countBody.contains("mutableListOf<Entry>()"))
        assertTrue(countBody.contains("countEntries(database.content.group)"))
    }

    private fun serviceSource(): String = projectFile(
        "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt",
    ).readText()

    private fun projectFile(relativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
