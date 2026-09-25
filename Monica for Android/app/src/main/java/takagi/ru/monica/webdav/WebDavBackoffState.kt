package takagi.ru.monica.webdav

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 按主机追踪 WebDAV 调用的 backoff 状态，作为应用层面的速率限制器。
 *
 * 行为摘要：
 * - `recordRateLimit` 在每次 429 / 503 响应后调用；
 *   若传入 `retryAfterMillis>0` 则严格遵守服务器指示，否则采用指数退避
 *   `min(60s, 1s * 2^(n-1))`，其中 n 为 60 秒窗口内累计触发次数。
 * - 60 秒窗口内累计 ≥3 次 429 会把主机临时禁用 5 分钟。
 * - `recordSuccess` 在业务成功后调用，清除该主机的所有 backoff 状态。
 *
 * 线程安全：外层 [ConcurrentHashMap] + 每个 [HostState] 的 `synchronized` 块。
 *
 * 可选持久化：调用 [attachPersistence] 后，状态会同步到
 * `webdav_backoff` SharedPreferences，使得 WorkManager 进程重启仍能遵守 backoff。
 */
object WebDavBackoffState {

    private const val PREFS_NAME = "webdav_backoff"
    private const val KEY_PREFIX = "host:"
    private const val KEY_BLOCK_UNTIL = ":blockUntil"
    private const val KEY_DISABLE_UNTIL = ":disableUntil"
    private const val KEY_RL_WINDOW = ":rl_window"

    internal const val RATE_LIMIT_WINDOW_MS: Long = 60_000L
    internal const val DISABLE_DURATION_MS: Long = 5 * 60_000L
    internal const val DISABLE_THRESHOLD: Int = 3
    internal const val EXPONENTIAL_BASE_MS: Long = 1_000L
    internal const val EXPONENTIAL_CAP_MS: Long = 60_000L

