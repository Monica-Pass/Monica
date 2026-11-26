package takagi.ru.monica.util

import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * 通用OTP生成器
 * 支持多种OTP算法：TOTP (RFC 6238)、HOTP (RFC 4226)、Steam Guard、Yandex、Mobile-OTP
 */
object TotpGenerator {
    
    /**
     * 通用OTP生成方法 - 根据TotpData配置自动选择算法
     * @param totpData OTP配置数据
     * @param currentCounter 可选的当前计数器值(用于HOTP,如果不提供则使用totpData中的counter)
     * @param timeOffset 时间偏移（秒），用于校正TOTP时间误差
     * @return 生成的OTP验证码
     */
    fun generateOtp(
        totpData: TotpData,
        currentCounter: Long? = null,
        timeOffset: Int = 0,
        currentSeconds: Long? = null
    ): String {
        val resolvedTotpData = TotpDataResolver.normalizeTotpData(totpData)
        val nowSeconds = currentSeconds ?: System.currentTimeMillis() / 1000
        return when (resolvedTotpData.otpType) {
            OtpType.TOTP -> generateTotp(
                secret = resolvedTotpData.secret,
                timeSeconds = nowSeconds,
                period = resolvedTotpData.period,
                digits = resolvedTotpData.digits,
                algorithm = resolvedTotpData.algorithm,
                timeOffset = timeOffset
            )
            OtpType.HOTP -> generateHotp(
                secret = resolvedTotpData.secret,
                counter = currentCounter ?: resolvedTotpData.counter,
                digits = resolvedTotpData.digits,
                algorithm = resolvedTotpData.algorithm
            )
            OtpType.STEAM -> generateSteamCode(
                secret = resolvedTotpData.secret,
                timeSeconds = nowSeconds
            )
            OtpType.YANDEX -> generateYandexCode(
                secret = resolvedTotpData.secret,
                pin = resolvedTotpData.pin,
                timeSeconds = nowSeconds,
                period = resolvedTotpData.period,
                digits = resolvedTotpData.digits,
                algorithm = resolvedTotpData.algorithm,
                timeOffset = timeOffset
            )
            OtpType.MOTP -> generateMobileOtp(
                secret = resolvedTotpData.secret,
                pin = resolvedTotpData.pin,
                timeSeconds = nowSeconds
            )
        }
    }
    
    /**
     * 生成TOTP验证码
     * @param secret Base32编码的密钥
     * @param timeSeconds 当前时间（秒）
     * @param period 时间周期（默认30秒）
     * @param digits 验证码位数（默认6位）
     * @param algorithm HMAC算法（SHA1, SHA256, SHA512）
     * @param timeOffset 时间偏移（秒），用于校正系统时间误差
     * @return TOTP验证码
     */
    fun generateTotp(
        secret: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = 30,
        digits: Int = 6,
        algorithm: String = "SHA1",
        timeOffset: Int = 0
    ): String {
        val safeDigits = digits.coerceIn(1, 10)
        try {
            // 应用时间偏移校正
            val correctedTime = timeSeconds + timeOffset
            
            // 计算时间步长
            val timeStep = correctedTime / period
            
            // 解码Base32密钥
            val key = decodeBase32(secret)
            
            // 生成HMAC
            val hmac = generateHmac(key, timeStep, algorithm)
            
            // 截断HMAC生成验证码
            return truncateHmac(hmac, safeDigits)
        } catch (e: Exception) {
            return zeroCode(safeDigits)
        }
    }
    
    /**
     * 计算当前验证码的剩余有效时间（秒）
     */
    fun getRemainingSeconds(
        period: Int = 30,
        timeOffset: Int = 0,
        currentSeconds: Long = System.currentTimeMillis() / 1000
    ): Int {
        val correctedSeconds = currentSeconds + timeOffset
        val remainder = (correctedSeconds % period).toInt()
        return period - remainder
    }
    
    /**
     * 获取当前时间步长的进度（0.0 - 1.0）
     */
    fun getProgress(
        period: Int = 30,
        timeOffset: Int = 0,
        currentSeconds: Long = System.currentTimeMillis() / 1000
    ): Float {
        val remaining = getRemainingSeconds(period, timeOffset, currentSeconds)
        return 1.0f - (remaining.toFloat() / period)
    }
    
    /**
     * 生成HMAC
     */
    private fun generateHmac(key: ByteArray, counter: Long, algorithm: String): ByteArray {
        val algorithmName = "Hmac$algorithm"
        val mac = Mac.getInstance(algorithmName)
        val secretKey = SecretKeySpec(key, algorithmName)
        mac.init(secretKey)
        
        // 将counter转换为8字节数组（大端序）
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(counter)
        
        return mac.doFinal(buffer.array())
    }
    
    /**
     * 截断HMAC生成验证码
     */
    private fun truncateHmac(hmac: ByteArray, digits: Int): String {
        val safeDigits = digits.coerceIn(1, 10)
        // 动态截断
        val offset = (hmac[hmac.size - 1].toInt() and 0x0F)
        
        val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
                ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                (hmac[offset + 3].toInt() and 0xFF)
        
        val otp = binary.toLong() % 10.0.pow(safeDigits).toLong()
        
        // 格式化为指定位数的字符串（前导零）
        return otp.toString().padStart(safeDigits, '0')
    }

