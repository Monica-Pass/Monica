package takagi.ru.monica.passkey

import android.content.Context
import takagi.ru.monica.R
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import takagi.ru.monica.data.PasswordDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import takagi.ru.monica.security.SecurityManager

/**
 * Monica Credential Provider Service
 * 
 * Android 14+ (API 34) Credential Provider API 实现
 * 为系统和其他应用提供 Passkey 凭据服务
 * 
 * 功能：
 * 1. 创建 Passkey - 当用户在网站/应用注册时
 * 2. 获取 Passkey - 当用户在网站/应用登录时
 * 3. 清除凭据 - 当用户登出时
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class MonicaCredentialProviderService : CredentialProviderService() {
    
    companion object {
        private const val TAG = "MonicaCredentialProvider"
        
        // Intent extras
        const val EXTRA_REQUEST_TYPE = "request_type"
        const val EXTRA_REQUEST_JSON = "request_json"
        const val EXTRA_CREDENTIAL_ID = "credential_id"
        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_RP_ID = "rp_id"
        const val EXTRA_USER_NAME = "user_name"
        const val EXTRA_USER_DISPLAY_NAME = "user_display_name"
        
        const val REQUEST_TYPE_CREATE = "create"
        const val REQUEST_TYPE_GET = "get"
    }
    
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private val database: PasswordDatabase by lazy {
        PasswordDatabase.getDatabase(applicationContext)
    }

    private val securityManager: SecurityManager by lazy {
        SecurityManager(applicationContext)
    }
    
    private val strings by lazy { AppLocaleStringResolver(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    /**
     * 构建 PendingIntent 用于启动 Passkey Activity
     * 
     * 重要：不要使用 FLAG_ACTIVITY_NEW_TASK！
     * Credential Provider API 会自动处理任务栈，
     * 使用 NEW_TASK 会导致 Activity 在错误的任务栈中启动，
     * 从而无法正确返回到调用方应用。
     */
    private fun buildPendingIntent(intent: Intent, requestCode: Int): PendingIntent {
        // 注意：不设置任何 Activity flags，让系统自动处理
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    
    /**
     * 处理开始创建凭据请求
     */
    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        Log.i(TAG, "onBeginCreateCredentialRequest")
        
        try {
            when (request) {
                is BeginCreatePublicKeyCredentialRequest -> {
                    handleBeginCreatePasskeyRequest(request, callback)
                }
                else -> {
                    Log.w(TAG, "Unsupported credential type")
                    callback.onError(CreateCredentialUnknownException("Unsupported credential type"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onBeginCreateCredentialRequest", e)
            callback.onError(CreateCredentialUnknownException(e.message))
        }
    }
    
    /**
     * 处理开始获取凭据请求
     */
    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        Log.i(TAG, "onBeginGetCredentialRequest")
        
        serviceScope.launch {
            try {
                val credentialEntries = mutableListOf<CredentialEntry>()
                
                for (option in request.beginGetCredentialOptions) {
                    when (option) {
                        is BeginGetPublicKeyCredentialOption -> {
                            val entries = handleBeginGetPasskeyRequest(option)
                            credentialEntries.addAll(entries)
                        }
                    }
                }
                
                val response = BeginGetCredentialResponse.Builder()
                    .setCredentialEntries(credentialEntries)
                    .build()
                
                callback.onResult(response)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in onBeginGetCredentialRequest", e)
                callback.onError(GetCredentialUnknownException(e.message))
            }
        }
    }
    
    /**
     * 处理清除凭据状态请求
     */
    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        Log.d(TAG, "onClearCredentialStateRequest")
        // Monica 不维护登录状态，直接返回成功
        callback.onResult(null)
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 处理开始创建 Passkey 请求
     */
    private fun handleBeginCreatePasskeyRequest(
        request: BeginCreatePublicKeyCredentialRequest,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        try {
            val requestJson = request.requestJson
            val json = JSONObject(requestJson)
            
            // 解析 RP 信息
            val rpJson = json.optJSONObject("rp")
            val rpId = rpJson?.optString("id") ?: json.optString("rpId", "")
            val rpName = rpJson?.optString("name") ?: rpId
            
            // 解析用户信息
            val userJson = json.optJSONObject("user")
            val userName = userJson?.optString("name") ?: ""
            val userDisplayName = userJson?.optString("displayName") ?: userName
            
            Log.i(TAG, "Create passkey for RP: $rpId, user=$userName")
            
            // 创建 PendingIntent 用于启动确认 Activity
            val intent = Intent(this, PasskeyCreateActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_TYPE, REQUEST_TYPE_CREATE)
                putExtra(EXTRA_REQUEST_JSON, requestJson)
                putExtra(EXTRA_RP_ID, rpId)
                putExtra(EXTRA_USER_NAME, userName)
                putExtra(EXTRA_USER_DISPLAY_NAME, userDisplayName)
            }
            
            val pendingIntent = buildPendingIntent(
                intent,
                SystemClock.uptimeMillis().toInt()
            )
            
            // 创建账户条目
            val createEntry = CreateEntry.Builder(
                "${strings.get(R.string.app_label)} - $rpName",
                pendingIntent
            )
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher_modern))
                .setDescription(strings.get(R.string.legacy_ui_passkey_create_for, userName))
                .build()
            
            val response = BeginCreateCredentialResponse.Builder()
                .addCreateEntry(createEntry)
                .build()
            
            callback.onResult(response)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing create request", e)
            callback.onError(CreateCredentialUnknownException(e.message))
        }
    }
    
    /**
     * 处理开始获取 Passkey 请求
     * 
     * 使用严格筛选逻辑：
     * 1. 如果 allowCredentials 列表存在，只返回列表中指定的凭据
     * 2. 如果 rpId 存在，只返回匹配 rpId 的凭据
     * 3. 否则返回所有可发现凭据（discoverable credentials）
     */
    private suspend fun handleBeginGetPasskeyRequest(
        option: BeginGetPublicKeyCredentialOption
    ): List<CredentialEntry> {
        val entries = mutableListOf<CredentialEntry>()
        
        try {
            val requestJson = option.requestJson
            val json = JSONObject(requestJson)
            
            val rpId = json.optString("rpId", "")
            Log.i(TAG, "Get passkeys for RP: $rpId")
            
            // 解析 allowCredentials 列表，并进行规范化
            val allowCredentials = json.optJSONArray("allowCredentials")
            val allowedCredentialIds = mutableSetOf<String>()
            if (allowCredentials != null && allowCredentials.length() > 0) {
                for (i in 0 until allowCredentials.length()) {
                    val cred = allowCredentials.optJSONObject(i)
                    val credId = cred?.optString("id") ?: continue
                    if (credId.isNotBlank()) {
                        normalizeCredentialId(credId)?.let { normalized ->
                            allowedCredentialIds.add(normalized)
                        }
                    }
                }
                Log.d(TAG, "allowCredentials specified: ${allowedCredentialIds.size} credentials")
            }

            warmUpDatabase()
            var filteredPasskeys = resolvePasskeys(
                rpId = rpId,
                allowedCredentialIds = allowedCredentialIds,
                strictAllowCredentials = true
            )

            // Some OEMs/request payloads can cause transient allowCredentials mismatch.
            // Fall back to RP/discoverable query to avoid empty provider sheet.
            if (filteredPasskeys.isEmpty() && allowedCredentialIds.isNotEmpty()) {
                Log.w(TAG, "No strict allowCredentials match, fallback to RP/discoverable")
                filteredPasskeys = resolvePasskeys(
                    rpId = rpId,
                    allowedCredentialIds = allowedCredentialIds,
                    strictAllowCredentials = false
                )
            }

            // Right after app update, first provider call may race with db/cache readiness.
            if (filteredPasskeys.isEmpty() && isRecentlyUpdated()) {
                delay(120)
                warmUpDatabase()
                filteredPasskeys = resolvePasskeys(
                    rpId = rpId,
                    allowedCredentialIds = allowedCredentialIds,
                    strictAllowCredentials = false
                )
            }
            
            Log.d(TAG, "Resolved passkeys count=${filteredPasskeys.size}, rpId=$rpId")
            
            for (passkey in filteredPasskeys) {
                val intent = Intent(this, PasskeyAuthActivity::class.java).apply {
                    action = buildGetCredentialIntentAction(passkey)
                    putExtra(EXTRA_REQUEST_TYPE, REQUEST_TYPE_GET)
                    putExtra(EXTRA_REQUEST_JSON, requestJson)
                    putExtra(EXTRA_CREDENTIAL_ID, passkey.credentialId)
                    putExtra(EXTRA_RECORD_ID, passkey.id)
                }
                
                val pendingIntent = buildPendingIntent(
                    intent,
                    buildGetCredentialRequestCode(passkey)
                )
                
                val entry = PublicKeyCredentialEntry.Builder(
                    this,
                    passkey.displayTitle(),
                    pendingIntent,
                    option
                )
                    .setDisplayName(passkey.rpName)
                    .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher_modern))
                    .build()
                
                entries.add(entry)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting passkeys", e)
        }
        
        return entries
    }

    private fun isUsablePasskey(passkey: takagi.ru.monica.data.PasskeyEntry): Boolean {
        return PasskeyCredentialDiscoveryPolicy.isUsable(
            passkey = passkey,
            privateKeyAvailable = PasskeyPrivateKeyStore.hasUsablePrivateKey(
                securityManager,
                passkey.privateKeyAlias,
            ),
        )
    }

    private suspend fun resolvePasskeys(
        rpId: String,
        allowedCredentialIds: Set<String>,
        strictAllowCredentials: Boolean
    ): List<takagi.ru.monica.data.PasskeyEntry> {
        val normalizedRpId = PasskeyRpIdNormalizer.normalize(rpId)

        suspend fun rpIdCandidates(): List<takagi.ru.monica.data.PasskeyEntry> {
            if (rpId.isBlank()) return emptyList()
            val direct = runCatching { database.passkeyDao().getPasskeysByRpIdSync(rpId) }
                .getOrDefault(emptyList())
            if (direct.isNotEmpty()) return direct

            if (normalizedRpId.isNullOrBlank()) return emptyList()
            val normalizedMatches = runCatching { database.passkeyDao().getAllPasskeysSync() }
                .getOrDefault(emptyList())
                .filter { PasskeyRpIdNormalizer.isEquivalent(it.rpId, normalizedRpId) }

            if (normalizedMatches.isNotEmpty()) {
                Log.i(TAG, "RP normalization fallback matched ${normalizedMatches.size} passkeys for $rpId")
            }
            return normalizedMatches
        }

        val passkeys = if (allowedCredentialIds.isNotEmpty() && strictAllowCredentials) {
            val candidates = if (rpId.isNotBlank()) {
                rpIdCandidates()
            } else {
                database.passkeyDao().getAllPasskeysSync()
            }
            PasskeyCredentialDiscoveryPolicy.filterByAllowedCredentialIds(
                candidates = candidates,
                allowedCredentialIds = allowedCredentialIds,
                normalizer = ::normalizeCredentialId,
            )
        } else if (rpId.isNotBlank()) {
            rpIdCandidates()
        } else {
            database.passkeyDao().getDiscoverablePasskeysSync()
        }
        return passkeys.filter(::isUsablePasskey)
    }

    private fun warmUpDatabase() {
        runCatching {
            database.openHelper.writableDatabase.query("SELECT 1").close()
        }.onFailure { error ->
            Log.w(TAG, "Database warmup failed", error)
        }
    }

    private fun isRecentlyUpdated(windowMs: Long = 5 * 60 * 1000L): Boolean {
        return runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val now = System.currentTimeMillis()
            (now - info.lastUpdateTime) in 0..windowMs
        }.getOrDefault(false)
    }

    private fun normalizeCredentialId(credentialId: String): String? {
        return PasskeyCredentialIdCodec.normalize(credentialId)
    }

    private fun buildGetCredentialIntentAction(passkey: takagi.ru.monica.data.PasskeyEntry): String {
        val recordPart = passkey.id.takeIf { it > 0L }?.toString() ?: "no_record"
        return "$packageName.PASSKEY_GET.$recordPart.${passkey.credentialId.hashCode()}"
    }

    private fun buildGetCredentialRequestCode(passkey: takagi.ru.monica.data.PasskeyEntry): Int {
        return buildString {
            append("get:")
            append(passkey.id)
            append(':')
            append(passkey.credentialId)
            append(':')
            append(passkey.rpId)
            append(':')
            append(passkey.userName)
        }.hashCode()
    }
}
