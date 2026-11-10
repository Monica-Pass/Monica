package takagi.ru.monica.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import takagi.ru.monica.BuildConfig
import takagi.ru.monica.update.UpdateChannel

data class UpdateCheckResult(
    val currentVersion: String,
    val latestVersion: String,
    val releaseName: String?,
    val releaseUrl: String,
    val apkAssetName: String?,
    val apkDownloadUrl: String?,
    val releaseNotes: String?,
    val isUpdateAvailable: Boolean,
    val releaseIdentity: String = latestVersion,
    val isFdroid: Boolean = false,
    val detailsUrl: String = releaseUrl,
)

data class UpdateDownloadProgress(val bytesRead: Long, val totalBytes: Long) {
    val hasTotal: Boolean get() = totalBytes > 0L
    val fraction: Float get() = if (hasTotal) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

object UpdateChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    suspend fun checkLatestRelease(
        currentVersion: String,
        channel: UpdateChannel = UpdateChannel.defaultForApplicationId(BuildConfig.APPLICATION_ID),
        currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): Result<UpdateCheckResult> = UpdateReleaseClient(client).check(currentVersion, channel, currentVersionCode, supportedAbis)

    suspend fun downloadApk(
        downloadUrl: String,
        outputDir: File,
        outputName: String,
        onProgress: suspend (UpdateDownloadProgress) -> Unit = {},
    ): Result<File> = downloadUpdateApk(downloadClient, downloadUrl, outputDir, outputName, onProgress)

    fun validateDownloadedApk(context: Context, apkFile: File): Result<Unit> =
        runCatching {
            val packageManager = context.packageManager
            val downloadedPackage = packageManager.getArchivePackageInfo(apkFile)
                ?: throw IOException("Downloaded APK package info is unreadable")
            val installedPackage = packageManager.getInstalledPackageInfo(context.packageName)

            if (downloadedPackage.packageName != context.packageName) {
                throw IOException("Downloaded APK package name does not match Monica")
            }

            val downloadedDigests = downloadedPackage.signingCertificateDigests()
            val installedDigests = installedPackage.signingCertificateDigests()
            if (downloadedDigests.isEmpty() || installedDigests.isEmpty()) {
                throw IOException("Downloaded APK signing certificate is unreadable")
            }

            val hasMatchingSigner = downloadedDigests.any { downloaded ->
                installedDigests.any { installed -> downloaded.contentEquals(installed) }
            }
            if (!hasMatchingSigner) {
                throw IOException("Downloaded APK signature does not match installed Monica")
            }
        }

    /** Monica's established comparison ignores build suffixes and letter patch labels. */
    fun compareVersionTags(candidate: String, current: String): Int {
        val candidateParts = versionParts(candidate) ?: throw IllegalArgumentException("Invalid candidate version")
        val currentParts = versionParts(current) ?: throw IllegalArgumentException("Invalid current version")
        for (index in 0..2) {
            val comparison = candidateParts[index].compareTo(currentParts[index])
            if (comparison != 0) return comparison
        }
        return 0
    }

    internal fun versionParts(value: String): List<Long>? {
        val match = Regex("""(?i)^v?(\d+)\.(\d+)\.(\d+)(?:[a-z][a-z0-9]*|[-+][a-z0-9][a-z0-9.+-]*)?$""")
            .matchEntire(value.trim()) ?: return null
        val parts = match.groupValues.drop(1).map { it.toLongOrNull() }
        return if (parts.all { it != null }) parts.filterNotNull() else null
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getArchivePackageInfo(apkFile: File): PackageInfo? =
        getPackageArchiveInfo(apkFile.absolutePath, signatureQueryFlags())

    @Suppress("DEPRECATION")
    private fun PackageManager.getInstalledPackageInfo(packageName: String): PackageInfo =
        getPackageInfo(packageName, signatureQueryFlags())

    private fun signatureQueryFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun PackageInfo.signingCertificateDigests(): List<ByteArray> =
        signaturesForVerification().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        }

    private fun PackageInfo.signaturesForVerification(): Array<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo ?: return emptyArray()
            if (info.hasMultipleSigners()) {
                info.apkContentsSigners ?: emptyArray()
            } else {
                info.signingCertificateHistory ?: info.apkContentsSigners ?: emptyArray()
            }
        } else {
            @Suppress("DEPRECATION")
            signatures ?: emptyArray()
        }
}

