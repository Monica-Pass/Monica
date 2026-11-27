package takagi.ru.monica.util

import android.content.Context
import com.nulabinc.zxcvbn.Zxcvbn
import takagi.ru.monica.data.PasswordEntry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.log2

/**
 * 🔐 增强的密码生成器工具类 (Keyguard + Monica 融合)
 * 
 * 核心改进：
 * - ✅ 最小字符数要求（Keyguard 特性）
 * - ✅ 密码短语生成（Diceware 风格）
 * - ✅ PIN 码生成
 * - ✅ 高级密码强度分析（熵值、破解时间估算）
 * - ✅ 保持 Monica 的简洁 API 设计
 */
class PasswordGenerator {
    companion object {
        private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
        private const val NUMBERS = "0123456789"
        private const val SYMBOLS = "!@#$%^&*()_+-=[]{}|;:,.<>?"
        // zxcvbn 在长度超过 32 时会显著变慢（其内部匹配器有超线性复杂度），
        // 超过此长度直接走熵估算路径。
        private const val PASSWORD_LENGTH_UPPER_LIMIT = 32
        
        private val random = SecureRandom()
        private val specialCharacterRegex = "[^a-zA-Z0-9]".toRegex()
        private val digitCharacterRegex = "[0-9]".toRegex()
        private val zxcvbn by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            Zxcvbn()
        }

        @Volatile
        private var cachedWordlist: List<String>? = null
        @Volatile
        private var cachedWordlistSet: Set<String>? = null
        private val wordlistLock = Any()
        
        /**
         * 生成密码（保持原有 API，增加 Keyguard 特性）
         */
        fun generatePassword(
            length: Int = 12,
            includeUppercase: Boolean = true,
            includeLowercase: Boolean = true,
            includeNumbers: Boolean = true,
            includeSymbols: Boolean = true,
            allowedSymbols: String? = null,
            excludeSimilar: Boolean = false,
            excludeAmbiguous: Boolean = false,
            // ✨ 新增 Keyguard 特性：最小字符数要求
            uppercaseMin: Int = 0,
            lowercaseMin: Int = 0,
            numbersMin: Int = 0,
            symbolsMin: Int = 0
        ): String {
            if (length <= 0) return ""
            
            // 构建字符集
            val uppercaseChars = if (includeUppercase) UPPERCASE else ""
            val lowercaseChars = if (includeLowercase) LOWERCASE else ""
            val numberChars = if (includeNumbers) NUMBERS else ""
            val symbolBase = allowedSymbols ?: SYMBOLS
            val symbolChars = if (includeSymbols) symbolBase else ""
            
            val allChars = buildString {
                append(uppercaseChars)
                append(lowercaseChars)
                append(numberChars)
                append(symbolChars)
            }
            
            // 应用排除规则
            val filteredChars = applyExclusionRules(allChars, excludeSimilar, excludeAmbiguous)
            if (filteredChars.isEmpty()) return ""
            
            // ✨ Keyguard 核心算法：确保最小字符数要求
            return generateWithMinimumRequirements(
                length = length,
                uppercaseChars = applyExclusionRules(uppercaseChars, excludeSimilar, excludeAmbiguous),
                lowercaseChars = applyExclusionRules(lowercaseChars, excludeSimilar, excludeAmbiguous),
                numberChars = applyExclusionRules(numberChars, excludeSimilar, excludeAmbiguous),
                symbolChars = applyExclusionRules(symbolChars, excludeSimilar, excludeAmbiguous),
                uppercaseMin = uppercaseMin,
                lowercaseMin = lowercaseMin,
                numbersMin = numbersMin,
                symbolsMin = symbolsMin,
                allChars = filteredChars
            )
        }

