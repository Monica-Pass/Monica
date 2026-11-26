package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.animateMonicaContentSize
import takagi.ru.monica.ui.components.MonicaSlider as Slider
import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import takagi.ru.monica.R
import takagi.ru.monica.util.PasswordGenerator
import takagi.ru.monica.viewmodel.GeneratorViewModel
import takagi.ru.monica.viewmodel.GeneratorType
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.data.PasswordHistoryManager
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordGenerationHistory
import takagi.ru.monica.data.HistoryFilterPreferences
import takagi.ru.monica.data.HistoryFilterSettings
import java.util.Date
import kotlin.random.Random
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.components.SshKeyGenerationProgressIndicator
import takagi.ru.monica.utils.ClipboardUtils

/**
 * 将密码转换为彩色文本
 * 数字:蓝色，符号:红色，字母:主题自适应颜色
 */
@Composable
fun colorizePassword(password: String): AnnotatedString {
    val colorScheme = MaterialTheme.colorScheme
    return buildAnnotatedString {
        password.forEach { char ->
            val color = when {
                char.isDigit() -> colorScheme.secondary
                char.isLetter() -> colorScheme.onSurface
                else -> colorScheme.error
            }
            withStyle(style = SpanStyle(color = color)) {
                append(char)
            }
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun GeneratorScreen(
    onNavigateBack: () -> Unit,
    passwordViewModel: PasswordViewModel,
    externalRefreshRequestKey: Int = 0,
    onRefreshRequestConsumed: () -> Unit = {},
    useExternalRefreshFab: Boolean = false,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {},
    viewModel: GeneratorViewModel = viewModel()
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // 历史记录状态
    var showHistorySheet by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showTopActionsMenu by remember { mutableStateOf(false) }
    val historyManager = remember { PasswordHistoryManager(context) }
    val historyList by historyManager.historyFlow.collectAsState(initial = emptyList())
    
    // 过滤设置
    val filterPreferences = remember { HistoryFilterPreferences(context) }
    val filterSettings by filterPreferences.filterSettings.collectAsState(initial = HistoryFilterSettings())
    
    // 根据过滤设置过滤历史记录
    val filteredHistoryList = remember(historyList, filterSettings) {
        historyList.filter { filterSettings.shouldShow(it.type) }
    }
    
    // 从ViewModel收集状态
    val selectedGenerator by viewModel.selectedGenerator.collectAsState()
    val gpgEditor: takagi.ru.monica.viewmodel.GpgEditorViewModel = viewModel(key = "gpg:generator")
    var saveGpg by remember { mutableStateOf(false) }
    var generatedDraft by remember { mutableStateOf<AddEditPasswordInitialDraft?>(null) }
    generatedDraft?.let { draft ->
        GeneratorEntryDialog(onDismiss = { generatedDraft = null }) {
            AddEditPasswordScreen(viewModel = passwordViewModel, passwordId = null, initialDraft = draft,
                onSaveCompleted = { generatedDraft = null }, onNavigateBack = { generatedDraft = null })
        }
    }
    if (saveGpg) {
        GeneratorEntryDialog(onDismiss = { saveGpg = false }) {
            GpgKeyScreen(passwords = passwordViewModel, editor = gpgEditor,
                onBack = { saveGpg = false }, onSaved = { saveGpg = false })
        }
    }
    val symbolLength by viewModel.symbolLength.collectAsState()
    val includeUppercase by viewModel.includeUppercase.collectAsState()
    val includeLowercase by viewModel.includeLowercase.collectAsState()
    val includeNumbers by viewModel.includeNumbers.collectAsState()
    val includeSymbols by viewModel.includeSymbols.collectAsState()
    val useSymbolExclusionMode by viewModel.useSymbolExclusionMode.collectAsState()
    val excludedSymbols by viewModel.excludedSymbols.collectAsState()
    val customSymbols by viewModel.customSymbols.collectAsState()
    val excludeSimilar by viewModel.excludeSimilar.collectAsState()
    val excludeAmbiguous by viewModel.excludeAmbiguous.collectAsState()
    val analyzeCommonPasswords by viewModel.analyzeCommonPasswords.collectAsState()
    val analyzeWeight by viewModel.analyzeWeight.collectAsState()
    val symbolResult by viewModel.symbolResult.collectAsState()
    val defaultSymbols = remember { PasswordGenerator.getDefaultSymbols() }
    val allowedSymbols = remember(useSymbolExclusionMode, excludedSymbols, customSymbols, defaultSymbols) {
        if (useSymbolExclusionMode) {
            defaultSymbols.filter { it !in excludedSymbols }
        } else {
            customSymbols
        }
    }
    val filteredAllowedSymbols = remember(allowedSymbols, excludeSimilar, excludeAmbiguous) {
        applyGeneratorSymbolFilters(allowedSymbols, excludeSimilar, excludeAmbiguous)
    }
    val excludedSymbolsSet = remember(excludedSymbols) { excludedSymbols.toSet() }

    // 用户密码数据用于分析
    val passwordEntries by passwordViewModel.passwordEntries.collectAsState()
    val appendGeneratorHistory: (password: String, domain: String, type: String) -> Unit = history@{ password, domain, type ->
        if (password.isBlank()) return@history
        val latest = historyList.firstOrNull()
        if (latest?.password == password && latest.type == type) return@history
        scope.launch {
            historyManager.addHistory(
                password = password,
                packageName = "generator",
                domain = domain,
                type = type
            )
        }
    }
    
    // 初始化时生成默认密码
    LaunchedEffect(Unit) {
        if (symbolResult.isEmpty()) {
            val result = PasswordGenerator.generatePassword(
                length = symbolLength,
                includeUppercase = includeUppercase,
                includeLowercase = includeLowercase,
                includeNumbers = includeNumbers,
                includeSymbols = includeSymbols,
                allowedSymbols = allowedSymbols,
                excludeSimilar = excludeSimilar,
                excludeAmbiguous = excludeAmbiguous,
                uppercaseMin = 0,
                lowercaseMin = 0,
                numbersMin = 0,
                symbolsMin = 0
            )
            viewModel.updateSymbolResult(result)
        }
    }
    
    // 最小字符数要求状态
    val uppercaseMin by viewModel.uppercaseMin.collectAsState()
    val lowercaseMin by viewModel.lowercaseMin.collectAsState()
    val numbersMin by viewModel.numbersMin.collectAsState()
    val symbolsMin by viewModel.symbolsMin.collectAsState()
    
    val passwordLength by viewModel.passwordLength.collectAsState()
    val firstLetterUppercase by viewModel.firstLetterUppercase.collectAsState()
    val includeNumbersInPassword by viewModel.includeNumbersInPassword.collectAsState()
    val customSeparator by viewModel.customSeparator.collectAsState()
    val separatorCountsTowardsLength by viewModel.separatorCountsTowardsLength.collectAsState()
    val segmentLength by viewModel.segmentLength.collectAsState()
    val passwordResult by viewModel.passwordResult.collectAsState()
    
    // 密码短语状态
    val passphraseWordCount by viewModel.passphraseWordCount.collectAsState()
    val passphraseDelimiter by viewModel.passphraseDelimiter.collectAsState()
    val passphraseCapitalize by viewModel.passphraseCapitalize.collectAsState()
    val passphraseIncludeNumber by viewModel.passphraseIncludeNumber.collectAsState()
    val passphraseCustomWord by viewModel.passphraseCustomWord.collectAsState()
    val passphraseCustomWords by viewModel.passphraseCustomWords.collectAsState()
    val passphraseResult by viewModel.passphraseResult.collectAsState()
    val parsedCustomMnemonicWords = remember(passphraseCustomWords) {
        passphraseCustomWords
            .split(",", " ", "\n", "\t")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    
    val pinLength by viewModel.pinLength.collectAsState()
    val pinResult by viewModel.pinResult.collectAsState()

    // SSH 密钥生成器状态
    val sshKeyAlgorithm by viewModel.sshKeyAlgorithm.collectAsState()
    val sshKeyRsaSize by viewModel.sshKeyRsaSize.collectAsState()
    val sshKeyResult by viewModel.sshKeyResult.collectAsState()
    var isSshKeyGenerating by remember { mutableStateOf(false) }
    var symbolRulesExpanded by remember { mutableStateOf(false) }

    val regenerateNow: () -> Unit = regenerate@{
        when (selectedGenerator) {
            GeneratorType.SYMBOL -> {
                if (includeSymbols && filteredAllowedSymbols.isEmpty()) {
                    viewModel.updateSymbolResult("")
                    return@regenerate
                }
                val result = if (analyzeCommonPasswords && passwordEntries.isNotEmpty()) {
                    PasswordGenerator.generateSimilarPassword(
                        passwords = passwordEntries,
                        targetLength = symbolLength,
                        includeUppercase = includeUppercase,
                        includeLowercase = includeLowercase,
                        includeNumbers = includeNumbers,
                        includeSymbols = includeSymbols,
                        allowedSymbols = allowedSymbols,
                        excludeSimilar = excludeSimilar,
                        excludeAmbiguous = excludeAmbiguous,
                        weightPercent = analyzeWeight
                    )
                } else {
                    PasswordGenerator.generatePassword(
                        length = symbolLength,
                        includeUppercase = includeUppercase,
                        includeLowercase = includeLowercase,
                        includeNumbers = includeNumbers,
                        includeSymbols = includeSymbols,
                        allowedSymbols = allowedSymbols,
                        excludeSimilar = excludeSimilar,
                        excludeAmbiguous = excludeAmbiguous,
                        uppercaseMin = uppercaseMin,
                        lowercaseMin = lowercaseMin,
                        numbersMin = numbersMin,
                        symbolsMin = symbolsMin
                    )
                }
                viewModel.updateSymbolResult(result)
                if (result.isEmpty()) return@regenerate
            }
            GeneratorType.PASSWORD -> {
                val result = generatePassword(
                    length = passwordLength,
                    firstLetterUppercase = firstLetterUppercase,
                    includeNumbers = includeNumbersInPassword,
                    separator = customSeparator,
                    separatorCountsTowardsLength = separatorCountsTowardsLength,
                    segmentLength = segmentLength
                )
                viewModel.updatePasswordResult(result)
            }
            GeneratorType.PASSPHRASE -> {
                val result = PasswordGenerator.generatePassphrase(
                    context = context,
                    wordCount = passphraseWordCount,
                    delimiter = passphraseDelimiter,
                    capitalize = passphraseCapitalize,
                    includeNumber = passphraseIncludeNumber,
                    customWord = passphraseCustomWord.takeIf { it.isNotEmpty() },
                    customWords = parsedCustomMnemonicWords
                )
                viewModel.updatePassphraseResult(result)
            }
            GeneratorType.PIN -> {
                val result = PasswordGenerator.generatePinCode(pinLength)
                viewModel.updatePinResult(result)
            }
            GeneratorType.GPG_KEY -> gpgEditor.generate()
            GeneratorType.SSH_KEY -> {
                if (isSshKeyGenerating) return@regenerate
                scope.launch {
                    isSshKeyGenerating = true
                    val request = buildSshKeyRequest(sshKeyAlgorithm, sshKeyRsaSize)
                    val generated = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching { takagi.ru.monica.utils.SshKeyGenerator.generate(request) }
                        }
                    } finally {
                        isSshKeyGenerating = false
                    }
                    generated.onSuccess { data ->
                        viewModel.updateSshKeyResult(data)
                    }.onFailure {
                        Toast.makeText(
                            context,
                            context.getString(R.string.ssh_key_generate_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(externalRefreshRequestKey) {
        if (externalRefreshRequestKey != 0) {
            regenerateNow()
            onRefreshRequestConsumed()
        }
    }
    
    // ✨ 自动生成：监听参数变化并实时生成
    LaunchedEffect(
        selectedGenerator,
        symbolLength, includeUppercase, includeLowercase, includeNumbers, 
        includeSymbols, useSymbolExclusionMode, excludedSymbols, customSymbols, excludeSimilar, excludeAmbiguous, analyzeCommonPasswords, analyzeWeight,
        uppercaseMin, lowercaseMin, numbersMin, symbolsMin,
        passwordLength, firstLetterUppercase, includeNumbersInPassword, 
        customSeparator, separatorCountsTowardsLength, segmentLength,
        passphraseWordCount, passphraseDelimiter, passphraseCapitalize, 
        passphraseIncludeNumber, passphraseCustomWord, passphraseCustomWords,
        pinLength,
        sshKeyAlgorithm, sshKeyRsaSize
    ) {
        // 延迟100ms以避免频繁生成
        kotlinx.coroutines.delay(100)
        
        when (selectedGenerator) {
            GeneratorType.SYMBOL -> {
                if (includeSymbols && filteredAllowedSymbols.isEmpty()) {
                    viewModel.updateSymbolResult("")
                    return@LaunchedEffect
                }
                val result = if (analyzeCommonPasswords && passwordEntries.isNotEmpty()) {
                    PasswordGenerator.generateSimilarPassword(
                        passwords = passwordEntries,
                        targetLength = symbolLength,
                        includeUppercase = includeUppercase,
                        includeLowercase = includeLowercase,
                        includeNumbers = includeNumbers,
                        includeSymbols = includeSymbols,
                        allowedSymbols = allowedSymbols,
                        excludeSimilar = excludeSimilar,
                        excludeAmbiguous = excludeAmbiguous,
                        weightPercent = analyzeWeight
                    )
                } else {
                    PasswordGenerator.generatePassword(
                        length = symbolLength,
                        includeUppercase = includeUppercase,
                        includeLowercase = includeLowercase,
                        includeNumbers = includeNumbers,
                        includeSymbols = includeSymbols,
                        allowedSymbols = allowedSymbols,
                        excludeSimilar = excludeSimilar,
                        excludeAmbiguous = excludeAmbiguous,
                        uppercaseMin = uppercaseMin,
                        lowercaseMin = lowercaseMin,
                        numbersMin = numbersMin,
                        symbolsMin = symbolsMin
                    )
                }
                viewModel.updateSymbolResult(result)
                if (result.isEmpty()) return@LaunchedEffect
            }
            GeneratorType.PASSWORD -> {
                val result = generatePassword(
                    length = passwordLength,
                    firstLetterUppercase = firstLetterUppercase,
                    includeNumbers = includeNumbersInPassword,
                    separator = customSeparator,
                    separatorCountsTowardsLength = separatorCountsTowardsLength,
                    segmentLength = segmentLength
                )
                viewModel.updatePasswordResult(result)
            }
            GeneratorType.PASSPHRASE -> {
                val result = PasswordGenerator.generatePassphrase(
                    context = context,
                    wordCount = passphraseWordCount,
                    delimiter = passphraseDelimiter,
                    capitalize = passphraseCapitalize,
                    includeNumber = passphraseIncludeNumber,
                    customWord = passphraseCustomWord.takeIf { it.isNotEmpty() },
                    customWords = parsedCustomMnemonicWords
                )
                viewModel.updatePassphraseResult(result)
            }
            GeneratorType.PIN -> {
                val result = PasswordGenerator.generatePinCode(pinLength)
                viewModel.updatePinResult(result)
            }
            GeneratorType.GPG_KEY -> Unit
            GeneratorType.SSH_KEY -> {
                // 生成 SSH 密钥可能比较慢 (RSA 4096 1-3s)，放到默认调度器。
                val request = buildSshKeyRequest(sshKeyAlgorithm, sshKeyRsaSize)
                isSshKeyGenerating = true
                val generated = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching {
                            takagi.ru.monica.utils.SshKeyGenerator.generate(request)
                        }.getOrNull()
                    }
                } finally {
                    isSshKeyGenerating = false
                }
                viewModel.updateSshKeyResult(generated)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        var generatorMenuExpanded by remember { mutableStateOf(false) }
        val currentResult = when (selectedGenerator) {
            GeneratorType.SYMBOL -> symbolResult
            GeneratorType.PASSWORD -> passwordResult
            GeneratorType.PASSPHRASE -> passphraseResult
            GeneratorType.PIN -> pinResult
            GeneratorType.GPG_KEY -> ""
            GeneratorType.SSH_KEY -> sshKeyResult?.fingerprintSha256.orEmpty()
        }
        val resultCardTitle = when (selectedGenerator) {
            GeneratorType.SSH_KEY -> sshKeyResult?.algorithm.orEmpty()
                .takeIf { it.isNotEmpty() } ?: stringResource(R.string.generator_ssh_key)
            else -> null
        }
        val resultCardShowStrength = selectedGenerator != GeneratorType.SSH_KEY
        val resultCardSupportingInfo = when (selectedGenerator) {
            GeneratorType.SSH_KEY -> sshKeyResult?.let { data ->
                if (data.algorithm == takagi.ru.monica.data.model.SshKeyData.ALGORITHM_RSA) {
                    "${data.keySize} bits"
                } else {
                    data.algorithm
                }
            } ?: ""
            else -> null
        }
        val isSshKeyGenerator = selectedGenerator == GeneratorType.SSH_KEY
        val shouldShowResultCard = currentResult.isNotEmpty()
        val shouldShowSshGenerationProgress = isSshKeyGenerator && isSshKeyGenerating
        val copyGeneratedResult: (String) -> Unit = { text ->
            copyToClipboard(context, text)
            appendGeneratorHistory(
                text,
                generatorTypeTitle(selectedGenerator, context),
                selectedGenerator.name
            )
            Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
        // SSH_KEY 的结果卡片不参与 sticky compact 模式：指纹文本短，不需要收缩，
        // 且 compact 切换会导致高度跳变影响滚动体验。
        val resultCardCompactProgress by remember(
            listState,
            shouldShowResultCard,
            selectedGenerator
        ) {
            derivedStateOf {
                if (isSshKeyGenerator || !shouldShowResultCard) {
                    0f
                } else {
                    if (listState.firstVisibleItemIndex > 0) {
                        1f
                    } else {
                        val generatorSelectorSize = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == 0 }
                            ?.size
                            ?.coerceAtLeast(1)
                            ?: 1
                        (listState.firstVisibleItemScrollOffset.toFloat() /
                            generatorSelectorSize.toFloat()).coerceIn(0f, 1f)
                    }
                }
            }
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize(),
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                        Text(
                            text = stringResource(R.string.generator_title),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row {
                            IconButton(onClick = { showHistorySheet = true }) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = stringResource(R.string.history),
                                )
                            }
                            if (showStandaloneSettingsEntry) {
                                Box {
                                    IconButton(onClick = { showTopActionsMenu = true }) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = stringResource(R.string.more_options),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showTopActionsMenu,
                                        onDismissRequest = { showTopActionsMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.nav_settings)) },
                                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                            onClick = {
                                                showTopActionsMenu = false
                                                onOpenStandaloneSettings()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                state = listState,
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GeneratorTypeSplitButton(
                        selectedGenerator = selectedGenerator,
                        expanded = generatorMenuExpanded,
                        onExpandedChange = { generatorMenuExpanded = it },
                        onGenerateCurrent = regenerateNow,
                        onSelectType = {
                            generatorMenuExpanded = false
                            viewModel.updateSelectedGenerator(it)
                        }
                    )
                }
            }

            if (selectedGenerator == GeneratorType.GPG_KEY && (gpgEditor.key != null || gpgEditor.busy || gpgEditor.failed)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (gpgEditor.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (gpgEditor.failed) Text(stringResource(gpgEditor.failure.message), color = MaterialTheme.colorScheme.error)
                        gpgEditor.key?.let { key ->
                            GpgGeneratedResult(key) { saveGpg = true }
                            FilledTonalButton(onClick = { saveGpg = true }) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.gpg_save))
                            }
                        }
                    }
                }
            }
            if (isSshKeyGenerator && (shouldShowSshGenerationProgress || shouldShowResultCard)) {
                item {
                    if (shouldShowSshGenerationProgress) {
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 20.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                        ) {
                            SshKeyGenerationProgressIndicator(
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        sshKeyResult?.let { key ->
                            SshGeneratedResult(key, Modifier.fillMaxWidth().padding(bottom = 20.dp))
                        }
                    }
                }
            } else if (shouldShowResultCard) {
                stickyHeader {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        ResultCard(
                            result = currentResult,
                            title = resultCardTitle,
                            showStrengthSection = resultCardShowStrength,
                            supportingInfo = resultCardSupportingInfo,
                            compactProgress = resultCardCompactProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 20.dp - 12.dp * resultCardCompactProgress),
                            onCopy = copyGeneratedResult,
                            onCreateUsername = { generatedDraft = AddEditPasswordInitialDraft(username = it) },
                            onCreatePassword = { generatedDraft = AddEditPasswordInitialDraft(password = it) }
                        )
                    }
                }
            }

            item {
                // 根据选择的生成器类型显示相应的配置选项
                ElevatedCard(
                    modifier = if (selectedGenerator == GeneratorType.SSH_KEY) {
                        Modifier.fillMaxWidth().fillParentMaxHeight(0.6f)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when (selectedGenerator) {
                    GeneratorType.SYMBOL -> {
                    // 随机符号生成器配置选项
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.symbol_password_generator),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        IntegerSliderWithInput(
                            label = stringResource(R.string.length_label, symbolLength),
                            value = symbolLength,
                            onValueChange = viewModel::updateSymbolLength,
                            valueRange = 4..128
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 字符类型复选框选项
                        Text(
                            text = stringResource(R.string.character_types),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CheckboxWithText(
                                text = stringResource(R.string.include_uppercase),
                                checked = includeUppercase,
                                onCheckedChange = { viewModel.updateIncludeUppercase(it) }
                            )
                            
                            CheckboxWithText(
                                text = stringResource(R.string.include_lowercase),
                                checked = includeLowercase,
                                onCheckedChange = { viewModel.updateIncludeLowercase(it) }
                            )
                            
                            CheckboxWithText(
                                text = stringResource(R.string.include_numbers),
                                checked = includeNumbers,
                                onCheckedChange = { viewModel.updateIncludeNumbers(it) }
                            )
                            
                            CheckboxWithText(
                                text = stringResource(R.string.include_symbols),
                                checked = includeSymbols,
                                onCheckedChange = {
                                    viewModel.updateIncludeSymbols(it)
                                    if (!it && symbolsMin > 0) {
                                        viewModel.updateSymbolsMin(0)
                                    }
                                }
                            )
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.generator_symbols_setup),
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                text = stringResource(
                                                    R.string.generator_effective_symbols,
                                                    filteredAllowedSymbols.ifEmpty { "-" }
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (includeSymbols && filteredAllowedSymbols.isEmpty()) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                        IconButton(
                                            onClick = { symbolRulesExpanded = !symbolRulesExpanded },
                                            enabled = includeSymbols
                                        ) {
                                            Icon(
                                                imageVector = if (symbolRulesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = if (symbolRulesExpanded) "Collapse symbol rules" else "Expand symbol rules"
                                            )
                                        }
                                    }

                                    AnimatedVisibility(visible = symbolRulesExpanded) {
                                        Column {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                                SegmentedButton(
                                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                                    onClick = { viewModel.updateUseSymbolExclusionMode(true) },
                                                    selected = useSymbolExclusionMode,
                                                    enabled = includeSymbols
                                                ) {
                                                    Text(stringResource(R.string.generator_symbols_mode_exclude))
                                                }
                                                SegmentedButton(
                                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                                    onClick = { viewModel.updateUseSymbolExclusionMode(false) },
                                                    selected = !useSymbolExclusionMode,
                                                    enabled = includeSymbols
                                                ) {
                                                    Text(stringResource(R.string.generator_symbols_mode_whitelist))
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            if (useSymbolExclusionMode) {
                                                Text(
                                                    text = stringResource(R.string.generator_symbols_exclude_hint),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(
                                                        onClick = { viewModel.clearExcludedSymbols() },
                                                        enabled = includeSymbols
                                                    ) {
                                                        Text(stringResource(R.string.generator_custom_symbols_reset))
                                                    }
                                                    OutlinedButton(
                                                        onClick = { viewModel.updateExcludedSymbols("&!") },
                                                        enabled = includeSymbols
                                                    ) {
                                                        Text(stringResource(R.string.generator_exclude_amp_bang))
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(8.dp))
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    defaultSymbols.forEach { symbol ->
                                                        FilterChip(
                                                            selected = symbol in excludedSymbolsSet,
                                                            onClick = { viewModel.toggleExcludedSymbol(symbol) },
                                                            enabled = includeSymbols,
                                                            label = { Text(symbol.toString()) }
                                                        )
                                                    }
                                                }
                                            } else {
                                                OutlinedTextField(
                                                    value = customSymbols,
                                                    onValueChange = { viewModel.updateCustomSymbols(it) },
                                                    label = { Text(stringResource(R.string.generator_custom_symbols_label)) },
                                                    placeholder = { Text(stringResource(R.string.generator_custom_symbols_placeholder)) },
                                                    singleLine = true,
                                                    enabled = includeSymbols,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.End
                                                ) {
                                                    TextButton(
                                                        onClick = { viewModel.resetCustomSymbols() },
                                                        enabled = includeSymbols
                                                    ) {
                                                        Text(text = stringResource(R.string.generator_custom_symbols_reset))
                                                    }
                                                }
                                            }

                                            if (includeSymbols && filteredAllowedSymbols.isEmpty()) {
                                                Text(
                                                    text = stringResource(R.string.generator_custom_symbols_empty_error),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                             
                            CheckboxWithText(
                                text = stringResource(R.string.generator_exclude_similar),
                                checked = excludeSimilar,
                                onCheckedChange = { viewModel.updateExcludeSimilar(it) }
                            )
                            
                            CheckboxWithText(
                                text = stringResource(R.string.generator_exclude_ambiguous),
                                checked = excludeAmbiguous,
                                onCheckedChange = { viewModel.updateExcludeAmbiguous(it) }
                            )

                            CheckboxWithText(
                                text = stringResource(R.string.analyze_common_passwords),
                                checked = analyzeCommonPasswords,
                                onCheckedChange = { viewModel.updateAnalyzeCommonPasswords(it) }
                            )
                            if (analyzeCommonPasswords) {
                                Text(
                                    text = stringResource(R.string.analyze_common_passwords_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                                )

                                // 权重滑块：控制保留常见片段的强度
                                IntegerSliderWithInput(
                                    label = stringResource(R.string.analyze_weight_label, analyzeWeight),
                                    value = analyzeWeight,
                                    onValueChange = viewModel::updateAnalyzeWeight,
                                    valueRange = 0..100,
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.generator_require_symbol),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = includeSymbols && symbolsMin > 0,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        viewModel.updateIncludeSymbols(true)
                                        viewModel.updateSymbolsMin(if (symbolsMin > 0) symbolsMin else 1)
                                    } else {
                                        viewModel.updateSymbolsMin(0)
                                    }
                                },
                                enabled = includeSymbols || symbolsMin > 0
                            )
                        }
                         
                        // 最小字符数要求
                        Text(
                            text = stringResource(R.string.minimum_characters_requirement),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        Column {
                            // 大写字母最小数量
                            IntegerSliderWithInput(
                                label = stringResource(R.string.min_uppercase, uppercaseMin),
                                value = uppercaseMin,
                                onValueChange = viewModel::updateUppercaseMin,
                                valueRange = 0..10,
                                enabled = includeUppercase
                            )
                            
                            // 小写字母最小数量
                            IntegerSliderWithInput(
                                label = stringResource(R.string.min_lowercase, lowercaseMin),
                                value = lowercaseMin,
                                onValueChange = viewModel::updateLowercaseMin,
                                valueRange = 0..10,
                                enabled = includeLowercase
                            )
                            
                            // 数字最小数量
                            IntegerSliderWithInput(
                                label = stringResource(R.string.min_numbers, numbersMin),
                                value = numbersMin,
                                onValueChange = viewModel::updateNumbersMin,
                                valueRange = 0..10,
                                enabled = includeNumbers
                            )
                            
                            // 符号最小数量
                            IntegerSliderWithInput(
                                label = stringResource(R.string.min_symbols, symbolsMin),
                                value = symbolsMin,
                                onValueChange = viewModel::updateSymbolsMin,
                                valueRange = 0..10,
                                enabled = includeSymbols
                            )
                        }
                    }
                }
                
                    GeneratorType.PASSWORD -> {
                    // 口令生成器配置选项
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.password_generator),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        IntegerSliderWithInput(
                            label = "${stringResource(R.string.generator_length)}: $passwordLength",
                            value = passwordLength,
                            onValueChange = viewModel::updatePasswordLength,
                            valueRange = 4..128
                        )
                        
                        // 复选框选项
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CheckboxWithText(
                                text = stringResource(R.string.generator_first_letter_uppercase),
                                checked = firstLetterUppercase,
                                onCheckedChange = { viewModel.updateFirstLetterUppercase(it) }
                            )
                            
                            CheckboxWithText(
                                text = stringResource(R.string.generator_include_numbers_in_password),
                                checked = includeNumbersInPassword,
                                onCheckedChange = { viewModel.updateIncludeNumbersInPassword(it) }
                            )
                            
                            // 新增：分隔符是否计入长度
                            CheckboxWithText(
                                text = stringResource(R.string.generator_separator_counts_towards_length),
                                checked = separatorCountsTowardsLength,
                                onCheckedChange = { viewModel.updateSeparatorCountsTowardsLength(it) }
                            )
                        }
                        
                        // 自定义分隔符
                        OutlinedTextField(
                            value = customSeparator,
                            onValueChange = { viewModel.updateCustomSeparator(it) },
                            label = { Text(stringResource(R.string.generator_custom_separator)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        
                        IntegerSliderWithInput(
                            label = "${stringResource(R.string.generator_segment_length)}: $segmentLength",
                            value = segmentLength,
                            onValueChange = viewModel::updateSegmentLength,
                            valueRange = 0..20,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                
                    GeneratorType.PASSPHRASE -> {
                    // 密码短语生成器配置选项
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.passphrase_generator),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        IntegerSliderWithInput(
                            label = stringResource(R.string.word_count, passphraseWordCount),
                            value = passphraseWordCount,
                            onValueChange = viewModel::updatePassphraseWordCount,
                            valueRange = 1..20
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 分隔符输入
                        OutlinedTextField(
                            value = passphraseDelimiter,
                            onValueChange = { viewModel.updatePassphraseDelimiter(it) },
                            label = { Text(stringResource(R.string.delimiter)) },
                            placeholder = { Text(stringResource(R.string.delimiter_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 选项设置
                        CheckboxWithText(
                            text = stringResource(R.string.capitalize_first_letter),
                            checked = passphraseCapitalize,
                            onCheckedChange = { viewModel.updatePassphraseCapitalize(it) }
                        )
                        
                        CheckboxWithText(
                            text = stringResource(R.string.add_number_at_end),
                            checked = passphraseIncludeNumber,
                            onCheckedChange = { viewModel.updatePassphraseIncludeNumber(it) }
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 自定义单词
                        OutlinedTextField(
                            value = passphraseCustomWord,
                            onValueChange = { viewModel.updatePassphraseCustomWord(it) },
                            label = { Text(stringResource(R.string.custom_word_optional)) },
                            placeholder = { Text(stringResource(R.string.custom_word_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = passphraseCustomWords,
                            onValueChange = { viewModel.updatePassphraseCustomWords(it) },
                            label = { Text(stringResource(R.string.custom_mnemonic_words_optional)) },
                            placeholder = { Text(stringResource(R.string.custom_mnemonic_words_placeholder)) },
                            supportingText = {
                                Text(stringResource(R.string.custom_mnemonic_words_hint))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )
                    }
                }
                
                    GeneratorType.PIN -> {
                    // PIN码生成器配置选项
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.pin_generator),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        IntegerSliderWithInput(
                            label = stringResource(R.string.pin_length, pinLength),
                            value = pinLength,
                            onValueChange = viewModel::updatePinLength,
                            valueRange = 3..9
                        )
                    }
                }
                    GeneratorType.GPG_KEY -> GpgGenerationForm(gpgEditor)
                    GeneratorType.SSH_KEY -> {
                        GeneratorSshKeySection(
                            algorithm = sshKeyAlgorithm,
                            onAlgorithmChange = viewModel::updateSshKeyAlgorithm,
                            rsaKeySize = sshKeyRsaSize,
                            onRsaKeySizeChange = viewModel::updateSshKeyRsaSize
                        )
                    }
                }
                    }
                }
            }
        }

        }
        
        if (!useExternalRefreshFab) {
            // 右下角重新生成按钮 - FAB 设计
            var isRegenerating by remember { mutableStateOf(false) }

            FloatingActionButton(
                onClick = {
                    if (selectedGenerator == GeneratorType.SSH_KEY && isSshKeyGenerating) return@FloatingActionButton
                    isRegenerating = true
                    regenerateNow()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                // 旋转动画
                val rotation by animateFloatAsState(
                    targetValue = if (isRegenerating) 360f else 0f,
                    animationSpec = tween(
                        durationMillis = 500,
                        easing = FastOutSlowInEasing
                    ),
                    finishedListener = { isRegenerating = false },
                    label = "refresh_rotation"
                )

                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.regenerate),
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = rotation }
                )
            }
        }
    }
    
    GeneratorHistorySheet(
        visible = showHistorySheet,
        onDismiss = { showHistorySheet = false },
        onShowFilter = { showFilterSheet = true },
        filteredHistoryList = filteredHistoryList,
        historyManager = historyManager,
        scope = scope,
        context = context,
        passwordViewModel = passwordViewModel
    )

    GeneratorFilterSheet(
        visible = showFilterSheet,
        onDismiss = { showFilterSheet = false },
        filterSettings = filterSettings,
        filterPreferences = filterPreferences,
        scope = scope
    )
}



@Composable
fun BookmarkTab(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(48.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 8.dp,
            topEnd = 8.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp
        ),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        onClick = onClick
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
fun CheckboxWithText(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun IntegerSliderWithInput(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val minValue = valueRange.first
    val maxValue = valueRange.last
    val coercedValue = value.coerceIn(minValue, maxValue)
    val sliderSteps = (maxValue - minValue - 1).coerceAtLeast(0)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var isEditing by remember { mutableStateOf(false) }
    var editingFieldWasFocused by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf(TextFieldValue(coercedValue.toString())) }
    val valueText = coercedValue.toString()
    val valueIndex = label.lastIndexOf(valueText)
    val labelPrefix = if (valueIndex >= 0) label.substring(0, valueIndex) else "$label: "
    val labelSuffix = if (valueIndex >= 0) label.substring(valueIndex + valueText.length) else ""
    val maxDigits = maxValue.toString().length.coerceAtLeast(1)

    fun finishEditing() {
        val committedValue = inputValue.text
            .toIntOrNull()
            ?.coerceIn(minValue, maxValue)
            ?: coercedValue
        if (committedValue != value) {
            onValueChange(committedValue)
        }
        inputValue = TextFieldValue(committedValue.toString())
        isEditing = false
        keyboardController?.hide()
    }

    LaunchedEffect(coercedValue, isEditing) {
        if (!isEditing && inputValue.text.toIntOrNull() != coercedValue) {
            inputValue = TextFieldValue(coercedValue.toString())
        }
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            editingFieldWasFocused = false
            inputValue = TextFieldValue(
                text = coercedValue.toString(),
                selection = TextRange(0, coercedValue.toString().length)
            )
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .then(
                        if (enabled && !isEditing) {
                            Modifier.clickable { isEditing = true }
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = labelPrefix,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
                Box(
                    modifier = Modifier.widthIn(min = 32.dp, max = 80.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (isEditing && enabled) {
                        BasicTextField(
                            value = inputValue,
                            onValueChange = { raw ->
                                val digits = raw.text.filter(Char::isDigit).take(maxDigits)
                                inputValue = TextFieldValue(
                                    text = digits,
                                    selection = TextRange(digits.length)
                                )
                                digits.toIntOrNull()
                                    ?.coerceIn(minValue, maxValue)
                                    ?.let { if (it != value) onValueChange(it) }
                            },
                            modifier = Modifier
                                .widthIn(min = 32.dp, max = 80.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        editingFieldWasFocused = true
                                    } else if (editingFieldWasFocused && isEditing) {
                                        finishEditing()
                                    }
                                },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { finishEditing() }
                            )
                        )
                    } else {
                        Text(
                            text = valueText,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                }
                if (labelSuffix.isNotEmpty()) {
                    Text(
                        text = labelSuffix,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }
        }
        Slider(
            value = coercedValue.toFloat(),
            onValueChange = {
                onValueChange(it.roundToInt().coerceIn(minValue, maxValue))
                if (!isEditing) {
                    inputValue = TextFieldValue(it.roundToInt().coerceIn(minValue, maxValue).toString())
                }
            },
            valueRange = minValue.toFloat()..maxValue.toFloat(),
            steps = sliderSteps,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )
    }
}

@Composable
fun ResultDisplay(
    result: String,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.generator_result),
            style = MaterialTheme.typography.bodyMedium
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f)
            ) {
                ColorCodedText(
                    text = result,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            
            IconButton(
                onClick = { onCopy(result) }
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.generator_copy)
                )
            }
        }
    }
}

@Composable
fun ColorCodedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    // 创建带颜色的AnnotatedString
    val annotatedString = buildAnnotatedString {
        text.forEach { char ->
            when {
                char.isDigit() -> {
                    // 数字字符显示为蓝色
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                        append(char)
                    }
                }
                char.isLetter() -> {
                    // 字母字符显示为白色（或根据主题的onSurface颜色）
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                        append(char)
                    }
                }
                else -> {
                    // 符号字符显示为红色（或使用secondary颜色以确保可读性）
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                        append(char)
                    }
                }
            }
        }
    }
    
    Text(
        text = annotatedString,
        style = style,
        modifier = modifier
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Generated Text", text)
    clipboard.setPrimaryClip(clip)
}

/**
 * 把 UI 上的算法字符串和 RSA 长度组合成 [takagi.ru.monica.utils.SshKeyGenerator.Request]。
 */
private fun buildSshKeyRequest(
    algorithm: String,
    rsaKeySize: Int
): takagi.ru.monica.utils.SshKeyGenerator.Request = when {
    algorithm.equals(
        takagi.ru.monica.data.model.SshKeyData.ALGORITHM_RSA,
        ignoreCase = true
    ) -> takagi.ru.monica.utils.SshKeyGenerator.Request.Rsa(rsaKeySize)
    else -> takagi.ru.monica.utils.SshKeyGenerator.Request.Ed25519
}

private fun generatePassword(
    length: Int,
    firstLetterUppercase: Boolean,
    includeNumbers: Boolean,
    separator: String,
    separatorCountsTowardsLength: Boolean = false,
    segmentLength: Int = 0
): String {
    val words = listOf(
        "apple", "banana", "cherry", "date", "elderberry",
        "fig", "grape", "honeydew", "kiwi", "lemon",
        "mango", "nectarine", "orange", "papaya", "quince",
        "raspberry", "strawberry", "tangerine", "watermelon", "blueberry"
    )
    
    // 确保分段长度有效（大于0且小于总长度）
    val effectiveSegmentLength = if (segmentLength > 0 && segmentLength < length) segmentLength else 0
    
    // 如果没有有效的分段长度或分隔符为空，则按原来的方式生成密码
    if (effectiveSegmentLength == 0 || separator.isEmpty()) {
        // 构建基础密码
        val targetWordCount = (length / 6).coerceIn(2, 5) // 至少2个单词，最多5个
        val selectedWords = (1..targetWordCount).map { words.random() }.toMutableList()
        
        // 处理首字母大写
        if (firstLetterUppercase) {
            selectedWords[0] = selectedWords[0].replaceFirstChar { it.uppercase() }
        }
        
        var result = selectedWords.joinToString("")
        
        // 添加数字
        val numbers = if (includeNumbers) {
            val remainingLength = (length - result.length).coerceAtLeast(0)
            if (remainingLength > 0) {
                (1..remainingLength).joinToString("") { Random.nextInt(0, 10).toString() }
            } else {
                ""
            }
        } else {
            ""
        }
        
        result += numbers
        return result.take(length)
    }
    
    // 处理分隔符的情况
    val separatorChar = separator.first()
    
    if (separatorCountsTowardsLength) {
        // 分隔符计入长度：需要在生成密码时就考虑分隔符占用的空间
        
        // 计算在指定长度内可以有多少个分隔符
        val maxSeparators = (length - 1) / effectiveSegmentLength
        val contentLength = length - maxSeparators
        
        // 生成指定长度的内容
        val targetWordCount = (contentLength / 6).coerceIn(2, 5)
        val selectedWords = (1..targetWordCount).map { words.random() }.toMutableList()
        
        // 处理首字母大写
        if (firstLetterUppercase) {
            selectedWords[0] = selectedWords[0].replaceFirstChar { it.uppercase() }
        }
        
        var content = selectedWords.joinToString("")
        
        // 添加数字
        val numbers = if (includeNumbers) {
            val remainingLength = (contentLength - content.length).coerceAtLeast(0)
            if (remainingLength > 0) {
                (1..remainingLength).joinToString("") { Random.nextInt(0, 10).toString() }
            } else {
                ""
            }
        } else {
            ""
        }
        
        content += numbers
        content = content.take(contentLength)
        
        // 插入分隔符
        val builder = StringBuilder()
        for (i in content.indices) {
            builder.append(content[i])
            // 在指定位置添加分隔符，但不包括最后一个字符
            if ((i + 1) % effectiveSegmentLength == 0 && i < content.length - 1 && builder.length < length) {
                builder.append(separatorChar)
            }
        }
        
        return builder.toString().take(length)
    } else {
        // 分隔符不计入长度：先生成指定长度的内容，然后再插入分隔符
        
        // 生成指定长度的内容
        val targetWordCount = (length / 6).coerceIn(2, 5)
        val selectedWords = (1..targetWordCount).map { words.random() }.toMutableList()
        
        // 处理首字母大写
        if (firstLetterUppercase) {
            selectedWords[0] = selectedWords[0].replaceFirstChar { it.uppercase() }
        }
        
        var content = selectedWords.joinToString("")
        
        // 添加数字
        val numbers = if (includeNumbers) {
            val remainingLength = (length - content.length).coerceAtLeast(0)
            if (remainingLength > 0) {
                (1..remainingLength).joinToString("") { Random.nextInt(0, 10).toString() }
            } else {
                ""
            }
        } else {
            ""
        }
        
        content += numbers
        content = content.take(length)
        
        // 插入分隔符
        val builder = StringBuilder()
        for (i in content.indices) {
            builder.append(content[i])
            // 在指定位置添加分隔符，但不包括最后一个字符
            if ((i + 1) % effectiveSegmentLength == 0 && i < content.length - 1) {
                builder.append(separatorChar)
            }
        }
        
        return builder.toString()
    }
}

private fun generatePin(length: Int): String {
    val digits = "0123456789"
    return (1..length)
        .map { digits.random() }
        .joinToString("")
}

/**
 * Pill 样式的标签按钮
 */
@Composable
private fun FilterChipTab(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) 
            MaterialTheme.colorScheme.primary 
        else 
            Color.Transparent,
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) 
                    MaterialTheme.colorScheme.onPrimary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 优化后的结果显示卡片 - 带流畅动画
 */
@Composable
internal fun ResultCard(
    result: String,
    title: String? = null,
    showStrengthSection: Boolean = true,
    supportingInfo: String? = null,
    modifier: Modifier = Modifier,
    compactProgress: Float = 0f,
    onCopy: (String) -> Unit,
    onCreateUsername: ((String) -> Unit)? = null,
    onCreatePassword: ((String) -> Unit)? = null
) {
    var showCopied by remember { mutableStateOf(false) }
    var resultMenu by remember(result) { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val progress = compactProgress.coerceIn(0f, 1f)
    // Keep discrete text/layout changes until the continuous size transition is
    // effectively complete, preventing a visible jump at the end of the drag.
    val compactMode = progress >= 0.999f
    val context = LocalContext.current
    val strengthResult = remember(result, context) {
        PasswordGenerator.analyzePasswordStrength(result, context)
    }
    val strengthColor = when (strengthResult.level) {
        PasswordGenerator.StrengthLevel.VERY_WEAK -> colorScheme.error
        PasswordGenerator.StrengthLevel.WEAK -> colorScheme.tertiary
        PasswordGenerator.StrengthLevel.FAIR -> colorScheme.secondary
        PasswordGenerator.StrengthLevel.STRONG -> colorScheme.primary
        PasswordGenerator.StrengthLevel.VERY_STRONG -> colorScheme.primary
    }
    
    LaunchedEffect(showCopied) {
        if (showCopied) {
            kotlinx.coroutines.delay(1500)
            showCopied = false
        }
    }
    
    // 渐变动画
    val cardAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "card_alpha"
    )
    
    // 复制按钮背景颜色动画
    val buttonColor by animateColorAsState(
        targetValue = if (showCopied) 
            colorScheme.tertiaryContainer
        else 
            colorScheme.secondaryContainer,
        animationSpec = tween(durationMillis = 300),
        label = "button_color"
    )
    val buttonContentColor by animateColorAsState(
        targetValue = if (showCopied) {
            colorScheme.onTertiaryContainer
        } else {
            colorScheme.onSecondaryContainer
        },
        animationSpec = tween(durationMillis = 300),
        label = "button_content_color"
    )
    val cardPadding = 20.dp - 10.dp * progress
    val headerIconSize = 24.dp - 6.dp * progress
    val headerSpacing = 16.dp - 8.dp * progress
    val resultMaxHeight = 120.dp - 92.dp * progress
    val infoSpacing = 12.dp - 6.dp * progress
    val infoIconSize = 16.dp - 2.dp * progress
    val cardElevation = 4.dp * (1f - progress)
    val cardContainerColor = androidx.compose.ui.graphics.lerp(
        colorScheme.primaryContainer,
        MaterialTheme.colorScheme.surfaceContainerHigh,
        progress
    )
    val resultTextStyle = when {
        compactMode && result.length > 72 -> MaterialTheme.typography.bodySmall.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.sp
        )
        compactMode -> MaterialTheme.typography.titleMedium.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            letterSpacing = 0.sp
        )
        result.length >= 96 -> MaterialTheme.typography.bodyMedium.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.sp
        )
        result.length >= 64 -> MaterialTheme.typography.bodyMedium.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp
        )
        result.length >= 40 -> MaterialTheme.typography.titleMedium.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp
        )
        result.length >= 24 -> MaterialTheme.typography.titleLarge.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp
        )
        else -> MaterialTheme.typography.headlineSmall.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            lineHeight = MaterialTheme.typography.headlineSmall.lineHeight * 1.2f,
            letterSpacing = 0.sp
        )
    }
    val resultTextScrollState = rememberScrollState()

    Box {
    ElevatedCard(
        onClick = { resultMenu = true },
        modifier = modifier.testTag("generator_result")
            .fillMaxWidth()
            .graphicsLayer { alpha = cardAlpha },
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = cardElevation
        ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = cardContainerColor
        )
    ) {
        val isCompact = compactMode
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Animate measured height once, inside the surface so its corners follow it.
                // Text is replaced immediately; no outgoing password composition is retained.
                .animateMonicaContentSize()
                .padding(cardPadding)
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(headerIconSize)
                        )
                        Text(
                            text = title ?: stringResource(R.string.generated_password),
                            style = if (isCompact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCompact) colorScheme.onSurfaceVariant else colorScheme.onPrimaryContainer,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    FilledTonalButton(
                        onClick = {
                            onCopy(result)
                            showCopied = true
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonColor,
                            contentColor = buttonContentColor
                        ),
                        contentPadding = if (isCompact) {
                            PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        } else {
                            PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        }
                    ) {
                        AnimatedContent(
                            targetState = showCopied,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(200)) togetherWith
                                    fadeOut(animationSpec = tween(200))
                            },
                            label = "icon_animation"
                        ) { copied ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Default.Done else Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(if (isCompact) 16.dp else 18.dp)
                                )
                                if (!isCompact) {
                                    Text(
                                        text = if (copied) stringResource(R.string.generator_copied) else stringResource(R.string.copy),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(headerSpacing))

                if (result.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = resultMaxHeight)
                            .then(
                                if (isCompact) {
                                    Modifier
                                } else {
                                    Modifier.verticalScroll(resultTextScrollState)
                                }
                            )
                    ) {
                        androidx.compose.runtime.key(result) {
                            Text(
                                text = colorizePassword(result),
                                style = resultTextStyle,
                                fontWeight = FontWeight.Medium,
                                maxLines = if (isCompact) 1 else Int.MAX_VALUE,
                                overflow = if (isCompact) TextOverflow.Ellipsis else TextOverflow.Clip
                            )
                        }
                    }
                }

                val infoText = supportingInfo ?: stringResource(R.string.length_chars, result.length)
                if (showStrengthSection || infoText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(infoSpacing))
                }

                if (showStrengthSection) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.legacy_ui_security_level),
                            style = if (isCompact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                            color = if (isCompact) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            }
                        )
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = strengthColor.copy(alpha = if (isCompact) 0.18f else 0.16f),
                            contentColor = strengthColor
                        ) {
                            Text(
                                text = strengthResult.level.displayName,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = if (isCompact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (result.isNotEmpty() && infoText.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isCompact) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            },
                            modifier = Modifier.size(infoIconSize)
                        )
                        Text(
                            text = infoText,
                            style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            color = if (isCompact) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            }
                        )
                    }
                }
        }
    }
    DropdownMenu(expanded = resultMenu, onDismissRequest = { resultMenu = false },
        modifier = Modifier.widthIn(max = 360.dp)) {
        DropdownMenuItem(text = { Column {
            Text(stringResource(R.string.copy))
            Text(result, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
        } }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
            onClick = { resultMenu = false; onCopy(result); showCopied = true })
        if (onCreateUsername != null || onCreatePassword != null) HorizontalDivider()
        onCreateUsername?.let { create -> DropdownMenuItem(
            text = { Text(stringResource(R.string.generator_create_username)) },
            leadingIcon = { Icon(Icons.Default.Add, null) },
            onClick = { resultMenu = false; create(result) }) }
        onCreatePassword?.let { create -> DropdownMenuItem(
            text = { Text(stringResource(R.string.generator_create_password)) },
            leadingIcon = { Icon(Icons.Default.Add, null) },
            onClick = { resultMenu = false; create(result) }) }
    }
    }

}

