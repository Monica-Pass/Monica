package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.model.PermissionStats

@Composable
fun PermissionStatsCard(
    stats: PermissionStats?,
    onRefresh: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.permission_summary_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.pm_inventory_hint), style = MaterialTheme.typography.bodyMedium)
            stats?.let {
                Text(stringResource(R.string.pm_inventory_count, it.declaredPermissions),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            }
            TextButton(onClick = onRefresh, enabled = !isLoading,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("permission_refresh")) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.permission_refresh_status))
            }
        }
    }
}
