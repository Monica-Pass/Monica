package takagi.ru.monica.data.model

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

/**
 * OTP类型枚举
 */
@Serializable
enum class OtpType {
    TOTP,   // 基于时间的一次性密码 (Time-based OTP)
    HOTP,   // 基于计数器的一次性密码 (HMAC-based OTP)
    STEAM,  // Steam Guard
    YANDEX, // Yandex OTP
    MOTP    // Mobile-OTP
}

/**
 * TOTP验证器数据
 * 支持多种OTP类型: TOTP, HOTP, Steam, Yandex, mOTP
 */
@Serializable
data class TotpData(
    val secret: String,                    // OTP密钥
    val issuer: String = "",               // 发行者（如：Google, GitHub, Steam）
    val accountName: String = "",          // 账户名
    val period: Int = 30,                  // 时间周期（默认30秒，仅TOTP/Steam/Yandex/mOTP使用）
    val digits: Int = 6,                   // 验证码位数（默认6位，Steam为5位）
    val algorithm: String = "SHA1",        // 算法（SHA1, SHA256, SHA512）
    val otpType: OtpType = OtpType.TOTP,  // OTP类型（默认TOTP，确保向后兼容）
    val counter: Long = 0,                 // 计数器（仅HOTP使用）
    val pin: String = "",                  // PIN码（仅mOTP使用，应加密存储）
    val link: String = "",                 // 关联链接
    val associatedApp: String = "",        // 关联应用
    val customIconType: String = "NONE",   // NONE / SIMPLE_ICON / UPLOADED
    val customIconValue: String? = null,   // SIMPLE_ICON: slug, UPLOADED: local file name
    val customIconUpdatedAt: Long = 0L,    // 图标更新时间（毫秒）
    val boundPasswordId: Long? = null,     // 绑定的密码ID
    val categoryId: Long? = null,          // 分类ID（用于验证器分类筛选）
    val keepassDatabaseId: Long? = null,   // 归属的 KeePass 数据库ID
    // Steam 令牌元数据（用于共存导入和精确去重）
    val steamFingerprint: String = "",
    val steamDeviceId: String = "",
    val steamSerialNumber: String = "",
    val steamSharedSecretBase64: String = "",
    val steamRevocationCode: String = "",
    val steamIdentitySecret: String = "",
    val steamTokenGid: String = "",
    val steamRawJson: String = ""
)

/**
 * 银行卡数据
 */
@Serializable
data class BankCardData(
    val cardNumber: String,       // 卡号（加密存储）
    val cardholderName: String,   // 持卡人姓名
    val expiryMonth: String,      // 有效期月份
    val expiryYear: String,       // 有效期年份
    val cvv: String = "",         // CVV安全码（加密存储）
    val bankName: String = "",    // 银行名称
    val cardType: CardType = CardType.CREDIT, // 卡类型
    val billingAddress: String = "", // 账单地址（JSON格式存储BillingAddress）
    val brand: String = "",
    val nickname: String = "",
    val validFromMonth: String = "",
    val validFromYear: String = "",
    val pin: String = "",
    val iban: String = "",
    val swiftBic: String = "",
    val routingNumber: String = "",
    val accountNumber: String = "",
    val branchCode: String = "",
    val currency: String = "",
    val customerServicePhone: String = "",
    val customFields: List<SecureCustomField> = emptyList(),
    /** Monica-managed visual card face. Null keeps the legacy card layout unchanged. */
    val cardFace: CardFaceConfig? = null
)

@Serializable
enum class CardFaceDisplayMode {
    ALL,
    CARD_NUMBER_ONLY,
    HIDDEN
}

/**
 * Portable card-face metadata. The image bytes live in the secure attachment system; only the
 * deterministic attachment name is stored here so the reference survives backup/import and
 * remote backends assigning different local attachment ids.
 */
