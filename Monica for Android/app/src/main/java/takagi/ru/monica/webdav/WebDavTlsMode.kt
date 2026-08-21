package takagi.ru.monica.webdav

/**
 * WebDAV HTTPS 证书校验策略。
 *
 * 三档之间是严格的「安全性递减」关系，UI 必须按此顺序展示：
 *
 * 1. [SYSTEM_DEFAULT]：完全沿用 OkHttp / Android 平台默认行为，
 *    即证书链必须由系统信任库签发，且主机名必须匹配。这是引入本设置之前
 *    唯一存在的行为，选择该档时不会向 [okhttp3.OkHttpClient.Builder]
 *    写入任何 TLS 相关配置。
 * 2. [ALLOW_SELF_SIGNED]：先按系统信任库校验；失败时，若服务器出示的证书链
 *    自身是闭合的（链尾为自签名证书），则把该链尾当作临时信任锚重新做一次
 *    完整的路径校验。签名、有效期、基本约束仍然强制校验，主机名校验也仍然生效。
 *    适用于自建 CA 或单张自签名证书的私有服务器。
 * 3. [ALLOW_UNTRUSTED]：不校验证书链，也不校验主机名。等同于关闭 TLS 身份认证，
 *    信道仍然加密但无法抵御中间人攻击。仅在用户明确知晓风险时使用。
 */
enum class WebDavTlsMode {
    /** 系统默认校验（安全）。 */
    SYSTEM_DEFAULT,

    /** 额外接受自签名证书链（较安全）。 */
    ALLOW_SELF_SIGNED,

    /** 接受任意证书且不校验主机名（不安全）。 */
    ALLOW_UNTRUSTED;

    /** 是否偏离了平台默认校验；用于 UI 展示风险提示。 */
    val isRelaxed: Boolean
        get() = this != SYSTEM_DEFAULT

    companion object {
        /** 未配置时的默认档位，保持历史行为不变。 */
        val DEFAULT: WebDavTlsMode = SYSTEM_DEFAULT

        /**
         * 从持久化字符串还原档位。
         *
         * 无法识别的值（包括 null、旧版本写入的未知枚举名）一律退回
         * [DEFAULT]，确保降级时不会意外放宽校验。
         */
        fun fromStorage(raw: String?): WebDavTlsMode =
            WebDavTlsMode.entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
