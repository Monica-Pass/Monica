package takagi.ru.monica.utils

import android.content.Context
import com.thegrizzlylabs.sardineandroid.DavResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.KeepassRemoteSource
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.webdav.WebDavCredentials
import takagi.ru.monica.webdav.WebDavGateway
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

data class KeePassLocalMirrorPaths(
    val workingCopyPath: String,
    val cacheCopyPath: String
)

class WebDavKeePassFileSource(
    private val serverUrl: String,
    private val username: String,
    private val password: String,
    private val remotePath: String? = null
) : KeePassFileSource {
    private val normalizedServerUrl = serverUrl.trim().trimEnd('/')
    private val normalizedRemotePath = normalizeOptionalRemotePath(remotePath)
    private val remoteUrl = buildRemoteUrl(normalizedServerUrl, normalizedRemotePath)
    private val sardine by lazy {
        // 走统一的 Gateway：除了预置式 Basic 鉴权、速率限制与超时，
        // 这里还需要继承用户选择的 HTTPS 证书校验档位。
        // 历史实现直接裸构造 sardine 并调用 setCredentials，会绕过全部这些配置。
        WebDavGateway.buildClient(WebDavCredentials(username.trim(), password))
    }

    override suspend fun stat(): FileSourceStat = withContext(Dispatchers.IO) {
        requireRemotePath()
        val resource = resolveResource(remoteUrl)
        if (resource != null) {
            val etag = resource.etag?.takeIf { it.isNotBlank() }
            return@withContext FileSourceStat(
                versionToken = etag ?: resource.modified?.time?.toString() ?: resource.contentLength?.toString(),
                etag = etag,
                lastModified = resource.modified?.time,
                sizeBytes = resource.contentLength,
                isDirectory = resource.isDirectory,
                displayName = resource.name
            )
        }

        val exists = webDavPathExists(remoteUrl)
        if (!exists) {
            throw IOException("远端文件不存在: $normalizedRemotePath")
        }

        FileSourceStat(
            versionToken = null,
            etag = null,
            lastModified = null,
            sizeBytes = null,
            isDirectory = false,
            displayName = normalizedRemotePath.substringAfterLast('/')
        )
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        requireRemotePath()
        if (!webDavPathExists(remoteUrl)) {
            throw IOException("远端文件不存在: $normalizedRemotePath")
        }
        sardine.get(remoteUrl).use { input ->
            input.readBytes()
        }
    }

    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?
    ): FileSourceWriteResult = withContext(Dispatchers.IO) {
        requireRemotePath()
        val parentUrl = buildRemoteUrl(normalizedServerUrl, parentPathOf(normalizedRemotePath))
        if (parentUrl.isNotBlank() && !webDavPathExists(parentUrl)) {
            throw IOException("远端目录不存在: ${parentPathOf(normalizedRemotePath)}")
        }

        if (!expectedVersion.isNullOrBlank()) {
            val current = runCatching { stat() }.getOrNull()
            if (current != null && !current.matchesExpectedVersion(expectedVersion)) {
                throw IOException("远端文件已变化，请先重新同步")
            }
        }

        sardine.put(remoteUrl, bytes, KEEPASS_KDBX_MIME_TYPE)
        val latest = runCatching { stat() }.getOrDefault(FileSourceStat())
        FileSourceWriteResult(
            versionToken = latest.versionToken,
            etag = latest.etag,
            lastModified = latest.lastModified
        )
    }

    override suspend fun listChildren(): List<FileSourceEntry> = withContext(Dispatchers.IO) {
        listDirectory(
            if (normalizedRemotePath.isBlank()) {
                ""
            } else {
                val stat = runCatching { stat() }.getOrNull()
                if (stat?.isDirectory == true) normalizedRemotePath else parentPathOf(normalizedRemotePath)
            }
        )
    }

    override suspend fun createFile(name: String): FileSourceEntry = withContext(Dispatchers.IO) {
        val targetPath = buildChildPath(parentPathOf(normalizedRemotePath), name)
        createFileInDirectory(parentPathOf(targetPath), name)
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = when {
                normalizedRemotePath.isBlank() -> ""
                runCatching { stat() }.getOrNull()?.isDirectory == true -> normalizedRemotePath
                else -> parentPathOf(normalizedRemotePath)
            }
            val targetUrl = buildRemoteUrl(normalizedServerUrl, targetDirectory).ifBlank { normalizedServerUrl }
            if (!webDavPathExists(targetUrl)) {
                throw IOException("无法访问 WebDAV 路径: $targetUrl")
            }
            Unit
        }
    }

    suspend fun listDirectory(directoryPath: String? = null): List<FileSourceEntry> = withContext(Dispatchers.IO) {
        val normalizedDirectoryPath = normalizeOptionalRemotePath(directoryPath)
        val targetUrl = buildRemoteUrl(normalizedServerUrl, normalizedDirectoryPath).ifBlank { normalizedServerUrl }
        if (!webDavPathExists(targetUrl)) {
            throw IOException(
                if (normalizedDirectoryPath.isBlank()) {
                    "无法访问 WebDAV 根目录"
                } else {
                    "远端目录不存在: $normalizedDirectoryPath"
                }
            )
        }
        sardine.list(targetUrl)
            .filterNot { resource ->
                normalizeResourceUrl(resource.href?.toString())
                    .equals(normalizeResourceUrl(targetUrl), ignoreCase = true)
            }
            .map { resource ->
                FileSourceEntry(
                    id = resource.href?.toString(),
                    name = resource.name,
                    path = buildChildPath(normalizedDirectoryPath, resource.name),
                    isDirectory = resource.isDirectory,
                    versionToken = resource.etag?.takeIf { it.isNotBlank() }
                        ?: resource.modified?.time?.toString()
                        ?: resource.contentLength?.toString(),
                    lastModified = resource.modified?.time,
                    sizeBytes = resource.contentLength
                )
            }
            .sortedWith(
                compareBy<FileSourceEntry> { !it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
    }

    suspend fun createDirectory(parentPath: String?, name: String): FileSourceEntry = withContext(Dispatchers.IO) {
        val normalizedParentPath = normalizeOptionalRemotePath(parentPath)
        val targetPath = buildChildPath(normalizedParentPath, name)
        val targetUrl = buildRemoteUrl(normalizedServerUrl, targetPath)
        if (webDavPathExists(targetUrl)) {
            throw IOException("同名目录已存在")
        }
        sardine.createDirectory(targetUrl)
        FileSourceEntry(
            id = targetUrl,
            name = name.trim(),
            path = targetPath,
            isDirectory = true
        )
    }

    suspend fun createFileInDirectory(
        parentPath: String?,
        name: String,
        bytes: ByteArray = ByteArray(0)
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        val normalizedParentPath = normalizeOptionalRemotePath(parentPath)
        val targetPath = buildChildPath(normalizedParentPath, name)
        val targetUrl = buildRemoteUrl(normalizedServerUrl, targetPath)
        val parentUrl = buildRemoteUrl(normalizedServerUrl, normalizedParentPath)
        if (parentUrl.isNotBlank() && !webDavPathExists(parentUrl)) {
            throw IOException(
                if (normalizedParentPath.isBlank()) {
                    "远端目录不存在"
                } else {
                    "远端目录不存在: $normalizedParentPath"
                }
            )
        }
        if (webDavPathExists(targetUrl)) {
            throw IOException("同名文件已存在")
        }
        sardine.put(targetUrl, bytes, KEEPASS_KDBX_MIME_TYPE)
        val latest = runCatching { resolveResource(targetUrl) }.getOrNull()
        FileSourceEntry(
            id = latest?.href?.toString() ?: targetUrl,
            name = latest?.name ?: name.trim(),
            path = targetPath,
            isDirectory = false,
            versionToken = latest?.etag?.takeIf { it.isNotBlank() }
                ?: latest?.modified?.time?.toString()
                ?: latest?.contentLength?.toString(),
            lastModified = latest?.modified?.time,
            sizeBytes = latest?.contentLength?.takeIf { it >= 0L } ?: bytes.size.toLong()
        )
    }

    private fun resolveResource(targetUrl: String): DavResource? {
        val directResources = runCatching { sardine.list(targetUrl) }.getOrNull().orEmpty()
        directResources.firstOrNull { resource ->
            normalizeResourceUrl(resource.href?.toString()).equals(
                normalizeResourceUrl(targetUrl),
                ignoreCase = true
            )
        }?.let { return it }
        directResources.firstOrNull()?.let { return it }

        val parentUrl = buildRemoteUrl(normalizedServerUrl, parentPathOf(normalizedRemotePath))
        if (parentUrl.isBlank()) return null
        val fileName = normalizedRemotePath.substringAfterLast('/')
        return runCatching { sardine.list(parentUrl) }
            .getOrNull()
            .orEmpty()
            .firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
    }

    private fun normalizeResourceUrl(url: String?): String {
        return url.orEmpty().trimEnd('/')
    }

    private fun webDavPathExists(targetUrl: String): Boolean {
        runCatching { sardine.exists(targetUrl) }
            .onSuccess { return it }
        return runCatching { sardine.list(targetUrl) }
            .map { true }
            .getOrElse { false }
    }

    private fun requireRemotePath() {
        if (normalizedRemotePath.isBlank()) {
            throw IllegalStateException("未指定远端文件路径")
        }
    }

    private fun FileSourceStat.matchesExpectedVersion(expectedVersion: String): Boolean {
        val expected = expectedVersion.trim()
        return expected.isBlank() ||
            expected == etag ||
            expected == versionToken ||
            expected == lastModified?.toString() ||
            expected == sizeBytes?.toString()
    }

    companion object {
        fun normalizeRemotePath(remotePath: String): String {
            val normalized = remotePath
                .trim()
                .replace('\\', '/')
                .trimStart('/')
                .replace(Regex("/+"), "/")
            if (normalized.isBlank()) {
                throw IllegalArgumentException("远端文件路径不能为空")
            }
            return normalized
        }

        fun normalizeOptionalRemotePath(remotePath: String?): String {
            val normalized = remotePath
                ?.trim()
                ?.replace('\\', '/')
                ?.trim('/')
                ?.replace(Regex("/+"), "/")
                .orEmpty()
            return normalized
        }

        fun buildRemoteUrl(serverUrl: String, remotePath: String?): String {
            val normalizedServerUrl = serverUrl.trim().trimEnd('/')
            val normalizedPath = remotePath
                ?.trim()
                ?.replace('\\', '/')
                ?.trim('/')
                .orEmpty()
            return if (normalizedPath.isBlank()) {
                normalizedServerUrl
            } else {
                "$normalizedServerUrl/$normalizedPath"
            }
        }

        fun parentPathOf(remotePath: String): String {
            if (remotePath.isBlank()) {
                return ""
            }
            val normalized = normalizeRemotePath(remotePath)
            val index = normalized.lastIndexOf('/')
            return if (index <= 0) "" else normalized.substring(0, index)
        }

        fun buildChildPath(parentPath: String, name: String): String {
            val sanitizedName = name.trim().trim('/').ifBlank {
                throw IllegalArgumentException("文件名不能为空")
            }
            require('/' !in sanitizedName) { "文件名不能包含路径分隔符" }
            return if (parentPath.isBlank()) sanitizedName else "$parentPath/$sanitizedName"
        }

    }
}

object WebDavKeePassSupport {
    fun createFileSource(
        source: KeepassRemoteSource,
        securityManager: SecurityManager
    ): WebDavKeePassFileSource {
        require(source.baseUrl?.isNotBlank() == true) { "WebDAV 基础地址不能为空" }
        val username = source.usernameEncrypted?.let { securityManager.decryptData(it) }.orEmpty()
        val password = source.passwordEncrypted?.let { securityManager.decryptData(it) }.orEmpty()
        return WebDavKeePassFileSource(
            serverUrl = source.baseUrl,
            username = username,
            password = password,
            remotePath = source.remotePath
        )
    }

    fun buildLocalMirrorPaths(sourceId: Long, remotePath: String): KeePassLocalMirrorPaths {
        val fileName = displayNameFromRemotePath(remotePath)
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "remote.kdbx" }
        val baseDir = "keepass_remote/webdav_$sourceId"
        return KeePassLocalMirrorPaths(
            workingCopyPath = "$baseDir/working_$fileName",
            cacheCopyPath = "$baseDir/cache_$fileName"
        )
    }

    fun displayNameFromRemotePath(remotePath: String): String {
        val normalized = WebDavKeePassFileSource.normalizeRemotePath(remotePath)
        return normalized.substringAfterLast('/').ifBlank { "remote.kdbx" }
    }

    fun writeRelativeFile(
        context: Context,
        relativePath: String,
        bytes: ByteArray
    ) {
        val file = File(context.filesDir, relativePath)
        val parent = file.parentFile ?: throw IOException("无效的文件路径")
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(parent, "${file.name}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            throw IOException("无法替换本地工作副本")
        }
        if (!tempFile.renameTo(file)) {
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            tempFile.delete()
        }
    }

    fun deleteRelativeFile(context: Context, relativePath: String?) {
        if (relativePath.isNullOrBlank()) return
        val file = File(context.filesDir, relativePath)
        if (file.exists()) {
            file.delete()
        }
        file.parentFile?.takeIf { it.exists() && it.listFiles().isNullOrEmpty() }?.delete()
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
    }
}
