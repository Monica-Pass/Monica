package takagi.ru.monica.repository

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxCapability
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.capabilities
import takagi.ru.monica.data.supports

class MdbxEngineMetadataTest {
    @Test
    fun oldDatabaseMetadataDefaultsToKotlinMdbx1() {
        val database = LocalMdbxDatabase(name = "Legacy", filePath = "legacy.mdbx")

        assertEquals(MdbxEngineType.KOTLIN_MDBX1.name, database.engineType)
        assertEquals(MdbxEngineType.KOTLIN_MDBX1, database.engineTypeEnum)
    }

    @Test
    fun unknownEngineMetadataFallsBackToKotlinMdbx1() {
        val database = LocalMdbxDatabase(
            name = "Unknown",
            filePath = "unknown.mdbx",
            engineType = "future-engine"
        )

        assertEquals(MdbxEngineType.KOTLIN_MDBX1, database.engineTypeEnum)
    }

    @Test
    fun engineCapabilitiesRetireMdbx1AndExposeVerifiedMdbx2Features() {
        assertEquals(emptySet<MdbxCapability>(), MdbxEngineType.KOTLIN_MDBX1.capabilities)
        assertEquals(
            setOf(
                MdbxCapability.LOCAL_CRUD,
                MdbxCapability.EMBEDDED_ATTACHMENTS,
                MdbxCapability.EXTERNAL_STORAGE,
                MdbxCapability.REMOTE_SYNC,
                MdbxCapability.NESTED_FOLDERS,
                MdbxCapability.PROJECT_TAGS,
                MdbxCapability.DELTA_HISTORY,
                MdbxCapability.SNAPSHOTS,
                MdbxCapability.CONFLICTS,
                MdbxCapability.SYNC_BUNDLES,
                MdbxCapability.BENCHMARK
            ),
            MdbxEngineType.RUST_MDBX2.capabilities
        )
        val rustDatabase = LocalMdbxDatabase(
            name = "Rust",
            filePath = "rust.mdbx",
            engineType = MdbxEngineType.RUST_MDBX2.name
        )
        assertEquals(true, rustDatabase.supports(MdbxCapability.LOCAL_CRUD))
        assertEquals(true, rustDatabase.supports(MdbxCapability.EXTERNAL_STORAGE))
        assertEquals(true, rustDatabase.supports(MdbxCapability.NESTED_FOLDERS))
        assertEquals(true, rustDatabase.supports(MdbxCapability.PROJECT_TAGS))
        assertEquals(true, rustDatabase.supports(MdbxCapability.DELTA_HISTORY))
        assertEquals(true, rustDatabase.supports(MdbxCapability.SNAPSHOTS))
        assertEquals(true, rustDatabase.supports(MdbxCapability.CONFLICTS))
        assertEquals(true, rustDatabase.supports(MdbxCapability.SYNC_BUNDLES))
        assertEquals(true, rustDatabase.supports(MdbxCapability.BENCHMARK))
        assertEquals(true, rustDatabase.supports(MdbxCapability.REMOTE_SYNC))
    }

    @Test
    fun rootProjectIdAndPasswordEntryIdAreStable() {
        val vaultId = "a9bf3c32-195d-4f87-b16a-44aa098ae061"
        val entry = PasswordEntry(
            id = 42,
            title = "Example",
            website = "https://example.com",
            username = "alice",
            password = "secret"
        )

        assertEquals(
            Mdbx2VaultSessionExecutor.rootProjectId(vaultId),
            Mdbx2VaultSessionExecutor.rootProjectId(vaultId)
        )
        assertEquals("password:42", mdbxPasswordObjectId(entry))
        assertEquals(
            "password:portable-id",
            mdbxPasswordObjectId(entry.copy(replicaGroupId = "password:portable-id"))
        )
        assertEquals(
            mdbx2PhysicalEntryId(vaultId, "password:42"),
            mdbx2PhysicalEntryId(vaultId, "password:42")
        )
    }

    @Test
    fun rustPasswordNormalizationUsesNfc() {
        val decomposed = "e\u0301"
        assertEquals(
            Normalizer.normalize(decomposed, Normalizer.Form.NFC),
            Mdbx2VaultSessionExecutor.normalizePassword(decomposed)
        )
    }
}
