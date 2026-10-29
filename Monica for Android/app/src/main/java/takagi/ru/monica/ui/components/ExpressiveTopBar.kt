package takagi.ru.monica.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

internal fun initialSearchTextFieldValue(searchQuery: String): TextFieldValue =
    TextFieldValue(
        text = searchQuery,
        selection = TextRange(searchQuery.length)
    )

internal fun reconcileSearchTextFieldValue(
    currentValue: TextFieldValue,
    searchQuery: String
): TextFieldValue = if (currentValue.text == searchQuery) {
    currentValue
} else {
    initialSearchTextFieldValue(searchQuery)
}

/**
 * M3E 风格的顶部标题栏
 * 支持大标题和集成的搜索展开动画
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpressiveTopBar(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    searchHint: String? = null,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    onActionPillBoundsChanged: ((Rect) -> Unit)? = null,
    collapsedTitleEndPadding: Dp = 180.dp,
    searchBackEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val startupSearchRequest = LocalStartupSearchRequest.current
    val startupWindowFocused = LocalWindowInfo.current.isWindowFocused
    val expandStartupSearch by rememberUpdatedState(onSearchExpandedChange)
    LaunchedEffect(startupSearchRequest, startupWindowFocused) {
        if (startupWindowFocused && startupSearchRequest?.invoke() == true) expandStartupSearch(true)
    }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var closeRequested by remember(isSearchExpanded) { mutableStateOf(false) }

    fun closeSearch() {
        if (!isSearchExpanded || closeRequested) return
        closeRequested = true
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        onSearchQueryChange("")
        onSearchExpandedChange(false)
    }

    // Hiding the IME leaves the query and results available. A subsequent Back exits search.
    // A page can give selection priority while keeping the query and results visible.
    BackHandler(enabled = isSearchExpanded && windowFocused && searchBackEnabled) {
        if (imeVisible) {
            keyboardController?.hide()
        } else {
            closeSearch()
        }
    }
    val searchInteractionSource = remember { MutableInteractionSource() }
    var searchFieldValueState by remember {
        mutableStateOf(initialSearchTextFieldValue(searchQuery))
    }
    val searchFieldValue = reconcileSearchTextFieldValue(searchFieldValueState, searchQuery)
    val resolvedSearchHint = searchHint ?: stringResource(R.string.topbar_search_hint)

    SideEffect {
        if (searchFieldValueState != searchFieldValue) {
            searchFieldValueState = searchFieldValue
        }
    }

    LaunchedEffect(searchInteractionSource, isSearchExpanded) {
        searchInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    // 动画状态：使用 Alpha 而不是 Visibility，避免布局重排导致挤压搜索框
    val titleAlpha by animateFloatAsState(
        targetValue = if (isSearchExpanded) 0f else 1f,
        animationSpec = tween(200),
        label = "TitleAlpha"
    )

    val isLongTitle = title.length > 10
    val titleStyle = when {
        title.length > 18 -> MaterialTheme.typography.bodyLarge
        isLongTitle -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.headlineLarge
    }
    val pillReserve = if (isSearchExpanded) 0.dp else collapsedTitleEndPadding

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        // 1. 标题区 (在左侧，始终占位，只改变透明度)
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer { alpha = titleAlpha }
                .padding(end = pillReserve),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            navigationIcon?.invoke()
            
            Text(
                text = title,
                style = titleStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = if (isLongTitle) 2 else 1,
                overflow = TextOverflow.Clip
            )
        }

        // 2. 搜索/操作胶囊 (在右侧，覆盖在标题之上)
        // 使用 Box + CenterEnd 确保严格的右锚定
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(

                modifier = Modifier

                    .height(56.dp)
                    .onGloballyPositioned { coordinates ->
                        onActionPillBoundsChanged?.invoke(coordinates.boundsInWindow())
                    }
                    // 添加左滑展开/右滑关闭手势
                    .pointerInput(isSearchExpanded) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragEnd = { totalDrag = 0f },
                            onDragCancel = { totalDrag = 0f }
                        ) { change, dragAmount ->
                            totalDrag += dragAmount
                            // 阈值设为 40px，避免过于灵敏
                            val threshold = 40f
                            
                            if (!isSearchExpanded && totalDrag < -threshold) {
                                change.consume()
                                onSearchExpandedChange(true)
                                totalDrag = 0f
                            } else if (isSearchExpanded && totalDrag > threshold) {
                                change.consume()
                                closeSearch()
                                totalDrag = 0f
                            }
                        }
                    },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp
            ) {
                // 内容切换
                AnimatedContent(
                    targetState = isSearchExpanded,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(300))
                            .togetherWith(fadeOut(animationSpec = tween(300)))
                            .using(
                                SizeTransform(
                                    clip = false,
                                    sizeAnimationSpec = { _, _ ->
                                        tween(300, easing = FastOutSlowInEasing)
                                    }
                                )
                            )
                    },
                    label = "PillContent"
                ) { expanded ->
                    if (expanded) {
                        // 展开状态：搜索框
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 搜索输入框 (现在在左侧)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 16.dp), // 增加左侧边距以平衡视觉
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = resolvedSearchHint,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                BasicTextField(
                                    value = searchFieldValue,
                                    onValueChange = { newValue ->
                                        searchFieldValueState = newValue
                                        if (newValue.text != searchQuery) {
                                            onSearchQueryChange(newValue.text)
                                        }
                                    },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            keyboardController?.hide()
                                        },
                                        onSearch = {
                                            keyboardController?.hide()
                                        },
                                    ),
                                    interactionSource = searchInteractionSource,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.focusRequester(focusRequester)
                                )
                            }
                            
                            // 按钮区域 (现在在右侧)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") }) {
                                        Icon(
                                            Icons.Default.Clear,
                                            stringResource(R.string.clear_search),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                IconButton(onClick = { closeSearch() }) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward, // 使用向右的箭头，表示收回方向
                                        contentDescription = stringResource(R.string.topbar_close_search),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        
                         LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
                    } else {
                        // 折叠状态：Action Buttons
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp) // 紧凑排列
                        ) {
                            actions()
                        }
                    }
                }
            }
        }
    }
}