@Serializable
data class CardFaceConfig(
    val imageAttachmentName: String,
    val displayMode: CardFaceDisplayMode = CardFaceDisplayMode.ALL,
    val showBrandIcon: Boolean = true
)

object CardFaceAttachment {
    const val MIME_TYPE = "image/jpeg"
    private const val PREFIX = "monica_card_face_"
    private const val SUFFIX = ".jpg"
    private val managedName = Regex("^${PREFIX}[a-z0-9]{16,64}\\${SUFFIX}$")

    fun newFileName(): String = fileNameFor(UUID.randomUUID().toString())

    fun fileNameFor(assetId: String): String {
        val normalized = assetId
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
            .take(64)
        require(normalized.length >= 16) { "Card-face asset id must contain at least 16 characters" }
        return "$PREFIX$normalized$SUFFIX"
    }

    fun isManagedFileName(fileName: String): Boolean = managedName.matches(fileName)
}

fun CardFaceConfig.validatedOrNull(): CardFaceConfig? =
    takeIf { CardFaceAttachment.isManagedFileName(imageAttachmentName) }

/**
 * 账单地址详细信息
 */
@Serializable
data class BillingAddress(
    val streetAddress: String = "",   // 街道地址
    val apartment: String = "",       // 公寓/单元号
    val city: String = "",            // 城市
    val stateProvince: String = "",   // 州/省
    val postalCode: String = "",      // 邮政编码
    val country: String = ""          // 国家
)

fun BillingAddress.isEmpty(): Boolean {
    return streetAddress.isBlank() &&
        apartment.isBlank() &&
        city.isBlank() &&
        stateProvince.isBlank() &&
        postalCode.isBlank() &&
        country.isBlank()
}

fun BillingAddress.formatForDisplay(): String {
    val lines = mutableListOf<String>()
    if (streetAddress.isNotBlank()) {
        lines += streetAddress
    }
    if (apartment.isNotBlank()) {
        lines += apartment
    }
    val cityState = listOf(city, stateProvince)
        .filter { it.isNotBlank() }
        .joinToString(", ")
    if (cityState.isNotBlank()) {
        lines += cityState
    }
    val postalCountry = listOf(postalCode, country)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    if (postalCountry.isNotBlank()) {
        lines += postalCountry
    }
    return lines.joinToString("\n")
}

@Serializable
data class BillingAddressData(
    val fullName: String = "",
    val company: String = "",
    val streetAddress: String = "",
    val apartment: String = "",
    val city: String = "",
    val stateProvince: String = "",
    val postalCode: String = "",
    val country: String = "",
    val phone: String = "",
    val email: String = "",
    val isDefault: Boolean = false,
    val customFields: List<SecureCustomField> = emptyList(),
    /** Optional visual card face. The image itself is stored as an encrypted attachment. */
    val cardFace: CardFaceConfig? = null
)

fun BillingAddressData.isEmpty(): Boolean {
    return fullName.isBlank() &&
        company.isBlank() &&
        streetAddress.isBlank() &&
        apartment.isBlank() &&
        city.isBlank() &&
        stateProvince.isBlank() &&
        postalCode.isBlank() &&
        country.isBlank() &&
        phone.isBlank() &&
        email.isBlank() &&
        customFields.none { it.isValid() } &&
        cardFace == null
}

fun BillingAddressData.toBillingAddress(): BillingAddress =
    BillingAddress(
        streetAddress = streetAddress,
        apartment = apartment,
        city = city,
        stateProvince = stateProvince,
        postalCode = postalCode,
        country = country
    )