internal data class UpdateEndpoints(
    val standardRelease: String = "https://api.github.com/repos/Monica-Pass/Monica/releases/latest",
    val fdroidPackage: String = "https://f-droid.org/api/v1/packages/takagi.ru.monica.fdroid",
    val fdroidReleaseTags: String = "https://api.github.com/repos/Monica-Pass/Monica-Fdroid/releases/tags/",
)

/** Endpoints are injectable for local transport tests; production defaults are fixed HTTPS. */
internal class UpdateReleaseClient(
    private val client: OkHttpClient,
    private val endpoints: UpdateEndpoints = UpdateEndpoints(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(currentVersion: String, channel: UpdateChannel, currentVersionCode: Long,
                      supportedAbis: List<String>): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        updateResult {
            when (channel) {
                UpdateChannel.STANDARD -> standard(currentVersion, supportedAbis)
                UpdateChannel.FDROID -> fdroid(currentVersion, currentVersionCode)
            }
        }
    }

    private suspend fun standard(currentVersion: String, supportedAbis: List<String>): UpdateCheckResult {
        val release = json.decodeFromString(GitHubRelease.serializer(), readJson(endpoints.standardRelease))
        val tag = release.tagName.trim()
        require(!release.draft && !release.prerelease) { "Expected a stable published release" }
        require(tag.length <= 128) { "Invalid release version" }
        val comparison = UpdateChecker.compareVersionTags(tag, currentVersion)
        val releaseUrl = requireHttpsUpdateUrl(release.htmlUrl)
        val asset = release.assets.matchingApk(supportedAbis)
        return UpdateCheckResult(currentVersion, tag, release.name?.takeIf { it.isNotBlank() }, releaseUrl,
            asset?.name, asset?.downloadUrl, release.body?.takeIf { it.isNotBlank() }, comparison > 0,
            releaseIdentity = "standard:$tag")
    }

    private suspend fun fdroid(currentVersion: String, currentVersionCode: Long): UpdateCheckResult {
        val metadata = json.decodeFromString(FdroidPackage.serializer(), readJson(endpoints.fdroidPackage))
        require(metadata.packageName == "takagi.ru.monica.fdroid" && metadata.suggestedVersionCode > 0 && currentVersionCode > 0) {
            "Invalid F-Droid package metadata"
        }
        val latest = metadata.packages.singleOrNull { it.versionCode == metadata.suggestedVersionCode }
            ?: throw IOException("F-Droid suggested version is unavailable")
        require(UpdateChecker.versionParts(latest.versionName) != null && latest.versionName.length <= 128) {
            "Invalid F-Droid version"
        }
        val available = metadata.suggestedVersionCode > currentVersionCode
        val tag = "v" + latest.versionName.removePrefix("v").removePrefix("V")
        val details = "https://github.com/Monica-Pass/Monica-Fdroid/releases/tag/$tag"
        // Official availability is authoritative. Release notes are optional and must
        // match this exact version, never the newest source release awaiting publication.
        val release = if (available) updateResult {
            val tagUrl = requireNotNull(endpoints.fdroidReleaseTags.toHttpUrlOrNull()).newBuilder().addPathSegment(tag).build()
            json.decodeFromString(GitHubRelease.serializer(), readJson(tagUrl.toString())).also {
                require(it.tagName == tag && !it.draft && !it.prerelease) { "Mismatched F-Droid release notes" }
            }
        }.getOrNull() else null
        return UpdateCheckResult(currentVersion, latest.versionName, release?.name,
            FDROID_APP_URL, null, null, release?.body?.takeIf { it.isNotBlank() }, available,
            releaseIdentity = "fdroid:${latest.versionCode}", isFdroid = true, detailsUrl = details)
    }

    private suspend fun readJson(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Monica-Android").build()
        return withUpdateCall(client.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("Update request failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Update response is empty")
            if (body.contentLength() > MAX_UPDATE_METADATA_BYTES) throw IOException("Update response is too large")
            val collected = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (collected.size() + count > MAX_UPDATE_METADATA_BYTES) throw IOException("Update response is too large")
                    collected.write(buffer, 0, count)
                }
            }
            collected.toByteArray().toString(Charsets.UTF_8).also {
                if (it.isBlank()) throw IOException("Update response is empty")
            }
        }
    }
}

