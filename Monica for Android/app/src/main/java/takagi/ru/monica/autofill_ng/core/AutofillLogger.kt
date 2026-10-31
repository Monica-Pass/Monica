package takagi.ru.monica.autofill_ng.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import takagi.ru.monica.utils.BoundedLogExecutorFactory

/**
 * 自动填充日志系统
 * 
 * 功能:
 * - 分级别日志记录 (DEBUG, INFO, WARN, ERROR)
 * - 自动脱敏敏感信息 (密码、邮箱、手机号等)
 * - 内存缓存最近 500 条日志
 * - 支持导出诊断报告
 * - 分类管理 (Service, Request, Matching, Parsing, Filling, Performance, Error, UserAction)
 * 
 * 使用示例:
 * ```kotlin
 * AutofillLogger.d(AutofillLogCategory.REQUEST, "开始处理填充请求", mapOf("packageName" to "com.example"))
 * AutofillLogger.e(AutofillLogCategory.ERROR, "填充失败", error = exception, mapOf("fieldId" to "username"))
 * ```
 * 
 * @author Monica Team
 * @since 1.0
 */
object AutofillLogger {
    
    private const val TAG = "MonicaAutofill"
    private const val MAX_LOG_ENTRIES = 500
    private const val LOG_DIR_NAME = "autofill_logs"
    private const val LOG_FILE_NAME = "autofill_structured_v3.log"
    private const val MAX_LOG_FILE_BYTES = 1024 * 1024L
    
    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }
    
    /**
     * 日志条目
     */
    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val category: String,
        val message: String,
        val metadata: Map<String, Any> = emptyMap()
    ) {
        fun format(): String {
            val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val timeStr = dateFormat.format(Date(timestamp))
            val metaStr = if (metadata.isNotEmpty()) " $metadata" else ""
            return "$timeStr [${level.name}] [$category] $message$metaStr"
        }
    }
    
    private val logs = mutableListOf<LogEntry>()
    private var isEnabled = true // 默认启用，生产环境可设置为 false
    private val fileLock = Any()
    private val writeExecutor = BoundedLogExecutorFactory.createSingleThreadExecutor("monica-autofill-log")
    @Volatile
    private var persistentLogFile: File? = null

    /**
     * 初始化持久化日志文件。
     * 可重复调用，首次成功后后续调用无副作用。
     */
    fun initialize(context: Context) {
        if (persistentLogFile != null) return
        synchronized(fileLock) {
            if (persistentLogFile != null) return
            runCatching {
                val logDir = File(context.applicationContext.filesDir, LOG_DIR_NAME)
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }
                persistentLogFile = File(logDir, LOG_FILE_NAME)
            }
        }
    }
    
    /**
     * DEBUG 级别日志
     */
    fun d(category: String, message: String, metadata: Map<String, Any> = emptyMap()) {
        log(Level.DEBUG, category, message, metadata)
    }
    
    /**
     * INFO 级别日志
     */
    fun i(category: String, message: String, metadata: Map<String, Any> = emptyMap()) {
        log(Level.INFO, category, message, metadata)
    }
    
    /**
     * WARN 级别日志
     */
    fun w(category: String, message: String, metadata: Map<String, Any> = emptyMap()) {
        log(Level.WARN, category, message, metadata)
    }
    
    /**
     * ERROR 级别日志
     */
    fun e(
        category: String,
        message: String,
        error: Throwable? = null,
        metadata: Map<String, Any> = emptyMap()
    ) {
        val meta = metadata.toMutableMap()
        error?.let {
            meta["error"] = it.toString()
            meta["stackTrace"] = it.stackTraceToString().take(500)
        }
        log(Level.ERROR, category, message, meta)
    }
    
    /**
     * 核心日志方法
     */
    private fun log(level: Level, category: String, message: String, metadata: Map<String, Any>) {
        // 自动脱敏
        val sanitizedMessage = sanitize(message)
        val sanitizedMetadata = metadata.mapValues { sanitize(it.value.toString()) }
        val entry = LogEntry(System.currentTimeMillis(), level, category, sanitizedMessage, sanitizedMetadata)
        
        // 控制台输出
        val logMessage = "[$category] $sanitizedMessage${
            sanitizedMetadata.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
        }"
        emitAndroidLog(level, logMessage)
        
        // 内存存储（最近 500 条）
        if (isEnabled) {
            synchronized(logs) {
                logs.add(entry)
                if (logs.size > MAX_LOG_ENTRIES) {
                    logs.removeAt(0)
                }
            }
            appendPersistentLog(entry)
        }
    }
    
    /**
     * 脱敏处理
     * 
     * 脱敏规则:
     * 1. 密码字段: password="xxx" -> password="***"
     * 2. 邮箱地址: user@example.com -> ***@***.com
     * 3. 手机号: 13812345678 -> ***********
     * 4. 身份证号: 110101199001011234 -> ******************
     */
    private fun sanitize(text: String): String {
        return text
            // 密码字段
            .replace(
                Regex("(password|pwd|passwd)[\"']?\\s*[:=]\\s*[\"']?([^\"',}\\s]+)", RegexOption.IGNORE_CASE),
                "$1=***"
            )
            // 邮箱地址
            .replace(
                Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"),
                "***@***.com"
            )
            // 手机号（11位）
            .replace(Regex("\\b1[3-9]\\d{9}\\b"), "***********")
            // 身份证号（18位）
            .replace(Regex("\\b\\d{17}[0-9Xx]\\b"), "******************")
            // Token/密钥（20+位字母数字组合）
            .replace(Regex("\\b[A-Za-z0-9]{20,}\\b"), "***TOKEN***")
    }
    
    /**
     * 导出日志
     * 
     * @param maxEntries 最多导出多少条日志
     * @return 格式化的日志字符串
     */
    fun exportLogs(maxEntries: Int = 500): String {
        return synchronized(logs) {
            val entriesToExport = logs.takeLast(maxEntries)
            buildString {
                appendLine("=== Monica Autofill 诊断日志 ===")
                appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("日志条数: ${entriesToExport.size}")
                appendLine("=====================================\n")
                
                entriesToExport.forEach { entry ->
                    appendLine(entry.format())
                }
                
                appendLine("\n=== 日志结束 ===")
            }
        }
    }
    
    /**
     * 获取最近的日志
     */
    fun getRecentLogs(count: Int = 100): List<LogEntry> {
        return synchronized(logs) {
            logs.takeLast(count).toList()
        }
    }
    
    /**
     * 清除日志
     */
    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
        synchronized(fileLock) {
            runCatching {
                persistentLogFile?.let { file ->
                    if (file.exists()) {
                        file.writeText("")
                    }
                }
            }
        }
        emitAndroidLog(Level.INFO, "日志已清除")
    }
    
    /**
     * 启用/禁用日志
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        emitAndroidLog(Level.INFO, "日志系统${if (enabled) "已启用" else "已禁用"}")
    }
    
    /**
     * 获取统计信息
     */
    fun getStats(): Map<String, Any> {
        synchronized(logs) {
            val stats = mutableMapOf<String, Any>()
            stats["totalLogs"] = logs.size
            stats["levelCounts"] = logs.groupingBy { it.level }.eachCount()
            stats["categoryCounts"] = logs.groupingBy { it.category }.eachCount()
            
            if (logs.isNotEmpty()) {
                stats["oldestLog"] = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(logs.first().timestamp))
                stats["newestLog"] = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(logs.last().timestamp))
            }
            
            return stats
        }
    }

    /**
     * 导出持久化日志（按行截取最近 maxEntries 条）。
     */
    fun exportPersistedLogs(maxEntries: Int = 1200): String {
        val file = persistentLogFile ?: return ""
        if (!file.exists()) return ""
        return synchronized(fileLock) {
            runCatching {
                file.readLines()
                    .takeLast(maxEntries.coerceAtLeast(1))
                    .joinToString(separator = "\n")
            }.getOrDefault("")
        }
    }

    /**
     * 封装 android.util.Log，避免在 JVM 单元测试环境崩溃
     */
    private fun emitAndroidLog(level: Level, message: String) {
        try {
            // Keep the fixed save-handoff diagnostics in release Logcat too;
            // ordinary verbose autofill logging retains its existing R8 policy.
            if (message.startsWith("[SAVE] ")) {
                Log.println(Log.INFO, TAG, message)
                return
            }
            when (level) {
                Level.DEBUG -> Log.d(TAG, message)
                Level.INFO -> Log.i(TAG, message)
                Level.WARN -> Log.w(TAG, message)
                Level.ERROR -> Log.e(TAG, message)
            }
        } catch (ignored: RuntimeException) {
            // 本地 JVM 测试环境没有 Android Log 实现，直接忽略
        }
    }

    private fun appendPersistentLog(entry: LogEntry) {
        val file = persistentLogFile ?: return
        writeExecutor.execute {
            val formatted = entry.format()
            synchronized(fileLock) {
                runCatching {
                    if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                        file.writeText(
                            "=== log rotated at ${
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                            } ===\n"
                        )
                    }
                    file.appendText(formatted + "\n")
                }
            }
        }
    }
}

/**
 * 日志分类
 */
object AutofillLogCategory {
    /** 服务生命周期 (onCreate, onDestroy, onConnected) */
    const val SERVICE = "Service"
    
    /** 填充请求 (onFillRequest, onSaveRequest) */
    const val REQUEST = "Request"
    
    /** 数据匹配 (域名匹配、包名匹配、模糊搜索) */
    const val MATCHING = "Matching"
    
    /** 结构解析 (AssistStructure 解析、字段检测) */
    const val PARSING = "Parsing"
    
    /** 字段填充 (数据填充、Presentation 构建) */
    const val FILLING = "Filling"
    
    /** 性能监控 (耗时统计、内存占用) */
    const val PERFORMANCE = "Perf"
    
    /** 错误追踪 (异常捕获、错误恢复) */
    const val ERROR = "Error"
    
    /** 用户操作 (选择密码、取消操作、生物识别) */
    const val USER_ACTION = "UserAction"
}


