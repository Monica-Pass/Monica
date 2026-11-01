package takagi.ru.monica.lansync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class DeviceSyncCacheRootsTest {
    private fun name() = UUID.randomUUID().toString()
    private fun fixture(body: suspend (File) -> Unit) = runBlocking {
        val cache = Files.createTempDirectory("monica-root-lease-").toFile()
        try { body(cache) } finally { check(cache.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile); cache.deleteRecursively() }
    }

    @Test fun legacyOrphanAndReleasedRootsAreReclaimedWithOnlyProtocolFiles() = fixture { cache ->
        val root = File(cache, "device-sync/${name()}").also { check(it.mkdirs()) }
        val out = File(root, "out-${name()}").also { check(it.mkdir()) }
        File(out,"changes.mdbxsync").writeBytes(byteArrayOf(1,2,3))
        File(out,".pairing.replica").writeBytes(byteArrayOf(4))
        File(out,".replica-${"A".repeat(22)}.tmp").writeBytes(byteArrayOf(5))
        val delta = File(out,".delta-${"a".repeat(64)}").also { check(it.mkdir()) }
        File(delta,"assets.monica").writeBytes(byteArrayOf(6))
        val staging = File(out,".delta-staging-${"B".repeat(22)}").also { check(it.mkdir()) }
        File(staging,"changes.mdbxsync").writeBytes(byteArrayOf(7))
        val incoming = File(root,"in-${name()}").also { check(it.mkdir()) }
        File(incoming,"changes.mdbxsync.part").writeBytes(byteArrayOf(8))
        val fallback = File(incoming,"local-assets-${name()}").also { check(it.mkdir()) }
        File(fallback,"assets.monica").writeBytes(byteArrayOf(9))
        val resume = File(root,"resume").also { check(it.mkdir()) }
        File(resume,"${"a".repeat(64)}-${"b".repeat(64)}.part").writeBytes(byteArrayOf(10))
        val first = DeviceSyncCacheRoots.acquire(cache,name())
        assertFalse(root.exists())
        val released = first.root
        first.close()
        DeviceSyncCacheRoots.acquire(cache,name()).use { assertFalse(released.exists()) }
    }

    @Test fun twoActiveLeasesAndRepeatedScannersKeepAllActiveInstances() = fixture { cache ->
        DeviceSyncCacheRoots.acquire(cache,name()).use { first ->
            DeviceSyncCacheRoots.acquire(cache,name()).use { second ->
                val out = File(first.root,"out-${name()}").also { check(it.mkdir()) }
                val bytes = byteArrayOf(11,12,13)
                File(out,"changes.mdbxsync").writeBytes(bytes)
                repeat(4) { DeviceSyncCacheRoots.acquire(cache,name()).use {
                    assertTrue(first.root.isDirectory); assertTrue(second.root.isDirectory)
                    assertArrayEquals(bytes,File(out,"changes.mdbxsync").readBytes())
                } }
            }
        }
    }

    @Test fun unknownLayoutsAndNamesArePreserved() = fixture { cache ->
        val root = File(cache,"device-sync/${name()}").also { check(it.mkdirs()) }
        val unknown = File(root,"user-information.txt").also { it.writeText("synthetic") }
        val unrelated = File(root.parentFile,"not-a-uuid").also { check(it.mkdir()) }
        DeviceSyncCacheRoots.acquire(cache,name()).use {
            assertEquals("synthetic",unknown.readText()); assertTrue(unrelated.isDirectory)
        }
    }

    @Test fun requestedInvalidLayoutAndInvalidNameNeverReturnALease() = fixture { cache ->
        try { DeviceSyncCacheRoots.acquire(cache,"../outside"); fail("invalid UUID accepted") } catch (_: java.io.IOException) { }
        assertFalse(File(cache,"device-sync").exists())
        val rootName = name()
        val root = File(cache,"device-sync/$rootName").also { check(it.mkdirs()) }
        val unknown = File(root,"unknown").also { it.writeText("keep") }
        try { DeviceSyncCacheRoots.acquire(cache,rootName); fail("unknown layout accepted") } catch (_: java.io.IOException) { }
        assertEquals("keep",unknown.readText())
        assertTrue(unknown.delete())
        DeviceSyncCacheRoots.acquire(cache,rootName).use { assertEquals(root.canonicalFile,it.root.canonicalFile) }
    }

    @Test fun cancellationAtIoReturnClosesTheUnreceivedLease() = fixture { cache ->
        val queue = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob()+dispatcher)
        val rootName = name()
        var returned = false
        val job = scope.launch { DeviceSyncCacheRoots.acquire(cache,rootName).use { returned = true } }
        queue.remove().run()
        withTimeout(10_000) { while(queue.isEmpty()) delay(5) }
        job.cancel()
        withTimeout(10_000) { while(!job.isCompleted) { queue.poll()?.run(); delay(5) } }
        assertFalse(returned)
        DeviceSyncCacheRoots.acquire(cache,rootName).use { assertTrue(it.root.isDirectory) }
        scope.cancel()
    }

    @Test fun excessiveRootEnumerationFailsWithoutPublishingANewLease() = fixture { cache ->
        val base = File(cache,"device-sync").also { check(it.mkdir()) }
        repeat(4097) { File(base,"unknown-$it").writeBytes(byteArrayOf()) }
        val rootName = name()
        try { DeviceSyncCacheRoots.acquire(cache,rootName); fail("unbounded namespace accepted") } catch (_: java.io.IOException) { }
        assertFalse(File(base,rootName).exists())
        assertEquals(4097,base.listFiles()!!.size)
    }

    @Test fun crossProcessActiveOwnerSurvivesAllLocalScanners() = fixture { cache ->
        val rootName = name()
        val child = probe(cache,rootName,"owner")
        try {
            assertEquals("LEASED",awaitLeased(child))
            repeat(3) { DeviceSyncCacheRoots.acquire(cache,name()).use {
                assertTrue(File(cache,"device-sync/$rootName").isDirectory)
            } }
        } finally { stopProbe(child) }
        DeviceSyncCacheRoots.acquire(cache,name()).use { assertFalse(File(cache,"device-sync/$rootName").exists()) }
    }

    @Test fun cancellationWaitingForCrossProcessGatePublishesNoRoot() = fixture { cache ->
        val rootName = name()
        val child = probe(cache,rootName,"gate")
        try {
            assertEquals("LEASED",awaitLeased(child))
            val scope = CoroutineScope(SupervisorJob())
            val job = scope.launch { DeviceSyncCacheRoots.acquire(cache,rootName).close() }
            delay(50); job.cancel()
            withTimeout(10_000) { job.join() }
            assertFalse(File(cache,"device-sync/$rootName").exists())
            scope.cancel()
        } finally { stopProbe(child) }
    }

    @Test fun hardLinkedOwnerCannotReleaseALiveProcessesOriginalOsLock() = fixture { cache ->
        DeviceSyncCacheRoots.acquire(cache,name()).use { active ->
            val aliasRoot = File(active.root.parentFile,name()).also { check(it.mkdir()) }
            Files.createLink(File(aliasRoot,".owner.lock").toPath(),File(active.root,".owner.lock").toPath())
            val fresh = name()
            try { DeviceSyncCacheRoots.acquire(cache,fresh); fail("owner inode alias accepted") } catch (_: java.io.IOException) { }
            assertFalse(File(active.root.parentFile,fresh).exists())
            val gate = File(active.root.parentFile,".roots.lock").toPath()
            Files.delete(gate)
            Files.createLink(gate,File(active.root,".owner.lock").toPath())
            try { DeviceSyncCacheRoots.acquire(cache,fresh); fail("global gate inode alias accepted") } catch (_: java.io.IOException) { }
            Files.delete(gate)
            Files.createFile(gate)
            val child = probe(cache,active.root.name,"busy")
            try { assertEquals("REJECTED",awaitLeased(child)) } finally { stopProbe(child) }
            assertTrue(active.root.isDirectory)
        }
    }

    @Test fun rootAndOwnerSymlinksRejectAndInternalLinksNeverFollowExternalFiles() = fixture { cache ->
        val base = File(cache,"device-sync").also { check(it.mkdir()) }
        val external = File(cache,"external").also { check(it.mkdir()) }
        val sentinel = File(external,"changes.mdbxsync").also { it.writeText("outside") }
        val linked = File(base,name()).toPath()
        try { Files.createSymbolicLink(linked,external.toPath()) } catch (_: java.nio.file.FileSystemException) { assumeTrue("symlink unavailable on this host",false) }
        try { DeviceSyncCacheRoots.acquire(cache,name()); fail("linked root accepted") } catch (_: java.io.IOException) { }
        Files.delete(linked)
        val root = File(base,name()).also { check(it.mkdir()) }
        val owner = File(root,".owner.lock").toPath()
        Files.createSymbolicLink(owner,sentinel.toPath())
        try { DeviceSyncCacheRoots.acquire(cache,name()); fail("linked owner accepted") } catch (_: java.io.IOException) { }
        Files.delete(owner)
        val out = File(root,"out-${name()}").also { check(it.mkdir()) }
        Files.createSymbolicLink(File(out,"changes.mdbxsync").toPath(),sentinel.toPath())
        DeviceSyncCacheRoots.acquire(cache,name()).use { assertEquals("outside",sentinel.readText()); assertTrue(root.exists()) }
    }

    private fun awaitLeased(child: Process): String? {
        val reader = Executors.newSingleThreadExecutor { task -> Thread(task,"cache-root-probe-reader").apply { isDaemon = true } }
        return try { reader.submit(Callable { child.inputStream.bufferedReader().readLine() }).get(10,TimeUnit.SECONDS) }
        finally { reader.shutdownNow() }
    }

    private fun stopProbe(child: Process) {
        try { child.outputStream.write('\n'.code); child.outputStream.flush() } catch (_: java.io.IOException) { }
        if (!child.waitFor(10,TimeUnit.SECONDS)) { child.destroyForcibly(); assertTrue(child.waitFor(10,TimeUnit.SECONDS)) }
        assertEquals("probe process failed",0,child.exitValue())
    }

    private fun probe(cache: File, rootName: String, mode: String): Process {
        // Gradle test workers can expose only gradle-worker.jar in java.class.path.
        val classpath = listOf(DeviceSyncCacheRootLockProbe::class.java,DeviceSyncCacheRoots::class.java,
            kotlinx.coroutines.Dispatchers::class.java,kotlin.coroutines.Continuation::class.java,kotlin.Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct().joinToString(File.pathSeparator)
        return ProcessBuilder(
        File(System.getProperty("java.home"),"bin/java").absolutePath,"-cp",classpath,
        DeviceSyncCacheRootLockProbe::class.java.name,cache.absolutePath,rootName,mode
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }
}

/** Separate JVM exercises real OS locks, independently of this JVM's registry. */
internal object DeviceSyncCacheRootLockProbe {
    @JvmStatic fun main(args: Array<String>) { runBlocking {
        if(args[2]=="busy") {
            try { DeviceSyncCacheRoots.acquire(File(args[0]),args[1]).close(); println("UNEXPECTED") }
            catch (_: java.io.IOException) { println("REJECTED") }
            System.out.flush()
        } else if(args[2]=="owner") DeviceSyncCacheRoots.acquire(File(args[0]),args[1]).use {
            println("LEASED"); System.out.flush(); readln()
        } else {
            val base = File(args[0],"device-sync").also { it.mkdirs() }
            FileChannel.open(File(base,".roots.lock").toPath(),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { println("LEASED"); System.out.flush(); readln() }
            }
        }
    } }
}