internal const val MAX_UPDATE_METADATA_BYTES = 1024 * 1024
private const val MAX_UPDATE_APK_BYTES = 512L * 1024L * 1024L
private const val FDROID_APP_URL = "https://f-droid.org/packages/takagi.ru.monica.fdroid/"

internal fun requireHttpsUpdateUrl(value: String): String {
    val url = value.toHttpUrlOrNull()
    require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "Invalid update link" }
    return url.toString()
}

private suspend fun <T> updateResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    currentCoroutineContext().ensureActive()
    Result.failure(failure)
}

/** Keeps cancellation attached until the response stream has been fully consumed. */
private suspend fun <T> withUpdateCall(call: Call, consume: suspend (Response) -> T): T = coroutineScope {
    val cancelCall = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, resource, _ -> resource.close() }
                }
            })
        }
        response.use { currentCoroutineContext().ensureActive(); consume(it) }
    } finally { cancelCall.cancel() }
}

internal suspend fun downloadUpdateApk(
    downloadClient: OkHttpClient,
    downloadUrl: String,
    outputDir: File,
    outputName: String,
    onProgress: suspend (UpdateDownloadProgress) -> Unit = {},
): Result<File> {
    var outputFile: File? = null
    return try {
        withContext(Dispatchers.IO) { updateResult {
            val validatedUrl = requireHttpsUpdateUrl(downloadUrl)
            if (!outputDir.isDirectory && !outputDir.mkdirs()) throw IOException("Unable to create update directory")
            val safeName = outputName.ifBlank { "Monica-update.apk" }
                .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").take(180)
                .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
            // A canceled request can still be closing its stream when a new request
            // starts. Unique files keep its cleanup from deleting the new download.
            val file = File.createTempFile("monica-update-", "-$safeName", outputDir)
            outputFile = file
            val request = Request.Builder().url(validatedUrl).header("Accept", "application/octet-stream")
                .header("User-Agent", "Monica-Android").build()
            withUpdateCall(downloadClient.newCall(request)) { response ->
                if (!response.isSuccessful) throw IOException("APK download failed: HTTP ${response.code}")
                val body = response.body ?: throw IOException("APK download response is empty")
                val totalBytes = body.contentLength()
                if (totalBytes > MAX_UPDATE_APK_BYTES) throw IOException("APK download is too large")
                file.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead = 0L
                        var lastProgressAt = 0L
                        onProgress(UpdateDownloadProgress(bytesRead, totalBytes))
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (bytesRead + read > MAX_UPDATE_APK_BYTES) throw IOException("APK download is too large")
                            output.write(buffer, 0, read)
                            bytesRead += read
                            val now = System.nanoTime()
                            if ((totalBytes > 0L && bytesRead >= totalBytes) || now - lastProgressAt >= 250_000_000L) {
                                lastProgressAt = now
                                onProgress(UpdateDownloadProgress(bytesRead, totalBytes))
                            }
                        }
                        if (totalBytes >= 0L && bytesRead != totalBytes) throw IOException("APK download is incomplete")
                        onProgress(UpdateDownloadProgress(bytesRead, totalBytes))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (file.length() <= 0L) throw IOException("Downloaded APK is empty")
            file
        }.also { if (it.isFailure) outputFile?.delete() } }
    } catch (cancelled: CancellationException) {
        outputFile?.delete()
        throw cancelled
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("content_type") val contentType: String? = null,
)

private fun List<GitHubReleaseAsset>.matchingApk(supportedAbis: List<String>): GitHubReleaseAsset? {
    val valid = filter { it.name.endsWith(".apk", ignoreCase = true) && runCatching { requireHttpsUpdateUrl(it.downloadUrl) }.isSuccess }
    val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    fun hasTag(name: String, tag: String): Boolean = Regex("(?i)(?:^|[^a-z0-9])${Regex.escape(tag)}(?:[^a-z0-9]|$)").containsMatchIn(name)
    for (abi in supportedAbis.filter { it in knownAbis }) {
        valid.firstOrNull { asset ->
            hasTag(asset.name, abi) && !(abi == "x86" && hasTag(asset.name, "x86_64"))
        }?.let { return it }
    }
    return valid.firstOrNull { asset -> hasTag(asset.name, "universal") && knownAbis.none { hasTag(asset.name, it) } }
}

@Serializable
private data class FdroidPackage(
    val packageName: String,
    val suggestedVersionCode: Long,
    val packages: List<FdroidVersion>,
)

@Serializable
private data class FdroidVersion(val versionName: String, val versionCode: Long)