        /**
         * Analyze stored passwords and generate a similar one.
         */
        fun generateSimilarPassword(
            passwords: List<PasswordEntry>,
            targetLength: Int,
            includeUppercase: Boolean,
            includeLowercase: Boolean,
            includeNumbers: Boolean,
            includeSymbols: Boolean,
            allowedSymbols: String? = null,
            excludeSimilar: Boolean,
            excludeAmbiguous: Boolean,
            weightPercent: Int
        ): String {
            if (passwords.isEmpty()) {
                return generatePassword(
                    length = targetLength,
                    includeUppercase = includeUppercase,
                    includeLowercase = includeLowercase,
                    includeNumbers = includeNumbers,
                    includeSymbols = includeSymbols,
                    allowedSymbols = allowedSymbols,
                    excludeSimilar = excludeSimilar,
                    excludeAmbiguous = excludeAmbiguous
                )
            }

            val weight = weightPercent.coerceIn(0, 100) / 100f // 0..1

            // 1) 目标长度：根据用户长度滑条与历史平均长度混合
            val avgLength = passwords.map { it.password.length }
                .average()
                .toInt()
                .coerceIn(4, 128)
            val mixedLength = ((targetLength * (1 - weight)) + (avgLength * weight)).toInt().coerceIn(4, 128)

            // 2) 字符集：尊重用户复选框，历史仅作参考
            val historyUse = analyzeCharUsage(passwords)
            val finalIncludeUpper = includeUppercase && historyUse.useUpper
            val finalIncludeLower = includeLowercase && historyUse.useLower
            val finalIncludeNumber = includeNumbers && historyUse.useNumber
            val finalIncludeSymbol = includeSymbols && historyUse.useSymbol

            // 若全部被禁用，则回退为用户勾选的原始值（避免全 false）
            val safeUpper = finalIncludeUpper || includeUppercase
            val safeLower = finalIncludeLower || includeLowercase
            val safeNumber = finalIncludeNumber || includeNumbers
            val safeSymbol = finalIncludeSymbol || includeSymbols
            val hasAny = safeUpper || safeLower || safeNumber || safeSymbol

            val commonFragment = findCommonFragment(passwords)

            // fragment 概率受权重影响：权重越高越倾向嵌入
            val shouldEmbed = !commonFragment.isNullOrEmpty() && random.nextFloat() <= weight

            // 基础长度预留 fragment 空间
            val reserved = if (shouldEmbed) commonFragment!!.length else 0
            val baseLength = (mixedLength - reserved).coerceAtLeast(3)

            val base = generatePassword(
                length = baseLength,
                includeUppercase = if (hasAny) safeUpper else true,
                includeLowercase = if (hasAny) safeLower else true,
                includeNumbers = if (hasAny) safeNumber else true,
                includeSymbols = if (hasAny) safeSymbol else true,
                allowedSymbols = allowedSymbols,
                excludeSimilar = excludeSimilar,
                excludeAmbiguous = excludeAmbiguous
            )

            if (!shouldEmbed) return base

            val placement = random.nextInt(3) // 0 start, 1 middle, 2 end
            val builder = StringBuilder()
            when (placement) {
                0 -> builder.append(commonFragment).append(base)
                1 -> {
                    val split = base.length / 2
                    builder.append(base.substring(0, split))
                    builder.append(commonFragment)
                    builder.append(base.substring(split))
                }
                else -> builder.append(base).append(commonFragment)
            }

            // 补齐长度（若 fragment 占用导致长度不足）
            val remaining = (mixedLength - builder.length).coerceAtLeast(0)
            if (remaining > 0) {
                builder.append(
                    generatePassword(
                        length = remaining,
                        includeUppercase = if (hasAny) safeUpper else true,
                        includeLowercase = if (hasAny) safeLower else true,
                        includeNumbers = if (hasAny) safeNumber else true,
                        includeSymbols = if (hasAny) safeSymbol else true,
                        allowedSymbols = allowedSymbols,
                        excludeSimilar = excludeSimilar,
                        excludeAmbiguous = excludeAmbiguous
                    )
                )
            }

            return builder.toString()
        }

        private data class CharUsage(
            val useUpper: Boolean,
            val useLower: Boolean,
            val useNumber: Boolean,
            val useSymbol: Boolean
        )

