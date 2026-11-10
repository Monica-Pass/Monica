package takagi.ru.monica.keepass

import takagi.ru.monica.data.PasswordEntry

/** Persisted compatibility field spellings; changing a value requires a separate migration design. */
internal object KeePassCompatibilityFieldNames {
    const val FIELD_MONICA_LOCAL_ID = "MonicaLocalId"
    const val FIELD_MONICA_CONFLICT_COPY = "MonicaConflictCopy"
    const val FIELD_MONICA_ITEM_ID = "MonicaSecureItemId"
    const val FIELD_MONICA_TOTP_DATA = "MonicaTotpData"
    const val FIELD_MONICA_ITEM_TYPE = "MonicaItemType"
    const val FIELD_MONICA_ITEM_DATA = "MonicaItemData"
    const val FIELD_MONICA_ITEM_NOTES = "MonicaItemNotes"
    const val FIELD_MONICA_IMAGE_PATHS = "MonicaImagePaths"
    const val FIELD_MONICA_IS_FAVORITE = "MonicaIsFavorite"
    const val FIELD_BANK_NAME = "Bank Name"
    const val FIELD_BANK_BRANCH = "Bank Branch"
    const val FIELD_CARD_TYPE = "Card Type"
    const val FIELD_BILLING_ADDRESS = "Billing Address"
    const val FIELD_BRAND = "Brand"
    const val FIELD_NICKNAME = "Nickname"
    const val FIELD_VALID_FROM_MONTH = "Valid From Month"
    const val FIELD_VALID_FROM_YEAR = "Valid From Year"
    const val FIELD_PIN = "PIN"
    const val FIELD_IBAN = "IBAN"
    const val FIELD_SWIFT_BIC = "SWIFT/BIC"
    const val FIELD_ROUTING_NUMBER = "Routing Number"
    const val FIELD_ACCOUNT_NUMBER = "Account Number"
    const val FIELD_BRANCH_CODE = "Branch Code"
    const val FIELD_CURRENCY = "Currency"
    const val FIELD_CUSTOMER_SERVICE_PHONE = "Customer Service Phone"
    const val FIELD_MONICA_PASSKEY_CREDENTIAL_ID = "MonicaPasskeyCredentialId"
    const val FIELD_MONICA_PASSKEY_DATA = "MonicaPasskeyData"
    const val FIELD_MONICA_PASSKEY_MODE = "MonicaPasskeyMode"
    const val FIELD_MONICA_SSH_ALGORITHM = "MonicaSshAlgorithm"
    const val FIELD_MONICA_SSH_KEY_SIZE = "MonicaSshKeySize"
    const val FIELD_MONICA_SSH_PUBLIC_KEY = "MonicaSshPublicKey"
    const val FIELD_MONICA_SSH_PRIVATE_KEY = "MonicaSshPrivateKey"
    const val FIELD_MONICA_SSH_FINGERPRINT = "MonicaSshFingerprint"
    const val FIELD_MONICA_SSH_COMMENT = "MonicaSshComment"
    const val FIELD_MONICA_SSH_FORMAT = "MonicaSshFormat"
    const val FIELD_KEEPASS_XC_TEMPLATE = "_etm_template"
    // === WIFI 条目互通字段 ===
    // `SSID` 是 keepass2android 官方 WLan 模板的 `TemplateField_WLan_SSID`，
    // 同时也是 KeeWeb/KeePassXC 社区常见命名；把它作为"标准字段"写入以保证
    // 其他 KeePass 客户端能读懂。
    const val FIELD_WIFI_SSID = "SSID"
    // Monica 扩展元数据（安全类型、代理、IP 等）。只有 Monica 自己解析；
    // 其它客户端看到的是一段 JSON 字符串，不影响它们的常规使用。
    const val FIELD_MONICA_WIFI_DATA = "MonicaWifiData"
    // 快速识别标志；与 PasswordEntry.loginType 对应，避免读回来时要解析 JSON
    // 才能判断是不是 WIFI 条目。值为 "WIFI"/"SSO"/"PASSWORD"。
    const val FIELD_MONICA_LOGIN_TYPE = "MonicaLoginType"
    const val FIELD_APP_PACKAGE_NAME = "App Package Name"
    const val FIELD_APP_NAME = "App Name"
    const val FIELD_EMAIL = "Email"
    const val FIELD_PHONE = "Phone"
    const val FIELD_ADDRESS_LINE = "Address"
    const val FIELD_CITY = "City"
    const val FIELD_STATE = "State"
    const val FIELD_POSTAL_CODE = "Postal Code"
    const val FIELD_COUNTRY = "Country"
    const val FIELD_CARD_NUMBER = "Card Number"
    const val FIELD_CARD_HOLDER = "Card Holder"
    const val FIELD_CARD_EXPIRY = "Card Expiry"
    const val FIELD_CARD_CVV = "Card CVV"
    const val FIELD_SSO_PROVIDER = "SSO Provider"
    const val FIELD_MONICA_SSO_REF_ENTRY_ID = "MonicaSsoRefEntryId"
}
