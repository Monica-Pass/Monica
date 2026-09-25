package takagi.ru.monica.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.ui.icons.InstalledIconCatalog
import takagi.ru.monica.ui.icons.InstalledIconOption
import takagi.ru.monica.ui.icons.InstalledIconPack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstalledIconPickerBottomSheet(
    onIconSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val compact = LocalConfiguration.current.screenHeightDp < 480
    var tab by rememberSaveable { mutableStateOf(0) }
    var packName by rememberSaveable { mutableStateOf<String?>(null) }
    var packLabel by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable(tab, packName) { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }
    var options by remember(tab, packName) { mutableStateOf(emptyList<InstalledIconOption>()) }
    var packs by remember(tab, packName) { mutableStateOf(emptyList<InstalledIconPack>()) }
    var loading by remember(tab, packName) { mutableStateOf(true) }
    var loadFailed by remember(tab, packName) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    LaunchedEffect(tab, packName, retry) {
        loading = true
        loadFailed = false
        try {
            val selectedPackage = packName
            when {
                selectedPackage != null -> options = InstalledIconCatalog.icons(
                    context, InstalledIconPack(selectedPackage, packLabel)
                )
                tab == 0 -> options = InstalledIconCatalog.applications(context)
                else -> packs = InstalledIconCatalog.packs(context)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            loading = false
        }
    }

    val filteredOptions = remember(options, query) {
        val search = query.trim()
        options.filter { it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
    }
    val filteredPacks = remember(packs, query) {
        val search = query.trim()
        packs.filter { it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
    }

    MonicaModalBottomSheet(
        onDismissRequest = { if (!saving) onDismissRequest() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    enabled = !saving,
                    onClick = { if (packName == null) onDismissRequest() else packName = null },
                ) {
                    Icon(
                        if (packName == null) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(if (packName == null) R.string.close else R.string.back),
                    )
                }
                Text(
                    text = if (packName == null) stringResource(R.string.custom_icon_pick_installed) else packLabel,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (compact) {
                    InstalledIconSearchField(query, { query = it }, !saving, Modifier.weight(1.5f))
                }
                if (saving) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            if (packName == null) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(R.string.custom_icon_installed_apps, R.string.custom_icon_installed_packs).forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = tab == index,
                            enabled = !saving,
                            onClick = { tab = index; saveFailed = false },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            if (!compact) {
                InstalledIconSearchField(query, { query = it }, !saving, Modifier.fillMaxWidth())
            }
            if (saveFailed) {
                Text(stringResource(R.string.custom_icon_installed_save_failed), color = MaterialTheme.colorScheme.error)
            }
            when {
                loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                loadFailed -> Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.custom_icon_installed_load_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                }
                packName == null && tab == 1 -> {
                    if (filteredPacks.isEmpty()) {
                        Text(
                            stringResource(if (query.isBlank()) R.string.custom_icon_installed_empty_packs else R.string.custom_icon_search_empty),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(filteredPacks, key = { it.packageName }) { pack ->
                                Surface(
                                    onClick = { packLabel = pack.label; packName = pack.packageName },
                                    enabled = !saving,
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        InstalledIconThumbnail(InstalledIconOption(pack.packageName, pack.label))
                                        Text(pack.label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                    }
                                }
                            }
                        }
                    }
                }
                filteredOptions.isEmpty() -> Text(
                    stringResource(if (packName != null && query.isBlank()) R.string.custom_icon_installed_empty_icons else R.string.custom_icon_search_empty),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textAlign = TextAlign.Center,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredOptions, key = { it.key }) { option ->
                        Surface(
                            onClick = {
                                saving = true
                                saveFailed = false
                                scope.launch {
                                    try {
                                        InstalledIconCatalog.importIcon(context, option)
                                            .onSuccess(onIconSelected)
                                            .onFailure { saveFailed = true }
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving,
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
                            ) {
                                InstalledIconThumbnail(option, size = if (compact) 40.dp else 48.dp)
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    minLines = if (compact) 1 else 2,
                                    maxLines = if (compact) 1 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledIconSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        placeholder = { Text(stringResource(R.string.custom_icon_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
    )
}

@Composable
private fun InstalledIconThumbnail(option: InstalledIconOption, size: Dp = 48.dp) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx().coerceIn(1, 384) }
    var bitmap by remember(option.key, sizePx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(option.key, sizePx) {
        bitmap = try {
            InstalledIconCatalog.bitmap(context, option, sizePx).asImageBitmap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) Image(loaded, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Outlined.Image, contentDescription = null)
    }
}
