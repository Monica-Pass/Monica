package takagi.ru.monica.navigation

import android.net.Uri

/**
 * Screen destinations for navigation
 */
sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Main : Screen("main?tab={tab}") {
        fun createRoute(tab: Int = 0): String {
            return "main?tab=$tab"
        }
        const val routePattern = "main?tab={tab}"
    }
    object PasswordList : Screen("password_list")
    object DataList : Screen("data_list")  // 新的统一数据列表界面
    object NoteList : Screen("note_list")
    object AddEditNote : Screen("add_edit_note/{noteId}") {
        fun createRoute(noteId: Long? = null): String {
            return if (noteId != null) {
                "add_edit_note/$noteId"
            } else {
                "add_edit_note/-1"
            }
        }
    }
    object NoteDetail : Screen("note_detail/{noteId}?highlight={highlight}") {
        fun createRoute(noteId: Long, highlight: String? = null): String {
            val trimmedHighlight = highlight?.trim().orEmpty()
            return if (trimmedHighlight.isBlank()) {
                "note_detail/$noteId"
            } else {
                "note_detail/$noteId?highlight=${Uri.encode(trimmedHighlight)}"
            }
        }
    }
    object AddEditSend : Screen("add_edit_send") {
        fun createRoute(): String {
            return "add_edit_send"
        }
    }
    object AddEditPassword : Screen("add_edit_password/{passwordId}?initialType={initialType}") {
        fun createRoute(passwordId: Long? = null, initialType: String? = null): String {
            val baseRoute = if (passwordId != null) {
                "add_edit_password/$passwordId"
            } else {
                "add_edit_password/-1"
            }
            val trimmedInitialType = initialType?.trim().orEmpty()
            return if (trimmedInitialType.isBlank()) {
                baseRoute
            } else {
                "$baseRoute?initialType=${Uri.encode(trimmedInitialType)}"
            }
        }
    }
    object AddEditWifi : Screen("add_edit_wifi/{passwordId}") {
        fun createRoute(passwordId: Long? = null): String {
            return if (passwordId != null) {
                "add_edit_wifi/$passwordId"
            } else {
                "add_edit_wifi/-1"
            }
        }
    }
    object WifiDetail : Screen("wifi_detail/{passwordId}") {
        fun createRoute(passwordId: Long): String {
            return "wifi_detail/$passwordId"
        }
    }
    object AddEditSshKey : Screen("add_edit_ssh_key/{passwordId}") {
        fun createRoute(passwordId: Long? = null): String {
            return if (passwordId != null) {
                "add_edit_ssh_key/$passwordId"
            } else {
                "add_edit_ssh_key/-1"
            }
        }
    }
    object SshKeyDetail : Screen("ssh_key_detail/{passwordId}") {
        fun createRoute(passwordId: Long): String {
            return "ssh_key_detail/$passwordId"
        }
    }
    object BarcodeDetail : Screen("barcode_detail/{passwordId}") {
        fun createRoute(passwordId: Long): String {
            return "barcode_detail/$passwordId"
        }
    }
    object AddEditTotp : Screen("add_edit_totp/{totpId}") {
        fun createRoute(totpId: Long? = null): String {
            return if (totpId != null) {
                "add_edit_totp/$totpId"
            } else {
                "add_edit_totp/0"
            }
        }
    }
    object AddEditBankCard : Screen("add_edit_bank_card/{cardId}") {
        fun createRoute(cardId: Long? = null): String {
            return if (cardId != null) {
                "add_edit_bank_card/$cardId"
            } else {
                "add_edit_bank_card/-1"
            }
        }
    }
    object AddEditDocument : Screen("add_edit_document/{documentId}") {
        fun createRoute(documentId: Long? = null): String {
            return if (documentId != null) {
                "add_edit_document/$documentId"
            } else {
                "add_edit_document/-1"
            }
        }
    }
    object AddEditBillingAddress : Screen("add_edit_billing_address/{addressId}") {
        fun createRoute(addressId: Long? = null): String {
            return if (addressId != null) {
                "add_edit_billing_address/$addressId"
            } else {
                "add_edit_billing_address/-1"
            }
        }
    }
    object WalletAdd : Screen("wallet_add/{initialType}") {
        fun createRoute(initialType: String = "BANK_CARDS"): String {
            return "wallet_add/$initialType"
        }
    }
    object DocumentDetail : Screen("document_detail/{documentId}") {
        fun createRoute(documentId: Long): String {
            return "document_detail/$documentId"
        }
    }
    object BillingAddressDetail : Screen("billing_address_detail/{addressId}") {
        fun createRoute(addressId: Long): String {
            return "billing_address_detail/$addressId"
        }
    }
    object PasswordDetail : Screen("password_detail/{passwordId}") {
        fun createRoute(passwordId: Long): String {
            return "password_detail/$passwordId"
        }
    }
    object PasskeyDetail : Screen("passkey_detail/{recordId}") {
        fun createRoute(recordId: Long): String {
            return "passkey_detail/$recordId"
        }
    }
    object QrScanner : Screen("qr_scanner")
    object FidoQrScan : Screen("fido_qr_scan")
    object QuickTotpScan : Screen("quick_totp_scan")  // 快速扫码添加验证器
    object SteamQrScan : Screen("steam_qr_scan?accountId={accountId}") {
        const val ARG_ACCOUNT_ID = "accountId"
        fun createRoute(accountId: Long? = null): String {
            return if (accountId != null) {
                "steam_qr_scan?accountId=$accountId"
            } else {
                "steam_qr_scan"
            }
        }
    }
    object Settings : Screen("settings")
    object ResetPassword : Screen("reset_password?skipCurrentPassword={skipCurrentPassword}") {
        fun createRoute(skipCurrentPassword: Boolean = false): String {
            return "reset_password?skipCurrentPassword=$skipCurrentPassword"
        }
    }
    object ForgotPassword : Screen("forgot_password")
    object SecurityQuestionsSetup : Screen("security_questions_setup")
    object SecurityQuestionsVerification : Screen("security_questions_verification")
    object SupportAuthor : Screen("support_author")
    object WebDavBackup : Screen("webdav_backup")
    object OneDriveBackup : Screen("onedrive_backup")
    object MdbxManager : Screen("mdbx_manager") {
        const val ARG_DATABASE_ID = "databaseId"
        const val ARG_PAGE = "page"
        const val PAGE_HOME = "home"
        const val PAGE_COMMIT_HISTORY = "commit_history"
        const val routePattern = "mdbx_manager?databaseId={databaseId}&page={page}"

        fun createRoute(databaseId: Long? = null, page: String = PAGE_HOME): String {
            return "mdbx_manager?databaseId=${databaseId ?: -1L}&page=${Uri.encode(page)}"
        }
    }
    object ApiTokens : Screen("api_tokens?databaseId={databaseId}") {
        fun createRoute(databaseId: Long? = null, entryId: String? = null, create: Boolean = false): String = when {
            create -> AddEditApiToken.createRoute(databaseId)
            entryId != null && databaseId != null -> ApiTokenDetail.createRoute(databaseId, entryId)
            else -> "api_tokens?databaseId=${databaseId ?: -1L}"
        }
    }
    object ApiTokenDetail : Screen("api_token_detail/{databaseId}/{entryId}") {
        fun createRoute(databaseId: Long, entryId: String) = "api_token_detail/$databaseId/${Uri.encode(entryId)}"
    }
    object AddEditApiToken : Screen("add_edit_api_token?databaseId={databaseId}&entryId={entryId}&folderId={folderId}") {
        fun createRoute(databaseId: Long? = null, entryId: String? = null, folderId: String? = null) =
            "add_edit_api_token?databaseId=${databaseId ?: -1L}&entryId=${Uri.encode(entryId.orEmpty())}&folderId=${Uri.encode(folderId.orEmpty())}"
    }

    object MdbxLocalCreate : Screen("mdbx_local_create")
    object MdbxLocalOpen : Screen("mdbx_local_open")
    object MdbxWebDavCreate : Screen("mdbx_webdav_create")
    object MdbxWebDavOpen : Screen("mdbx_webdav_open")
    object MdbxOneDriveCreate : Screen("mdbx_onedrive_create")
    object MdbxOneDriveOpen : Screen("mdbx_onedrive_open")
    object ExportData : Screen("export_data")
    object ImportData : Screen("import_data") {
        val routePattern = "$route?fromApps={fromApps}"
        fun createRoute(fromApps: Boolean) = "$route?fromApps=$fromApps"
    }
    object ChangePassword : Screen("change_password")
    object SecurityQuestion : Screen("security_question")
    object AutofillSettings : Screen("autofill_settings")
    object AutofillBlockedFields : Screen("autofill_blocked_fields")
    object AutofillSaveBlockedTargets : Screen("autofill_save_blocked_targets")
    object PasskeySettings : Screen("passkey_settings")
    object SecurityAnalysis : Screen("security_analysis")
    object MasterPasswordLockingSettings : Screen("master_password_locking_settings")
    object BottomNavSettings : Screen("bottom_nav_settings")
    object ColorSchemeSelection : Screen("color_scheme_selection")
    object CustomColorSettings : Screen("custom_color_settings")
    object Generator : Screen("generator")  // 添加生成器页面路由
    object DeveloperSettings : Screen("developer_settings")
    object DeveloperLogs : Screen("developer_logs")
    object PermissionManagement : Screen("permission_management")  // 权限管理页面路由
    object QuickSetup : Screen("quick_setup")  // 快速初始化引导
    object Extensions : Screen("extensions")  // 功能拓展页面路由
    object CommonAccountTemplates : Screen("common_account_templates") // 常用账号信息模板管理
    object PageAdjustmentCustomization : Screen("page_adjustment_customization") // 页面调整自定义
    object PasswordCardAdjustment : Screen("password_card_adjustment") // 密码卡片调整
    object AuthenticatorCardAdjustment : Screen("authenticator_card_adjustment") // 验证器卡片调整
    object IconSettings : Screen("icon_settings") // 图标设置
    object PasswordFieldCustomization : Screen("password_field_customization")  // 添加密码页面字段定制
    object PasswordListCustomization : Screen("password_list_customization") // 密码列表自定义
    object AddButtonCustomization : Screen("add_button_customization") // 兼容旧路由：已并入密码列表自定义
    object SyncBackup : Screen("sync_backup")  // 同步与备份页面路由
    object LanSync : Screen("lan_sync")
    object LanSyncScanner : Screen("lan_sync_scanner")
    object LocalKeePass : Screen("local_keepass")  // 本地 KeePass 数据库页面
    object MonicaPlus : Screen("monica_plus") // Monica Plus 页面
    object Payment : Screen("payment") // 付款页面
    
    // Bitwarden 集成相关路由
    object BitwardenLogin : Screen("bitwarden_login")  // Bitwarden 登录页面
    object BitwardenSettings : Screen("bitwarden_settings")  // Bitwarden 设置/管理页面
    object SyncQueue : Screen("sync_queue")  // 同步队列管理页面
}
