package takagi.ru.monica.nfc.card

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import takagi.ru.monica.R
import takagi.ru.monica.security.SessionManager

/** One MainActivity owns NFC. Separate FIDO activities retain their own reader sessions. */
internal class NfcCardEntryController(private val activity: ComponentActivity) : DefaultLifecycleObserver {
    internal class Editor(
        val owner: Any, val kind: NfcCardEditorKind,
        val busy: () -> Boolean, val fields: () -> NfcCardFields,
        val fill: (EmvCardReadResult.BankCard) -> Unit,
    )
    internal class Pending(val id: Long, val card: EmvCardReadResult.BankCard, val generation: Long, val deadline: Long) {
        override fun toString() = "PendingNfcCard(redacted)"
    }
    internal enum class Prompt { NONE, REPLACE, NEW_CARD }
    internal data class Ui(val reading: Boolean = false, val message: Int? = null, val prompt: Prompt = Prompt.NONE, val revision: Long = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull()
    private val epoch = AtomicLong()
    private val connection = AtomicReference<IsoDep?>()
    private val workerRunning = AtomicBoolean(false)
    private val closing = AtomicReference<IsoDep?>()
    private val state = MutableStateFlow(Ui())
    val ui = state.asStateFlow()
    private var resumed = false
    private var focused = false
    private var authorized = false
    private var enabled = false
    private var readJob: Job? = null
    private var expiryJob: Job? = null
    private var pendingTag: Tag? = null
    private var tagExpiryJob: Job? = null
    private var needsRetap = false
    private var pending: Pending? = null
    private var editor: Editor? = null
    private val blockers = mutableSetOf<Any>()
    private var promptOwner: Any? = null
    private var allowNew = false
    private var route: String? = null
    private var awaitingNewEditor = false
    private var sequence = 0L
    private var lastReadAt = -3000L
    var navigateNew: (() -> Unit)? = null

    fun block(owner: Any) { blockers += owner; invalidate() }
    fun unblock(owner: Any) { blockers -= owner }
    fun revoke() { authorized = false; pendingTag = null; invalidate(); updateReader() }
    private fun otherEditorOpen() = blockers.isNotEmpty() ||
        (editor == null && takagi.ru.monica.lansync.DeviceSyncEditGate.isEditing)

    init {
        activity.lifecycle.addObserver(this)
        scope.launch {
            combine(SessionManager.isUnlocked, SessionManager.lockGeneration) { unlocked, generation -> unlocked to generation }
                .collect { (unlocked, _) ->
                    invalidate()
                    if (!unlocked) authorized = false
                    updateReader()
                }
        }
    }

    fun authorize(value: Boolean, currentRoute: String?, allowNewCard: Boolean) {
        if (route != currentRoute && !(awaitingNewEditor && currentRoute == "add_edit_bank_card/{cardId}")) invalidate()
        route = currentRoute
        allowNew = allowNewCard
        authorized = value && SessionManager.isUnlocked.value
        if (!authorized) invalidate(keepMessage = true)
        if (authorized && needsRetap) {
            needsRetap = false
            state.value = Ui(message = R.string.nfc_card_unlock_retap)
        }
        updateReader()
        dispatch()
        schedulePendingTag()
    }

    fun receive(intent: Intent?) {
        if (intent?.action != NfcAdapter.ACTION_TECH_DISCOVERED) return
        val tag = runCatching {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) as? Tag)
        }.getOrNull()
        intent.removeExtra(NfcAdapter.EXTRA_TAG)
        intent.removeExtra(NfcAdapter.EXTRA_ID)
        intent.removeExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (tag == null || IsoDep::class.java.name !in tag.techList) return
        // A cold or locked launch must never retain a card while waiting for authentication.
        if (!authorized || !SessionManager.isUnlocked.value) {
            needsRetap = true
            state.value = Ui(message = R.string.nfc_card_unlock_retap)
            return
        }
        pendingTag = tag
        tagExpiryJob?.cancel()
        tagExpiryJob = scope.launch {
            delay(3_000)
            if (pendingTag != null) {
                pendingTag = null
                if (admitted()) state.value = Ui(message = R.string.nfc_card_busy)
            }
        }
        schedulePendingTag()
    }

    fun focus(value: Boolean) {
        focused = value
        if (!value && state.value.reading) {
            epoch.incrementAndGet()
            readJob?.cancel()
            closeConnection()
            state.value = Ui()
        }
        // Dialogs temporarily lose Activity focus; keep their review data but stop RF reads.
        updateReader()
        schedulePendingTag()
    }

    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        focused = activity.hasWindowFocus()
        updateReader()
        schedulePendingTag()
    }

    private fun schedulePendingTag() {
        // Activity resumes before its navigation entries. Do not consume a warm-launch tag
        // until the resumed editor has registered; never capture a Tag in a queued callback.
        activity.window.decorView.post {
            if (admitted() && focused && (editor != null || allowNew || blockers.isNotEmpty())) {
                pendingTag?.let { pendingTag = null; tagExpiryJob?.cancel(); scan(it) }
            }
        }
    }

    private fun closeConnection() {
        val dep = connection.get() ?: return
        if (!closing.compareAndSet(null, dep)) return
        // Closing a retired connection must survive the Activity scope's cancellation.
        closeScope.launch {
            try { runCatching { dep.close() } }
            finally { closing.compareAndSet(dep, null) }
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        pendingTag = null
        invalidate()
        updateReader()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        invalidate()
        scope.cancel()
        activity.lifecycle.removeObserver(this)
    }

    private fun admitted() = authorized && resumed && SessionManager.isUnlocked.value

    private fun updateReader() {
        val shouldEnable = admitted() && focused && state.value.prompt == Prompt.NONE
        if (shouldEnable == enabled) return
        enabled = shouldEnable && adapter?.isEnabled == true
        runCatching {
            if (enabled) adapter?.enableReaderMode(activity, { tag ->
                scope.launch { if (admitted() && focused) scan(tag) }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
            else adapter?.disableReaderMode(activity)
        }
    }

    private fun scan(tag: Tag) {
        if (!admitted() || !focused || workerRunning.get() || pending != null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadAt < 1500) return
        lastReadAt = now
        if (otherEditorOpen() || editor?.busy?.invoke() == true || (editor == null && !allowNew)) {
            state.value = Ui(message = R.string.nfc_card_busy)
            return
        }
        val iso = IsoDep.get(tag)
        if (iso == null) { state.value = Ui(message = R.string.nfc_card_unsupported); return }
        if (!processWorkerRunning.compareAndSet(false, true)) return
        workerRunning.set(true)
        val lease = epoch.incrementAndGet()
        val generation = SessionManager.lockGeneration.value
        val deadline = now + 10_000
        state.value = Ui(reading = true)
        readJob = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    fun checkCurrent() {
                        check(epoch.get() == lease && SessionManager.isUnlocked.value &&
                            SessionManager.lockGeneration.value == generation && SystemClock.elapsedRealtime() < deadline)
                    }
                    try {
                        checkCurrent()
                        check(connection.compareAndSet(null, iso))
                        checkCurrent()
                        iso.timeout = 1200
                        iso.connect()
                        checkCurrent()
                        EmvCardReader(EmvCardChannel(iso::transceive), ::checkCurrent, allowGpo = true).read()
                    } finally {
                        connection.compareAndSet(iso, null)
                        runCatching { iso.close() }
                    }
                }
            } catch (_: CancellationException) { return@launch }
            catch (_: Exception) {
                if (epoch.get() == lease && admitted()) state.value = Ui(message = R.string.nfc_card_read_failed)
                return@launch
            } finally { workerRunning.set(false); processWorkerRunning.set(false) }
            if (epoch.get() != lease || !admitted() || SessionManager.lockGeneration.value != generation) return@launch
            if (result !is EmvCardReadResult.BankCard) {
                state.value = Ui(message = R.string.nfc_card_unsupported)
            } else accept(result, generation)
        }
    }

    /** Called only after the hardware reader's bounded, current-session check. */
    internal fun accept(card: EmvCardReadResult.BankCard, generation: Long = SessionManager.lockGeneration.value) {
        if (!admitted() || generation != SessionManager.lockGeneration.value) return
        pending = Pending(++sequence, card, generation, SystemClock.elapsedRealtime() + 30_000)
        state.value = Ui(revision = sequence)
        expiryJob?.cancel()
        expiryJob = scope.launch { delay(30_000); invalidate() }
        dispatch()
    }

    fun register(value: Editor) {
        if (editor?.owner !== value.owner && !awaitingNewEditor && workerRunning.get()) invalidate()
        editor = value
        dispatch()
        schedulePendingTag()
    }
    fun unregister(owner: Any) {
        if (editor?.owner === owner) {
            editor = null
            if (!awaitingNewEditor) invalidate()
        }
    }

    private fun current(): Pending? = pending?.takeIf {
        admitted() && it.generation == SessionManager.lockGeneration.value && SystemClock.elapsedRealtime() < it.deadline
    }

    private fun dispatch() {
        val value = current() ?: return
        if (state.value.prompt != Prompt.NONE) return
        val target = editor
        if (awaitingNewEditor && target?.kind != NfcCardEditorKind.BANK) return
        when (nfcCardRouteDecision(target?.kind, otherEditorOpen() || target?.busy?.invoke() == true,
            target?.fields?.invoke()?.conflictsWith(value.card) == true, allowNew)) {
            NfcCardRouteDecision.FILL -> { target?.fill?.invoke(value.card); clearPending(); state.value = Ui() }
            NfcCardRouteDecision.REPLACE -> { promptOwner = target?.owner; state.value = Ui(prompt = Prompt.REPLACE) }
            NfcCardRouteDecision.CONFIRM_NEW_CARD -> { promptOwner = target?.owner; state.value = Ui(prompt = Prompt.NEW_CARD) }
            NfcCardRouteDecision.NEW_CARD -> openNew()
            NfcCardRouteDecision.BUSY -> { clearPending(); state.value = Ui(message = R.string.nfc_card_busy) }
        }
        updateReader()
    }

    fun confirm() {
        val value = current() ?: run { dismiss(); return }
        val target = editor
        if (target == null || target.owner !== promptOwner || target.busy()) { dismiss(); return }
        when (state.value.prompt) {
            Prompt.REPLACE -> { target.fill(value.card); clearPending(); state.value = Ui() }
            Prompt.NEW_CARD -> openNew()
            Prompt.NONE -> Unit
        }
        updateReader()
    }

    private fun openNew() {
        if (navigateNew == null) { dismiss(); return }
        awaitingNewEditor = true
        promptOwner = null
        state.value = Ui(revision = sequence)
        navigateNew?.invoke()
    }

    fun dismiss() { clearPending(); state.value = Ui(); updateReader() }

    private fun clearPending() {
        pending = null; promptOwner = null; awaitingNewEditor = false
        expiryJob?.cancel(); expiryJob = null
    }

    private fun invalidate(keepMessage: Boolean = false) {
        epoch.incrementAndGet()
        tagExpiryJob?.cancel(); tagExpiryJob = null
        pendingTag = null
        readJob?.cancel()
        closeConnection()
        clearPending()
        state.value = Ui(message = state.value.message.takeIf { keepMessage })
    }

    private companion object {
        // A recreated Activity cannot overlap a retired, still-blocked hardware operation.
        val processWorkerRunning = AtomicBoolean(false)
        val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
