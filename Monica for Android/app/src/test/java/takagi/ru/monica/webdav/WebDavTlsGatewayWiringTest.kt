package takagi.ru.monica.webdav

import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级守卫：确保 TLS 档位真的被接到了每一个 WebDAV 出口上。
 *
 * 这类断言比行为测试脆弱，但这里是刻意的：
 * [WebDavGateway] 的完整客户端依赖 `android.util.Base64` 与 `BuildConfig`，
 * 在 JVM 单元测试里无法实例化；而「有人新增了一处绕过 Gateway 的
 * `OkHttpSardine()`」正是本功能最容易被悄悄破坏的方式。
 * 与 [takagi.ru.monica.utils.WebDavSecurityStorageGuardTest] 采用同一模式。
 */
class WebDavTlsGatewayWiringTest {

    @Test
    fun gateway_appliesTlsModeAndDefaultsToUserSetting() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/webdav/WebDavGateway.kt")

        assertTrue(source.contains("WebDavTlsSupport.configure(this, tlsMode)"))
        assertTrue(source.contains("tlsMode: WebDavTlsMode = WebDavTlsSettings.currentMode()"))
    }

    @Test
    fun systemDefaultMode_writesNoTlsConfigurationAtAll() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/webdav/WebDavTlsSupport.kt")

        // 默认档必须是一个空分支：任何手工 SSLContext 都会绕过平台优化路径。
        assertTrue(source.contains("WebDavTlsMode.SYSTEM_DEFAULT -> Unit"))
    }

    @Test
    fun hostnameVerificationIsOnlyDisabledForTheUntrustedMode() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/webdav/WebDavTlsSupport.kt")

        val untrustedBranch = source.substringAfter("WebDavTlsMode.ALLOW_UNTRUSTED -> {")
        assertTrue(untrustedBranch.contains("hostnameVerifier(AllowAllHostnameVerifier)"))

        val selfSignedBranch = source
            .substringAfter("WebDavTlsMode.ALLOW_SELF_SIGNED -> {")
            .substringBefore("WebDavTlsMode.ALLOW_UNTRUSTED ->")
        assertFalse(selfSignedBranch.contains("hostnameVerifier"))
    }

    @Test
    fun everyWebDavClientGoesThroughTheGateway() {
        // 绕过 Gateway 直接构造 sardine 会丢掉 TLS 档位、超时与鉴权拦截器。
        listOf(
            "app/src/main/java/takagi/ru/monica/utils/WebDavKeePassFileSource.kt",
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt",
        ).forEach { path ->
            val source = projectFile(path)
            assertFalse(
                "$path must not construct a bare OkHttpSardine()",
                source.contains("OkHttpSardine()")
            )
        }

        assertTrue(
            projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavKeePassFileSource.kt")
                .contains("WebDavGateway.buildClient(")
        )
        assertTrue(
            projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavMdbxFileSource.kt")
                .contains("WebDavGateway.buildHttpClient(")
        )
    }

    @Test
    fun tlsModeIsAttachedAtApplicationStartup() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/MonicaApplication.kt")

        // 后台备份进程没有 Activity，必须在 Application 阶段绑定持久化。
        assertTrue(source.contains("WebDavTlsSettings.attachPersistence(this)"))
    }

    @Test
    fun clearingWebDavConfigAlsoResetsTlsMode() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")

        assertTrue(source.contains("WebDavTlsSettings.reset(context)"))
        // 切档后必须重建客户端，否则新档位要等冷启动才生效。
        assertTrue(source.contains("fun setTlsMode("))
        assertTrue(source.contains("sardine = createSardineClient()"))
    }

    @Test
    fun relaxedModesRequireExplicitConfirmationInUi() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/WebDavBackupScreen.kt"
        )

        assertTrue(source.contains("if (mode.isRelaxed)"))
        assertTrue(source.contains("pendingTlsMode = mode"))
        assertTrue(source.contains("WebDavTlsRelaxConfirmDialog("))
    }

    private fun projectFile(relativePath: String): String {
        val start = Paths.get("").toAbsolutePath()
        var cursor = start
        while (cursor.parent != null) {
            val candidate = cursor.resolve(relativePath).toFile()
            if (candidate.exists()) {
                return candidate.readText()
            }
            cursor = cursor.parent
        }
        error("Project file not found from $start: $relativePath")
    }
}