private fun generatorTypeLabel(type: GeneratorType, context: Context): String = when (type) {
    GeneratorType.SYMBOL -> context.getString(R.string.generator_symbol)
    GeneratorType.PASSWORD -> context.getString(R.string.generator_word)
    GeneratorType.PASSPHRASE -> context.getString(R.string.generator_passphrase)
    GeneratorType.PIN -> context.getString(R.string.generator_pin)
    GeneratorType.GPG_KEY -> context.getString(R.string.gpg_title)
    GeneratorType.SSH_KEY -> context.getString(R.string.generator_ssh_key)
}

private fun generatorTypeTitle(type: GeneratorType, context: Context): String = when (type) {
    GeneratorType.SYMBOL -> context.getString(R.string.random_symbol_generator)
    GeneratorType.PASSWORD -> context.getString(R.string.password_generator)
    GeneratorType.PASSPHRASE -> context.getString(R.string.passphrase_generator)
    GeneratorType.PIN -> context.getString(R.string.pin_generator)
    GeneratorType.GPG_KEY -> context.getString(R.string.gpg_title)
    GeneratorType.SSH_KEY -> context.getString(R.string.generator_ssh_key)
}

@Composable
private fun generatorTypeIcon(type: GeneratorType) = when (type) {
    GeneratorType.SYMBOL -> Icons.Default.Refresh
    GeneratorType.PASSWORD -> Icons.Default.Key
    GeneratorType.PASSPHRASE -> Icons.Default.Info
    GeneratorType.PIN -> Icons.Default.Visibility
    GeneratorType.GPG_KEY -> Icons.Default.Key
    GeneratorType.SSH_KEY -> Icons.Default.Key
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GeneratorTypeSplitButton(
    selectedGenerator: GeneratorType,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onGenerateCurrent: () -> Unit,
    onSelectType: (GeneratorType) -> Unit
) {
    val context = LocalContext.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "generator_split_rotation"
    )

    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.TonalLeadingButton(
                    onClick = onGenerateCurrent
                ) {
                    Icon(
                        imageVector = generatorTypeIcon(selectedGenerator),
                        contentDescription = null,
                        modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize)
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(generatorTypeLabel(selectedGenerator, context))
                }
            },
            trailingButton = {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = expanded,
                    onCheckedChange = onExpandedChange
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = stringResource(R.string.generator_switch_type),
                        modifier = Modifier
                            .size(SplitButtonDefaults.TrailingIconSize)
                            .graphicsLayer { rotationZ = rotation }
                    )
                }
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.width(240.dp),
            offset = DpOffset(x = 0.dp, y = 10.dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                GeneratorType.entries.forEach { type ->
                    GeneratorTypeMenuItem(
                        title = generatorTypeLabel(type, context),
                        selected = type == selectedGenerator,
                        icon = generatorTypeIcon(type),
                        onClick = { onSelectType(type) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneratorTypeMenuItem(
    title: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        } else {
            Color.Transparent
        },
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorHistorySheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onShowFilter: () -> Unit,
    filteredHistoryList: List<PasswordGenerationHistory>,
    historyManager: PasswordHistoryManager,
    scope: CoroutineScope,
    context: Context,
    passwordViewModel: PasswordViewModel
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun dismissHistorySheet(afterDismiss: (() -> Unit)? = null) {
        scope.launch {
            if (sheetState.isVisible) {
                sheetState.hide()
            }
            onDismiss()
            afterDismiss?.invoke()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismissHistorySheet() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 标题和过滤按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.history),
                    style = MaterialTheme.typography.titleLarge
                )

                IconButton(
                    onClick = { dismissHistorySheet(onShowFilter) }
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = stringResource(R.string.filter_history),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (filteredHistoryList.isEmpty()) {
                // 空状态
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = stringResource(R.string.no_history),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.generator_history_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // 历史记录列表
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredHistoryList) { historyItem ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                // 密码显示和操作按钮
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    var passwordVisible by remember { mutableStateOf(false) }

                                    Text(
                                        text = if (passwordVisible) historyItem.password else "••••••••••",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )

                                    // 显示/隐藏密码按钮
                                    IconButton(
                                        onClick = { passwordVisible = !passwordVisible }
                                    ) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (passwordVisible) context.getString(R.string.hide_password) else context.getString(R.string.show_password),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    var copied by remember { mutableStateOf(false) }

                                    // 复制按钮
                                    IconButton(
                                        onClick = {
                                            ClipboardUtils.copyToClipboard(
                                                context = context,
                                                text = historyItem.password,
                                                label = context.getString(R.string.password),
                                                sensitive = true
                                            )
                                            copied = true
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (copied) Icons.Default.Done else Icons.Default.ContentCopy,
                                            contentDescription = stringResource(R.string.copy_password_desc),
                                            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // 删除按钮
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                historyManager.deleteHistory(historyItem.timestamp)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // 应用信息
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = if (historyItem.domain.isNotEmpty()) {
                                            historyItem.domain
                                        } else {
                                            historyItem.packageName
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }

                                // 用户名信息
                                if (historyItem.username.isNotEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Key,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                        )
                                        Text(
                                            text = stringResource(R.string.username_label, historyItem.username),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                        )
                                    }
                                }

                                // 时间戳
                                Text(
                                    text = java.text.SimpleDateFormat(
                                        "yyyy-MM-dd HH:mm:ss",
                                        java.util.Locale.getDefault()
                                    ).format(java.util.Date(historyItem.timestamp)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(top = 4.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // 保存到密码库按钮
                                var saved by remember { mutableStateOf(false) }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            val entry = PasswordEntry(
                                                title = if (historyItem.domain.isNotEmpty()) {
                                                    historyItem.domain
                                                } else {
                                                    historyItem.packageName.substringAfterLast('.')
                                                },
                                                website = historyItem.domain,
                                                username = historyItem.username,
                                                password = historyItem.password,
                                                notes = context.getString(
                                                    R.string.saved_from_generator_history,
                                                    java.text.SimpleDateFormat(
                                                        "yyyy-MM-dd HH:mm:ss",
                                                        java.util.Locale.getDefault()
                                                    ).format(java.util.Date(historyItem.timestamp))
                                                ),
                                                appPackageName = historyItem.packageName,
                                                createdAt = Date(),
                                                updatedAt = Date()
                                            )
                                            passwordViewModel.addPasswordEntry(entry)
                                            saved = true
                                            Toast.makeText(context, context.getString(R.string.saved_to_vault), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !saved,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (saved) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = if (saved) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (saved) Icons.Default.Done else Icons.Default.Save,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (saved) stringResource(R.string.saved_to_vault)
                                        else stringResource(R.string.save_to_vault)
                                    )
                                }
                            }
                        }
                    }
                }

                // 清空历史按钮
                Button(
                    onClick = {
                        scope.launch {
                            historyManager.clearHistory()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(R.string.clear_history))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorFilterSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    filterSettings: HistoryFilterSettings,
    filterPreferences: HistoryFilterPreferences,
    scope: CoroutineScope
) {
    if (!visible) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.select_history_types),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = stringResource(R.string.history_filter_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 过滤选项
            CheckboxWithText(
                text = stringResource(R.string.history_type_symbol),
                checked = filterSettings.showSymbol,
                onCheckedChange = {
                    scope.launch {
                        filterPreferences.updateFilterSettings(
                            filterSettings.copy(showSymbol = it)
                        )
                    }
                }
            )

            CheckboxWithText(
                text = stringResource(R.string.history_type_password),
                checked = filterSettings.showPassword,
                onCheckedChange = {
                    scope.launch {
                        filterPreferences.updateFilterSettings(
                            filterSettings.copy(showPassword = it)
                        )
                    }
                }
            )

            CheckboxWithText(
                text = stringResource(R.string.history_type_passphrase),
                checked = filterSettings.showPassphrase,
                onCheckedChange = {
                    scope.launch {
                        filterPreferences.updateFilterSettings(
                            filterSettings.copy(showPassphrase = it)
                        )
                    }
                }
            )

            CheckboxWithText(
                text = stringResource(R.string.history_type_pin),
                checked = filterSettings.showPin,
                onCheckedChange = {
                    scope.launch {
                        filterPreferences.updateFilterSettings(
                            filterSettings.copy(showPin = it)
                        )
                    }
                }
            )

            CheckboxWithText(
                text = stringResource(R.string.history_type_autofill),
                checked = filterSettings.showAutofill,
                onCheckedChange = {
                    scope.launch {
                        filterPreferences.updateFilterSettings(
                            filterSettings.copy(showAutofill = it)
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 关闭按钮
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

private fun applyGeneratorSymbolFilters(
    symbols: String,
    excludeSimilar: Boolean,
    excludeAmbiguous: Boolean
): String {
    var result = symbols
    if (excludeSimilar) {
        result = result.filter { it !in "0OlI1" }
    }
    if (excludeAmbiguous) {
        result = result.filter { it !in "{}[]()/~`'" }
    }
    return result
}