        private fun analyzeCharUsage(passwords: List<PasswordEntry>): CharUsage {
            var useUpper = 0
            var useLower = 0
            var useNumber = 0
            var useSymbol = 0
            val total = passwords.size.toDouble().coerceAtLeast(1.0)

            passwords.forEach { entry ->
                val p = entry.password
                if (p.any { it.isUpperCase() }) useUpper++
                if (p.any { it.isLowerCase() }) useLower++
                if (p.any { it.isDigit() }) useNumber++
                if (p.any { !it.isLetterOrDigit() }) useSymbol++
            }

            return CharUsage(
                useUpper = (useUpper / total) > 0.2,
                useLower = (useLower / total) > 0.2,
                useNumber = (useNumber / total) > 0.2,
                useSymbol = (useSymbol / total) > 0.2
            )
        }

        private fun findCommonFragment(passwords: List<PasswordEntry>): String? {
            val freq = mutableMapOf<String, Int>()
            passwords.forEach { entry ->
                val p = entry.password
                if (p.length < 3) return@forEach
                val maxLen = minOf(11, p.length)
                for (len in 3..maxLen) {
                    for (i in 0..p.length - len) {
                        val sub = p.substring(i, i + len)
                        if (sub.isBlank()) continue
                        freq[sub] = freq.getOrDefault(sub, 0) + 1
                    }
                }
            }

            if (freq.isEmpty()) return null

            // Phone-like numeric片段加权，优先出现频率高且长度长的
            return freq.entries
                .maxByOrNull { (key, value) ->
                    val isNumeric = key.all { it.isDigit() }
                    val phoneBoost = if (isNumeric && key.length in 6..11) 1.5 else 1.0
                    (value * key.length * phoneBoost).toInt()
                }
                ?.key
        }
        
        /**
         * ✨ 新功能：生成密码短语（Diceware 风格）
         */
        fun generatePassphrase(
            wordCount: Int = 4,
            delimiter: String = "-",
            capitalize: Boolean = false,
            includeNumber: Boolean = false,
            customWord: String? = null,
            customWords: List<String> = emptyList(),
            context: Context? = null
        ): String {
            require(wordCount > 0) { "Word count must be greater than zero" }
            
            val wordlist = loadWordlist(context)
            if (wordlist.isEmpty()) return ""

            val cleanedCustomWords = customWords
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toMutableList()
            customWord
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { single ->
                    if (single !in cleanedCustomWords) {
                        cleanedCustomWords.add(single)
                    }
                }
            val hasCustomPool = cleanedCustomWords.isNotEmpty()
            val guaranteedCustomIndex = if (hasCustomPool) random.nextInt(wordCount) else -1
            
            val phrases = buildList {
                repeat(wordCount) { index ->
                    val rawWord = when {
                        index == guaranteedCustomIndex && hasCustomPool -> {
                            cleanedCustomWords[random.nextInt(cleanedCustomWords.size)]
                        }
                        hasCustomPool && random.nextFloat() < 0.35f -> {
                            cleanedCustomWords[random.nextInt(cleanedCustomWords.size)]
                        }
                        else -> wordlist[random.nextInt(wordlist.size)]
                    }
                    
                    val processedWord = if (capitalize) {
                        rawWord.replaceFirstChar { char ->
                            if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
                        }
                    } else {
                        rawWord
                    }
                    
                    add(processedWord)
                }
            }
            
            // 如果需要，在随机单词后添加数字
            val finalPhrases = if (includeNumber) {
                val targetIndex = random.nextInt(phrases.size)
                val numberRange = when (wordCount) {
                    1 -> 1000..9999
                    2 -> 100..999
                    else -> 10..99
                }
                val number = random.nextInt(numberRange.last - numberRange.first + 1) + numberRange.first
                
                phrases.mapIndexed { index, word ->
                    if (index == targetIndex) "$word$number" else word
                }
            } else {
                phrases
            }
            
            return finalPhrases.joinToString(delimiter)
        }
        
        /**
         * ✨ 新功能：生成 PIN 码
         */
        fun generatePinCode(length: Int = 4): String {
            require(length in 3..9) { "PIN length must be between 3 and 9" }
            
            return (1..length)
                .map { random.nextInt(10) }
                .joinToString("")
        }
        
        // ===== 私有方法 =====
        
