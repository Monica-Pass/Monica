package takagi.ru.monica.autofill_ng

import takagi.ru.monica.security.launchWithSecureStorage

import android.content.Context
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.theme.MonicaTheme

class AutofillPickerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PASSWORD_IDS = "extra_password_ids"
        const val EXTRA_PAYMENT_IDS = "extra_payment_ids"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_FIELD_TYPE = "extra_field_type"
        const val EXTRA_DOMAIN = "extra_domain"
        const val EXTRA_AUTOFILL_HINTS = "extra_autofill_hints"

        const val RESULT_PASSWORD_ID = "result_password_id"
        const val RESULT_PAYMENT_ID = "result_payment_id"
        const val RESULT_SELECTION_TYPE = "result_selection_type"

        const val SELECTION_TYPE_PASSWORD = "password"
        const val SELECTION_TYPE_PAYMENT = "payment"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchWithSecureStorage { onSecureCreate(savedInstanceState) }
    }

    private fun onSecureCreate(savedInstanceState: Bundle?) {

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val passwordIds = intent.getLongArrayExtra(EXTRA_PASSWORD_IDS) ?: longArrayOf()
        val database = PasswordDatabase.getDatabase(applicationContext)
        val repository = PasswordRepository(database.passwordEntryDao())

        setContent {
            MonicaTheme {
                var passwords by remember { mutableStateOf<List<PasswordEntry>>(emptyList()) }
                var loading by remember { mutableStateOf(true) }

                LaunchedEffect(passwordIds.contentHashCode()) {
                    passwords = repository.getPasswordsByIds(passwordIds.toList())
                    loading = false
                }

                if (loading) {
                    LoadingView()
                } else {
                    PickerList(
                        passwords = passwords,
                        onSelect = ::handleSelection,
                    )
                }
            }
        }
    }

    private fun handleSelection(password: PasswordEntry) {
        val responseAuthMode = intent.getBooleanExtra("extra_response_auth_mode", false)
        val dataset = createDatasetForPassword(password)
        val authResult: android.os.Parcelable = if (responseAuthMode) {
            android.service.autofill.FillResponse.Builder()
                .addDataset(dataset)
                .build()
        } else {
            dataset
        }
        val resultIntent = Intent().apply {
            putExtra(android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT, authResult)
            putExtra(RESULT_SELECTION_TYPE, SELECTION_TYPE_PASSWORD)
            putExtra(RESULT_PASSWORD_ID, password.id)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun createDatasetForPassword(password: PasswordEntry): android.service.autofill.Dataset {
        val autofillIds = intent.getParcelableArrayListExtra<AutofillId>("autofill_ids")
        val autofillHints = intent.getStringArrayListExtra(EXTRA_AUTOFILL_HINTS)

        val securityManager = SecurityManager(applicationContext)
        val accountValue = AccountFillPolicy.resolveAccountIdentifier(password, securityManager)
        val fillEmailWithAccount = AccountFillPolicy.shouldFillEmailWithAccount(applicationContext)
        val decryptedPassword = AutofillSecretResolver.decryptPasswordOrNull(
            securityManager = securityManager,
            encryptedOrPlain = password.password,
            logTag = "AutofillPicker",
        )

        val builder = android.service.autofill.Dataset.Builder()

        if (!autofillIds.isNullOrEmpty()) {
            var filled = 0
            autofillIds.forEachIndexed { index, autofillId ->
                val hint = autofillHints?.getOrNull(index)
                val value = when (hint) {
                    EnhancedAutofillStructureParserV2.FieldHint.USERNAME.name -> accountValue
                    EnhancedAutofillStructureParserV2.FieldHint.PHONE_NUMBER.name -> AutofillAccountValues.resolve(hint, accountValue, false)
                    EnhancedAutofillStructureParserV2.FieldHint.EMAIL_ADDRESS.name ->
                        if (fillEmailWithAccount || accountValue.contains("@")) accountValue else null
                    EnhancedAutofillStructureParserV2.FieldHint.PASSWORD.name,
                    EnhancedAutofillStructureParserV2.FieldHint.NEW_PASSWORD.name -> decryptedPassword
                    else -> null
                }
                if (value != null) {
                    builder.setValue(autofillId, AutofillValue.forText(value))
                    filled++
                }
            }

            if (filled == 0) {
                autofillIds.forEachIndexed { index, autofillId ->
                    val fallback = if (index % 2 == 0) {
                        accountValue
                    } else {
                        decryptedPassword
                    }
                    if (!fallback.isNullOrBlank()) {
                        builder.setValue(autofillId, AutofillValue.forText(fallback))
                    }
                }
            }
        }

        return builder.build()
    }
}

@Composable
private fun LoadingView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PickerList(
    passwords: List<PasswordEntry>,
    onSelect: (PasswordEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(passwords, key = { it.id }) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                onClick = { onSelect(item) },
            ) {
                Row(modifier = Modifier.padding(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = item.title.ifBlank { "Untitled" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val username = item.username.ifBlank { "(no username)" }
                        Text(
                            text = username,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}


