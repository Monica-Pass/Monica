package takagi.ru.monica.service

import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.utils.AppLauncherIconManager
import takagi.ru.monica.utils.SettingsManager

class NotificationValidatorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settingsManager: SettingsManager
    private lateinit var secureItemRepository: SecureItemRepository
    private lateinit var securityManager: SecurityManager
    private var updateJob: Job? = null
    private var currentState: ValidatorState = ValidatorState.Idle
    private var hasStartedForeground = false
    @Volatile private var latestIndex: TotpIndex? = null
    @Volatile private var latestSettings: AppSettings? = null

    companion object {
        private const val TAG = "NotificationValidator"
        private const val CHANNEL_ID = "notification_validator_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_COPY = "takagi.ru.monica.service.ACTION_COPY"
        private const val ACTION_SWITCH = "takagi.ru.monica.service.ACTION_SWITCH"
        private const val EXTRA_CODE = "extra_code"
    }

    private val strings by lazy { AppLocaleStringResolver(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        val database = PasswordDatabase.getDatabase(this)
        secureItemRepository = SecureItemRepository(database.secureItemDao())
        createNotificationChannel()
        startPlaceholderNotification()
        serviceScope.launch {
            when (val startup = takagi.ru.monica.security.SecureStorageStartup.prepareForEntry(applicationContext)) {
                is takagi.ru.monica.security.SecureStartupResult.Ready -> {
                    securityManager = startup.manager
                    startObserving()
                }
                is takagi.ru.monica.security.SecureStartupResult.Blocked -> stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_COPY -> {
                val code = intent.getStringExtra(EXTRA_CODE)
                if (!code.isNullOrEmpty()) {
                    copyToClipboard(code)
                }
            }
            ACTION_SWITCH -> handleSwitchRequest()
        }
        return START_STICKY
    }

    private fun copyToClipboard(code: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("TOTP Code", code)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, strings.get(R.string.generator_copied), Toast.LENGTH_SHORT).show()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        serviceScope.cancel()
    }

    private fun startObserving() {
        val totpIndexFlow = secureItemRepository.getItemsByType(ItemType.TOTP)
            .map { items ->
                val entries = items.mapNotNull { item ->
                    TotpDataResolver.parseStoredItemData(
                        itemData = item.itemData,
                        fallbackIssuer = item.title,
                        decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
                    )?.let { data ->
                        TotpEntry(
                            id = item.id,
                            title = item.title,
                            data = data
                        )
                    }
                }

                TotpIndex(entries = entries)
            }

        serviceScope.launch {
            combine(
                settingsManager.settingsFlow,
                totpIndexFlow
            ) { settings, index ->
                settings to index
            }.collectLatest { (settings, index) ->
                latestSettings = settings
                latestIndex = index

                if (!settings.notificationValidatorEnabled) {
                    cancelActiveJobs()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    hasStartedForeground = false
                    currentState = ValidatorState.Idle
                    stopSelf()
                    return@collectLatest
                }

                val targetId = settings.notificationValidatorId.takeIf { it != -1L }
                if (targetId != null) {
                    startUpdatingNotification(targetId, index)
                } else {
                    showNoMatchPlaceholder()
                }
            }
        }
    }

    private fun handleSwitchRequest() {
        val index = latestIndex ?: return
        val entries = index.entries
        if (entries.isEmpty()) return

        val sorted = entries.sortedBy { it.title.lowercase() }
        val currentId = (currentState as? ValidatorState.Showing)?.id
            ?: latestSettings?.notificationValidatorId
            ?: -1L

        val nextEntry = when (val pos = sorted.indexOfFirst { it.id == currentId }) {
            -1 -> sorted.first()
            sorted.lastIndex -> sorted.first()
            else -> sorted[pos + 1]
        }

        serviceScope.launch {
            settingsManager.updateNotificationValidatorId(nextEntry.id)
        }
        startUpdatingNotification(nextEntry.id, index)
    }

    private fun startUpdatingNotification(id: Long, index: TotpIndex) {
        if (currentState is ValidatorState.Showing && (currentState as ValidatorState.Showing).id == id && updateJob?.isActive == true) return

        updateJob?.cancel()
        currentState = ValidatorState.Showing(id)

        val entry = index.entries.firstOrNull { it.id == id }
        if (entry == null) {
            currentState = ValidatorState.Idle
            return
        }
        Log.d(
            TAG,
            "startUpdatingNotification: id=${entry.id}, title=${entry.title}, otpType=${entry.data.otpType}, rawSecretLen=${entry.data.secret.length}, boundPasswordId=${entry.data.boundPasswordId}"
        )

        updateJob = serviceScope.launch {
            val resolvedEntryData = resolveTotpDataForGeneration(entry.data)
            Log.d(
                TAG,
                "resolvedEntryData: id=${entry.id}, otpType=${resolvedEntryData.otpType}, secretLen=${resolvedEntryData.secret.length}"
            )
            while (isActive) {
                val code = TotpGenerator.generateOtp(resolvedEntryData)
                val remaining = TotpGenerator.getRemainingSeconds(resolvedEntryData.period)
                Log.v(
                    TAG,
                    "tick: id=${entry.id}, otpType=${resolvedEntryData.otpType}, codeLen=${code.length}, remaining=$remaining"
                )
                updateNotification(entry.title, code, remaining)
                delay(1000)
            }
        }
    }

    private fun resolveTotpDataForGeneration(data: TotpData): TotpData {
        val decryptResult = runCatching { securityManager.decryptData(data.secret) }
        val decryptedSecret = decryptResult.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        Log.d(
            TAG,
            "resolveTotpDataForGeneration: otpType=${data.otpType}, rawLen=${data.secret.length}, decryptSuccess=${decryptResult.isSuccess && !decryptedSecret.isNullOrEmpty()}, resolvedLen=${decryptedSecret?.length ?: data.secret.length}"
        )
        return if (!decryptedSecret.isNullOrEmpty()) {
            data.copy(secret = decryptedSecret)
        } else {
            data
        }
    }

    private suspend fun ensureForeground(notification: android.app.Notification) {
        withContext(Dispatchers.Main.immediate) {
            if (!hasStartedForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                hasStartedForeground = true
            } else {
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private suspend fun updateNotification(title: String, code: String, remaining: Int) {
        val notificationIntent = Intent(this@NotificationValidatorService, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this@NotificationValidatorService, 0, notificationIntent,
            pendingIntentFlags
        )

        val copyIntent = Intent(this@NotificationValidatorService, NotificationValidatorService::class.java).apply {
            action = ACTION_COPY
            putExtra(EXTRA_CODE, code)
        }
        val copyPendingIntent = PendingIntent.getService(
            this@NotificationValidatorService, 1, copyIntent,
            pendingIntentFlags
        )

        val formattedCode = if (code.length == 6) {
            "${code.substring(0, 3)} ${code.substring(3)}"
        } else {
            code
        }

        val spannableCode = SpannableString(formattedCode)
        spannableCode.setSpan(RelativeSizeSpan(1.5f), 0, spannableCode.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannableCode.setSpan(StyleSpan(Typeface.BOLD), 0, spannableCode.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val switchIntent = Intent(this@NotificationValidatorService, NotificationValidatorService::class.java).apply {
            action = ACTION_SWITCH
        }
        val switchPendingIntent = PendingIntent.getService(
            this@NotificationValidatorService, 2, switchIntent,
            pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(this@NotificationValidatorService, CHANNEL_ID)
            .setContentTitle("$title ($remaining s)")
            .setContentText(spannableCode)
            .setSmallIcon(AppLauncherIconManager.resolveBrandingIconRes(this))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_copy, strings.get(R.string.copy), copyPendingIntent)
            .addAction(R.drawable.ic_copy, strings.get(R.string.notification_validator_switch), switchPendingIntent)
            .build()

        ensureForeground(notification)
    }

    private fun startPlaceholderNotification() {
        serviceScope.launch {
            val notification = NotificationCompat.Builder(this@NotificationValidatorService, CHANNEL_ID)
                .setContentTitle(strings.get(R.string.app_name))
                .setContentText(strings.get(R.string.notification_validator_ready))
                .setSmallIcon(AppLauncherIconManager.resolveBrandingIconRes(this@NotificationValidatorService))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            ensureForeground(notification)
            currentState = ValidatorState.Idle
        }
    }

    private fun showNoMatchPlaceholder() {
        if (currentState is ValidatorState.NoMatch) return
        updateJob?.cancel()
        currentState = ValidatorState.NoMatch

        serviceScope.launch {
            val notification = NotificationCompat.Builder(this@NotificationValidatorService, CHANNEL_ID)
                .setContentTitle(strings.get(R.string.app_name))
                .setContentText(strings.get(R.string.notification_validator_no_match))
                .setSmallIcon(AppLauncherIconManager.resolveBrandingIconRes(this@NotificationValidatorService))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            ensureForeground(notification)
        }
    }

    private fun cancelActiveJobs() {
        updateJob?.cancel()
        currentState = ValidatorState.Idle
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                strings.get(R.string.legacy_ui_notification_validator_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = strings.get(R.string.legacy_ui_notification_validator_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

private data class TotpEntry(
    val id: Long,
    val title: String,
    val data: TotpData
)

private data class TotpIndex(
    val entries: List<TotpEntry>
)

private sealed interface ValidatorState {
    object Idle : ValidatorState
    object NoMatch : ValidatorState
    data class Showing(val id: Long) : ValidatorState
}
