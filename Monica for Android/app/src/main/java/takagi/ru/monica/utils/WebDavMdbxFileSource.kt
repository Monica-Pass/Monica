package takagi.ru.monica.utils

import takagi.ru.monica.keepass.KeePassSourceChangedException

import takagi.ru.monica.R

import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.webdav.WebDavCredentials
import takagi.ru.monica.webdav.WebDavGateway
import takagi.ru.monica.webdav.WebDavErrorClassifier
import takagi.ru.monica.webdav.WebDavErrorKind
import takagi.ru.monica.webdav.WebDavConditionalWriter
import takagi.ru.monica.webdav.WebDavPreconditionException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class WebDavMdbxFileSource internal constructor(
    private val serverUrl: String,
    private val username: String,
    private val password: String,
    private val strings: StringResolver
) : MdbxFileSource {

    private val normalizedServerUrl = serverUrl.trim().trimEnd('/')
    private val credentials = WebDavCredentials(username, password)
    private val httpClient by lazy { WebDavGateway.buildHttpClient(credentials, normalizedServerUrl) }
    private val sardine: OkHttpSardine by lazy { OkHttpSardine(httpClient) }
    private val conditionalWriter by lazy { WebDavConditionalWriter(httpClient) }
    // One source lives for one sync transport. Segment/blob uploads share these parents.
    private val verifiedDirectories = mutableSetOf<String>()

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            sardine.list(normalizedServerUrl, 0)
        }.map { Unit }
    }

    override suspend fun listDirectory(path: String?): List<FileSourceEntry> =
        withContext(Dispatchers.IO) {
            val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(
                normalizedServerUrl, path
            )
            val resources: List<DavResource> = sardine.list(targetUrl)
            resources.filter { it.name != "." && it.name != ".." }.map { resource ->
                FileSourceEntry(
                    name = resource.name,
                    path = WebDavKeePassFileSource.buildChildPath(
                        WebDavKeePassFileSource.normalizeOptionalRemotePath(path), resource.name,
                        strings = strings,
                    ),
                    isDirectory = resource.isDirectory,
                    lastModified = resource.modified?.time,
                    sizeBytes = resource.contentLength
                )
            }.sortedWith(
                compareByDescending<FileSourceEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
        }

    override suspend fun createDirectory(
        parentPath: String?,
        name: String
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        val sanitizedName = name.trim().trim('/').ifBlank {
            throw IOException(strings.get(R.string.cloud_message_foldername_required))
        }
        val normalizedParent = WebDavKeePassFileSource.normalizeOptionalRemotePath(parentPath)
        val targetPath = if (normalizedParent.isBlank()) sanitizedName
        else "$normalizedParent/$sanitizedName"
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, targetPath)

        if (webDavPathExists(targetUrl)) {
            throw IOException(strings.get(R.string.cloud_message_folder_exists_path, sanitizedName))
        }

        sardine.createDirectory(targetUrl)
        FileSourceEntry(
            name = sanitizedName,
            path = targetPath,
            isDirectory = true
        )
    }

    override suspend fun createPlaceholderFile(
        parentPath: String?,
        name: String
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        writeFile(parentPath, name, ByteArray(0))
    }

    override suspend fun writeFile(
        parentPath: String?,
        name: String,
        bytes: ByteArray
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        val sanitizedName = name.trim().trim('/').ifBlank {
            throw IOException(strings.get(R.string.cloud_message_filename_required))
        }
        val normalizedParent = WebDavKeePassFileSource.normalizeOptionalRemotePath(parentPath)
        val targetPath = if (normalizedParent.isBlank()) sanitizedName
        else "$normalizedParent/$sanitizedName"
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, targetPath)

        if (webDavPathExists(targetUrl)) {
            throw IOException(strings.get(R.string.cloud_message_file_exists_path, sanitizedName))
        }

        // Auto-create intermediate directories
        if (normalizedParent.isNotBlank()) {
            ensureDirectoryPathExists(normalizedParent)
        }

        sardine.put(targetUrl, bytes, "application/octet-stream")
        FileSourceEntry(
            name = sanitizedName,
            path = targetPath,
            isDirectory = false,
            sizeBytes = bytes.size.toLong()
        )
    }

    override suspend fun readFile(path: String): ByteArray = withContext(Dispatchers.IO) {
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, path)
        sardine.get(targetUrl).use { input -> input.readBytes() }
    }

    /** File-to-file download used by MDBX2; avoids keeping a Blob in the JVM heap. */
    suspend fun readFileTo(path: String, destination: File) = withContext(Dispatchers.IO) {
        val normalizedPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(path)
        require(normalizedPath.isNotBlank()) { strings.get(R.string.cloud_message_path_required) }
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, normalizedPath)
        val parent = destination.parentFile ?: throw IOException(strings.get(R.string.cloud_message_download_directory_missing))
        check(parent.exists() || parent.mkdirs()) { strings.get(R.string.cloud_message_download_directory_create) }
        val temporary = File.createTempFile(".mdbx-download-", ".tmp", parent)
        try {
            sardine.get(targetUrl).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                if (!temporary.renameTo(destination)) {
                    throw IOException(strings.get(R.string.cloud_message_download_finalize, "WebDAV"))
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Returns metadata for an exact path, or null when the path is absent. */
    suspend fun statPath(path: String): FileSourceEntry? = withContext(Dispatchers.IO) {
        val normalizedPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(path)
        if (normalizedPath.isBlank()) return@withContext null
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, normalizedPath)
        val direct = try {
            sardine.list(targetUrl, 0)
        } catch (error: IOException) {
            if (isNotFound(error)) return@withContext null
            throw error
        }
        val directMatch = direct.firstOrNull { resource ->
            normalizeResourceUrl(resource.href?.toString())
                .equals(normalizeResourceUrl(targetUrl), ignoreCase = true)
        } ?: direct.firstOrNull { it.name.equals(normalizedPath.substringAfterLast('/'), ignoreCase = true) }
        val resource = directMatch ?: run {
            val parentPath = WebDavKeePassFileSource.parentPathOf(normalizedPath, strings = strings)
            val parentUrl = WebDavMdbxSourceUrl(normalizedServerUrl, parentPath)
            listOrEmptyWhenNotFound { sardine.list(parentUrl) }
                .firstOrNull { it.name.equals(normalizedPath.substringAfterLast('/'), ignoreCase = true) }
        } ?: return@withContext null
        FileSourceEntry(
            id = resource.href?.toString(),
            name = resource.name,
            path = normalizedPath,
            isDirectory = resource.isDirectory,
            // Only an ETag is a valid If-Match token. Timestamps and sizes are
            // useful metadata but cannot safely protect a conditional PUT.
            versionToken = resource.etag?.takeIf { it.isNotBlank() },
            lastModified = resource.modified?.time,
            sizeBytes = resource.contentLength
        )
    }

    /**
     * Uploads an immutable or conditionally replaced object from disk.
     * CREATE_ONLY treats an existing byte-identical object as success and
     * rejects a same-name/different-content collision.
     */
    suspend fun writeFileFrom(
        path: String,
        source: File,
        mode: MdbxRemoteWriteMode = MdbxRemoteWriteMode.CREATE_ONLY,
        expectedVersion: String? = null
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        MdbxRemoteSyncPaths.requireRegularFile(source)
        val normalizedPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(path)
        require(normalizedPath.isNotBlank()) { strings.get(R.string.cloud_message_filename_required) }
        val parentPath = WebDavKeePassFileSource.parentPathOf(normalizedPath, strings = strings)
        if (parentPath.isNotBlank()) ensureDirectoryPathExists(parentPath)
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, normalizedPath)
        val existing = statPath(normalizedPath)
        if (mode == MdbxRemoteWriteMode.CREATE_ONLY && existing != null) {
            if (isSameImmutableContent(normalizedPath, existing, source)) return@withContext existing
            throw IOException(strings.get(R.string.cloud_message_immutable_differs_path, normalizedPath))
        }
        if (mode == MdbxRemoteWriteMode.IF_MATCH) {
            val requiredVersion = expectedVersion?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("WebDAV conditional replacement requires an ETag")
            if (existing?.versionToken != requiredVersion) {
                throw KeePassSourceChangedException(strings.get(R.string.cloud_message_remote_changed))
            }
        }
        try {
            conditionalWriter.write(targetUrl, source, mode, expectedVersion)
        } catch (error: WebDavPreconditionException) {
            if (mode == MdbxRemoteWriteMode.CREATE_ONLY) {
                val raced = statPath(normalizedPath)
                if (raced != null && isSameImmutableContent(normalizedPath, raced, source)) {
                    return@withContext raced
                }
                throw IOException(strings.get(R.string.cloud_message_immutable_differs_path, normalizedPath), error)
            }
            throw IOException(strings.get(R.string.cloud_message_remote_changed), error)
        }
        statPath(normalizedPath) ?: FileSourceEntry(
            name = normalizedPath.substringAfterLast('/'),
            path = normalizedPath,
            isDirectory = false,
            sizeBytes = source.length()
        )
    }

    private suspend fun isSameImmutableContent(
        normalizedPath: String,
        existing: FileSourceEntry,
        source: File
    ): Boolean {
        if (existing.isDirectory) throw IOException(strings.get(R.string.cloud_message_remote_is_directory_path, normalizedPath))
        if (existing.sizeBytes != null && existing.sizeBytes != source.length()) return false
        val comparisonDirectory = source.parentFile
            ?: throw IOException("MDBX2 comparison source has no parent directory")
        val temporary = File.createTempFile(".mdbx-compare-", ".tmp", comparisonDirectory)
        return try {
            readFileTo(normalizedPath, temporary)
            temporary.length() == source.length() &&
                MdbxRemoteSyncPaths.sha256Hex(temporary) == MdbxRemoteSyncPaths.sha256Hex(source)
        } finally {
            temporary.delete()
        }
    }

    suspend fun ensureDirectoryPath(path: String) = withContext(Dispatchers.IO) {
        ensureDirectoryPathExists(WebDavKeePassFileSource.normalizeOptionalRemotePath(path))
    }

    suspend fun overwriteFile(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val normalizedPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(path)
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, normalizedPath)
        sardine.put(targetUrl, bytes, "application/octet-stream")
    }

    /**
     * Recursively create intermediate directories on WebDAV server.
     * Skips directories that already exist.
     */
    private fun ensureDirectoryPathExists(path: String) = synchronized(verifiedDirectories) {
        val segments = path.split("/").filter { it.isNotBlank() }
        var accumulatedPath = ""
        for (segment in segments) {
            accumulatedPath = if (accumulatedPath.isEmpty()) segment else "$accumulatedPath/$segment"
            if (accumulatedPath in verifiedDirectories) continue
            val dirUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, accumulatedPath)
            try {
                if (!directoryExists(dirUrl)) {
                    try {
                        sardine.createDirectory(dirUrl)
                    } catch (error: SardineException) {
                        // Another client may have created it after our 404. Only
                        // this collision warrants a probe; preserve service/auth errors.
                        if (error.statusCode != 405 || !directoryExists(dirUrl)) throw error
                    }
                }
                verifiedDirectories.add(accumulatedPath)
            } catch (error: IOException) {
                verifiedDirectories.clear()
                throw error
            }
        }
    }

    private fun directoryExists(url: String): Boolean {
        val resources = try {
            sardine.list(url, 0)
        } catch (error: IOException) {
            if (isNotFound(error)) return false
            throw error
        }
        if (resources.isEmpty() || resources.none { it.isDirectory }) {
            throw IOException("WebDAV directory path does not reference a collection")
        }
        return true
    }

    private fun normalizeResourceUrl(url: String?): String = url.orEmpty().trimEnd('/')

    private fun WebDavMdbxSourceUrl(server: String, path: String): String =
        WebDavKeePassFileSource.buildRemoteUrl(server, path).ifBlank { server }

    private fun webDavPathExists(targetUrl: String): Boolean {
        return try {
            sardine.list(targetUrl, 1)
            true
        } catch (error: Throwable) {
            if (isNotFound(error)) false else throw error
        }
    }

    private fun <T> listOrEmptyWhenNotFound(block: () -> List<T>): List<T> {
        return try {
            block()
        } catch (error: Throwable) {
            if (isNotFound(error)) emptyList() else throw error
        }
    }

    private fun isNotFound(error: Throwable): Boolean =
        WebDavErrorClassifier.classify(error).kind == WebDavErrorKind.NotFound
}
