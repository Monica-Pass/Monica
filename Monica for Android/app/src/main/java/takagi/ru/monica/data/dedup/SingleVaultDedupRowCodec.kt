package takagi.ru.monica.data.dedup

import java.util.Date
import kotlinx.serialization.json.*
import takagi.ru.monica.data.*
import takagi.ru.monica.attachments.model.Attachment

/** Versioned complete row codec. Field lists are guarded against entity schema changes. */
internal object SingleVaultDedupRowCodec {
    val passwordEntryKeys = setOf("id", "title", "website", "username", "password", "notes", "createdAt", "updatedAt", "isFavorite", "sortOrder", "isGroupCover", "appPackageName", "appName", "email", "phone", "addressLine", "city", "state", "zipCode", "country", "creditCardNumber", "creditCardHolder", "creditCardExpiry", "creditCardCVV", "categoryId", "boundNoteId", "keepassDatabaseId", "keepassGroupPath", "keepassEntryUuid", "keepassGroupUuid", "mdbxDatabaseId", "mdbxFolderId", "authenticatorKey", "passkeyBindings", "sshKeyData", "loginType", "ssoProvider", "ssoRefEntryId", "wifiMetadata", "customIconType", "customIconValue", "customIconUpdatedAt", "isDeleted", "deletedAt", "isArchived", "archivedAt", "replicaGroupId", "passwordGroupId", "bitwardenVaultId", "bitwardenCipherId", "bitwardenFolderId", "bitwardenRevisionDate", "bitwardenCipherType", "bitwardenLocalModified")
    fun encode(row: PasswordEntry): JsonObject = buildJsonObject {
        put("id", row.id)
        put("title", row.title)
        put("website", row.website)
        put("username", row.username)
        put("password", row.password)
        put("notes", row.notes)
        put("createdAt", row.createdAt.time)
        put("updatedAt", row.updatedAt.time)
        put("isFavorite", row.isFavorite)
        put("sortOrder", row.sortOrder)
        put("isGroupCover", row.isGroupCover)
        put("appPackageName", row.appPackageName)
        put("appName", row.appName)
        put("email", row.email)
        put("phone", row.phone)
        put("addressLine", row.addressLine)
        put("city", row.city)
        put("state", row.state)
        put("zipCode", row.zipCode)
        put("country", row.country)
        put("creditCardNumber", row.creditCardNumber)
        put("creditCardHolder", row.creditCardHolder)
        put("creditCardExpiry", row.creditCardExpiry)
        put("creditCardCVV", row.creditCardCVV)
        put("categoryId", row.categoryId)
        put("boundNoteId", row.boundNoteId)
        put("keepassDatabaseId", row.keepassDatabaseId)
        put("keepassGroupPath", row.keepassGroupPath)
        put("keepassEntryUuid", row.keepassEntryUuid)
        put("keepassGroupUuid", row.keepassGroupUuid)
        put("mdbxDatabaseId", row.mdbxDatabaseId)
        put("mdbxFolderId", row.mdbxFolderId)
        put("authenticatorKey", row.authenticatorKey)
        put("passkeyBindings", row.passkeyBindings)
        put("sshKeyData", row.sshKeyData)
        put("loginType", row.loginType)
        put("ssoProvider", row.ssoProvider)
        put("ssoRefEntryId", row.ssoRefEntryId)
        put("wifiMetadata", row.wifiMetadata)
        put("customIconType", row.customIconType)
        put("customIconValue", row.customIconValue)
        put("customIconUpdatedAt", row.customIconUpdatedAt)
        put("isDeleted", row.isDeleted)
        put("deletedAt", row.deletedAt?.time)
        put("isArchived", row.isArchived)
        put("archivedAt", row.archivedAt?.time)
        put("replicaGroupId", row.replicaGroupId)
        put("passwordGroupId", row.passwordGroupId)
        put("bitwardenVaultId", row.bitwardenVaultId)
        put("bitwardenCipherId", row.bitwardenCipherId)
        put("bitwardenFolderId", row.bitwardenFolderId)
        put("bitwardenRevisionDate", row.bitwardenRevisionDate)
        put("bitwardenCipherType", row.bitwardenCipherType)
        put("bitwardenLocalModified", row.bitwardenLocalModified)
    }
    fun decodePasswordEntry(obj: JsonObject): PasswordEntry {
        require(obj.keys == passwordEntryKeys)
        shape(obj.getValue("id"), quoted = false, nullable = false)
        shape(obj.getValue("title"), quoted = true, nullable = false)
        shape(obj.getValue("website"), quoted = true, nullable = false)
        shape(obj.getValue("username"), quoted = true, nullable = false)
        shape(obj.getValue("password"), quoted = true, nullable = false)
        shape(obj.getValue("notes"), quoted = true, nullable = false)
        shape(obj.getValue("createdAt"), quoted = false, nullable = false)
        shape(obj.getValue("updatedAt"), quoted = false, nullable = false)
        shape(obj.getValue("isFavorite"), quoted = false, nullable = false)
        shape(obj.getValue("sortOrder"), quoted = false, nullable = false)
        shape(obj.getValue("isGroupCover"), quoted = false, nullable = false)
        shape(obj.getValue("appPackageName"), quoted = true, nullable = false)
        shape(obj.getValue("appName"), quoted = true, nullable = false)
        shape(obj.getValue("email"), quoted = true, nullable = false)
        shape(obj.getValue("phone"), quoted = true, nullable = false)
        shape(obj.getValue("addressLine"), quoted = true, nullable = false)
        shape(obj.getValue("city"), quoted = true, nullable = false)
        shape(obj.getValue("state"), quoted = true, nullable = false)
        shape(obj.getValue("zipCode"), quoted = true, nullable = false)
        shape(obj.getValue("country"), quoted = true, nullable = false)
        shape(obj.getValue("creditCardNumber"), quoted = true, nullable = false)
        shape(obj.getValue("creditCardHolder"), quoted = true, nullable = false)
        shape(obj.getValue("creditCardExpiry"), quoted = true, nullable = false)
        shape(obj.getValue("creditCardCVV"), quoted = true, nullable = false)
        shape(obj.getValue("categoryId"), quoted = false, nullable = true)
        shape(obj.getValue("boundNoteId"), quoted = false, nullable = true)
        shape(obj.getValue("keepassDatabaseId"), quoted = false, nullable = true)
        shape(obj.getValue("keepassGroupPath"), quoted = true, nullable = true)
        shape(obj.getValue("keepassEntryUuid"), quoted = true, nullable = true)
        shape(obj.getValue("keepassGroupUuid"), quoted = true, nullable = true)
        shape(obj.getValue("mdbxDatabaseId"), quoted = false, nullable = true)
        shape(obj.getValue("mdbxFolderId"), quoted = true, nullable = true)
        shape(obj.getValue("authenticatorKey"), quoted = true, nullable = false)
        shape(obj.getValue("passkeyBindings"), quoted = true, nullable = false)
        shape(obj.getValue("sshKeyData"), quoted = true, nullable = false)
        shape(obj.getValue("loginType"), quoted = true, nullable = false)
        shape(obj.getValue("ssoProvider"), quoted = true, nullable = false)
        shape(obj.getValue("ssoRefEntryId"), quoted = false, nullable = true)
        shape(obj.getValue("wifiMetadata"), quoted = true, nullable = false)
        shape(obj.getValue("customIconType"), quoted = true, nullable = false)
        shape(obj.getValue("customIconValue"), quoted = true, nullable = true)
        shape(obj.getValue("customIconUpdatedAt"), quoted = false, nullable = false)
        shape(obj.getValue("isDeleted"), quoted = false, nullable = false)
        shape(obj.getValue("deletedAt"), quoted = false, nullable = true)
        shape(obj.getValue("isArchived"), quoted = false, nullable = false)
        shape(obj.getValue("archivedAt"), quoted = false, nullable = true)
        shape(obj.getValue("replicaGroupId"), quoted = true, nullable = true)
        shape(obj.getValue("passwordGroupId"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenVaultId"), quoted = false, nullable = true)
        shape(obj.getValue("bitwardenCipherId"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenFolderId"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenRevisionDate"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenCipherType"), quoted = false, nullable = false)
        shape(obj.getValue("bitwardenLocalModified"), quoted = false, nullable = false)
        return PasswordEntry(
            id = obj.getValue("id").jsonPrimitive.long,
            title = obj.getValue("title").jsonPrimitive.content,
            website = obj.getValue("website").jsonPrimitive.content,
            username = obj.getValue("username").jsonPrimitive.content,
            password = obj.getValue("password").jsonPrimitive.content,
            notes = obj.getValue("notes").jsonPrimitive.content,
            createdAt = Date(obj.getValue("createdAt").jsonPrimitive.long),
            updatedAt = Date(obj.getValue("updatedAt").jsonPrimitive.long),
            isFavorite = obj.getValue("isFavorite").jsonPrimitive.boolean,
            sortOrder = obj.getValue("sortOrder").jsonPrimitive.int,
            isGroupCover = obj.getValue("isGroupCover").jsonPrimitive.boolean,
            appPackageName = obj.getValue("appPackageName").jsonPrimitive.content,
            appName = obj.getValue("appName").jsonPrimitive.content,
            email = obj.getValue("email").jsonPrimitive.content,
            phone = obj.getValue("phone").jsonPrimitive.content,
            addressLine = obj.getValue("addressLine").jsonPrimitive.content,
            city = obj.getValue("city").jsonPrimitive.content,
            state = obj.getValue("state").jsonPrimitive.content,
            zipCode = obj.getValue("zipCode").jsonPrimitive.content,
            country = obj.getValue("country").jsonPrimitive.content,
            creditCardNumber = obj.getValue("creditCardNumber").jsonPrimitive.content,
            creditCardHolder = obj.getValue("creditCardHolder").jsonPrimitive.content,
            creditCardExpiry = obj.getValue("creditCardExpiry").jsonPrimitive.content,
            creditCardCVV = obj.getValue("creditCardCVV").jsonPrimitive.content,
            categoryId = if (obj.getValue("categoryId") == JsonNull) null else obj.getValue("categoryId").jsonPrimitive.long,
            boundNoteId = if (obj.getValue("boundNoteId") == JsonNull) null else obj.getValue("boundNoteId").jsonPrimitive.long,
            keepassDatabaseId = if (obj.getValue("keepassDatabaseId") == JsonNull) null else obj.getValue("keepassDatabaseId").jsonPrimitive.long,
            keepassGroupPath = if (obj.getValue("keepassGroupPath") == JsonNull) null else obj.getValue("keepassGroupPath").jsonPrimitive.content,
            keepassEntryUuid = if (obj.getValue("keepassEntryUuid") == JsonNull) null else obj.getValue("keepassEntryUuid").jsonPrimitive.content,
            keepassGroupUuid = if (obj.getValue("keepassGroupUuid") == JsonNull) null else obj.getValue("keepassGroupUuid").jsonPrimitive.content,
            mdbxDatabaseId = if (obj.getValue("mdbxDatabaseId") == JsonNull) null else obj.getValue("mdbxDatabaseId").jsonPrimitive.long,
            mdbxFolderId = if (obj.getValue("mdbxFolderId") == JsonNull) null else obj.getValue("mdbxFolderId").jsonPrimitive.content,
            authenticatorKey = obj.getValue("authenticatorKey").jsonPrimitive.content,
            passkeyBindings = obj.getValue("passkeyBindings").jsonPrimitive.content,
            sshKeyData = obj.getValue("sshKeyData").jsonPrimitive.content,
            loginType = obj.getValue("loginType").jsonPrimitive.content,
            ssoProvider = obj.getValue("ssoProvider").jsonPrimitive.content,
            ssoRefEntryId = if (obj.getValue("ssoRefEntryId") == JsonNull) null else obj.getValue("ssoRefEntryId").jsonPrimitive.long,
            wifiMetadata = obj.getValue("wifiMetadata").jsonPrimitive.content,
            customIconType = obj.getValue("customIconType").jsonPrimitive.content,
            customIconValue = if (obj.getValue("customIconValue") == JsonNull) null else obj.getValue("customIconValue").jsonPrimitive.content,
            customIconUpdatedAt = obj.getValue("customIconUpdatedAt").jsonPrimitive.long,
            isDeleted = obj.getValue("isDeleted").jsonPrimitive.boolean,
            deletedAt = if (obj.getValue("deletedAt") == JsonNull) null else Date(obj.getValue("deletedAt").jsonPrimitive.long),
            isArchived = obj.getValue("isArchived").jsonPrimitive.boolean,
            archivedAt = if (obj.getValue("archivedAt") == JsonNull) null else Date(obj.getValue("archivedAt").jsonPrimitive.long),
            replicaGroupId = if (obj.getValue("replicaGroupId") == JsonNull) null else obj.getValue("replicaGroupId").jsonPrimitive.content,
            passwordGroupId = if (obj.getValue("passwordGroupId") == JsonNull) null else obj.getValue("passwordGroupId").jsonPrimitive.content,
            bitwardenVaultId = if (obj.getValue("bitwardenVaultId") == JsonNull) null else obj.getValue("bitwardenVaultId").jsonPrimitive.long,
            bitwardenCipherId = if (obj.getValue("bitwardenCipherId") == JsonNull) null else obj.getValue("bitwardenCipherId").jsonPrimitive.content,
            bitwardenFolderId = if (obj.getValue("bitwardenFolderId") == JsonNull) null else obj.getValue("bitwardenFolderId").jsonPrimitive.content,
            bitwardenRevisionDate = if (obj.getValue("bitwardenRevisionDate") == JsonNull) null else obj.getValue("bitwardenRevisionDate").jsonPrimitive.content,
            bitwardenCipherType = obj.getValue("bitwardenCipherType").jsonPrimitive.int,
            bitwardenLocalModified = obj.getValue("bitwardenLocalModified").jsonPrimitive.boolean,
        )
    }

    val secureItemKeys = setOf("id", "itemType", "title", "notes", "isFavorite", "sortOrder", "createdAt", "updatedAt", "itemData", "imagePaths", "categoryId", "keepassDatabaseId", "keepassGroupPath", "keepassEntryUuid", "keepassGroupUuid", "mdbxDatabaseId", "mdbxFolderId", "isDeleted", "deletedAt", "isArchived", "archivedAt", "bitwardenArchiveIntent", "replicaGroupId", "bitwardenVaultId", "bitwardenCipherId", "bitwardenFolderId", "bitwardenRevisionDate", "bitwardenLocalModified", "syncStatus")
    fun encode(row: SecureItem): JsonObject = buildJsonObject {
        put("id", row.id)
        put("itemType", row.itemType.name)
        put("title", row.title)
        put("notes", row.notes)
        put("isFavorite", row.isFavorite)
        put("sortOrder", row.sortOrder)
        put("createdAt", row.createdAt.time)
        put("updatedAt", row.updatedAt.time)
        put("itemData", row.itemData)
        put("imagePaths", row.imagePaths)
        put("categoryId", row.categoryId)
        put("keepassDatabaseId", row.keepassDatabaseId)
        put("keepassGroupPath", row.keepassGroupPath)
        put("keepassEntryUuid", row.keepassEntryUuid)
        put("keepassGroupUuid", row.keepassGroupUuid)
        put("mdbxDatabaseId", row.mdbxDatabaseId)
        put("mdbxFolderId", row.mdbxFolderId)
        put("isDeleted", row.isDeleted)
        put("deletedAt", row.deletedAt?.time)
        put("isArchived", row.isArchived)
        put("archivedAt", row.archivedAt?.time)
        put("bitwardenArchiveIntent", row.bitwardenArchiveIntent)
        put("replicaGroupId", row.replicaGroupId)
        put("bitwardenVaultId", row.bitwardenVaultId)
        put("bitwardenCipherId", row.bitwardenCipherId)
        put("bitwardenFolderId", row.bitwardenFolderId)
        put("bitwardenRevisionDate", row.bitwardenRevisionDate)
        put("bitwardenLocalModified", row.bitwardenLocalModified)
        put("syncStatus", row.syncStatus)
    }
    fun decodeSecureItem(obj: JsonObject): SecureItem {
        require(obj.keys == secureItemKeys)
        shape(obj.getValue("id"), quoted = false, nullable = false)
        shape(obj.getValue("itemType"), quoted = true, nullable = false)
        shape(obj.getValue("title"), quoted = true, nullable = false)
        shape(obj.getValue("notes"), quoted = true, nullable = false)
        shape(obj.getValue("isFavorite"), quoted = false, nullable = false)
        shape(obj.getValue("sortOrder"), quoted = false, nullable = false)
        shape(obj.getValue("createdAt"), quoted = false, nullable = false)
        shape(obj.getValue("updatedAt"), quoted = false, nullable = false)
        shape(obj.getValue("itemData"), quoted = true, nullable = false)
        shape(obj.getValue("imagePaths"), quoted = true, nullable = false)
        shape(obj.getValue("categoryId"), quoted = false, nullable = true)
        shape(obj.getValue("keepassDatabaseId"), quoted = false, nullable = true)
        shape(obj.getValue("keepassGroupPath"), quoted = true, nullable = true)
        shape(obj.getValue("keepassEntryUuid"), quoted = true, nullable = true)
        shape(obj.getValue("keepassGroupUuid"), quoted = true, nullable = true)
        shape(obj.getValue("mdbxDatabaseId"), quoted = false, nullable = true)
        shape(obj.getValue("mdbxFolderId"), quoted = true, nullable = true)
        shape(obj.getValue("isDeleted"), quoted = false, nullable = false)
        shape(obj.getValue("deletedAt"), quoted = false, nullable = true)
        shape(obj.getValue("isArchived"), quoted = false, nullable = false)
        shape(obj.getValue("archivedAt"), quoted = false, nullable = true)
        shape(obj.getValue("bitwardenArchiveIntent"), quoted = false, nullable = true)
        shape(obj.getValue("replicaGroupId"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenVaultId"), quoted = false, nullable = true)
        shape(obj.getValue("bitwardenCipherId"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenFolderId"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenRevisionDate"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenLocalModified"), quoted = false, nullable = false)
        shape(obj.getValue("syncStatus"), quoted = true, nullable = false)
        return SecureItem(
            id = obj.getValue("id").jsonPrimitive.long,
            itemType = ItemType.valueOf(obj.getValue("itemType").jsonPrimitive.content),
            title = obj.getValue("title").jsonPrimitive.content,
            notes = obj.getValue("notes").jsonPrimitive.content,
            isFavorite = obj.getValue("isFavorite").jsonPrimitive.boolean,
            sortOrder = obj.getValue("sortOrder").jsonPrimitive.int,
            createdAt = Date(obj.getValue("createdAt").jsonPrimitive.long),
            updatedAt = Date(obj.getValue("updatedAt").jsonPrimitive.long),
            itemData = obj.getValue("itemData").jsonPrimitive.content,
            imagePaths = obj.getValue("imagePaths").jsonPrimitive.content,
            categoryId = if (obj.getValue("categoryId") == JsonNull) null else obj.getValue("categoryId").jsonPrimitive.long,
            keepassDatabaseId = if (obj.getValue("keepassDatabaseId") == JsonNull) null else obj.getValue("keepassDatabaseId").jsonPrimitive.long,
            keepassGroupPath = if (obj.getValue("keepassGroupPath") == JsonNull) null else obj.getValue("keepassGroupPath").jsonPrimitive.content,
            keepassEntryUuid = if (obj.getValue("keepassEntryUuid") == JsonNull) null else obj.getValue("keepassEntryUuid").jsonPrimitive.content,
            keepassGroupUuid = if (obj.getValue("keepassGroupUuid") == JsonNull) null else obj.getValue("keepassGroupUuid").jsonPrimitive.content,
            mdbxDatabaseId = if (obj.getValue("mdbxDatabaseId") == JsonNull) null else obj.getValue("mdbxDatabaseId").jsonPrimitive.long,
            mdbxFolderId = if (obj.getValue("mdbxFolderId") == JsonNull) null else obj.getValue("mdbxFolderId").jsonPrimitive.content,
            isDeleted = obj.getValue("isDeleted").jsonPrimitive.boolean,
            deletedAt = if (obj.getValue("deletedAt") == JsonNull) null else Date(obj.getValue("deletedAt").jsonPrimitive.long),
            isArchived = obj.getValue("isArchived").jsonPrimitive.boolean,
            archivedAt = if (obj.getValue("archivedAt") == JsonNull) null else Date(obj.getValue("archivedAt").jsonPrimitive.long),
            bitwardenArchiveIntent = if (obj.getValue("bitwardenArchiveIntent") == JsonNull) null else obj.getValue("bitwardenArchiveIntent").jsonPrimitive.boolean,
            replicaGroupId = if (obj.getValue("replicaGroupId") == JsonNull) null else obj.getValue("replicaGroupId").jsonPrimitive.content,
            bitwardenVaultId = if (obj.getValue("bitwardenVaultId") == JsonNull) null else obj.getValue("bitwardenVaultId").jsonPrimitive.long,
            bitwardenCipherId = if (obj.getValue("bitwardenCipherId") == JsonNull) null else obj.getValue("bitwardenCipherId").jsonPrimitive.content,
            bitwardenFolderId = if (obj.getValue("bitwardenFolderId") == JsonNull) null else obj.getValue("bitwardenFolderId").jsonPrimitive.content,
            bitwardenRevisionDate = if (obj.getValue("bitwardenRevisionDate") == JsonNull) null else obj.getValue("bitwardenRevisionDate").jsonPrimitive.content,
            bitwardenLocalModified = obj.getValue("bitwardenLocalModified").jsonPrimitive.boolean,
            syncStatus = obj.getValue("syncStatus").jsonPrimitive.content,
        )
    }

    val customFieldKeys = setOf("id", "entryId", "title", "value", "isProtected", "sortOrder")
    fun encode(row: CustomField): JsonObject = buildJsonObject {
        put("id", row.id)
        put("entryId", row.entryId)
        put("title", row.title)
        put("value", row.value)
        put("isProtected", row.isProtected)
        put("sortOrder", row.sortOrder)
    }
    fun decodeCustomField(obj: JsonObject): CustomField {
        require(obj.keys == customFieldKeys)
        shape(obj.getValue("id"), quoted = false, nullable = false)
        shape(obj.getValue("entryId"), quoted = false, nullable = false)
        shape(obj.getValue("title"), quoted = true, nullable = false)
        shape(obj.getValue("value"), quoted = true, nullable = false)
        shape(obj.getValue("isProtected"), quoted = false, nullable = false)
        shape(obj.getValue("sortOrder"), quoted = false, nullable = false)
        return CustomField(
            id = obj.getValue("id").jsonPrimitive.long,
            entryId = obj.getValue("entryId").jsonPrimitive.long,
            title = obj.getValue("title").jsonPrimitive.content,
            value = obj.getValue("value").jsonPrimitive.content,
            isProtected = obj.getValue("isProtected").jsonPrimitive.boolean,
            sortOrder = obj.getValue("sortOrder").jsonPrimitive.int,
        )
    }

    val passwordHistoryEntryKeys = setOf("id", "entryId", "password", "lastUsedAt")
    fun encode(row: PasswordHistoryEntry): JsonObject = buildJsonObject {
        put("id", row.id)
        put("entryId", row.entryId)
        put("password", row.password)
        put("lastUsedAt", row.lastUsedAt.time)
    }
    fun decodePasswordHistoryEntry(obj: JsonObject): PasswordHistoryEntry {
        require(obj.keys == passwordHistoryEntryKeys)
        shape(obj.getValue("id"), quoted = false, nullable = false)
        shape(obj.getValue("entryId"), quoted = false, nullable = false)
        shape(obj.getValue("password"), quoted = true, nullable = false)
        shape(obj.getValue("lastUsedAt"), quoted = false, nullable = false)
        return PasswordHistoryEntry(
            id = obj.getValue("id").jsonPrimitive.long,
            entryId = obj.getValue("entryId").jsonPrimitive.long,
            password = obj.getValue("password").jsonPrimitive.content,
            lastUsedAt = Date(obj.getValue("lastUsedAt").jsonPrimitive.long),
        )
    }

    val passwordArchiveSyncMetaKeys = setOf("entryId", "providerType", "originKeePassDatabaseId", "originKeePassGroupPath", "originBitwardenFolderId", "syncStatus", "lastError", "updatedAt")
    fun encode(row: PasswordArchiveSyncMeta): JsonObject = buildJsonObject {
        put("entryId", row.entryId)
        put("providerType", row.providerType)
        put("originKeePassDatabaseId", row.originKeePassDatabaseId)
        put("originKeePassGroupPath", row.originKeePassGroupPath)
        put("originBitwardenFolderId", row.originBitwardenFolderId)
        put("syncStatus", row.syncStatus)
        put("lastError", row.lastError)
        put("updatedAt", row.updatedAt)
    }
    fun decodePasswordArchiveSyncMeta(obj: JsonObject): PasswordArchiveSyncMeta {
        require(obj.keys == passwordArchiveSyncMetaKeys)
        shape(obj.getValue("entryId"), quoted = false, nullable = false)
        shape(obj.getValue("providerType"), quoted = true, nullable = false)
        shape(obj.getValue("originKeePassDatabaseId"), quoted = false, nullable = true)
        shape(obj.getValue("originKeePassGroupPath"), quoted = true, nullable = true)
        shape(obj.getValue("originBitwardenFolderId"), quoted = true, nullable = true)
        shape(obj.getValue("syncStatus"), quoted = true, nullable = false)
        shape(obj.getValue("lastError"), quoted = true, nullable = true)
        shape(obj.getValue("updatedAt"), quoted = false, nullable = false)
        return PasswordArchiveSyncMeta(
            entryId = obj.getValue("entryId").jsonPrimitive.long,
            providerType = obj.getValue("providerType").jsonPrimitive.content,
            originKeePassDatabaseId = if (obj.getValue("originKeePassDatabaseId") == JsonNull) null else obj.getValue("originKeePassDatabaseId").jsonPrimitive.long,
            originKeePassGroupPath = if (obj.getValue("originKeePassGroupPath") == JsonNull) null else obj.getValue("originKeePassGroupPath").jsonPrimitive.content,
            originBitwardenFolderId = if (obj.getValue("originBitwardenFolderId") == JsonNull) null else obj.getValue("originBitwardenFolderId").jsonPrimitive.content,
            syncStatus = obj.getValue("syncStatus").jsonPrimitive.content,
            lastError = if (obj.getValue("lastError") == JsonNull) null else obj.getValue("lastError").jsonPrimitive.content,
            updatedAt = obj.getValue("updatedAt").jsonPrimitive.long,
        )
    }

    val timelineVersionSnapshotKeys = setOf("id", "operationLogId", "itemType", "itemId", "operationType", "encryptedChangesJson", "createdAt")
    fun encode(row: TimelineVersionSnapshot): JsonObject = buildJsonObject {
        put("id", row.id)
        put("operationLogId", row.operationLogId)
        put("itemType", row.itemType)
        put("itemId", row.itemId)
        put("operationType", row.operationType)
        put("encryptedChangesJson", row.encryptedChangesJson)
        put("createdAt", row.createdAt)
    }
    fun decodeTimelineVersionSnapshot(obj: JsonObject): TimelineVersionSnapshot {
        require(obj.keys == timelineVersionSnapshotKeys)
        shape(obj.getValue("id"), quoted = false, nullable = false)
        shape(obj.getValue("operationLogId"), quoted = false, nullable = false)
        shape(obj.getValue("itemType"), quoted = true, nullable = false)
        shape(obj.getValue("itemId"), quoted = false, nullable = false)
        shape(obj.getValue("operationType"), quoted = true, nullable = false)
        shape(obj.getValue("encryptedChangesJson"), quoted = true, nullable = false)
        shape(obj.getValue("createdAt"), quoted = false, nullable = false)
        return TimelineVersionSnapshot(
            id = obj.getValue("id").jsonPrimitive.long,
            operationLogId = obj.getValue("operationLogId").jsonPrimitive.long,
            itemType = obj.getValue("itemType").jsonPrimitive.content,
            itemId = obj.getValue("itemId").jsonPrimitive.long,
            operationType = obj.getValue("operationType").jsonPrimitive.content,
            encryptedChangesJson = obj.getValue("encryptedChangesJson").jsonPrimitive.content,
            createdAt = obj.getValue("createdAt").jsonPrimitive.long,
        )
    }

    val operationLogKeys = setOf("id", "itemType", "itemId", "itemTitle", "operationType", "changesJson", "deviceId", "deviceName", "timestamp", "isReverted")
    fun encode(row: OperationLog): JsonObject = buildJsonObject {
        put("id", row.id)
        put("itemType", row.itemType)
        put("itemId", row.itemId)
        put("itemTitle", row.itemTitle)
        put("operationType", row.operationType)
        put("changesJson", row.changesJson)
        put("deviceId", row.deviceId)
        put("deviceName", row.deviceName)
        put("timestamp", row.timestamp)
        put("isReverted", row.isReverted)
    }
    fun decodeOperationLog(obj: JsonObject): OperationLog {
        require(obj.keys == operationLogKeys)
        shape(obj.getValue("id"), quoted = false, nullable = false)
        shape(obj.getValue("itemType"), quoted = true, nullable = false)
        shape(obj.getValue("itemId"), quoted = false, nullable = false)
        shape(obj.getValue("itemTitle"), quoted = true, nullable = false)
        shape(obj.getValue("operationType"), quoted = true, nullable = false)
        shape(obj.getValue("changesJson"), quoted = true, nullable = false)
        shape(obj.getValue("deviceId"), quoted = true, nullable = false)
        shape(obj.getValue("deviceName"), quoted = true, nullable = false)
        shape(obj.getValue("timestamp"), quoted = false, nullable = false)
        shape(obj.getValue("isReverted"), quoted = false, nullable = false)
        return OperationLog(
            id = obj.getValue("id").jsonPrimitive.long,
            itemType = obj.getValue("itemType").jsonPrimitive.content,
            itemId = obj.getValue("itemId").jsonPrimitive.long,
            itemTitle = obj.getValue("itemTitle").jsonPrimitive.content,
            operationType = obj.getValue("operationType").jsonPrimitive.content,
            changesJson = obj.getValue("changesJson").jsonPrimitive.content,
            deviceId = obj.getValue("deviceId").jsonPrimitive.content,
            deviceName = obj.getValue("deviceName").jsonPrimitive.content,
            timestamp = obj.getValue("timestamp").jsonPrimitive.long,
            isReverted = obj.getValue("isReverted").jsonPrimitive.boolean,
        )
    }

    val attachmentKeys = setOf("id", "parentPasswordId", "parentSecureItemId", "source", "fileName", "mimeType", "sizeBytes", "sha256Hex", "wrappedCek", "localPath", "bitwardenAttachmentId", "bitwardenUrl", "bitwardenFileKeyEnc", "keepassBinaryRef", "downloadState", "createdAt", "updatedAt", "isDeleted", "deletedAt")
    fun encode(row: Attachment): JsonObject = buildJsonObject {
        put("id", row.id)
        put("parentPasswordId", row.parentPasswordId)
        put("parentSecureItemId", row.parentSecureItemId)
        put("source", row.source)
        put("fileName", row.fileName)
        put("mimeType", row.mimeType)
        put("sizeBytes", row.sizeBytes)
        put("sha256Hex", row.sha256Hex)
        put("wrappedCek", row.wrappedCek)
        put("localPath", row.localPath)
        put("bitwardenAttachmentId", row.bitwardenAttachmentId)
        put("bitwardenUrl", row.bitwardenUrl)
        put("bitwardenFileKeyEnc", row.bitwardenFileKeyEnc)
        put("keepassBinaryRef", row.keepassBinaryRef)
        put("downloadState", row.downloadState)
        put("createdAt", row.createdAt)
        put("updatedAt", row.updatedAt)
        put("isDeleted", row.isDeleted)
        put("deletedAt", row.deletedAt)
    }
    fun decodeAttachment(obj: JsonObject): Attachment {
        require(obj.keys == attachmentKeys)
        shape(obj.getValue("id"), quoted = false, nullable = false)
        shape(obj.getValue("parentPasswordId"), quoted = false, nullable = true)
        shape(obj.getValue("parentSecureItemId"), quoted = false, nullable = true)
        shape(obj.getValue("source"), quoted = true, nullable = false)
        shape(obj.getValue("fileName"), quoted = true, nullable = false)
        shape(obj.getValue("mimeType"), quoted = true, nullable = false)
        shape(obj.getValue("sizeBytes"), quoted = false, nullable = false)
        shape(obj.getValue("sha256Hex"), quoted = true, nullable = true)
        shape(obj.getValue("wrappedCek"), quoted = true, nullable = true)
        shape(obj.getValue("localPath"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenAttachmentId"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenUrl"), quoted = true, nullable = true)
        shape(obj.getValue("bitwardenFileKeyEnc"), quoted = true, nullable = true)
        shape(obj.getValue("keepassBinaryRef"), quoted = true, nullable = true)
        shape(obj.getValue("downloadState"), quoted = true, nullable = false)
        shape(obj.getValue("createdAt"), quoted = false, nullable = false)
        shape(obj.getValue("updatedAt"), quoted = false, nullable = false)
        shape(obj.getValue("isDeleted"), quoted = false, nullable = false)
        shape(obj.getValue("deletedAt"), quoted = false, nullable = true)
        return Attachment(
            id = obj.getValue("id").jsonPrimitive.long,
            parentPasswordId = if (obj.getValue("parentPasswordId") == JsonNull) null else obj.getValue("parentPasswordId").jsonPrimitive.long,
            parentSecureItemId = if (obj.getValue("parentSecureItemId") == JsonNull) null else obj.getValue("parentSecureItemId").jsonPrimitive.long,
            source = obj.getValue("source").jsonPrimitive.content,
            fileName = obj.getValue("fileName").jsonPrimitive.content,
            mimeType = obj.getValue("mimeType").jsonPrimitive.content,
            sizeBytes = obj.getValue("sizeBytes").jsonPrimitive.long,
            sha256Hex = if (obj.getValue("sha256Hex") == JsonNull) null else obj.getValue("sha256Hex").jsonPrimitive.content,
            wrappedCek = if (obj.getValue("wrappedCek") == JsonNull) null else obj.getValue("wrappedCek").jsonPrimitive.content,
            localPath = if (obj.getValue("localPath") == JsonNull) null else obj.getValue("localPath").jsonPrimitive.content,
            bitwardenAttachmentId = if (obj.getValue("bitwardenAttachmentId") == JsonNull) null else obj.getValue("bitwardenAttachmentId").jsonPrimitive.content,
            bitwardenUrl = if (obj.getValue("bitwardenUrl") == JsonNull) null else obj.getValue("bitwardenUrl").jsonPrimitive.content,
            bitwardenFileKeyEnc = if (obj.getValue("bitwardenFileKeyEnc") == JsonNull) null else obj.getValue("bitwardenFileKeyEnc").jsonPrimitive.content,
            keepassBinaryRef = if (obj.getValue("keepassBinaryRef") == JsonNull) null else obj.getValue("keepassBinaryRef").jsonPrimitive.content,
            downloadState = obj.getValue("downloadState").jsonPrimitive.content,
            createdAt = obj.getValue("createdAt").jsonPrimitive.long,
            updatedAt = obj.getValue("updatedAt").jsonPrimitive.long,
            isDeleted = obj.getValue("isDeleted").jsonPrimitive.boolean,
            deletedAt = if (obj.getValue("deletedAt") == JsonNull) null else obj.getValue("deletedAt").jsonPrimitive.long,
        )
    }

    private fun shape(value: JsonElement, quoted: Boolean, nullable: Boolean) {
        require(if (value == JsonNull) nullable else value is JsonPrimitive && value.isString == quoted)
    }
}