fun BillingAddressData.formatForDisplay(): String {
    val identity = listOf(fullName, company)
        .filter { it.isNotBlank() }
        .joinToString(" / ")
    val address = toBillingAddress().formatForDisplay()
    return listOf(identity, address, phone, email)
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

@Serializable
enum class PaymentAccountType {
    DIGITAL_WALLET,
    BANK_ACCOUNT,
    PAYMENT_APP,
    BUY_NOW_PAY_LATER,
    CRYPTO_WALLET,
    OTHER
}

@Serializable
data class PaymentAccountData(
    val paymentType: PaymentAccountType = PaymentAccountType.DIGITAL_WALLET,
    val provider: String = "",
    val accountName: String = "",
    val accountHolderName: String = "",
    val email: String = "",
    val phone: String = "",
    val username: String = "",
    val accountId: String = "",
    val maskedAccountNumber: String = "",
    val linkedCardLast4: String = "",
    val routingNumber: String = "",
    val iban: String = "",
    val swiftBic: String = "",
    val billingAddress: String = "",
    val website: String = "",
    val currency: String = "",
    val notes: String = "",
    val isDefault: Boolean = false,
    val customFields: List<SecureCustomField> = emptyList()
)

fun PaymentAccountData.isEmpty(): Boolean {
    return provider.isBlank() &&
        accountName.isBlank() &&
        accountHolderName.isBlank() &&
        email.isBlank() &&
        phone.isBlank() &&
        username.isBlank() &&
        accountId.isBlank() &&
        maskedAccountNumber.isBlank() &&
        linkedCardLast4.isBlank() &&
        routingNumber.isBlank() &&
        iban.isBlank() &&
        swiftBic.isBlank() &&
        billingAddress.isBlank() &&
        website.isBlank() &&
        currency.isBlank() &&
        notes.isBlank() &&
        customFields.none { it.isValid() }
}

enum class CardType {
    CREDIT,      // 信用卡
    DEBIT,       // 借记卡
    PREPAID      // 预付卡
}

@Serializable
enum class SecureCustomFieldType {
    TEXT,
    HIDDEN,
    BOOLEAN
}

@Serializable
data class SecureCustomField(
    val label: String,
    val value: String = "",
    val type: SecureCustomFieldType = SecureCustomFieldType.TEXT
) {
    fun isValid(): Boolean = label.isNotBlank()

    fun isProtected(): Boolean = type == SecureCustomFieldType.HIDDEN
}

/**
 * 证件数据
 */
@Serializable
data class DocumentData(
    val documentType: DocumentType, // 证件类型
    val documentNumber: String,      // 证件号码（加密存储）
    val fullName: String,            // 姓名
    val issuedDate: String = "",     // 签发日期
    val expiryDate: String = "",     // 有效期至
    val issuedBy: String = "",       // 签发机关
    val nationality: String = "",    // 国籍
    val additionalInfo: String = "",  // 其他信息
    val title: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
    val address1: String = "",
    val address2: String = "",
    val address3: String = "",
    val city: String = "",
    val stateProvince: String = "",
    val postalCode: String = "",
    val country: String = "",
    val company: String = "",
    val email: String = "",
    val phone: String = "",
    val ssn: String = "",
    val username: String = "",
    val passportNumber: String = "",
    val licenseNumber: String = "",
    val customFields: List<SecureCustomField> = emptyList(),
    /** Optional visual card face. The image itself is stored as an encrypted attachment. */
    val cardFace: CardFaceConfig? = null
)

enum class DocumentType {
    ID_CARD,       // 身份证
    PASSPORT,      // 护照
    DRIVER_LICENSE,// 驾驶证
    SOCIAL_SECURITY,// 社保卡
    OTHER          // 其他
}

fun DocumentData.displayFullName(): String {
    val parts = listOf(firstName, middleName, lastName).filter { it.isNotBlank() }
    return when {
        parts.isNotEmpty() -> parts.joinToString(" ")
        fullName.isNotBlank() -> fullName
        else -> ""
    }
}

/**
 * 笔记数据
 */
@Serializable
data class NoteData(
    val content: String,            // 笔记正文
    val tags: List<String> = emptyList(), // 标签列表
    val isMarkdown: Boolean = false,     // 是否为Markdown格式
    val customFields: List<SecureCustomField> = emptyList()
)
