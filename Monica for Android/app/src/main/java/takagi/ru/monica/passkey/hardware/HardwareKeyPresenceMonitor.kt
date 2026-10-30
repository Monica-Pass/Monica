package takagi.ru.monica.passkey.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Discovery hints only. The hardware activity verifies the FIDO interface/applet before use. */
internal data class HardwareKeyPresence(val usbCount: Int = 0, val nfcDetected: Boolean = false) {
    val available: Boolean get() = usbCount > 0 || nfcDetected
    val transport: String get() = if (usbCount > 0) "USB" else "NFC"
}

internal class HardwareKeyPresenceMonitor(
    private val activity: FragmentActivity,
    private val onChanged: (HardwareKeyPresence) -> Unit,
) : DefaultLifecycleObserver {
    private val usb = activity.getSystemService(UsbManager::class.java)
    private val nfc = NfcAdapter.getDefaultAdapter(activity)
    private var registered = false
    private var resumed = false
    var detectedNfcTag: Tag? = null
        private set
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }
    init { activity.lifecycle.addObserver(this) }
    private fun refresh() {
        onChanged(HardwareKeyPresence(runCatching { UsbTransport.candidates(usb).size }.getOrDefault(0), detectedNfcTag != null))
    }
    override fun onStart(owner: LifecycleOwner) {
        if (!registered) {
            ContextCompat.registerReceiver(activity, receiver, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        refresh()
    }
    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        refresh()
        if (nfc?.isEnabled == true) runCatching {
            nfc.enableReaderMode(activity, { tag ->
                if (IsoDep::class.java.name in tag.techList) activity.runOnUiThread {
                    if (resumed) { detectedNfcTag = tag; refresh() }
                }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        }
    }
    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        nfc?.let { runCatching { it.disableReaderMode(activity) } }
        detectedNfcTag = null
        refresh()
    }
    override fun onStop(owner: LifecycleOwner) {
        if (registered) { activity.unregisterReceiver(receiver); registered = false }
    }
    override fun onDestroy(owner: LifecycleOwner) { activity.lifecycle.removeObserver(this) }
}
