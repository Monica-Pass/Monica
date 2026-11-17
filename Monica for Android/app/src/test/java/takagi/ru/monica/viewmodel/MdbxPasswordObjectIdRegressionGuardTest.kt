package takagi.ru.monica.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbxPasswordObjectIdRegressionGuardTest {

    @Test
    fun mdbxPasswordRowsNeverPersistBareReplicaUuidAsRemoteEntryId() {
        val passwordRepositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt"
        ).readText()
        val mdbxViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()

        val mdbxObjectIdBody = passwordRepositorySource
            .substringAfter("private suspend fun PasswordEntry.mdbxPasswordObjectId(): String")
            .substringBefore("suspend fun archivePasswordById")

        assertTrue(
            "MDBX password ids must only preserve real MDBX password object ids.",
            mdbxObjectIdBody.contains("?.takeIf { it.isMdbxPasswordObjectId() }") &&
                mdbxObjectIdBody.contains("?: id.takeIf { it > 0 }?.let { \"password:${'$'}it\" }") &&
                mdbxObjectIdBody.contains("private fun String.isMdbxPasswordObjectId(): Boolean")
        )
        assertFalse(
            "A bare replicaGroupId UUID must not be accepted as an MDBX remote entry id.",
            mdbxObjectIdBody.contains("?.takeIf { it.isNotBlank() }")
        )
        assertTrue(
            "MDBX import must repair old bare UUID rows before orphan rescue can loop.",
            mdbxViewModelSource.contains("normalizeLegacyMdbxPasswordRows(") &&
                mdbxViewModelSource.contains("withNormalizedMdbxPasswordEntryId()") &&
                mdbxViewModelSource.contains("remoteRoomIdsByEntryId[entryId]") &&
                mdbxViewModelSource.contains("[MDBX][legacy-normalize]") &&
                mdbxViewModelSource.contains("[MDBX][duplicate-local-delete]") &&
                mdbxViewModelSource.contains(".map { it.withNormalizedMdbxPasswordEntryId() }")
        )
    }

    @Test
    fun mdbxPasswordRowsPreserveAppAssociationAcrossSync() {
        val mdbxStoreSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val mdbxViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()
        val passwordRepositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt"
        ).readText()
        val passwordDaoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/PasswordEntryDao.kt"
        ).readText()

        assertTrue(
            "MDBX password payload must include app binding fields so sync does not drop autofill app matches.",
            mdbxStoreSource.contains(".put(\"app_package_name\", entry.appPackageName)") &&
                mdbxStoreSource.contains(".put(\"app_name\", entry.appName)")
        )
        assertTrue(
            "MDBX import must restore app binding fields and preserve existing values for older payloads without these keys.",
            mdbxViewModelSource.contains("appPackageName = payload.optStringPreservingExisting(") &&
                mdbxViewModelSource.contains("primaryKey = \"app_package_name\"") &&
                mdbxViewModelSource.contains("legacyKey = \"appPackageName\"") &&
                mdbxViewModelSource.contains("existingValue = existing?.appPackageName.orEmpty()") &&
                mdbxViewModelSource.contains("appName = payload.optStringPreservingExisting(") &&
                mdbxViewModelSource.contains("primaryKey = \"app_name\"") &&
                mdbxViewModelSource.contains("legacyKey = \"appName\"") &&
                mdbxViewModelSource.contains("existingValue = existing?.appName.orEmpty()")
        )
        assertTrue(
            "App association quick updates must mirror affected MDBX rows back into the MDBX file.",
            passwordRepositorySource.contains("mdbxRepository?.upsertPasswords(passwordEntryDao.getActiveMdbxEntriesByWebsite(website))") &&
                passwordRepositorySource.contains("mdbxRepository?.upsertPasswords(passwordEntryDao.getActiveMdbxEntriesByTitle(title))") &&
                passwordDaoSource.contains("updatedAt = :now WHERE website = :website") &&
                passwordDaoSource.contains("updatedAt = :now WHERE title = :title") &&
                passwordDaoSource.contains("suspend fun getActiveMdbxEntriesByWebsite(website: String): List<PasswordEntry>") &&
                passwordDaoSource.contains("suspend fun getActiveMdbxEntriesByTitle(title: String): List<PasswordEntry>")
        )
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!.canonicalFile
        }
        return File(dir, path)
    }
}
