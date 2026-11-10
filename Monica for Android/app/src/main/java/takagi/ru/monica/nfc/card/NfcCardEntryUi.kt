package takagi.ru.monica.nfc.card

import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import takagi.ru.monica.R

internal val LocalNfcCardEntry = staticCompositionLocalOf<NfcCardEntryController?> { null }

/** Existing manually entered state keeps its behavior; NFC-filled values never enter a Bundle. */
@Composable
internal fun rememberNfcCardField(scanned: () -> Boolean): MutableState<String> {
    val currentScanned by rememberUpdatedState(scanned)
    return rememberSaveable(stateSaver = Saver<String, String>(
        save = { if (currentScanned()) "" else it }, restore = { it },
    )) { mutableStateOf("") }
}

@Composable
internal fun NfcCardEntryHost(authorized: Boolean, route: String?, onNewBankCard: () -> Unit) {
    val controller = LocalNfcCardEntry.current ?: return
    val navigate by rememberUpdatedState(onNewBankCard)
    val ui by controller.ui.collectAsState()
    val context = LocalContext.current
    // Mutating an unrelated editor must not navigate away and drop its private draft.
    val allowNew = route != null && !route.startsWith("add_") && !route.startsWith("edit_") &&
        !route.contains("login") && !route.contains("setup")
    DisposableEffect(controller) {
        controller.navigateNew = { navigate() }
        onDispose { controller.navigateNew = null; controller.revoke() }
    }
    LaunchedEffect(authorized, route) { controller.authorize(authorized, route, allowNew) }
    DisposableEffect(ui.message, authorized) {
        val toast = ui.message?.takeIf { authorized }?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).also(Toast::show) }
        onDispose { toast?.cancel() }
    }
    if (!authorized) return
    if (ui.prompt != NfcCardEntryController.Prompt.NONE) {
        val replace = ui.prompt == NfcCardEntryController.Prompt.REPLACE
        AlertDialog(
            modifier = Modifier.testTag(if (replace) "nfc_card_replace" else "nfc_card_new"),
            onDismissRequest = controller::dismiss,
            title = { Text(stringResource(if (replace) R.string.nfc_card_replace_title else R.string.nfc_card_new_title)) },
            text = { Text(stringResource(if (replace) R.string.nfc_card_replace_message else R.string.nfc_card_new_message)) },
            dismissButton = { TextButton(onClick = controller::dismiss, modifier = Modifier.testTag("nfc_card_cancel")) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = controller::confirm, modifier = Modifier.testTag("nfc_card_confirm")) {
                Text(stringResource(if (replace) R.string.nfc_card_replace_action else R.string.nfc_card_new_action))
            } },
        )
    }
}

@Composable
internal fun NfcCardBlockingEditor() {
    val controller = LocalNfcCardEntry.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val token = remember { Any() }
    DisposableEffect(controller, lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { controller.block(token) }
            override fun onPause(owner: LifecycleOwner) { controller.unblock(token) }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.block(token)
        onDispose { lifecycle.removeObserver(observer); controller.unblock(token) }
    }
}

/** A navigation entry registers only while RESUMED, never during an outgoing transition. */
@Composable
internal fun NfcCardEditor(
    kind: NfcCardEditorKind,
    eligible: Boolean,
    busy: Boolean,
    fields: NfcCardFields = NfcCardFields("", "", "", ""),
    onFill: (EmvCardReadResult.BankCard) -> Unit = {},
) {
    val controller = LocalNfcCardEntry.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember { Any() }
    val currentBusy by rememberUpdatedState(busy)
    val currentFields by rememberUpdatedState(fields)
    val currentFill by rememberUpdatedState(onFill)
    DisposableEffect(controller, lifecycle, eligible) {
        val target = NfcCardEntryController.Editor(owner, kind, { currentBusy }, { currentFields }, { currentFill(it) })
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { if (eligible) controller.register(target) }
            override fun onPause(owner: LifecycleOwner) { controller.unregister(target.owner) }
        }
        lifecycle.addObserver(observer)
        if (eligible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.register(target)
        onDispose { lifecycle.removeObserver(observer); controller.unregister(target.owner) }
    }
}
