package takagi.ru.monica.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuDefaults.outlinedTextFieldColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import takagi.ru.monica.security.BoundedSecureDataRead
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.PredefinedSecurityQuestions
import takagi.ru.monica.data.SecurityQuestion
import takagi.ru.monica.security.SecurityManager

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SecurityQuestionsSetupScreen(
    securityManager: SecurityManager,
    onNavigateBack: () -> Unit,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val active = remember { AtomicBoolean(true) }
    val entryGeneration = remember { SecurityManager.dataReadGeneration.value }
    val generation by SecurityManager.dataReadGeneration.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val back = rememberUpdatedState(onNavigateBack)
    val complete = rememberUpdatedState(onSetupComplete)
    val questionIds = remember { PredefinedSecurityQuestions.questions.map { it.id } }
    val questions = questionIds.map { id ->
        SecurityQuestion(id, stringResource(PredefinedSecurityQuestions.textResIdFor(id)))
    }
    val isExistingSetup = securityManager.areSecurityQuestionsSet()

    var selectedQuestion1 by remember { mutableStateOf<SecurityQuestion?>(null) }
    var selectedQuestion2 by remember { mutableStateOf<SecurityQuestion?>(null) }
    var customQuestion1Text by remember { mutableStateOf("") }
    var customQuestion2Text by remember { mutableStateOf("") }
    var answer1 by remember { mutableStateOf("") }
    var answer2 by remember { mutableStateOf("") }
    var showQuestion1Dropdown by remember { mutableStateOf(false) }
    var showQuestion2Dropdown by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    fun current() = active.get() && securityManager.isDataReadCurrent(entryGeneration)
    fun cancel() {
        active.set(false)
        selectedQuestion1 = null; selectedQuestion2 = null
        customQuestion1Text = ""; customQuestion2Text = ""
        answer1 = ""; answer2 = ""
        showQuestion1Dropdown = false; showQuestion2Dropdown = false
        errorMessage = ""
        isLoading = false
    }
    fun leave() { if (active.get()) { cancel(); back.value() } }
    BackHandler(onBack = ::leave)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) leave()
        }
        lifecycle.addObserver(observer)
        onDispose { cancel(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(generation) { if (generation != entryGeneration) leave() }

    LaunchedEffect(Unit) {
        if (!current()) return@LaunchedEffect
        if (isExistingSetup) {
            val question1Id = securityManager.getSecurityQuestion1Id()
            val question2Id = securityManager.getSecurityQuestion2Id()
            selectedQuestion1 = questions.find { it.id == question1Id }
            selectedQuestion2 = questions.find { it.id == question2Id }
            if (PredefinedSecurityQuestions.isCustomQuestion(question1Id)) {
                customQuestion1Text = securityManager.getSecurityQuestion1Text().orEmpty()
            }
            if (PredefinedSecurityQuestions.isCustomQuestion(question2Id)) {
                customQuestion2Text = securityManager.getSecurityQuestion2Text().orEmpty()
            }
        }
    }

    val sharedTransitionScope = takagi.ru.monica.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = takagi.ru.monica.ui.LocalAnimatedVisibilityScope.current

    var sharedModifier: Modifier = Modifier
    if (false && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope!!) {
            sharedModifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "security_questions_card"),
                animatedVisibilityScope = animatedVisibilityScope!!,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }

    if (!active.get()) return
    Scaffold(
        modifier = sharedModifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (isExistingSetup) {
                            stringResource(R.string.edit_security_questions)
                        } else {
                            stringResource(R.string.setup_security_questions)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
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
                .verticalScroll(scrollState)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SecurityQuestionSetupHero(isExistingSetup = isExistingSetup)

            SecurityQuestionConfigCard(
                title = stringResource(R.string.security_question_1),
                selectedQuestion = selectedQuestion1,
                allQuestions = questions,
                expanded = showQuestion1Dropdown,
                customQuestionText = customQuestion1Text,
                answer = answer1,
                onExpandedChange = { showQuestion1Dropdown = it },
                onQuestionSelected = { question ->
                    selectedQuestion1 = question
                    if (!PredefinedSecurityQuestions.isCustomQuestion(question.id)) {
                        customQuestion1Text = ""
                    }
                    errorMessage = ""
                },
                onCustomQuestionChange = {
                    customQuestion1Text = it
                    errorMessage = ""
                },
                onAnswerChange = {
                    answer1 = it
                    errorMessage = ""
                },
                excludedQuestionId = selectedQuestion2?.id,
                enabled = !isLoading
            )

            SecurityQuestionConfigCard(
                title = stringResource(R.string.security_question_2),
                selectedQuestion = selectedQuestion2,
                allQuestions = questions,
                expanded = showQuestion2Dropdown,
                customQuestionText = customQuestion2Text,
                answer = answer2,
                onExpandedChange = { showQuestion2Dropdown = it },
                onQuestionSelected = { question ->
                    selectedQuestion2 = question
                    if (!PredefinedSecurityQuestions.isCustomQuestion(question.id)) {
                        customQuestion2Text = ""
                    }
                    errorMessage = ""
                },
                onCustomQuestionChange = {
                    customQuestion2Text = it
                    errorMessage = ""
                },
                onAnswerChange = {
                    answer2 = it
                    errorMessage = ""
                },
                excludedQuestionId = selectedQuestion1?.id,
                enabled = !isLoading
            )

            if (errorMessage.isNotEmpty()) {
                SecurityQuestionMessageCard(
                    message = errorMessage,
                    isError = true
                )
            }

            Button(
                onClick = {
                    if (!current()) return@Button
                    val customQuestion1 = customQuestion1Text.trim()
                    val customQuestion2 = customQuestion2Text.trim()
                    when {
                        selectedQuestion1 == null -> {
                            errorMessage = context.getString(R.string.select_first_question)
                        }
                        selectedQuestion2 == null -> {
                            errorMessage = context.getString(R.string.select_second_question)
                        }
                        PredefinedSecurityQuestions.isCustomQuestion(selectedQuestion1!!.id) &&
                            customQuestion1.isEmpty() -> {
                            errorMessage = context.getString(R.string.first_custom_question_required)
                        }
                        PredefinedSecurityQuestions.isCustomQuestion(selectedQuestion2!!.id) &&
                            customQuestion2.isEmpty() -> {
                            errorMessage = context.getString(R.string.second_custom_question_required)
                        }
                        areSecurityQuestionsDuplicated(
                            question1 = selectedQuestion1,
                            question2 = selectedQuestion2,
                            customQuestion1Text = customQuestion1,
                            customQuestion2Text = customQuestion2
                        ) -> {
                            errorMessage = if (
                                PredefinedSecurityQuestions.isCustomQuestion(selectedQuestion1!!.id) &&
                                PredefinedSecurityQuestions.isCustomQuestion(selectedQuestion2!!.id)
                            ) {
                                context.getString(R.string.custom_questions_must_be_different)
                            } else {
                                context.getString(R.string.questions_must_be_different)
                            }
                        }
                        answer1.trim().isEmpty() -> {
                            errorMessage = context.getString(R.string.first_answer_required)
                        }
                        answer2.trim().isEmpty() -> {
                            errorMessage = context.getString(R.string.second_answer_required)
                        }
                        else -> {
                            isLoading = true
                            errorMessage = ""

                            val firstId = selectedQuestion1!!.id
                            val secondId = selectedQuestion2!!.id
                            val firstAnswer = answer1.trim()
                            val secondAnswer = answer2.trim()
                            answer1 = ""; answer2 = ""
                            scope.launch {
                                try {
                                    val saved = BoundedSecureDataRead.readChecked(isCurrent = ::current) {
                                        securityManager.setSecurityQuestions(
                                            question1Id = firstId,
                                            answer1 = firstAnswer,
                                            question2Id = secondId,
                                            answer2 = secondAnswer,
                                            question1Text = customQuestion1.takeIf {
                                                PredefinedSecurityQuestions.isCustomQuestion(firstId)
                                            },
                                            question2Text = customQuestion2.takeIf {
                                                PredefinedSecurityQuestions.isCustomQuestion(secondId)
                                            },
                                            ensureActive = { ensureActive() },
                                        )
                                        true
                                    }
                                    if (!current()) return@launch
                                    if (saved == true) {
                                        cancel()
                                        complete.value()
                                    }
                                    else errorMessage = context.getString(R.string.recovery_authorization_expired)
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                enabled = active.get() && generation == entryGeneration && !isLoading &&
                    selectedQuestion1 != null &&
                    selectedQuestion2 != null &&
                    answer1.trim().isNotEmpty() &&
                    answer2.trim().isNotEmpty()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(
                    text = if (isExistingSetup) {
                        stringResource(R.string.update_questions)
                    } else {
                        stringResource(R.string.save_questions)
                    }
                )
            }
        }
    }
}

@Composable
private fun SecurityQuestionSetupHero(isExistingSetup: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isExistingSetup) Icons.Default.Edit else Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Text(
                text = if (isExistingSetup) {
                    stringResource(R.string.edit_security_questions)
                } else {
                    stringResource(R.string.setup_security_questions)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Text(
                text = stringResource(R.string.recovery_device_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
            )

            SecurityQuestionInfoPill(
                icon = {
                    Icon(
                        imageVector = Icons.Default.LockReset,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                text = stringResource(R.string.security_questions_must_choose_two)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityQuestionConfigCard(
    title: String,
    selectedQuestion: SecurityQuestion?,
    allQuestions: List<SecurityQuestion>,
    expanded: Boolean,
    customQuestionText: String,
    answer: String,
    onExpandedChange: (Boolean) -> Unit,
    onQuestionSelected: (SecurityQuestion) -> Unit,
    onCustomQuestionChange: (String) -> Unit,
    onAnswerChange: (String) -> Unit,
    excludedQuestionId: Int?,
    enabled: Boolean
) {
    val allowDuplicateCustomQuestion = excludedQuestionId?.let {
        PredefinedSecurityQuestions.isCustomQuestion(it)
    } == true
    val availableQuestions = allQuestions.filter { question ->
        excludedQuestionId == null ||
            question.id != excludedQuestionId ||
            question.id == selectedQuestion?.id ||
            (allowDuplicateCustomQuestion && PredefinedSecurityQuestions.isCustomQuestion(question.id))
    }
    val selectedIsCustom = selectedQuestion?.let { PredefinedSecurityQuestions.isCustomQuestion(it.id) } == true

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { onExpandedChange(!expanded) }
            ) {
                OutlinedTextField(
                    value = selectedQuestion?.questionText.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.select_question)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    colors = outlinedTextFieldColors(),
                    shape = RoundedCornerShape(22.dp)
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { onExpandedChange(false) }
                ) {
                    availableQuestions.forEach { question ->
                        DropdownMenuItem(
                            text = { Text(question.questionText) },
                            onClick = {
                                onQuestionSelected(question)
                                onExpandedChange(false)
                            }
                        )
                    }
                }
            }

            if (selectedIsCustom) {
                OutlinedTextField(
                    value = customQuestionText,
                    onValueChange = onCustomQuestionChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.security_question_custom_label)) },
                    placeholder = { Text(stringResource(R.string.security_question_custom_placeholder)) },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null)
                    },
                    enabled = enabled,
                    shape = RoundedCornerShape(22.dp),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.your_answer)) },
                placeholder = { Text(stringResource(R.string.enter_answer_hint)) },
                leadingIcon = {
                    Icon(Icons.Default.HelpOutline, contentDescription = null)
                },
                enabled = enabled,
                shape = RoundedCornerShape(22.dp),
                singleLine = true
            )
        }
    }
}

private fun areSecurityQuestionsDuplicated(
    question1: SecurityQuestion?,
    question2: SecurityQuestion?,
    customQuestion1Text: String,
    customQuestion2Text: String
): Boolean {
    if (question1 == null || question2 == null) return false

    val firstIsCustom = PredefinedSecurityQuestions.isCustomQuestion(question1.id)
    val secondIsCustom = PredefinedSecurityQuestions.isCustomQuestion(question2.id)

    return if (firstIsCustom && secondIsCustom) {
        customQuestion1Text.equals(customQuestion2Text, ignoreCase = true)
    } else {
        question1.id == question2.id
    }
}

@Composable
private fun SecurityQuestionInfoPill(
    icon: @Composable () -> Unit,
    text: String
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SecurityQuestionMessageCard(
    message: String,
    isError: Boolean
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.HelpOutline else Icons.Default.Security,
                contentDescription = null,
                tint = contentColor
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
    }
}
