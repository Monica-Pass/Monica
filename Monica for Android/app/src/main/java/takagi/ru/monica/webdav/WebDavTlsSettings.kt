package takagi.ru.monica.webdav

import android.content.Context
import android.content.SharedPreferences

/**
 * WebDAV TLS 校验档位的进程级单一数据源。
 *
 * 设计动机与 [WebDavBackoffState] 相同：[WebDavGateway] 是一个无 Context 的
 * `object`，而 WorkManager 触发的后台备份可能运行在没有任何 Activity 的进程里，
 * 因此档位必须能在不持有 Context 的情况下读取，同时又要在进程重启后仍然生效。
 *
 * 存储位置复用 `webdav_config` SharedPreferences，使
 * [takagi.ru.monica.utils.WebDavHelper.clearConfig] 清空连接配置时能一并重置档位。
 * 注意：档位本身不是敏感信息（不含凭据），因此按明文布尔/字符串存储即可，
 * 无需经过 [takagi.ru.monica.security.SecurityManager]。
 *
 * 线程安全：写入走 `@Synchronized`，读取走 `@Volatile` 缓存字段。
 */
object WebDavTlsSettings {

    internal const val PREFS_NAME = "webdav_config"
    internal const val KEY_TLS_MODE = "webdav_tls_mode"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var cachedMode: WebDavTlsMode = WebDavTlsMode.DEFAULT

    /**
     * 绑定持久化存储并把已保存的档位载入内存缓存。
     *
     * 只需在应用启动阶段调用一次；重复调用会被忽略。
     */
    @Synchronized
    fun attachPersistence(context: Context) {
        if (prefs != null) return
        val sharedPreferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sharedPreferences
        cachedMode = WebDavTlsMode.fromStorage(
            sharedPreferences.getString(KEY_TLS_MODE, null)
        )
    }

    /**
     * 当前生效的档位。
     *
     * 若尚未调用 [attachPersistence]（例如单元测试或极早期的初始化路径），
     * 返回 [WebDavTlsMode.DEFAULT]，即最严格的校验。
     */
    fun currentMode(): WebDavTlsMode = cachedMode

    /**
     * 读取档位，必要时用传入的 Context 惰性绑定持久化。
     *
     * 供持有 Context 的调用方（如 [takagi.ru.monica.utils.WebDavHelper]）使用，
     * 避免依赖 Application 初始化顺序。
     */
    fun currentMode(context: Context): WebDavTlsMode {
        attachPersistence(context)
        return cachedMode
    }

    /** 写入档位；同时更新内存缓存，使后续构造的客户端立即生效。 */
    @Synchronized
    fun setMode(context: Context, mode: WebDavTlsMode) {
        attachPersistence(context)
        cachedMode = mode
        prefs?.edit()?.putString(KEY_TLS_MODE, mode.name)?.apply()
    }

    /**
     * 重置为默认档位并清除持久化值；供清除 WebDAV 配置时调用。
     *
     * 需要 [context] 是因为清除配置的调用点可能早于 Application 初始化
     * （例如 WorkManager 直接实例化 WebDavHelper），此时必须先绑定持久化，
     * 否则旧档位只会从内存里消失、下次冷启动又被读回来。
     */
    @Synchronized
    fun reset(context: Context) {
        attachPersistence(context)
        cachedMode = WebDavTlsMode.DEFAULT
        prefs?.edit()?.remove(KEY_TLS_MODE)?.apply()
    }

    /** 仅测试用：解除持久化绑定并恢复默认档位。 */
    internal fun resetForTest() {
        prefs = null
        cachedMode = WebDavTlsMode.DEFAULT
    }
}