        /**
         * 使用最小字符数要求生成密码（Keyguard 核心算法）
         */
        private fun generateWithMinimumRequirements(
            length: Int,
            uppercaseChars: String,
            lowercaseChars: String,
            numberChars: String,
            symbolChars: String,
            uppercaseMin: Int,
            lowercaseMin: Int,
            numbersMin: Int,
            symbolsMin: Int,
            allChars: String
        ): String {
            val output = mutableListOf<Char>()
            
            // Phase 1: 确保满足最小字符数要求
            repeat(uppercaseMin) {
                if (uppercaseChars.isNotEmpty()) {
                    output += uppercaseChars[random.nextInt(uppercaseChars.length).absoluteValue % uppercaseChars.length]
                }
            }
            repeat(lowercaseMin) {
                if (lowercaseChars.isNotEmpty()) {
                    output += lowercaseChars[random.nextInt(lowercaseChars.length).absoluteValue % lowercaseChars.length]
                }
            }
            repeat(numbersMin) {
                if (numberChars.isNotEmpty()) {
                    output += numberChars[random.nextInt(numberChars.length).absoluteValue % numberChars.length]
                }
            }
            repeat(symbolsMin) {
                if (symbolChars.isNotEmpty()) {
                    output += symbolChars[random.nextInt(symbolChars.length).absoluteValue % symbolChars.length]
                }
            }
            
            // Phase 2: 填充剩余长度
            repeat(length - output.size) {
                if (allChars.isNotEmpty()) {
                    output += allChars[random.nextInt(allChars.length).absoluteValue % allChars.length]
                }
            }
            
            // Phase 3: 随机打乱（Keyguard 特性）
            return output
                .take(length)
                .shuffled(random)
                .joinToString("")
        }
        
        /**
         * 应用排除规则
         */
        private fun applyExclusionRules(
            charset: String, 
            excludeSimilar: Boolean, 
            excludeAmbiguous: Boolean
        ): String {
            var result = charset
            
            if (excludeSimilar) {
                result = result.filter { it !in "0OlI1" }
            }
            
            if (excludeAmbiguous) {
                result = result.filter { it !in "{}[]()/~`'" }
            }
            
            return result
        }
        
        /**
         * 加载词表（优先从资源文件，后备使用内置词表）。结果缓存以避免重复 IO。
         */
        private fun loadWordlist(context: Context?): List<String> {
            cachedWordlist?.let { return it }
            synchronized(wordlistLock) {
                cachedWordlist?.let { return it }
                val list = readWordlist(context)
                cachedWordlist = list
                return list
            }
        }

        private fun readWordlist(context: Context?): List<String> {
            // 尝试从资源加载
            context?.let { ctx ->
                try {
                    val resId = ctx.resources.getIdentifier("eff_short_wordlist", "raw", ctx.packageName)
                    if (resId != 0) {
                        val inputStream = ctx.resources.openRawResource(resId)
                        return BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                            lines.filter { it.isNotBlank() }
                                .map { line ->
                                    // EFF 词表格式："11111\tword"
                                    val parts = line.split('\t', ' ', limit = 2)
                                    if (parts.size == 2) parts[1].trim() else parts.first().trim()
                                }
                                .toList()
                        }
                    }
                } catch (e: Exception) {
                    // 忽略错误，使用后备词表
                }
            }
            
