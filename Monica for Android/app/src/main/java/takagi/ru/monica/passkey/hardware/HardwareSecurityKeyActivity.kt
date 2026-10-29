package takagi.ru.monica.passkey.hardware

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.credentials.*
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import takagi.ru.monica.R
import takagi.ru.monica.passkey.PasskeyBrowserOrigin
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache

class HardwareSecurityKeyActivity : FragmentActivity() {
    private var state by mutableStateOf(HardwareKeyUiState())
    private var transport: FidoTransport? = null
    private var job: Job? = null
    private var awaitingDevice: Int? = null
    private var epoch = 0
    private val permissionAction by lazy { "$packageName.hardware.USB_PERMISSION.${java.util.UUID.randomUUID()}" }
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val providerMode get() = intent.action == ACTION_CREATE || intent.action == ACTION_GET
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, data: Intent?) {
            if (data?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val device = data.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device?.deviceId == (transport as? UsbTransport)?.deviceId) disconnect()
                return
            }
            if (data?.action != permissionAction) return
            val device = data.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (device.deviceId != awaitingDevice) return
            awaitingDevice = null
            if (usb.hasPermission(device)) connect { UsbTransport.connect(usb, device) }
            else state = state.copy(error = R.string.hardware_key_permission_denied)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        state = HardwareKeyUiState(providerMode = providerMode)
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(permissionAction); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (providerMode) {
            if (Build.VERSION.SDK_INT >= 34) loadProviderRequest()
            else { state = state.copy(error = R.string.hardware_key_android14) }
        }
        setContent {
            MonicaTheme {
                BackHandler { cancelAndFinish() }
                HardwareKeyScreen(state, ::cancelAndFinish, ::findUsb, ::disconnect,
                    onPin = { state = state.copy(pin = it.take(128)) },
                    onPinDialog = { show -> state = state.copy(pinDialog = show, currentPin = "", newPin = "", confirmPin = "", error = null) },
                    onPinFields = { current, new, confirm -> state = state.copy(currentPin = current.take(128), newPin = new.take(128), confirmPin = confirm.take(128)) },
                    onSavePin = ::savePin, onReadCredentials = ::readCredentials, onPerform = ::perform,
                    onChooseAssertion = { assertion -> if (Build.VERSION.SDK_INT >= 34) returnCredential(state.request!!.assertionResponse(assertion), false) })
            }
        }
    }

    @RequiresApi(34)
    private fun loadProviderRequest() {
        state = state.copy(busy = true)
        job = lifecycleScope.launch {
            try {
                val create = intent.action == ACTION_CREATE
                val (raw, caller, hash) = if (create) {
                    val provider = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: error("Missing provider request")
                    val request = provider.callingRequest as? CreatePublicKeyCredentialRequest ?: error("Invalid request type")
                    Triple(request.requestJson, provider.callingAppInfo, request.clientDataHash)
                } else {
                    val provider = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: error("Missing provider request")
                    val request = provider.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().singleOrNull() ?: error("Ambiguous request")
                    Triple(request.requestJson, provider.callingAppInfo, request.clientDataHash)
                }
                val request = withContext(Dispatchers.IO) {
                    require(raw.length <= 65535)
                    val json = JSONObject(raw)
                    val rp = if (create) json.getJSONObject("rp").optString("id") else json.optString("rpId")
                    val effectiveRp = rp.ifEmpty {
                        java.net.URI(PasskeyBrowserOrigin.read(this@HardwareSecurityKeyActivity, caller) ?: error("Missing RP ID")).host
                    }
                    val canonicalRp = HardwareRequestOrigin.canonicalHost(effectiveRp)
                    val origin = HardwareRequestOrigin.resolve(this@HardwareSecurityKeyActivity, caller, canonicalRp, hash)
                    HardwareWebAuthn(json, canonicalRp, origin, hash, create)
                }
                state = state.copy(request = request, error = null, busy = false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = state.copy(error = R.string.hardware_key_invalid_request, busy = false) }
        }
    }

    private fun findUsb() {
        if (state.busy) return
        val devices = UsbTransport.candidates(usb)
        if (devices.isEmpty()) { state = state.copy(error = R.string.hardware_key_no_usb); return }
        if (devices.size > 1) {
            // Do not choose a key silently when several are attached.
            state = state.copy(error = R.string.hardware_key_multiple_usb); return
        }
        val device = devices.single()
        if (usb.hasPermission(device)) connect { UsbTransport.connect(usb, device) }
        else {
            awaitingDevice = device.deviceId
            usb.requestPermission(device, PendingIntent.getBroadcast(this, device.deviceId,
                Intent(permissionAction).setPackage(packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }

    private fun connect(open: suspend () -> FidoTransport) {
        if (state.busy) return
        disconnect()
        val startedEpoch = epoch
        state = state.copy(busy = true, error = null, status = null)
        job = lifecycleScope.launch {
            var opened: FidoTransport? = null
            try {
                val active = withContext(Dispatchers.IO) { open().also { opened = it } }
                if (epoch != startedEpoch) { active.close(); return@launch }
                transport = active
                val info = withContext(Dispatchers.IO) { readInfo(active) }
                if (epoch == startedEpoch && transport === active) {
                    state = state.copy(info = info, transport = active.transportType.value, busy = false)
                }
            } catch (e: CancellationException) { opened?.close(); throw e }
            catch (e: Exception) {
                opened?.close()
                if (epoch == startedEpoch) { disconnect(); state = state.copy(error = errorString(e)) }
            }
        }
    }

    private suspend fun readInfo(active: FidoTransport): DeviceInfo = CTAP.parseGetInfoStructured(active.sendCtapCommand(byteArrayOf(4))).getOrThrow()

    private fun operation(run: suspend (FidoTransport, DeviceInfo, String) -> Unit) {
        if (state.busy) return
        val active = transport ?: return
        val info = state.info ?: return
        val pin = state.pin
        val startedEpoch = epoch
        state = state.copy(busy = true, error = null, status = null, pin = "", assertions = emptyList())
        job = lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { run(active, info, pin) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (epoch == startedEpoch) {
                    state = state.copy(error = errorString(e))
                    if (!active.isConnected) { transport = null; state = state.copy(info = null, credentials = null) }
                }
            } finally { if (epoch == startedEpoch) state = state.copy(busy = false) }
        }
    }

    private fun savePin() {
        val new = state.newPin
        val current = if (state.info?.clientPinSet == true) state.currentPin else null
        if (new != state.confirmPin) return
        try { HardwarePin.validatePin(new, state.info?.minPinLength ?: 4) }
        catch (_: Exception) { state = state.copy(error = R.string.hardware_key_pin_invalid); return }
        state = state.copy(currentPin = "", newPin = "", confirmPin = "")
        operation { active, info, _ ->
            HardwarePin(active, HardwarePin.select(info)).agreement().use { it.setOrChange(new, current, info.minPinLength ?: 4) }
            val updated = readInfo(active)
            withContext(Dispatchers.Main) { if (transport === active) state = state.copy(info = updated, pinDialog = false, status = R.string.hardware_key_pin_saved, credentials = null) }
        }
    }

    private fun readCredentials() = operation { active, info, pin ->
        val snapshot = HardwareCredentials.read(active, info, pin)
        withContext(Dispatchers.Main) { if (transport === active) state = state.copy(credentials = snapshot) }
    }

    private fun perform() {
        val request = state.request ?: return
        operation { active, info, pin ->
            if (request.create) {
                val response = request.register(active, info, pin.ifEmpty { null })
                withContext(Dispatchers.Main) { if (transport === active && Build.VERSION.SDK_INT >= 34) returnCredential(response, true) }
            } else {
                val assertions = request.assertions(active, info, pin.ifEmpty { null })
                withContext(Dispatchers.Main) {
                    if (transport !== active) return@withContext
                    if (assertions.size == 1 && Build.VERSION.SDK_INT >= 34) returnCredential(request.assertionResponse(assertions.single()), false)
                    else state = state.copy(assertions = assertions)
                }
            }
        }
    }

    @RequiresApi(34)
    private fun returnCredential(json: String, create: Boolean) {
        val data = Intent()
        if (create) PendingIntentHandler.setCreateCredentialResponse(data, CreatePublicKeyCredentialResponse(json))
        else PendingIntentHandler.setGetCredentialResponse(data, GetCredentialResponse(PublicKeyCredential(json)))
        setResult(RESULT_OK, data)
        finish()
    }

    private fun cancelAndFinish() {
        if (providerMode && Build.VERSION.SDK_INT >= 34) {
            val data = Intent()
            if (intent.action == ACTION_CREATE) PendingIntentHandler.setCreateCredentialException(data, CreateCredentialCancellationException())
            else PendingIntentHandler.setGetCredentialException(data, GetCredentialCancellationException())
            setResult(RESULT_CANCELED, data)
        }
        disconnect(); finish()
    }

    private fun disconnect() {
        epoch++
        awaitingDevice = null
        job?.cancel(); job = null
        transport?.close(); transport = null
        state = state.copy(info = null, transport = "", busy = false, pin = "", currentPin = "", newPin = "", confirmPin = "",
            pinDialog = false, credentials = null, assertions = emptyList())
    }

    override fun onResume() {
        super.onResume()
        if (providerMode && Build.VERSION.SDK_INT >= 34 && state.request == null && job?.isActive != true) loadProviderRequest()
        if (nfc?.isEnabled == true) nfc?.enableReaderMode(this, { tag ->
            lifecycleScope.launch(Dispatchers.Main) { if (!state.busy) connect { NfcTransport.connect(tag) } }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }
    override fun onPause() {
        nfc?.disableReaderMode(this)
        state = state.copy(pin = "", currentPin = "", newPin = "", confirmPin = "")
        super.onPause()
    }
    override fun onStop() { disconnect(); super.onStop() }
    override fun onDestroy() { runCatching { unregisterReceiver(receiver) }; disconnect(); super.onDestroy() }

    companion object {
        const val ACTION_CREATE = "takagi.ru.monica.hardware.CREATE"
        const val ACTION_GET = "takagi.ru.monica.hardware.GET"
        private fun errorString(e: Exception): Int = when ((e as? CTAP.Exception)?.error) {
            CTAP.Error.PIN_INVALID -> R.string.hardware_key_pin_wrong
            CTAP.Error.PIN_BLOCKED, CTAP.Error.PIN_AUTH_BLOCKED -> R.string.hardware_key_pin_blocked
            CTAP.Error.CREDENTIAL_EXCLUDED -> R.string.hardware_key_exists
            CTAP.Error.NO_CREDENTIALS -> R.string.hardware_key_no_credentials
            CTAP.Error.KEY_STORE_FULL -> R.string.hardware_key_full
            CTAP.Error.PIN_POLICY_VIOLATION -> R.string.hardware_key_pin_invalid
            CTAP.Error.OPERATION_DENIED, CTAP.Error.KEEPALIVE_CANCEL, CTAP.Error.ACTION_TIMEOUT, CTAP.Error.USER_ACTION_TIMEOUT -> R.string.hardware_key_cancelled
            else -> R.string.hardware_key_failed
        }
    }
}
