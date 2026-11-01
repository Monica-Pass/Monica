package takagi.ru.monica.lansync

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Real Android FileChannel locks and symlinks, with an isolated synthetic cache. */
class DeviceSyncCacheRootsInstrumentedTest {
    private fun name() = UUID.randomUUID().toString()
    private fun fixture(body: suspend (File) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) {
            val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
            val fixture = File(cacheDir,"cache-root-lease-fixture-${name()}").also { check(it.mkdir()) }
            try { body(fixture) } finally {
                check(fixture.canonicalFile.parentFile == cacheDir)
                // Files.walk does not follow links. Test cleanup uses the same boundary.
                Files.walk(fixture.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            }
        }
    }

    @Test fun orphanCiphertextIsReclaimedWhileBothActiveRootsSurvive() = fixture { cache ->
        val orphan = File(cache,"device-sync/${name()}").also { check(it.mkdirs()) }
        val out = File(orphan,"out-${name()}").also { check(it.mkdir()) }
        File(out,"changes.mdbxsync").writeBytes(byteArrayOf(1,2,3))
        val resumed = File(orphan,"resume").also { check(it.mkdir()) }
        File(resumed,"${"a".repeat(64)}-${"b".repeat(64)}.part").writeBytes(byteArrayOf(4,5,6))
        DeviceSyncCacheRoots.acquire(cache,name()).use { left ->
            DeviceSyncCacheRoots.acquire(cache,name()).use { right ->
                assertFalse(orphan.exists())
                repeat(3) { DeviceSyncCacheRoots.acquire(cache,name()).use {
                    assertTrue(left.root.isDirectory); assertTrue(right.root.isDirectory)
                } }
            }
        }
    }

    @Test fun rootAndOwnerLinksRejectAndInternalLinksPreserveExternalCiphertext() = fixture { cache ->
        val base = File(cache,"device-sync").also { check(it.mkdir()) }
        val outside = File(cache,"outside").also { check(it.mkdir()) }
        val sentinel = File(outside,"changes.mdbxsync").also { it.writeBytes(byteArrayOf(7,8,9)) }
        val linkedRoot = File(base,name()).toPath()
        Files.createSymbolicLink(linkedRoot,outside.toPath())
        try { DeviceSyncCacheRoots.acquire(cache,name()); fail("root link accepted") } catch (_: java.io.IOException) { }
        Files.delete(linkedRoot)
        val root = File(base,name()).also { check(it.mkdir()) }
        val owner = File(root,".owner.lock").toPath()
        Files.createSymbolicLink(owner,sentinel.toPath())
        try { DeviceSyncCacheRoots.acquire(cache,name()); fail("owner link accepted") } catch (_: java.io.IOException) { }
        Files.delete(owner)
        val out = File(root,"out-${name()}").also { check(it.mkdir()) }
        Files.createSymbolicLink(File(out,"assets.monica").toPath(),sentinel.toPath())
        DeviceSyncCacheRoots.acquire(cache,name()).use {
            assertTrue(root.exists()); assertArrayEquals(byteArrayOf(7,8,9),sentinel.readBytes())
        }
    }

    @Test fun invalidRequestedRootPreservesUnknownFileAndCannotPublishALease() = fixture { cache ->
        val rootName = name()
        val root = File(cache,"device-sync/$rootName").also { check(it.mkdirs()) }
        val unknown = File(root,"keep.txt").also { it.writeText("synthetic") }
        try { DeviceSyncCacheRoots.acquire(cache,rootName); fail("unknown root accepted") } catch (_: java.io.IOException) { }
        assertEquals("synthetic",unknown.readText())
        assertFalse(File(root,".owner.lock").exists())
        check(unknown.delete())
        DeviceSyncCacheRoots.acquire(cache,rootName).use { assertEquals(root.canonicalFile,it.root.canonicalFile) }
    }
}
