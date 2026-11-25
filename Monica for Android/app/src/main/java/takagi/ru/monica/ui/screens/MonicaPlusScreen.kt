package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.PlusFeatures
import takagi.ru.monica.ui.components.PlusFeatureCard
import takagi.ru.monica.ui.components.PlusHonestyStatement

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonicaPlusScreen(
    isPlusActivated: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToPayment: () -> Unit,
    onDeactivatePlus: () -> Unit = {}
) {
    val features = remember { PlusFeatures.getPlaceholderFeatures() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.monica_plus_title)) },
            navigationIcon = { IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            } }
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().testTag("plus_content"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "support") {
                    PlusHonestyStatement {
                        if (isPlusActivated) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.CheckCircle, null, Modifier.size(24.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.plus_activation_title), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.plus_status_activated), style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.testTag("plus_active_status"))
                                }
                                Switch(checked = true, onCheckedChange = { if (!it) onDeactivatePlus() },
                                    modifier = Modifier.testTag("plus_deactivate"))
                            }
                        } else {
                            Button(onClick = onNavigateToPayment,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plus_support")) {
                                Icon(Icons.Default.Favorite, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.click_to_pay))
                            }
                        }
                    }
                }
                item(key = "features_title") {
                    Text(stringResource(R.string.monica_plus_features_title),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp))
                }
                itemsIndexed(features, key = { _, feature -> feature.id }) { index, feature ->
                    PlusFeatureCard(feature, isPlusActivated,
                        shape = RoundedCornerShape(
                            topStart = if (index == 0) 24.dp else 6.dp,
                            topEnd = if (index == 0) 24.dp else 6.dp,
                            bottomStart = if (index == features.lastIndex) 24.dp else 6.dp,
                            bottomEnd = if (index == features.lastIndex) 24.dp else 6.dp))
                }
            }
        }
    }
}
