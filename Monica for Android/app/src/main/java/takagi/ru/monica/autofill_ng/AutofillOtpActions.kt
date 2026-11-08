package takagi.ru.monica.autofill_ng

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import takagi.ru.monica.autofill_ng.core.AutofillLogger
import takagi.ru.monica.autofill_ng.service.AutofillOtpNotificationService
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator

/** Optional post-selection work. Never writes credentials or advances an HOTP counter. */
internal class AutofillOtpActions(context: Context, private val bindingCache: AutofillOtpBindingCache? = null) {
    private val context = context.applicationContext
    private val securityManager by lazy { SecurityManager(this.context) }

    /** Do this before obtaining any API 30 inline objects in the fill-request coroutine. */
    suspend fun eligiblePasswordIds(passwords: List<PasswordEntry>): Set<Long> {
        val ids = mutableSetOf<Long>()
        val indexResolved = optional { withTimeoutOrNull(100) {
            val preferences = AutofillPreferences(context)
            if (passwords.isEmpty() ||
                (!preferences.isOtpNotificationEnabled.first() && !preferences.isAutoCopyOtpEnabled.first())
            ) return@withTimeoutOrNull true
            passwords.filter { it.authenticatorKey.isNotBlank() }.mapTo(ids) { it.id }
            if (ids.size < passwords.size) {
                val candidates = passwords.mapTo(hashSetOf()) { it.id }
                val load: suspend () -> Set<Long> = { validators().mapNotNullTo(hashSetOf()) { it.boundPasswordId } }
                val bound = bindingCache?.getOrLoad(load)?.await() ?: load()
                ids.addAll(bound.filter { it in candidates })
            }
            true
        } }
        // Keep the request bounded without dropping a cold binding's post-fill
        // action. Selection checks preferences and the authoritative binding again;
        // credentials with no OTP simply complete without an optional action.
        if (indexResolved != true) passwords.mapTo(ids) { it.id }
        return ids
    }

    suspend fun process(password: PasswordEntry, isAuthorized: () -> Boolean = { true }) {
        // The optional long-lived OTP service has no native revision/lock authorization.
        // Explicit OTP fields still use resolveData inside the selected fill request.
        if (AutofillPasswordSource.isNative(password)) return
        optional {
            if (!isAuthorized()) return@optional
            val current = AutofillRoomCredentialReader.read(PasswordDatabase.getDatabase(context), password.id)
            if (current !is AutofillRoomCredentialReader.Result.Available || current.entry != password) return@optional
            val preferences = AutofillPreferences(context)
            val notify = preferences.isOtpNotificationEnabled.first()
            val copy = preferences.isAutoCopyOtpEnabled.first()
            if (!notify && !copy) return@optional
            val data = resolveData(password) ?: return@optional
            val code = TotpGenerator.generateOtp(data).takeIf { it.isNotBlank() } ?: return@optional
            currentCoroutineContext().ensureActive()
            if (!isAuthorized()) return@optional
            // Failure in either optional action must not prevent the other action or autofill.
            if (copy) optional {
                val current = AutofillRoomCredentialReader.read(PasswordDatabase.getDatabase(context), password.id)
                if (current !is AutofillRoomCredentialReader.Result.Available || current.entry != password) return@optional
                withContext(Dispatchers.Main.immediate) {
                    if (!isAuthorized()) return@withContext
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("OTP Code", code))
                }
            }
            if (notify) {
                val duration = preferences.otpNotificationDuration.first()
                val current = AutofillRoomCredentialReader.read(PasswordDatabase.getDatabase(context), password.id)
                if (current !is AutofillRoomCredentialReader.Result.Available || current.entry != password) return@optional
                withContext(Dispatchers.Main.immediate) {
                    if (!isAuthorized()) return@withContext
                    AutofillOtpNotificationService.start(context, data, password.title, duration)
                }
            }
        }
    }

    suspend fun resolveData(password: PasswordEntry): TotpData? = optional {
        withContext(Dispatchers.IO) {
            val inline = optional {
                TotpDataResolver.fromAuthenticatorKey(
                    if (AutofillPasswordSource.isNative(password)) password.authenticatorKey
                    else securityManager.decryptDataIfMonicaCiphertext(password.authenticatorKey)
                )
            }
            val existing = validators()
            val selected = existing.firstOrNull { it.boundPasswordId == password.id }
                ?: inline?.let { key -> existing.firstOrNull { identity(it) == identity(key) } }
                ?: inline
                ?: return@withContext null
            val resolved = selected.copy(
                secret = (if (AutofillPasswordSource.isNative(password) && selected === inline) selected.secret
                    else securityManager.decryptDataIfMonicaCiphertext(selected.secret)).trim()
            )
            resolved.takeIf(::hasUsableSecret)
        }
    }

    private suspend fun validators(): List<TotpData> = withContext(Dispatchers.IO) {
        PasswordDatabase.getDatabase(context).secureItemDao().getActiveItemsByTypeSync(ItemType.TOTP)
            .mapNotNull { item ->
                currentCoroutineContext().ensureActive()
                TotpDataResolver.parseStoredItemData(
                    itemData = item.itemData,
                    fallbackIssuer = item.title,
                    decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext,
                )
            }
    }

    private fun identity(data: TotpData): List<Any> {
        val normalized = TotpDataResolver.normalizeTotpData(data)
        return listOf(normalized.otpType, normalized.secret, normalized.digits,
            normalized.period, normalized.algorithm, normalized.counter)
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Exceptions from parsers/providers can contain sensitive input. Do not log them.
        AutofillLogger.w("OTP", "Optional autofill OTP action unavailable")
        null
    }

    internal companion object {
        private val postFillScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Like the full picker: finish the system fill immediately, then perform optional work. */
        fun launchAfterFill(context: Context, passwordId: Long) {
            if (takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(passwordId)) return
            val appContext = context.applicationContext
            postFillScope.launch {
                try {
                    withTimeoutOrNull(5000) {
                        val security = SecurityManager(appContext)
                        val generation = SecurityManager.dataReadGeneration.value
                        val password = (AutofillRoomCredentialReader.read(PasswordDatabase.getDatabase(appContext), passwordId)
                            as? AutofillRoomCredentialReader.Result.Available)?.entry
                        if (password != null) AutofillOtpActions(appContext).process(password) {
                            security.isDataReadCurrent(generation) &&
                                appContext.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true &&
                                appContext.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == false
                        }
                    }
                } catch (_: Exception) {
                    AutofillLogger.w("OTP", "Fill completed; optional OTP action unavailable")
                }
            }
        }

        fun hasUsableSecret(data: TotpData): Boolean {
            val secret = data.secret.trim()
            if (secret.isEmpty() || secret.startsWith("MDK|") || secret.startsWith("V2|") || secret.startsWith("C2|")) return false
            if (data.otpType == OtpType.MOTP) return true
            val base32 = TotpDataResolver.normalizeBase32Secret(secret)
            return base32.trimEnd('=').length >= 2 && base32.matches(Regex("[A-Z2-7]+={0,6}"))
        }
    }
}
