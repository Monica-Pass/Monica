package takagi.ru.monica.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import takagi.ru.monica.R
import takagi.ru.monica.keepass.KeePassNativeEntryRecord

@Stable
internal class NativeEntryPropertiesState(entry: KeePassNativeEntryRecord?) {
    var tagsText by mutableStateOf(entry?.tags.orEmpty().joinToString("\n"))
    var tagsChanged by mutableStateOf(false)
    var expires by mutableStateOf(entry?.times?.expires ?: false)
    var expiryTime by mutableStateOf(entry?.times?.expiryTime ?: Instant.now().plus(30, ChronoUnit.DAYS))
    var expiryChanged by mutableStateOf(false)
}

internal fun formatNativeEntryTime(time: Instant, locale: Locale = Locale.getDefault()): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withLocale(locale)
    .withZone(ZoneId.systemDefault()).format(time)

@Composable
internal fun NativeEntryPropertiesEditorCard(
    tagsText: String,
    expires: Boolean,
    expiryTime: Instant,
    enabled: Boolean,
    onTagsChange: (String) -> Unit,
    onExpiresChange: (Boolean) -> Unit,
    onExpiryTimeChange: (Instant) -> Unit,
) {
    val context = LocalContext.current
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag("native_entry_properties"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.keepass_entry_properties),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = tagsText,
                onValueChange = onTagsChange,
                label = { Text(stringResource(R.string.keepass_native_tags)) },
                supportingText = { Text(stringResource(R.string.keepass_entry_tags_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("native_entry_tags"),
                enabled = enabled,
                minLines = 2,
                maxLines = 5,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.keepass_entry_expires), Modifier.weight(1f))
                Switch(
                    checked = expires,
                    onCheckedChange = onExpiresChange,
                    enabled = enabled,
                    modifier = Modifier.testTag("native_entry_expires"),
                )
            }
            if (expires) {
                TextButton(
                    modifier = Modifier.fillMaxWidth().testTag("native_entry_expiry_time"),
                    enabled = enabled,
                    onClick = {
                        val current = expiryTime.atZone(ZoneId.systemDefault())
                        DatePickerDialog(context, { _, year, month, day ->
                            val date = current.toLocalDate().withDayOfMonth(1)
                                .withYear(year).withMonth(month + 1).withDayOfMonth(day)
                            TimePickerDialog(context, { _, hour, minute ->
                                onExpiryTimeChange(date.atTime(hour, minute)
                                    .atZone(ZoneId.systemDefault()).toInstant())
                            }, current.hour, current.minute, DateFormat.is24HourFormat(context)).show()
                        }, current.year, current.monthValue - 1, current.dayOfMonth).show()
                    },
                ) { Text(formatNativeEntryTime(expiryTime, context.resources.configuration.locales[0])) }
            } else {
                Text(stringResource(R.string.keepass_entry_never_expires),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
