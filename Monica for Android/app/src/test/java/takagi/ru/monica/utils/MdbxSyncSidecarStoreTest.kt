package takagi.ru.monica.utils

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbxSyncSidecarStoreTest {

    @Test
    fun manifestIsAtomicallyWrittenAndReadableAfterStoreRecreation() = runBlocking {
        val root = kotlin.io.path.createTempDirectory(prefix = "mdbx-sidecar-test-").toFile()
        try {
            val file = File(root, "vault.json")
            val store = MdbxSyncSidecarStore(root)
            val manifest = MdbxSyncSidecarManifest(
                vaultId = "vault",
                generationId = "generation",
                streamId = "device",
                remoteVaultPath = "vaults/main.mdbx",
                nextSequence = 2,
                segments = listOf(
                    MdbxSyncSidecarSegment(
                        streamId = "device",
                        generationId = "generation",
                        sequence = 1,
                        fileName = "1-deadbeef.mdbxsync",
                        digestHex = "deadbeef",
                        sizeBytes = 42,
                        uploaded = true
                    )
                )
            )
            store.write(file, manifest)
            assertEquals(manifest, MdbxSyncSidecarStore(root).read(file))

            val updated = MdbxSyncSidecarStore(root).update(file) { current ->
                current!!.copy(nextSequence = 3)
            }
            assertEquals(3L, updated.nextSequence)
            assertTrue(file.length() > 0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sidecarRejectsPathEscapeAndMalformedManifest(): Unit = runBlocking {
        val root = kotlin.io.path.createTempDirectory(prefix = "mdbx-sidecar-invalid-").toFile()
        try {
            val store = MdbxSyncSidecarStore(root)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.read(File(root, "nested/vault.json")) }
            }
            val file = File(root, "bad.json")
            file.writeText("{\"format\":\"unknown\"}")
            assertThrows(Exception::class.java) {
                runBlocking { store.read(file) }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