    private val hosts = ConcurrentHashMap<String, HostState>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Bind persistence without opening SharedPreferences during Application
     * startup. The file-backed store is created on the first real WebDAV
     * backoff operation instead.
     */
    @Synchronized
    fun attachPersistence(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    /** 记录一次 429 / 503 事件，更新阻塞截止时间。 */
    fun recordRateLimit(
        host: String,
        retryAfterMillis: Long? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        if (host.isEmpty()) return
        val state = stateForHost(host)
        synchronized(state) {
            pruneLocked(state, now)
            state.rateLimitTimestamps.addLast(now)
            val attempt = state.rateLimitTimestamps.size

            val waitMs = if (retryAfterMillis != null && retryAfterMillis > 0L) {
                retryAfterMillis
            } else {
                computeExponentialWait(attempt)
            }
            state.blockUntil = maxOf(state.blockUntil, now + waitMs)

            if (attempt >= DISABLE_THRESHOLD) {
                state.disableUntil = maxOf(state.disableUntil, now + DISABLE_DURATION_MS)
            }
            persistLocked(host, state)
        }
    }

    /** 标记一次业务成功，清除该主机所有 backoff 状态。 */
    fun recordSuccess(host: String) {
        if (host.isEmpty()) return
        val state = hosts[host] ?: return
        synchronized(state) {
            state.blockUntil = 0L
            state.disableUntil = 0L
            state.rateLimitTimestamps.clear()
            clearLocked(host)
        }
    }

    /** 当前主机是否应阻塞请求（尚未到阻塞截止或临时禁用中）。 */
    fun shouldBlock(host: String, now: Long = System.currentTimeMillis()): Boolean {
        if (host.isEmpty()) return false
        val state = stateForHost(host)
        synchronized(state) {
            return now < maxOf(state.blockUntil, state.disableUntil)
        }
    }

    /** 建议等待毫秒数；若无 backoff 返回 0。 */
    fun suggestedWaitMillis(host: String, now: Long = System.currentTimeMillis()): Long {
        if (host.isEmpty()) return 0L
        val state = stateForHost(host)
        synchronized(state) {
            val deadline = maxOf(state.blockUntil, state.disableUntil)
            return (deadline - now).coerceAtLeast(0L)
        }
    }

    /** 该主机是否处于 5 分钟临时禁用状态。 */
    fun isTemporarilyDisabled(host: String, now: Long = System.currentTimeMillis()): Boolean {
        if (host.isEmpty()) return false
        val state = stateForHost(host)
        synchronized(state) {
            return now < state.disableUntil
        }
    }

    /** 仅测试用：清空所有内存状态。 */
    internal fun resetForTest() {
        hosts.clear()
        persistence()?.edit()?.clear()?.apply()
    }

    internal fun computeExponentialWait(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        if (safeAttempt >= 64) return EXPONENTIAL_CAP_MS
        val naive = EXPONENTIAL_BASE_MS shl (safeAttempt - 1)
        return minOf(EXPONENTIAL_CAP_MS, naive)
    }

    private fun pruneLocked(state: HostState, now: Long) {
        while (state.rateLimitTimestamps.isNotEmpty() &&
            now - state.rateLimitTimestamps.first() > RATE_LIMIT_WINDOW_MS
        ) {
            state.rateLimitTimestamps.removeFirst()
        }
    }

    private fun persistLocked(host: String, state: HostState) {
        val p = persistence() ?: return
        val window = state.rateLimitTimestamps.joinToString(",")
        val key = storageKey(host)
        p.edit()
            .putLong(key + KEY_BLOCK_UNTIL, state.blockUntil)
            .putLong(key + KEY_DISABLE_UNTIL, state.disableUntil)
            .putString(key + KEY_RL_WINDOW, window)
            .apply()
    }

    private fun clearLocked(host: String) {
        val p = persistence() ?: return
        val key = storageKey(host)
        val legacyKey = legacyStorageKey(host)
        p.edit()
            .remove(key + KEY_BLOCK_UNTIL)
            .remove(key + KEY_DISABLE_UNTIL)
            .remove(key + KEY_RL_WINDOW)
            .remove(legacyKey + KEY_BLOCK_UNTIL)
            .remove(legacyKey + KEY_DISABLE_UNTIL)
            .remove(legacyKey + KEY_RL_WINDOW)
            .apply()
    }

    private fun stateForHost(host: String): HostState {
        return hosts.getOrPut(host) {
            restoreHostFromPrefs(host) ?: HostState()
        }
    }

    private fun restoreHostFromPrefs(host: String): HostState? {
        val p = persistence() ?: return null
        readState(p, storageKey(host))?.let { return it }

        val legacyKey = legacyStorageKey(host)
        val legacyState = readState(p, legacyKey) ?: return null
        p.edit()
            .remove(legacyKey + KEY_BLOCK_UNTIL)
            .remove(legacyKey + KEY_DISABLE_UNTIL)
            .remove(legacyKey + KEY_RL_WINDOW)
            .apply()
        persistLocked(host, legacyState)
        return legacyState
    }

    private fun readState(prefs: SharedPreferences, key: String): HostState? {
        val blockUntil = prefs.getLong(key + KEY_BLOCK_UNTIL, 0L)
        val disableUntil = prefs.getLong(key + KEY_DISABLE_UNTIL, 0L)
        val window = prefs.getString(key + KEY_RL_WINDOW, null).orEmpty()
        if (blockUntil <= 0L && disableUntil <= 0L && window.isBlank()) return null
        val state = HostState(blockUntil = blockUntil, disableUntil = disableUntil)
        window
            .split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull() }
            .forEach { state.rateLimitTimestamps.addLast(it) }
        return state
    }

    private fun persistence(): SharedPreferences? {
        prefs?.let { return it }
        val context = appContext ?: return null
        synchronized(this) {
            prefs?.let { return it }
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also {
                prefs = it
            }
        }
    }

    private fun storageKey(host: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(host.lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return KEY_PREFIX + digest
    }

    private fun legacyStorageKey(host: String): String = KEY_PREFIX + host

    internal class HostState(
        @Volatile var blockUntil: Long = 0L,
        @Volatile var disableUntil: Long = 0L,
    ) {
        val rateLimitTimestamps: ArrayDeque<Long> = ArrayDeque(16)
    }
}
