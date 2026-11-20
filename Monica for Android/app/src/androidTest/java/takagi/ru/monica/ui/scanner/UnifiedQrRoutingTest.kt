package takagi.ru.monica.ui.scanner

import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.processImageWithZxing

@RunWith(AndroidJUnit4::class)
class UnifiedQrRoutingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun supportedAuthenticatorProtocolsRemainInAuthenticatorFlow() {
        listOf(QrScannerFixtures.TOTP,
            "otpauth://hotp/Test?secret=JBSWY3DPEHPK3PXP&counter=7",
            "motp://Example:alice?secret=0123456789abcdef&pin=1234",
            "phonefactor://activate_account?code=synthetic",
            "otpauth-migration://offline?data=invalid")
            .forEach { assertEquals(it.substringBefore(':'), CredentialQrKind.AUTHENTICATOR, UnifiedQrRouter.classify(it)) }
        listOf("https://example.test", "intent://test", "FIDO:/123", "otpauth://totp/Test", "plain text")
            .forEach { assertEquals(CredentialQrKind.UNSUPPORTED, UnifiedQrRouter.classify(it)) }
        assertEquals(CredentialQrKind.PASSKEY, UnifiedQrRouter.classify(UnifiedScannerFixtures.fido()))
    }

    @Test fun systemDispatchRejectsNonFidoAndReportsMissingHandler() {
        val intents = mutableListOf<Intent>()
        val fake = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { intents += intent }
        }
        assertEquals(R.string.passkey_qr_invalid, launchSystemFidoQrIntent(fake, "https://example.test"))
        assertEquals(R.string.passkey_qr_invalid, launchSystemFidoQrIntent(fake, "FIDO:/123"))
        assertTrue(intents.isEmpty())
        assertNull(launchSystemFidoQrIntent(fake, UnifiedScannerFixtures.fido()))
        assertEquals(1, intents.size)
        assertEquals("FIDO", intents.single().data?.scheme)
        val missing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertEquals(R.string.passkey_qr_open_failed, launchSystemFidoQrIntent(missing, UnifiedScannerFixtures.fido()))
    }

    @Test fun galleryRecognizesOtpAndFidoUsingTheSameRules() = runBlocking {
        for (code in listOf(QrScannerFixtures.TOTP, UnifiedScannerFixtures.fido())) {
            assertEquals(listOf(code), gallery(QrScannerFixtures.Frame().code(code)))
        }
    }

    @Test fun galleryRefusesTwoValidCodesAndAllowsDuplicateDetections() = runBlocking {
        assertEquals(listOf("multiple"), gallery(QrScannerFixtures.Frame()
            .code(QrScannerFixtures.TOTP, left = 70, top = 290, size = 380)
            .code(UnifiedScannerFixtures.fido(), left = 830, top = 290, size = 380)))
        assertEquals(listOf(QrScannerFixtures.TOTP), gallery(QrScannerFixtures.Frame()
            .code(QrScannerFixtures.TOTP, left = 70, top = 290, size = 380)
            .code(QrScannerFixtures.TOTP, left = 830, top = 290, size = 380)))
    }

    @Test fun galleryReportsUnknownAndEmptyImagesWithoutDispatch() = runBlocking {
        assertEquals(listOf("invalid"), gallery(QrScannerFixtures.Frame().code(QrScannerFixtures.UNRELATED)))
        assertEquals(listOf("empty"), gallery(QrScannerFixtures.Frame()))
    }

    @Test fun fullResolutionSweepsCheckBothPolaritiesBeforeChoosing() {
        val code = UnifiedScannerFixtures.fido()
        val frame = QrScannerFixtures.Frame()
            .code(QrScannerFixtures.TOTP, left = 70, top = 290, size = 380)
            .code(code, left = 830, top = 290, size = 380, inverted = true)
        val decoder = ZxingBarcodeDecoder(listOf(BarcodeFormat.QR_CODE), confirmBothPolarities = true)
        repeat(6) {
            assertEquals("Every sweep must see both choices", setOf(QrScannerFixtures.TOTP, code), decoder.decodeFrame(frame.proxy()).toSet())
        }
    }

    @Test fun cancelledGalleryRequestCannotDeliverAfterDecoding() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        var delivered = false
        val job = launch {
            gallery(QrScannerFixtures.Frame().code(QrScannerFixtures.TOTP), beforeDelivery = {
                ready.complete(Unit)
                awaitCancellation()
            })
            delivered = true
        }
        withTimeout(10_000) { ready.await() }
        job.cancelAndJoin()
        assertFalse(delivered)
    }

    private suspend fun gallery(frame: QrScannerFixtures.Frame, beforeDelivery: suspend () -> Unit = {}): List<String> {
        val file = File.createTempFile("unified-gallery-", ".png", context.cacheDir)
        val bitmap = frame.bitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val results = mutableListOf<String>()
        try {
            withContext(Dispatchers.Main) {
                processImageWithZxing(context, Uri.fromFile(file), ZxingBarcodeDecoder(listOf(BarcodeFormat.QR_CODE)), null,
                    resultValidator = { UnifiedQrRouter.classify(it) != CredentialQrKind.UNSUPPORTED },
                    requireSingleResult = true, beforeDelivery = beforeDelivery, onMultiple = { results += "multiple" },
                    onResult = { results += it.text }, onInvalid = { results += "invalid" }, onNotFound = { results += "empty" })
            }
            return results
        } finally { file.delete() }
    }
}
