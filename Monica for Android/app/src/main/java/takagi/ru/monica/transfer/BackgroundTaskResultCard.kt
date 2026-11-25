package takagi.ru.monica.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@Composable
fun BackgroundTaskResultCard(job: ExportJobState) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (job.status) {
                ExportJobStatus.SUCCEEDED -> R.string.transfer_task_done
                ExportJobStatus.CANCELLED -> R.string.transfer_cancelled
                else -> R.string.transfer_task_failed
            }), style = MaterialTheme.typography.titleMedium)
            job.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = DatabaseExportJobs::dismiss) { Text(stringResource(R.string.close)) }
        }
    }
}
