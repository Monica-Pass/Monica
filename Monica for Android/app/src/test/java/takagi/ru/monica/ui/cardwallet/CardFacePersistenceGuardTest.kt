package takagi.ru.monica.ui.cardwallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFacePersistenceGuardTest {
    @Test
    fun cardFaceUsesPortableMetadataAndEveryAttachmentBackend() {
        val models = source("app/src/main/java/takagi/ru/monica/data/model/SecureItemModels.kt")
        val localExecutor = source("app/src/main/java/takagi/ru/monica/attachments/executor/LocalAttachmentExecutor.kt")
        val facade = source("app/src/main/java/takagi/ru/monica/attachments/facade/AttachmentFacade.kt")
        val bitwardenUpload = source("app/src/main/java/takagi/ru/monica/bitwarden/service/CipherUploadProcessor.kt")
        val bitwardenSync = source("app/src/main/java/takagi/ru/monica/bitwarden/service/CipherSyncProcessor.kt")
        val keepass = source("app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt")
        val portableBackup = source("app/src/main/java/takagi/ru/monica/attachments/backup/PortableAttachmentBackup.kt")
        val mdbx = source("app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt")

        assertTrue(models.contains("val cardFace: CardFaceConfig? = null"))
        assertTrue(models.contains("imageAttachmentName"))
        assertFalse(models.contains("cardFaceBase64"))
        assertTrue(localExecutor.contains("writeFromBytes"))
        assertTrue(facade.contains("promoteLocalAttachmentsToBitwarden"))
        assertTrue(bitwardenUpload.contains("Monica Card Face"))
        assertTrue(bitwardenSync.contains("parseCardFaceConfig"))
        assertTrue(keepass.contains("FIELD_MONICA_ITEM_DATA"))
        assertTrue(portableBackup.contains("parentSecureItemId"))
        assertTrue(mdbx.contains("upsertAttachment"))
    }

    private fun source(relativePath: String): String = projectFile(relativePath).readText()

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, relativePath)
    }
}
