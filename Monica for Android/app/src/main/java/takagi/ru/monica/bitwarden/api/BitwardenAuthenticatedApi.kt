package takagi.ru.monica.bitwarden.api

import retrofit2.Response

/** Explicit allowlist of authenticated API calls; anonymous and signed transfers are not wrapped. */
internal class BitwardenAuthenticatedApi(
    private val delegate: BitwardenVaultApi,
    private val session: BitwardenAuthenticatedSession,
) : BitwardenVaultApi by delegate {
    override suspend fun profileDocument(authorization: String): Response<kotlinx.serialization.json.JsonObject> =
        session.execute(authorization) { delegate.profileDocument(it) }

    override suspend fun getAccountDevices(
        authorization: String,
    ): Response<BitwardenAccountDevicesResponse> = session.execute(authorization) { delegate.getAccountDevices(it) }

    override suspend fun profile(
        authorization: String,
    ): Response<ProfileResponse> = session.execute(authorization) { delegate.profile(it) }

    override suspend fun sync(
        authorization: String,
        excludeDomains: Boolean,
    ): Response<SyncResponse> = session.execute(authorization) { delegate.sync(it, excludeDomains) }

    override suspend fun syncSource(
        authorization: String,
        excludeDomains: Boolean,
    ): Response<okhttp3.ResponseBody> = session.execute(authorization) { delegate.syncSource(it, excludeDomains) }

    override suspend fun getCipherSource(
        authorization: String,
        cipherId: String,
    ): Response<okhttp3.ResponseBody> = session.execute(authorization) { delegate.getCipherSource(it, cipherId) }

    override suspend fun updateCipherSource(
        authorization: String,
        cipherId: String,
        cipher: kotlinx.serialization.json.JsonObject,
    ): Response<okhttp3.ResponseBody> = session.execute(authorization) { delegate.updateCipherSource(it, cipherId, cipher) }

    override suspend fun getCipher(
        authorization: String,
        cipherId: String,
    ): Response<CipherApiResponse> = session.execute(authorization) { delegate.getCipher(it, cipherId) }

    override suspend fun getCipherDocument(
        authorization: String,
        cipherId: String,
    ): Response<kotlinx.serialization.json.JsonObject> = session.execute(authorization) { delegate.getCipherDocument(it, cipherId) }

    override suspend fun updateCipherDocument(
        authorization: String,
        cipherId: String,
        cipher: kotlinx.serialization.json.JsonObject,
    ): Response<kotlinx.serialization.json.JsonObject> = session.execute(authorization) { delegate.updateCipherDocument(it, cipherId, cipher) }

    override suspend fun createCipher(
        authorization: String,
        cipher: CipherCreateRequest,
    ): Response<CipherApiResponse> = session.execute(authorization) { delegate.createCipher(it, cipher) }

    override suspend fun createCipherDocument(
        authorization: String,
        cipher: kotlinx.serialization.json.JsonObject,
    ): Response<kotlinx.serialization.json.JsonObject> = session.execute(authorization) { delegate.createCipherDocument(it, cipher) }

    override suspend fun updateCipher(
        authorization: String,
        cipherId: String,
        cipher: CipherUpdateRequest,
    ): Response<CipherApiResponse> = session.execute(authorization) { delegate.updateCipher(it, cipherId, cipher) }

    override suspend fun deleteCipher(
        authorization: String,
        cipherId: String,
    ): Response<Unit> = session.execute(authorization) { delegate.deleteCipher(it, cipherId) }

    override suspend fun permanentDeleteCipher(
        authorization: String,
        cipherId: String,
    ): Response<Unit> = session.execute(authorization) { delegate.permanentDeleteCipher(it, cipherId) }

    override suspend fun restoreCipher(
        authorization: String,
        cipherId: String,
    ): Response<CipherApiResponse> = session.execute(authorization) { delegate.restoreCipher(it, cipherId) }

    override suspend fun getFolders(
        authorization: String,
    ): Response<FoldersResponse> = session.execute(authorization) { delegate.getFolders(it) }

    override suspend fun createFolder(
        authorization: String,
        folder: FolderCreateRequest,
    ): Response<FolderApiResponse> = session.execute(authorization) { delegate.createFolder(it, folder) }

    override suspend fun updateFolder(
        authorization: String,
        folderId: String,
        folder: FolderUpdateRequest,
    ): Response<FolderApiResponse> = session.execute(authorization) { delegate.updateFolder(it, folderId, folder) }

    override suspend fun deleteFolder(
        authorization: String,
        folderId: String,
    ): Response<Unit> = session.execute(authorization) { delegate.deleteFolder(it, folderId) }

    override suspend fun createAttachmentUploadUrl(
        authorization: String,
        cipherId: String,
        request: AttachmentUploadRequest,
    ): Response<AttachmentUploadResponse> = session.execute(authorization) { delegate.createAttachmentUploadUrl(it, cipherId, request) }

    override suspend fun uploadAttachmentDirect(
        authorization: String,
        cipherId: String,
        attachmentId: String,
        key: okhttp3.MultipartBody.Part,
        data: okhttp3.MultipartBody.Part,
    ): Response<Unit> = session.execute(authorization, replayable = !key.body.isOneShot() && !data.body.isOneShot()) { delegate.uploadAttachmentDirect(it, cipherId, attachmentId, key, data) }

    override suspend fun getAttachmentDownload(
        authorization: String,
        cipherId: String,
        attachmentId: String,
    ): Response<AttachmentDownloadInfo> = session.execute(authorization) { delegate.getAttachmentDownload(it, cipherId, attachmentId) }

    override suspend fun deleteAttachment(
        authorization: String,
        cipherId: String,
        attachmentId: String,
    ): Response<Unit> = session.execute(authorization) { delegate.deleteAttachment(it, cipherId, attachmentId) }

    override suspend fun createSend(
        authorization: String,
        send: SendCreateRequest,
    ): Response<SendApiResponse> = session.execute(authorization) { delegate.createSend(it, send) }

    override suspend fun createFileSend(
        authorization: String,
        send: SendCreateRequest,
    ): Response<SendFileUploadDataResponse> = session.execute(authorization) { delegate.createFileSend(it, send) }

    override suspend fun uploadSendFileDirect(
        authorization: String,
        sendId: String,
        fileId: String,
        data: okhttp3.MultipartBody.Part,
    ): Response<Unit> = session.execute(authorization, replayable = !data.body.isOneShot()) { delegate.uploadSendFileDirect(it, sendId, fileId, data) }

    override suspend fun getSend(
        authorization: String,
        sendId: String,
    ): Response<SendApiResponse> = session.execute(authorization) { delegate.getSend(it, sendId) }

    override suspend fun updateSend(
        authorization: String,
        sendId: String,
        send: SendCreateRequest,
    ): Response<SendApiResponse> = session.execute(authorization) { delegate.updateSend(it, sendId, send) }

    override suspend fun getSendDocument(
        authorization: String,
        sendId: String,
    ): Response<kotlinx.serialization.json.JsonObject> = session.execute(authorization) { delegate.getSendDocument(it, sendId) }

    override suspend fun updateSendDocument(
        authorization: String,
        sendId: String,
        send: kotlinx.serialization.json.JsonObject,
    ): Response<kotlinx.serialization.json.JsonObject> = session.execute(authorization) { delegate.updateSendDocument(it, sendId, send) }

    override suspend fun removeSendPassword(
        authorization: String,
        sendId: String,
    ): Response<SendApiResponse> = session.execute(authorization) { delegate.removeSendPassword(it, sendId) }

    override suspend fun deleteSend(
        authorization: String,
        sendId: String,
    ): Response<Unit> = session.execute(authorization) { delegate.deleteSend(it, sendId) }
}