            // 后备词表
            return listOf(
                "able", "about", "above", "abuse", "actor", "acute", "admit", "adopt", "adult", "after",
                "again", "agent", "agree", "ahead", "alarm", "album", "alert", "alike", "alive", "allow",
                "alone", "along", "alter", "among", "anger", "angle", "angry", "apart", "apple", "apply",
                "arena", "argue", "arise", "array", "aside", "asset", "avoid", "awake", "award", "aware",
                "badly", "baker", "bases", "basic", "beach", "began", "begin", "bench", "billy", "birth",
                "black", "blame", "blind", "block", "blood", "board", "boost", "booth", "bound", "brain",
                "brand", "brass", "brave", "bread", "break", "breed", "brief", "bring", "broad", "broke",
                "brown", "build", "built", "buyer", "cable", "calif", "carry", "catch", "cause", "chain",
                "chair", "chaos", "charm", "chart", "chase", "cheap", "check", "chest", "chief", "child",
                "china", "chose", "civil", "claim", "class", "clean", "clear", "click", "climb", "clock"
            )
        }
        
        /**
         * ✨ 增强的密码强度分析（融合 Keyguard 的高级算法）
         */
        fun analyzePasswordStrength(
            password: String,
            context: Context? = null
        ): PasswordStrengthResult {
            if (password.isEmpty()) {
                return PasswordStrengthResult(
                    score = 0,
                    level = StrengthLevel.VERY_WEAK,
                    entropy = 0.0,
                    crackTime = "瞬间",
                    feedback = listOf("密码不能为空")
                )
            }

            val charset = detectCharset(password)
            val entropy = calculateEntropy(password.length, charset.size)

            val crackTimeSeconds = calculateKeyguardCrackTimeSeconds(password, context)
            val level = keyguardLevel(crackTimeSeconds)
            val finalScore = keyguardScore(level)
            val crackTime = formatCrackTimeSeconds(crackTimeSeconds)
            val feedback = generateFeedback(password, finalScore, entropy)
            
            return PasswordStrengthResult(finalScore, level, entropy, crackTime, feedback)
        }
        
        /**
         * 保持向后兼容的简单强度计算
         */
        fun calculatePasswordStrength(password: String): Int {
            return analyzePasswordStrength(password).score
        }
        
        /**
         * 保持向后兼容的强度描述
         */
        fun getPasswordStrengthDescription(strength: Int): String {
            return when {
                strength < 20 -> "非常弱"
                strength < 40 -> "弱"
                strength < 60 -> "中等"
                strength < 80 -> "强"
                else -> "非常强"
            }
        }

        fun getDefaultSymbols(): String = SYMBOLS
        
        // ===== 高级强度分析算法 =====
        
        /**
         * 检测密码字符集大小
         */
        private fun detectCharset(password: String): CharacterSet {
            val hasLowercase = password.any { it.isLowerCase() }
            val hasUppercase = password.any { it.isUpperCase() }
            val hasDigits = password.any { it.isDigit() }
            val hasSymbols = password.any { it in SYMBOLS }
            val hasOtherSymbols = password.any { !it.isLetterOrDigit() && it !in SYMBOLS }
            
            var size = 0
            if (hasLowercase) size += 26
            if (hasUppercase) size += 26
            if (hasDigits) size += 10
            if (hasSymbols) size += SYMBOLS.length
            if (hasOtherSymbols) size += 10  // 估算其他符号数量
            
            return CharacterSet(size, hasLowercase, hasUppercase, hasDigits, hasSymbols)
        }
        
        /**
         * 计算熵值（信息论）
         */
        private fun calculateEntropy(length: Int, charsetSize: Int): Double {
            return if (charsetSize > 0) {
                length * log2(charsetSize.toDouble())
            } else {
                0.0
            }
        }
        
        private fun calculateKeyguardCrackTimeSeconds(
            password: String,
            context: Context?
        ): Long {
            passphraseCrackTimeSeconds(password, context)?.let { return it }

            // 对于长密码，跳过 zxcvbn（其匹配器在长输入下非常慢，可能阻塞主线程），
            // 直接用熵估算。短密码继续走 zxcvbn 以获得更精确的字典/模式分析。
            if (password.length > PASSWORD_LENGTH_UPPER_LIMIT) {
                return entropyBasedCrackTimeSeconds(password)
            }

            return runCatching {
                zxcvbn.measure(password)
                    .crackTimeSeconds
                    .offlineSlowHashing1e4perSecond
                    .toLong()
                    .coerceAtLeast(0L)
            }.getOrElse {
                entropyBasedCrackTimeSeconds(password)
            }
        }

        /**
         * 基于熵估算破解时间（offline slow hashing @ 1e4 guesses/sec）。
         * 对超长密码直接返回上限值避免 Math.pow 溢出。
         */
        private fun entropyBasedCrackTimeSeconds(password: String): Long {
            val charset = detectCharset(password)
            val entropy = calculateEntropy(password.length, charset.size)
            return when {
                entropy <= 0 -> 0L
                entropy > 60 -> 100_000_000_001L // 视为不可破解
                else -> (Math.pow(2.0, entropy) / 1e4).toLong().coerceAtLeast(1L)
            }
        }

        private fun passphraseCrackTimeSeconds(
            password: String,
            context: Context?
        ): Long? {
            val parts = password
                .splitToSequence(specialCharacterRegex)
                .filter { it.isNotEmpty() }
                .toList()
            if (parts.size < 2) return null
            if (parts.any { it.length < 3 }) return null

            val wordList = cachedWordlistSet ?: synchronized(wordlistLock) {
                cachedWordlistSet ?: loadWordlist(context)
                    .asSequence()
                    .map { it.lowercase(Locale.ROOT) }
                    .toSet()
                    .also { cachedWordlistSet = it }
            }
            if (wordList.isEmpty()) return null

            fun inWordList(word: String) = word
                .replace(digitCharacterRegex, "")
                .lowercase(Locale.ROOT) in wordList

            val hasDigit = password.contains(digitCharacterRegex)
            val isPassphrase = inWordList(parts.first()) && inWordList(parts.last())
            if (!isPassphrase) return null

            return when {
                parts.size <= 3 -> 1_000L
                parts.size <= 4 -> if (hasDigit) 1_000_000L else 100_000L
                parts.size <= 5 -> if (hasDigit) 100_000_000_000L else 1_000_000L
                parts.size <= 6 -> if (hasDigit) 100_000_000_001L else 100_000_000_000L
                else -> 100_000_000_001L
            }
        }

        private fun keyguardLevel(crackTimeSeconds: Long): StrengthLevel = when {
            crackTimeSeconds <= 1_000L -> StrengthLevel.WEAK
            crackTimeSeconds <= 100_000L -> StrengthLevel.FAIR
            crackTimeSeconds <= 100_000_000L -> StrengthLevel.STRONG
            crackTimeSeconds <= 100_000_000_000L -> StrengthLevel.STRONG
            else -> StrengthLevel.VERY_STRONG
        }

        private fun keyguardScore(level: StrengthLevel): Int = when (level) {
            StrengthLevel.VERY_WEAK -> 0
            StrengthLevel.WEAK -> 10
            StrengthLevel.FAIR -> 35
            StrengthLevel.STRONG -> 75
            StrengthLevel.VERY_STRONG -> 100
        }

        private fun formatCrackTimeSeconds(seconds: Long): String {
            return when {
                seconds < 1 -> "瞬间"
                seconds < 60 -> "${seconds}秒"
                seconds < 3600 -> "${seconds / 60}分钟"
                seconds < 86400 -> "${seconds / 3600}小时"
                seconds < 2_592_000 -> "${seconds / 86400}天"
                seconds < 31_536_000 -> "${seconds / 2_592_000}个月"
                seconds < 3_155_760_000L -> "${seconds / 31_536_000}年"
                else -> "数千年"
            }
        }
        
        /**
         * 检测连续字符
         */
        private fun detectSequentialChars(password: String): Int {
            if (password.length < 3) return 0
            
            var count = 0
            for (i in 0..password.length - 3) {
                val char1 = password[i].code
                val char2 = password[i + 1].code
                val char3 = password[i + 2].code
                
                if ((char2 == char1 + 1 && char3 == char2 + 1) ||
                    (char2 == char1 - 1 && char3 == char2 - 1)) {
                    count++
                }
            }
            return count
        }
        
        /**
         * 检测常见模式
         */
        private fun detectCommonPatterns(password: String): Int {
            val commonPatterns = listOf(
                "123", "abc", "qwe", "asd", "zxc", "111", "000",
                "password", "admin", "user", "login", "pass"
            )
            
            val lowerPassword = password.lowercase()
            return commonPatterns.count { lowerPassword.contains(it) }
        }
        
        /**
         * 检测键盘模式
         */
        private fun detectKeyboardPatterns(password: String): Int {
            val keyboardRows = listOf(
                "qwertyuiop",
                "asdfghjkl",
                "zxcvbnm",
                "1234567890"
            )
            
            val lowerPassword = password.lowercase()
            var count = 0
            
            keyboardRows.forEach { row ->
                for (i in 0..row.length - 3) {
                    val pattern = row.substring(i, i + 3)
                    if (lowerPassword.contains(pattern) || lowerPassword.contains(pattern.reversed())) {
                        count++
                    }
                }
            }
            
            return count
        }
        
        /**
         * 生成改进建议
         */
        private fun generateFeedback(password: String, score: Int, entropy: Double): List<String> {
            val feedback = mutableListOf<String>()
            
            if (password.length < 8) {
                feedback.add("建议使用至少 8 位字符")
            }
            
            if (password.length < 12) {
                feedback.add("建议使用 12 位或更长的密码")
            }
            
            val charset = detectCharset(password)
            if (!charset.hasLowercase) feedback.add("添加小写字母")
            if (!charset.hasUppercase) feedback.add("添加大写字母")
            if (!charset.hasDigits) feedback.add("添加数字")
            if (!charset.hasSymbols) feedback.add("添加特殊符号")
            
            if (entropy < 40) {
                feedback.add("增加密码复杂度以提高安全性")
            }
            
            if (detectSequentialChars(password) > 0) {
                feedback.add("避免使用连续字符（如 123、abc）")
            }
            
            if (detectCommonPatterns(password) > 0) {
                feedback.add("避免使用常见单词或模式")
            }
            
            if (detectKeyboardPatterns(password) > 0) {
                feedback.add("避免使用键盘上相邻的字符")
            }
            
            if (feedback.isEmpty() && score >= 80) {
                feedback.add("密码强度很好！")
            }
            
            return feedback
        }
    }
    
    /**
     * 密码选项数据类（保持向后兼容）
     */
    data class PasswordOptions(
        val length: Int = 12,
        val includeUppercase: Boolean = true,
        val includeLowercase: Boolean = true,
        val includeNumbers: Boolean = true,
        val includeSymbols: Boolean = true,
        val excludeSimilar: Boolean = false,
        // ✨ 新增 Keyguard 特性
        val uppercaseMin: Int = 0,
        val lowercaseMin: Int = 0,
        val numbersMin: Int = 0,
        val symbolsMin: Int = 0,
        val excludeAmbiguous: Boolean = false
    )
    
    /**
     * 密码短语选项数据类
     */
    data class PassphraseOptions(
        val wordCount: Int = 4,
        val delimiter: String = "-",
        val capitalize: Boolean = false,
        val includeNumber: Boolean = false,
        val customWord: String? = null
    )
    
    /**
     * PIN 码选项数据类
     */
    data class PinCodeOptions(
        val length: Int = 4
    ) {
        init {
            require(length in 3..9) { "PIN length must be between 3 and 9" }
        }
    }
    
    /**
     * 密码强度分析结果
     */
    data class PasswordStrengthResult(
        val score: Int,              // 0-100 分数
        val level: StrengthLevel,    // 强度等级
        val entropy: Double,         // 熵值（bits）
        val crackTime: String,       // 破解时间估算
        val feedback: List<String>   // 改进建议
    )
    
    /**
     * 密码强度等级
     */
    enum class StrengthLevel(@get:androidx.annotation.StringRes val labelRes: Int, val color: String) {
        VERY_WEAK(takagi.ru.monica.R.string.strength_very_weak, "#F44336"),      // 红色
        WEAK(takagi.ru.monica.R.string.strength_weak, "#FF9800"),              // 橙色
        FAIR(takagi.ru.monica.R.string.strength_fair, "#FFC107"),            // 黄色
        STRONG(takagi.ru.monica.R.string.strength_strong, "#4CAF50"),            // 绿色
        VERY_STRONG(takagi.ru.monica.R.string.strength_very_strong, "#2196F3")    // 蓝色
    }
    
    /**
     * 字符集信息（内部使用）
     */
    private data class CharacterSet(
        val size: Int,
        val hasLowercase: Boolean,
        val hasUppercase: Boolean,
        val hasDigits: Boolean,
        val hasSymbols: Boolean
    )
}
