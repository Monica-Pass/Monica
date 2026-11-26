package takagi.ru.monica.ui.screens

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import takagi.ru.monica.R
import takagi.ru.monica.data.ColorScheme
import takagi.ru.monica.ui.theme.generateCustomMaterialColorScheme
import takagi.ru.monica.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ColorSchemeSelectionScreen(
    settingsViewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCustomColors: () -> Unit
) {
    val settings by settingsViewModel.settings.collectAsState()
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val defaultPreviewColors = remember(context, isDarkTheme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scheme = if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            Triple(scheme.primary, scheme.secondary, scheme.tertiary)
        } else {
            Triple(Color(0xFF6650a4), Color(0xFF625b71), Color(0xFF7D5260))
        }
    }
    val customPreviewScheme = remember(
        settings.customPrimaryColor,
        settings.customSecondaryColor,
        settings.customTertiaryColor,
        settings.customNeutralColor,
        settings.customNeutralVariantColor,
        isDarkTheme
    ) {
        generateCustomMaterialColorScheme(
            darkTheme = isDarkTheme,
            primarySeed = settings.customPrimaryColor,
            secondarySeed = settings.customSecondaryColor,
            tertiarySeed = settings.customTertiaryColor,
            neutralSeed = settings.customNeutralColor,
            neutralVariantSeed = settings.customNeutralVariantColor
        )
    }
    
    // 用于即时预览的颜色方案
    var previewColorScheme by remember { mutableStateOf(settings.colorScheme) }
    
    // 防止在返回动画期间误触
    var isNavigatingOut by remember { mutableStateOf(false) }
    
    // 拦截系统返回键
    BackHandler {
        if (!isNavigatingOut) {
            isNavigatingOut = true
            onNavigateBack()
        }
    }
    
    // 准备共享元素 Modifier
    val sharedTransitionScope = takagi.ru.monica.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = takagi.ru.monica.ui.LocalAnimatedVisibilityScope.current
    
    var sharedModifier: Modifier = Modifier
    if (false && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope!!) {
            sharedModifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "color_scheme_card"),
                animatedVisibilityScope = animatedVisibilityScope!!,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }

    Scaffold(
        modifier = sharedModifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.color_scheme)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!isNavigatingOut) {
                            isNavigatingOut = true
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 说明文本
            Text(
                text = stringResource(R.string.color_scheme_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            // 配色方案选项
            ColorSchemeOption(
                colorScheme = ColorScheme.DEFAULT,
                name = stringResource(R.string.default_color_scheme),
                primaryColor = defaultPreviewColors.first,
                secondaryColor = defaultPreviewColors.second,
                tertiaryColor = defaultPreviewColors.third,
                isSelected = previewColorScheme == ColorScheme.DEFAULT,
                onClick = { 
                    if (!isNavigatingOut) {
                        previewColorScheme = ColorScheme.DEFAULT
                        settingsViewModel.updateColorScheme(ColorScheme.DEFAULT)
                    }
                }
            )
            
            ColorSchemeOption(
                colorScheme = ColorScheme.OCEAN_BLUE,
                name = stringResource(R.string.ocean_blue_scheme),
                primaryColor = Color(0xFF1565C0),
                secondaryColor = Color(0xFF0277BD),
                tertiaryColor = Color(0xFF26C6DA),
                isSelected = previewColorScheme == ColorScheme.OCEAN_BLUE,
                onClick = { 
                    if (!isNavigatingOut) {
                        previewColorScheme = ColorScheme.OCEAN_BLUE
                        settingsViewModel.updateColorScheme(ColorScheme.OCEAN_BLUE)
                    }
                }
            )
            
            ColorSchemeOption(
                colorScheme = ColorScheme.SUNSET_ORANGE,
                name = stringResource(R.string.sunset_orange_scheme),
                primaryColor = Color(0xFFE65100),
                secondaryColor = Color(0xFFF57C00),
                tertiaryColor = Color(0xFFFFA726),
                isSelected = previewColorScheme == ColorScheme.SUNSET_ORANGE,
                onClick = { 
                    if (!isNavigatingOut) {
                        previewColorScheme = ColorScheme.SUNSET_ORANGE
                        settingsViewModel.updateColorScheme(ColorScheme.SUNSET_ORANGE)
                    }
                }
            )
            
            ColorSchemeOption(
                colorScheme = ColorScheme.FOREST_GREEN,
                name = stringResource(R.string.forest_green_scheme),
                primaryColor = Color(0xFF1B5E20),
                secondaryColor = Color(0xFF2E7D32),
                tertiaryColor = Color(0xFF388E3C),
                isSelected = previewColorScheme == ColorScheme.FOREST_GREEN,
                onClick = { 
                    if (!isNavigatingOut) {
                        previewColorScheme = ColorScheme.FOREST_GREEN
                        settingsViewModel.updateColorScheme(ColorScheme.FOREST_GREEN)
                    }
                }
            )
            
            ColorSchemeOption(
                colorScheme = ColorScheme.TECH_PURPLE,
                name = stringResource(R.string.tech_purple_scheme),
                primaryColor = Color(0xFF4A148C),
                secondaryColor = Color(0xFF6A1B9A),
                tertiaryColor = Color(0xFF8E24AA),
                isSelected = previewColorScheme == ColorScheme.TECH_PURPLE,
                onClick = { 
                    if (!isNavigatingOut) {
                        previewColorScheme = ColorScheme.TECH_PURPLE
                        settingsViewModel.updateColorScheme(ColorScheme.TECH_PURPLE)
                    }
                }
            )
            
            ColorSchemeOption(
                colorScheme = ColorScheme.BLACK_MAMBA,
                name = stringResource(R.string.black_mamba_scheme),
                primaryColor = Color(0xFF552583),  // 湖人紫
                secondaryColor = Color(0xFFFDB927), // 湖人金
                tertiaryColor = Color(0xFF2A2A2A),  // 黑曼巴灰
                isSelected = previewColorScheme == ColorScheme.BLACK_MAMBA,
                onClick = { 
                    if (!isNavigatingOut) {
                        previewColorScheme = ColorScheme.BLACK_MAMBA
                        settingsViewModel.updateColorScheme(ColorScheme.BLACK_MAMBA)
                    }
                }
            )
            
            ColorSchemeOption(
                colorScheme = ColorScheme.GREY_STYLE,
                name = stringResource(R.string.grey_style_scheme),
                primaryColor = Color(0xFF616161),  // 高级灰
                secondaryColor = Color(0xFFE0E0E0), // 浅灰
                tertiaryColor = Color(0xFF37474F),  // 深蓝灰
                isSelected = previewColorScheme == ColorScheme.GREY_STYLE,
                onClick = { 
                    if (!isNavigatingOut) {
                        previewColorScheme = ColorScheme.GREY_STYLE
                        settingsViewModel.updateColorScheme(ColorScheme.GREY_STYLE)
                    }
                }
            )

            // Monet Impressionist Schemes
            if (settings.isPlusActivated) {
                ColorSchemeOption(
                    colorScheme = ColorScheme.WATER_LILIES,
                    name = stringResource(R.string.water_lilies_scheme),
                    primaryColor = Color(0xFF00796B),
                    secondaryColor = Color(0xFF009688),
                    tertiaryColor = Color(0xFFAB47BC),
                    isSelected = previewColorScheme == ColorScheme.WATER_LILIES,
                    onClick = { 
                        if (!isNavigatingOut) {
                            previewColorScheme = ColorScheme.WATER_LILIES
                            settingsViewModel.updateColorScheme(ColorScheme.WATER_LILIES)
                        }
                    }
                )

                ColorSchemeOption(
                    colorScheme = ColorScheme.IMPRESSION_SUNRISE,
                    name = stringResource(R.string.impression_sunrise_scheme),
                    primaryColor = Color(0xFFE65100),
                    secondaryColor = Color(0xFFFB8C00),
                    tertiaryColor = Color(0xFF1565C0),
                    isSelected = previewColorScheme == ColorScheme.IMPRESSION_SUNRISE,
                    onClick = { 
                        if (!isNavigatingOut) {
                            previewColorScheme = ColorScheme.IMPRESSION_SUNRISE
                            settingsViewModel.updateColorScheme(ColorScheme.IMPRESSION_SUNRISE)
                        }
                    }
                )

                ColorSchemeOption(
                    colorScheme = ColorScheme.JAPANESE_BRIDGE,
                    name = stringResource(R.string.japanese_bridge_scheme),
                    primaryColor = Color(0xFF2E7D32),
                    secondaryColor = Color(0xFF43A047),
                    tertiaryColor = Color(0xFF0277BD),
                    isSelected = previewColorScheme == ColorScheme.JAPANESE_BRIDGE,
                    onClick = { 
                        if (!isNavigatingOut) {
                            previewColorScheme = ColorScheme.JAPANESE_BRIDGE
                            settingsViewModel.updateColorScheme(ColorScheme.JAPANESE_BRIDGE)
                        }
                    }
                )

                ColorSchemeOption(
                    colorScheme = ColorScheme.HAYSTACKS,
                    name = stringResource(R.string.haystacks_scheme),
                    primaryColor = Color(0xFFF57F17),
                    secondaryColor = Color(0xFFFBC02D),
                    tertiaryColor = Color(0xFF7B1FA2),
                    isSelected = previewColorScheme == ColorScheme.HAYSTACKS,
                    onClick = { 
                        if (!isNavigatingOut) {
                            previewColorScheme = ColorScheme.HAYSTACKS
                            settingsViewModel.updateColorScheme(ColorScheme.HAYSTACKS)
                        }
                    }
                )

                ColorSchemeOption(
                    colorScheme = ColorScheme.ROUEN_CATHEDRAL,
                    name = stringResource(R.string.rouen_cathedral_scheme),
                    primaryColor = Color(0xFFD84315),
                    secondaryColor = Color(0xFFFF7043),
                    tertiaryColor = Color(0xFF1976D2),
                    isSelected = previewColorScheme == ColorScheme.ROUEN_CATHEDRAL,
                    onClick = { 
                        if (!isNavigatingOut) {
                            previewColorScheme = ColorScheme.ROUEN_CATHEDRAL
                            settingsViewModel.updateColorScheme(ColorScheme.ROUEN_CATHEDRAL)
                        }
                    }
                )

                ColorSchemeOption(
                    colorScheme = ColorScheme.PARLIAMENT_FOG,
                    name = stringResource(R.string.parliament_fog_scheme),
                    primaryColor = Color(0xFF673AB7),
                    secondaryColor = Color(0xFF9575CD),
                    tertiaryColor = Color(0xFF00BCD4),
                    isSelected = previewColorScheme == ColorScheme.PARLIAMENT_FOG,
                    onClick = { 
                        if (!isNavigatingOut) {
                            previewColorScheme = ColorScheme.PARLIAMENT_FOG
                            settingsViewModel.updateColorScheme(ColorScheme.PARLIAMENT_FOG)
                        }
                    }
                )

                // Catppuccin (Plus exclusive)
                ColorSchemeOption(
                    colorScheme = ColorScheme.CATPPUCCIN_LATTE,
                    name = stringResource(R.string.catppuccin_latte_scheme),
                    primaryColor = Color(0xFF7287FD),
                    secondaryColor = Color(0xFF40A02B),
                    tertiaryColor = Color(0xFFDC8A78),
                    isSelected = previewColorScheme == ColorScheme.CATPPUCCIN_LATTE,
                    onClick = {
                        if (!isNavigatingOut) {
                            previewColorScheme = ColorScheme.CATPPUCCIN_LATTE
                            settingsViewModel.updateColorScheme(ColorScheme.CATPPUCCIN_LATTE)
                        }
                    }
                )

                ColorSchemeOption(
                    colorScheme = ColorScheme.CATPPUCCIN_FRAPPE,
                    name = stringResource(R.string.catppuccin_frappe_scheme),
                    primaryColor = Color(0xFFBABBF1),
                    secondaryColor = Color(0xFFA6D189),
                    tertiaryColor = Color(0xFFE6A0B6),
                    isSelected = previewColorScheme == ColorScheme.CATPPUCCIN_FRAPPE,
                    onClick = {
                        if (!isNavigatingOut) {
                            previewColorScheme = ColorScheme.CATPPUCCIN_FRAPPE
                            settingsViewModel.updateColorScheme(ColorScheme.CATPPUCCIN_FRAPPE)
                        }
                    }
                )

                ColorSchemeOption(
                    colorScheme = ColorScheme.CATPPUCCIN_MACCHIATO,
                    name = stringResource(R.string.catppuccin_macchiato_scheme),
                    primaryColor = Color(0xFFB7BDF8),
                    secondaryColor = Color(0xFFA6DA95),
                    tertiaryColor = Color(0xFFF0C6C6),
                    isSelected = previewColorScheme == ColorScheme.CATPPUCCIN_MACCHIATO,
                    onClick = {
                        if (!isNavigatingOut) {
                            previewColorScheme = ColorScheme.CATPPUCCIN_MACCHIATO
                            settingsViewModel.updateColorScheme(ColorScheme.CATPPUCCIN_MACCHIATO)
                        }
                    }
                )

                ColorSchemeOption(
                    colorScheme = ColorScheme.CATPPUCCIN_MOCHA,
                    name = stringResource(R.string.catppuccin_mocha_scheme),
                    primaryColor = Color(0xFFCBA6F7),
                    secondaryColor = Color(0xFFA6E3A1),
                    tertiaryColor = Color(0xFFF2CDCD),
                    isSelected = previewColorScheme == ColorScheme.CATPPUCCIN_MOCHA,
                    onClick = {
                        if (!isNavigatingOut) {
                            previewColorScheme = ColorScheme.CATPPUCCIN_MOCHA
                            settingsViewModel.updateColorScheme(ColorScheme.CATPPUCCIN_MOCHA)
                        }
                    }
                )
            }
            
            ColorSchemeOption(
                colorScheme = ColorScheme.CUSTOM,
                name = stringResource(R.string.custom_color_scheme),
                primaryColor = Color(settings.customPrimaryColor),
                secondaryColor = Color(settings.customSecondaryColor),
                tertiaryColor = Color(settings.customTertiaryColor),
                customPreviewScheme = customPreviewScheme,
                isSelected = previewColorScheme == ColorScheme.CUSTOM,
                onClick = { 
                    // 导航到自定义颜色设置界面
                    if (!isNavigatingOut) {
                        onNavigateToCustomColors()
                    }
                }
            )
        }
    }
}

@Composable
fun ColorSchemeOption(
    colorScheme: ColorScheme,
    name: String,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    customPreviewScheme: androidx.compose.material3.ColorScheme? = null,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (customPreviewScheme == null) {
                // 传统三色预览（预设主题）
                Row(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(primaryColor)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(secondaryColor)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(tertiaryColor)
                    )
                }
            } else {
                // M3 自定义主题预览（展示完整角色风格）
                Surface(
                    modifier = Modifier.size(60.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = customPreviewScheme.surfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(customPreviewScheme.primary)
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(customPreviewScheme.secondary)
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(customPreviewScheme.tertiary)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(customPreviewScheme.primaryContainer)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(customPreviewScheme.outlineVariant)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 方案名称
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            
            // 选中指示器
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}


