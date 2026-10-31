package takagi.ru.monica.autofill_ng

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache

/** A bounded, application-owned window: showing it never starts an Activity. */
internal object AutofillSaveOverlay {
    private var active: Request? = null

    fun show(context: Context, pending: PendingIntent, requestId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            pending.cancel()
            AutofillSaveDiagnostics.record(app, requestId, AutofillSaveDiagnostics.Event.OVERLAY_PERMISSION_MISSING)
            throw SecurityException("Overlay permission required")
        }
        active?.close(AutofillSaveDiagnostics.Event.OVERLAY_REPLACED)
        val request = Request(app, pending, requestId)
        active = request
        try {
            request.show()
        } catch (e: Exception) {
            request.close(AutofillSaveDiagnostics.Event.OVERLAY_FAILED)
            throw e
        }
    }

    private class Request(val app: Context, val pending: PendingIntent, val id: String) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val generation = SessionManager.lockGeneration.value
        private val context = LocaleHelper.setLocale(app, StartupLanguageCache.read(app))
        private val dialog = ComponentDialog(context)
        private var registered = false
        private var closed = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) close(AutofillSaveDiagnostics.Event.SCREEN_OFF)
            }
        }

        private fun screenAvailable(): Boolean =
            app.getSystemService(PowerManager::class.java).isInteractive &&
                !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked

        fun show() {
            check(screenAvailable())
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnDismissListener { close(AutofillSaveDiagnostics.Event.OVERLAY_CANCELED) }
            dialog.window!!.apply {
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setGravity(Gravity.BOTTOM)
                setDimAmount(0.32f)
            }
            dialog.setContentView(ComposeView(context).apply {
                setContent {
                    val settings by remember { SettingsManager(context).settingsFlow }
                        .collectAsState(initial = AppSettings())
                    val dark = when (settings.themeMode) {
                        ThemeMode.SYSTEM -> isSystemInDarkTheme()
                        ThemeMode.DARK -> true
                        ThemeMode.LIGHT -> false
                    }
                    MonicaTheme(darkTheme = dark, oledPureBlackEnabled = settings.oledPureBlackEnabled,
                        colorScheme = settings.colorScheme, customPrimaryColor = settings.customPrimaryColor,
                        customSecondaryColor = settings.customSecondaryColor, customTertiaryColor = settings.customTertiaryColor,
                        customNeutralColor = settings.customNeutralColor, customNeutralVariantColor = settings.customNeutralVariantColor) {
                        Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                                .padding(24.dp).testTag("autofill_save_overlay"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(stringResource(R.string.autofill_save_overlay_title), style = MaterialTheme.typography.headlineSmall)
                                Text(stringResource(R.string.autofill_save_overlay_body), style = MaterialTheme.typography.bodyLarge)
                                Button(onClick = ::continueSave, modifier = Modifier.fillMaxWidth().testTag("autofill_save_overlay_continue")) {
                                    Text(stringResource(R.string.autofill_save_overlay_continue))
                                }
                                TextButton(onClick = { close(AutofillSaveDiagnostics.Event.OVERLAY_CANCELED) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        }
                    }
                }
            })
            ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            dialog.show()
            dialog.window!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            AutofillSaveDiagnostics.record(app, id, AutofillSaveDiagnostics.Event.OVERLAY_SHOWN, compatibilityMode = true)
            scope.launch {
                combine(SessionManager.lockGeneration, AutofillPreferences(app).isHyperOsSaveCompatibilityEnabled,
                    AutofillPreferences(app).isRequestSaveDataEnabled) { lock, enabled, saveEnabled ->
                    lock == generation && enabled && saveEnabled
                }.collect { allowed -> if (!allowed) close(AutofillSaveDiagnostics.Event.OVERLAY_INVALIDATED) }
            }
            scope.launch { delay(60_000); close(AutofillSaveDiagnostics.Event.OVERLAY_EXPIRED) }
        }

        private fun continueSave() {
            if (closed) return
            if (SessionManager.lockGeneration.value != generation || !screenAvailable() || !Settings.canDrawOverlays(app)) {
                close(AutofillSaveDiagnostics.Event.OVERLAY_INVALIDATED)
                return
            }
            try {
                val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle() else null
                // Keep the overlay visible until the user-initiated launch is sent.
                // Dismissing first would remove the visible-window BAL eligibility.
                pending.send(app, 0, null, null, null, null, options)
                close(AutofillSaveDiagnostics.Event.OVERLAY_CONTINUED)
            } catch (e: Exception) {
                AutofillSaveDiagnostics.record(app, id, AutofillSaveDiagnostics.Event.OVERLAY_FAILED, e)
                Toast.makeText(context, R.string.legacy_ui_autofill_save_failed, Toast.LENGTH_LONG).show()
                close(AutofillSaveDiagnostics.Event.OVERLAY_FAILED)
            }
        }

        fun close(event: AutofillSaveDiagnostics.Event) {
            if (closed) return
            closed = true
            pending.cancel()
            scope.cancel()
            if (registered) { app.unregisterReceiver(receiver); registered = false }
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            if (active === this) active = null
            AutofillSaveDiagnostics.record(app, id, event, compatibilityMode = true)
        }
    }
}