    private fun zeroCode(digits: Int): String {
        return "0".repeat(digits.coerceIn(1, 10))
    }
    
    /**
     * Base32解码
     */
    private fun decodeBase32(encoded: String): ByteArray {
        // 移除空格和分隔符
        val clean = encoded.replace(Regex("[\\s\\-]"), "").uppercase()
        
        // Base32字符集
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        
        for (char in clean) {
            val value = base32Chars.indexOf(char)
            if (value == -1) continue
            
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            
            if (bitsLeft >= 8) {
                output.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        
        return output.toByteArray()
    }
    
    /**
     * 验证TOTP码是否有效（考虑时间窗口）
     * @param secret 密钥
     * @param code 用户输入的验证码
     * @param window 允许的时间窗口数量（默认1，即允许前后各1个时间段）
     */
    fun verifyTotp(
        secret: String,
        code: String,
        period: Int = 30,
        digits: Int = 6,
        algorithm: String = "SHA1",
        window: Int = 1
    ): Boolean {
        val currentTime = System.currentTimeMillis() / 1000
        
        for (i in -window..window) {
            val timeOffset = currentTime + (i * period)
            val expectedCode = generateTotp(secret, timeOffset, period, digits, algorithm)
            if (expectedCode == code) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 生成HOTP验证码 (RFC 4226)
     * 基于计数器的一次性密码算法
     * @param secret Base32编码的密钥
     * @param counter 计数器值
     * @param digits 验证码位数（默认6位）
     * @param algorithm HMAC算法（SHA1, SHA256, SHA512）
     * @return HOTP验证码
     */
    fun generateHotp(
        secret: String,
        counter: Long,
        digits: Int = 6,
        algorithm: String = "SHA1"
    ): String {
        val safeDigits = digits.coerceIn(1, 10)
        return try {
            // 解码Base32密钥
            val key = decodeBase32(secret)
            
            // 生成HMAC
            val hmac = generateHmac(key, counter, algorithm)
            
            // 截断HMAC生成验证码
            truncateHmac(hmac, safeDigits)
        } catch (e: Exception) {
            zeroCode(safeDigits)
        }
    }
    
    /**
     * 生成Steam Guard验证码
     * Steam使用特殊的5位字符码（而非数字）
     * @param secret Base32编码的密钥
     * @param timeSeconds 当前时间（秒）
     * @return Steam 5位字符验证码
     */
    fun generateSteamCode(
        secret: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000
    ): String {
        return try {
            // Steam使用30秒周期
            val timeStep = timeSeconds / 30
            
            // 解码Base32密钥
            val key = decodeBase32(secret)
            
            // 生成HMAC-SHA1
            val hmac = generateHmac(key, timeStep, "SHA1")
            
            // Steam特殊字符集 (26个字母去除易混淆的 + 8个数字去除0和1)
            val steamChars = "23456789BCDFGHJKMNPQRTVWXY"
            
            // 动态截断
            val offset = (hmac[hmac.size - 1].toInt() and 0x0F)
            
            var fullCode = ((hmac[offset].toInt() and 0x7F) shl 24) or
                    ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                    (hmac[offset + 3].toInt() and 0xFF)
            
            // 生成5位Steam字符码
            val code = StringBuilder()
            for (i in 0 until 5) {
                code.append(steamChars[fullCode % steamChars.length])
                fullCode /= steamChars.length
            }
            
            code.toString()
        } catch (e: Exception) {
            "2345B"
        }
    }
    
    /**
     * 生成Yandex OTP验证码
     * Yandex使用标准TOTP算法（与Google Authenticator兼容）
     * @param secret Base32编码的密钥
     * @param pin PIN码（Yandex可能需要，但通常不使用）
     * @param timeSeconds 当前时间（秒）
     * @return Yandex验证码
     */
    fun generateYandexCode(
        secret: String,
        pin: String = "",
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = 30,
        digits: Int = 6,
        algorithm: String = "SHA1",
        timeOffset: Int = 0
    ): String {
        // Yandex使用标准TOTP算法
        return generateTotp(
            secret = secret,
            timeSeconds = timeSeconds,
            period = period,
            digits = digits,
            algorithm = algorithm,
            timeOffset = timeOffset
        )
    }
    
    /**
     * 生成Mobile-OTP (mOTP)验证码
     * mOTP算法: MD5(epoch + secret + pin)，取前6位数字
     * @param secret 密钥（不是Base32编码，是原始字符串）
     * @param pin PIN码（必需）
     * @param timeSeconds 当前时间（秒）
     * @return mOTP 6位数字验证码
     */
    fun generateMobileOtp(
        secret: String,
        pin: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000
    ): String {
        return try {
            // mOTP使用10秒为单位的时间戳（epoch）
            val epoch = timeSeconds / 10
            
            // 构造待哈希的字符串: epoch + secret + pin
            val data = "$epoch$secret$pin"
            
            // 计算MD5哈希
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(data.toByteArray())
            
            // 转换为十六进制字符串
            val hexString = digest.joinToString("") { "%02x".format(it) }
            
            // 从十六进制字符串中提取数字，取前6位
            val digits = hexString.filter { it.isDigit() }
            
            if (digits.length >= 6) {
                digits.substring(0, 6)
            } else {
                // 如果数字不足6位，补零
                digits.padEnd(6, '0')
            }
        } catch (e: Exception) {
            "000000"
        }
    }
}
