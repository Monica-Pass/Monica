package takagi.ru.monica.passkey.hardware

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.passkey.MonicaCredentialProviderService
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Calls the production provider entry points; no duplicate implementation of candidate selection. */
@SdkSuppress(minSdkVersion = 34)
class HardwarePasskeyOptInTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preference: HardwarePasskeyPreferenceFixture
    private lateinit var service: MonicaCredentialProviderService
    private val rpId = "hardware-opt-in-${UUID.randomUUID()}.example.invalid"
    private val createJson get() = """{"challenge":"AQIDBA","rp":{"id":"$rpId","name":"Opt-in fixture"},"user":{"id":"BQYH","name":"software-fixture","displayName":"Software fixture"},"pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""

    @Before fun setUp() {
        preference = HardwarePasskeyPreferenceFixture(context)
        preference.removeChoice()
        assertTrue("Device must expose USB host or NFC for this regression",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC))
        assertTrue(runBlocking { SecureStorageStartup.prepareForEntry(context) } is SecureStartupResult.Ready)
        service = MonicaCredentialProviderService()
        // A framework-independent service instance still uses the installed application's real
        // resources, storage and PendingIntent APIs. Only Android's host attachment is supplied.
        MonicaCredentialProviderService::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, context)
        service.onCreate()
    }

    @After fun tearDown() {
        if (::service.isInitialized) service.onDestroy()
        if (::preference.isInitialized) preference.restore()
    }

    private fun create(json: String = createJson): BeginCreateCredentialResponse {
        var response: BeginCreateCredentialResponse? = null
        var error: Exception? = null
        val done = CountDownLatch(1)
        service.onBeginCreateCredentialRequest(
            BeginCreatePublicKeyCredentialRequest(json, null, Bundle()), CancellationSignal(),
            object : OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException> {
                override fun onResult(result: BeginCreateCredentialResponse) { response = result; done.countDown() }
                override fun onError(failure: CreateCredentialException) { error = failure; done.countDown() }
            },
        )
        assertTrue("Create provider did not complete", done.await(10, TimeUnit.SECONDS))
        error?.let { throw AssertionError("Create provider failed", it) }
        return checkNotNull(response)
    }

    private fun get(): BeginGetCredentialResponse {
        var response: BeginGetCredentialResponse? = null
        var error: Exception? = null
        val done = CountDownLatch(1)
        service.onBeginGetCredentialRequest(
            BeginGetCredentialRequest(listOf(BeginGetPublicKeyCredentialOption(Bundle(), "opt-in",
                """{"challenge":"AQIDBA","rpId":"$rpId"}"""))), CancellationSignal(),
            object : OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException> {
                override fun onResult(result: BeginGetCredentialResponse) { response = result; done.countDown() }
                override fun onError(failure: GetCredentialException) { error = failure; done.countDown() }
            },
        )
        assertTrue("Get provider did not complete", done.await(10, TimeUnit.SECONDS))
        error?.let { throw AssertionError("Get provider failed", it) }
        return checkNotNull(response)
    }

    @Test fun missingPreferenceDefaultsOffDespiteDeviceHardwareCapability() {
        assertFalse(HardwarePasskeyPreferences.isEnabled(context))
        val response = create()
        assertEquals(1, response.createEntries.size)
        assertTrue(response.createEntries.none { it.icon?.resId == R.drawable.ic_hardware_key })
        assertTrue(get().credentialEntries.isEmpty())
    }

    @Test fun explicitChoiceControlsBothProviderResponsesAndKeepsPlatformOnlyCreateSoftwareOnly() {
        HardwarePasskeyPreferences.setEnabled(context, true)
        val enabledCreate = create()
        assertEquals(2, enabledCreate.createEntries.size)
        assertEquals(1, enabledCreate.createEntries.count { it.icon?.resId == R.drawable.ic_hardware_key })
        val enabledGet = get().credentialEntries.filterIsInstance<PublicKeyCredentialEntry>()
        assertEquals(1, enabledGet.size)
        assertEquals(R.drawable.ic_hardware_key, enabledGet.single().icon.resId)
        val platform = JSONObject(createJson).put("authenticatorSelection",
            JSONObject().put("authenticatorAttachment", "platform")).toString()
        assertEquals(1, create(platform).createEntries.size)
        HardwarePasskeyPreferences.setEnabled(context, false)
        assertEquals(1, create().createEntries.size)
        assertTrue(get().credentialEntries.isEmpty())
    }

    @Test fun softwareCandidateMetadataAndRealSigningKeySurviveEnableDisable() = runBlocking {
        val dao = PasswordDatabase.getDatabase(context).passkeyDao()
        val before = dao.getAllPasskeysSync()
        val alias = "hardware-opt-in-test-${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        var rowId: Long? = null
        try {
            val row = PasskeyEntry(credentialId = "AQIDBA", rpId = rpId, rpName = "Opt-in fixture",
                userId = "BQYH", userName = "software-fixture", userDisplayName = "Software fixture",
                publicKey = android.util.Base64.encodeToString(pair.public.encoded, android.util.Base64.NO_WRAP),
                privateKeyAlias = alias)
            rowId = dao.insertCopy(row)
            val stored = checkNotNull(dao.getPasskeyByRecordId(rowId))
            val message = "synthetic hardware opt-in regression".toByteArray()
            for (enabled in listOf(false, true, false)) {
                HardwarePasskeyPreferences.setEnabled(context, enabled)
                val entries = get().credentialEntries.filterIsInstance<PublicKeyCredentialEntry>()
                assertEquals(if (enabled) 2 else 1, entries.size)
                assertEquals(1, entries.count { it.icon.resId != R.drawable.ic_hardware_key })
                assertEquals("Software fixture", entries.single { it.icon.resId != R.drawable.ic_hardware_key }.username.toString())
                assertEquals(stored, dao.getPasskeyByRecordId(rowId))
                val signature = Signature.getInstance("SHA256withECDSA").run {
                    initSign(store.getKey(alias, null) as java.security.PrivateKey)
                    update(message)
                    sign()
                }
                assertTrue(Signature.getInstance("SHA256withECDSA").run {
                    initVerify(pair.public); update(message); verify(signature)
                })
            }
        } finally {
            rowId?.let { dao.deleteByRecordId(it) }
            store.deleteEntry(alias)
        }
        assertEquals(before.toSet(), dao.getAllPasskeysSync().toSet())
    }

    @Test fun cancelledGetRequestPublishesNoHardwareOrSoftwareResponse() {
        HardwarePasskeyPreferences.setEnabled(context, true)
        val signal = CancellationSignal().apply { cancel() }
        val called = CountDownLatch(1)
        service.onBeginGetCredentialRequest(
            BeginGetCredentialRequest(listOf(BeginGetPublicKeyCredentialOption(Bundle(), "cancelled",
                """{"challenge":"AQIDBA","rpId":"$rpId"}"""))), signal,
            object : OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException> {
                override fun onResult(result: BeginGetCredentialResponse) { called.countDown() }
                override fun onError(error: GetCredentialException) { called.countDown() }
            },
        )
        assertFalse(called.await(200, TimeUnit.MILLISECONDS))
    }
}
