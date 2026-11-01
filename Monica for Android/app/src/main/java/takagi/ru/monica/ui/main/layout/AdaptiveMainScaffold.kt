package takagi.ru.monica.ui.main.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.ui.main.navigation.BottomNavItem
import takagi.ru.monica.ui.main.navigation.DockLabel
import takagi.ru.monica.ui.main.navigation.shortLabelRes

@Composable
fun AdaptiveMainScaffold(
    isCompactWidth: Boolean,
    tabs: List<BottomNavItem>,
    currentTab: BottomNavItem,
    onTabSelected: (BottomNavItem) -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        bottomBar = {
            if (isCompactWidth) {
                NavigationBar(
                    tonalElevation = 0.dp,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    tabs.forEach { item ->
                        val label = stringResource(item.shortLabelRes())
                        NavigationBarItem(
                            selected = item.key == currentTab.key,
                            onClick = { onTabSelected(item) },
                            icon = { Icon(item.icon, contentDescription = label) },
                            label = {
                                DockLabel(label)
                            }
                        )
                    }
                }
            }
        },
        floatingActionButton = {}
    ) { paddingValues ->
        if (isCompactWidth) {
            content(paddingValues)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tabs.forEach { item ->
                            val label = stringResource(item.shortLabelRes())
                            NavigationRailItem(
                                selected = item.key == currentTab.key,
                                onClick = { onTabSelected(item) },
                                icon = { Icon(item.icon, contentDescription = label) },
                                label = {
                                    Text(
                                        text = label,
                                        maxLines = 2,
                                        overflow = TextOverflow.Clip
                                    )
                                },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    content(PaddingValues())
                }
            }
        }
    }
}
