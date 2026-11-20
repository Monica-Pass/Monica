package takagi.ru.monica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60 * 1000 -> stringResource(R.string.time_just_now)
        diff < 60 * 60 * 1000 -> stringResource(R.string.time_minutes_ago, diff / (60 * 1000))
        diff < 24 * 60 * 60 * 1000 -> stringResource(R.string.time_hours_ago, diff / (60 * 60 * 1000))
        else -> {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
