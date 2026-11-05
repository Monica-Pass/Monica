package takagi.ru.monica.bitwarden

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class BitwardenTransportClientTest {
    private fun environment(base: String, api: String? = null, identity: String? = null,
        web: String? = null, notifications: String? = null) = requireNotNull(BitwardenEnvironmentResolver.resolve(
        BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, base, web, api, identity, notifications,
            listOf(BitwardenHeaderDraft("CF-Access-Client-Secret", "synthetic proxy credential"))),
        UUID.randomUUID().toString(),
    ).value)

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Destination was accepted") } catch (_: IOException) { }
    }

    @Test fun independentIdentityAndApiUseLiteralServiceBasesAndProxyHeaders(): Unit = runBlocking {
        MockWebServer().use { base -> MockWebServer().use { api -> MockWebServer().use { identity ->
            val environment = environment(base.url("/Vault/").toString(), api.url("/Case/v2/").toString(),
                identity.url("/Login/").toString())
            val manager = BitwardenApiManager()
            try {
                identity.enqueue(MockResponse().setBody("{\"kdf\":0,\"kdfIterations\":600000}"))
                api.enqueue(MockResponse().setBody("{\"id\":\"synthetic\",\"email\":\"a@example.test\"}"))
                assertTrue(manager.getIdentityApi(environment).preLogin(PreLoginRequest("a@example.test")).isSuccessful)
                assertTrue(manager.getVaultApi(environment).profile("Bearer synthetic bearer").isSuccessful)
                val authRequest = identity.takeRequest(2, TimeUnit.SECONDS)!!
                val apiRequest = api.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/Login/accounts/prelogin", authRequest.path)
                assertEquals("/Case/v2/accounts/profile", apiRequest.path)
                assertEquals("synthetic proxy credential", authRequest.getHeader("CF-Access-Client-Secret"))
                assertEquals(environment.base.toString(), apiRequest.getHeader("Referer"))
                assertNull(authRequest.getHeader("Authorization"))
                assertEquals("Bearer synthetic bearer", apiRequest.getHeader("Authorization"))
                assertEquals(0, base.requestCount)
            } finally { manager.clearCache() }
        } } }
    }

    @Test fun independentWebDoesNotAuthorizeNativeHeadersAndLookalikePathCannotConnect() {
        MockWebServer().use { base -> MockWebServer().use { web ->
            val environment = environment(base.url("/Vault/").toString(), web = web.url("/").toString())
            val client = BitwardenApiFactory.createAccountClient(environment)
            try {
                listOf(web.url("/"), base.url("/Vault2/private"), base.url("/vault/private")).forEach { url ->
                    rejects { client.newCall(Request.Builder().url(url).build()).execute().close() }
                }
                assertEquals(0, web.requestCount)
                assertEquals(0, base.requestCount)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        } }
    }

    @Test fun redirectsReturnToCallerWithoutASecondRequest() {
        MockWebServer().use { base -> MockWebServer().use { foreign ->
            base.enqueue(MockResponse().setResponseCode(302).setHeader("Location", foreign.url("/capture")))
            val client = BitwardenApiFactory.createAccountClient(environment(base.url("/").toString()))
            try {
                client.newCall(Request.Builder().url(base.url("/redirect")).build()).execute().use { assertEquals(302, it.code) }
                assertEquals(1, base.requestCount)
                assertEquals(0, foreign.requestCount)
                assertFalse(client.followRedirects)
                assertFalse(client.followSslRedirects)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        } }
    }

    @Test fun retiredAttemptCannotDispatchExistingApiOrObtainANewOne(): Unit = runBlocking {
        MockWebServer().use { server ->
            val environment = environment(server.url("/").toString())
            val manager = BitwardenApiManager()
            val api = manager.getIdentityApi(environment)
            manager.retireEnvironment(environment)
            try {
                api.preLogin(PreLoginRequest("a@example.test"))
                fail("Retired client executed")
            } catch (_: IOException) { }
            try { manager.getVaultApi(environment); fail("Attempt resurrected") } catch (_: IllegalStateException) { }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun retiringOneAccountLeavesAnotherPoolAndRequestUsable() {
        MockWebServer().use { server ->
            val environment = environment(server.url("/").toString())
            val manager = BitwardenApiManager(environmentForVault = { environment })
            val first = manager.getOkHttpClient(BitwardenVault(id = 1, email = "a@example.test"))
            val second = manager.getOkHttpClient(BitwardenVault(id = 2, email = "b@example.test"))
            assertNotSame(first.connectionPool, second.connectionPool)
            manager.retireVault(1)
            try {
                rejects { first.newCall(Request.Builder().url(server.url("/old")).build()).execute().close() }
                server.enqueue(MockResponse().setBody("synthetic content"))
                second.newCall(Request.Builder().url(server.url("/current")).build()).execute().use {
                    assertEquals("synthetic content", it.body!!.string())
                }
                assertEquals(1, server.requestCount)
            } finally { manager.clearCache() }
        }
    }

    @Test fun foreignTransfersHaveSeparatePoolAndRejectExplicitCredentialOrPlainHttp() {
        MockWebServer().use { server ->
            val environment = environment(server.url("/").toString())
            val manager = BitwardenApiManager(environmentForVault = { environment })
            val vault = BitwardenVault(id = 1, email = "a@example.test")
            try {
                val account = manager.getOkHttpClient(vault)
                val foreign = manager.getTransferClient(vault, "https://storage.example.test/signed?key=synthetic")
                assertNotSame(account.connectionPool, foreign.connectionPool)
                assertFalse(foreign.followRedirects)
                listOf("Authorization", "Cookie", "Referer", "Proxy-Authorization").forEach { name ->
                    rejects { foreign.newCall(Request.Builder().url("https://storage.example.test/blob")
                        .header(name, "synthetic").build()).execute().close() }
                }
                rejects { foreign.newCall(Request.Builder().url(server.url("/plain")).build()).execute().close() }
                rejects { manager.getTransferClient(vault, "http://outside.example.test/blob") }
                assertEquals(0, server.requestCount)
            } finally { manager.clearCache() }
        }
    }

    @Test fun leaseIsCheckedWhenCallExecutesRatherThanWhenClientWasBuilt() {
        MockWebServer().use { server ->
            var current = true
            val client = BitwardenApiFactory.createAccountClient(environment(server.url("/").toString()), requestIsCurrent = { current })
            val call = client.newCall(Request.Builder().url(server.url("/" )).build())
            current = false
            try { rejects { call.execute().close() }; assertEquals(0, server.requestCount) }
            finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
