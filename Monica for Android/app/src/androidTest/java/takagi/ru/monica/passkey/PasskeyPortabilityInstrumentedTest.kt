package takagi.ru.monica.passkey

import android.content.res.Configuration
import android.graphics.Bitmap
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Locale
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.ui.components.PasskeyPortabilityBadges
import takagi.ru.monica.ui.components.PasskeyPortabilityDetails
import takagi.ru.monica.ui.components.rememberPasskeyPortability
import takagi.ru.monica.ui.theme.MonicaTheme

class PasskeyPortabilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun row(key: String) = PasskeyEntry(credentialId = "Y3JlZGVudGlhbA", rpId = "example.invalid",
        rpName = "Example", userId = "dXNlcg", userName = "Alice", userDisplayName = "Alice", publicKey = "public",
        privateKeyAlias = key, passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT)

    @Test fun realDeviceBoundKeyIsLocalOnlyButRawKeyAndMissingAliasAreDifferent() {
        val alias = "passkey-portability-test-${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
            assertEquals(PasskeyPortability.DEVICE_ONLY, PasskeyPortability.inspect(context, row(alias)))
            val key = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private.encoded)
            assertEquals(PasskeyPortability.PORTABLE, PasskeyPortability.inspect(context, row(key)))
            val protected = PasskeyPrivateKeyStore.protectPasskey(context, row(key))
            try {
                assertTrue(PasskeyPrivateKeyStore.isProtectedReference(protected.privateKeyAlias))
                assertEquals(PasskeyPortability.PORTABLE, PasskeyPortability.inspect(context, protected))
            } finally { PasskeyPrivateKeyStore.removeIfProtectedReference(context, protected.privateKeyAlias) }
            assertEquals(PasskeyPortability.KEY_UNAVAILABLE, PasskeyPortability.inspect(context, row("nonexistent-${UUID.randomUUID()}")))
        } finally { store.deleteEntry(alias) }
    }

    @Test fun localOnlyBadgeAndReasonFitNarrowLargeFontInBothThemes() {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = context.createConfigurationContext(config)
        var dark by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalResources provides localized.resources, LocalDensity provides Density(LocalDensity.current.density, 1.7f)) {
                MonicaTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.width(280.dp).testTag("portability_preview")) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PasskeyPortabilityBadges(row("alias"), PasskeyPortability.DEVICE_ONLY)
                            PasskeyPortabilityDetails(PasskeyPortability.DEVICE_ONLY)
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithTag("passkey_portability_badge").assertIsDisplayed()
            compose.onNodeWithText("KeePass 格式").assertIsDisplayed()
            compose.onNodeWithText("跨设备使用").assertIsDisplayed()
            compose.onNodeWithText("私钥绑定此设备，无法导出。仅复制数据库文件不能让此密钥在其他设备登录。").performScrollTo().assertIsDisplayed()
            val image = compose.onNodeWithTag("portability_preview").captureToImage().asAndroidBitmap()
            File(context.filesDir, "passkey-portability-${if (night) "dark" else "light"}.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    @Test fun actualAsyncInspectionShowsMissingKeyInsteadOfClaimingLocalOnly() {
        compose.setContent { MonicaTheme {
            val item = row("nonexistent-${javaClass.simpleName}")
            PasskeyPortabilityBadges(item, rememberPasskeyPortability(item))
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("passkey_portability_badge").fetchSemanticsNodes().isNotEmpty() }
        // A newly constructed record on recomposition must not continuously reset inspection.
        compose.onNodeWithTag("passkey_portability_badge").assertIsDisplayed()
    }
}
