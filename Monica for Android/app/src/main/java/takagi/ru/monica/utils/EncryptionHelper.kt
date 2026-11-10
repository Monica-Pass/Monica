package takagi.ru.monica.utils

import takagi.ru.monica.R
import takagi.ru.monica.data.BackupEncryptionFormat

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 加密帮助类
 * 使用 AES-256-GCM 算法加密 WebDAV 备份数据
 */
class EncryptionHelper {
    
    companion object {
        private const val TAG = "EncryptionHelper"

        // AES-GCM 参数
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256 // AES-256
        private const val GCM_TAG_LENGTH = 128 // 16 bytes
        private const val GCM_IV_LENGTH = 12 // 12 bytes (recommended for GCM)
        
        // PBKDF2 参数
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 100000 // Frozen unversioned configuration-string format
        private const val SALT_LENGTH = 32 // 32 bytes salt
        
        // 文件头标识 (用于验证文件是否是 Monica 加密文件)
        private const val FILE_MAGIC = PortableBackupCrypto.FAMILY_PREFIX
        
        /**
         * 检测文件是否加密
         */
        fun isEncryptedFile(file: File): Boolean {
            try {
                // 通过扩展名判断
                if (file.name.endsWith(".enc.zip")) {
                    return true
                }

                return hasEncryptedFileHeader(file)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking if file is encrypted", e)
                return false
            }
        }

        fun hasEncryptedFileHeader(file: File): Boolean {
            if (!file.isFile || file.length() < FILE_MAGIC.length) return false
            return FileInputStream(file).use(::hasEncryptedFileHeader)
        }

        fun hasEncryptedFileHeader(input: InputStream): Boolean {
            // Recognize the family, including unknown versions, so callers never treat it as plaintext ZIP.
            val expected = FILE_MAGIC.toByteArray(Charsets.US_ASCII)
            val header = ByteArray(expected.size)
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read <= 0) return false
                offset += read
            }
            return header.contentEquals(expected)
        }
        
        /**
         * 从密码派生加密密钥
         */
        private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
            val spec = PBEKeySpec(
                password.toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                KEY_SIZE
            )
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        }
        
        /**
         * 生成随机盐值
         */
        private fun generateSalt(): ByteArray {
            val salt = ByteArray(SALT_LENGTH)
            SecureRandom().nextBytes(salt)
            return salt
        }
        
        /**
         * 生成随机 IV
         */
        private fun generateIV(): ByteArray {
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            return iv
        }
        
        /**
         * 加密文件
         */
        internal fun encryptFile(
            inputFile: File,
            outputFile: File,
            password: String,
            strings: StringResolver,
            format: BackupEncryptionFormat = BackupEncryptionFormat.COMPATIBLE_V1,
        ): Result<File> = try {
            require(inputFile.canonicalFile != outputFile.canonicalFile) { "Backup output must differ from input" }
            val plaintext = inputFile.readBytes()
            try {
                outputFile.writeBytes(PortableBackupCrypto.encrypt(plaintext, password, format))
            } finally { plaintext.fill(0) }
            Result.success(outputFile)
        } catch (error: Exception) {
            Log.e(TAG, "Backup encryption failed: ${error.javaClass.simpleName}")
            Result.failure(Exception(strings.get(R.string.crypto_message_encrypt_failed,
                strings.get(R.string.import_data_unknown_error)), error))
        }

        /** Automatically reads V1 or V2. Invalid or unsupported envelopes never fall back to another KDF. */
        internal fun decryptFile(inputFile: File, outputFile: File, password: String, strings: StringResolver): Result<File> = try {
            require(inputFile.canonicalFile != outputFile.canonicalFile) { "Backup output must differ from input" }
            val plaintext = PortableBackupCrypto.decrypt(inputFile.readBytes(), password)
            try {
                // Authentication completes before touching the destination.
                outputFile.writeBytes(plaintext)
            } finally { plaintext.fill(0) }
            Result.success(outputFile)
        } catch (error: javax.crypto.AEADBadTagException) {
            Result.failure(Exception(strings.get(R.string.crypto_message_password_or_corrupt), error))
        } catch (error: IllegalArgumentException) {
            Result.failure(Exception(strings.get(R.string.crypto_message_invalid_file), error))
        } catch (error: Exception) {
            Log.e(TAG, "Backup decryption failed: ${error.javaClass.simpleName}")
            Result.failure(Exception(strings.get(R.string.crypto_message_decrypt_failed,
                strings.get(R.string.import_data_unknown_error)), error))
        }

        /**
         * 测试密码是否正确
         */
        internal fun testPassword(encryptedFile: File, password: String, strings: StringResolver): Boolean {
            val tempFile = File.createTempFile("monica_pwd_test", ".tmp")
            try {
                val result = decryptFile(encryptedFile, tempFile, password, strings)
                return result.isSuccess
            } catch (e: Exception) {
                return false
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
        
        /**
         * 尝试解密文件（如果是加密文件）
         * 如果不是加密文件，直接返回原文件
         */
        internal fun decryptIfNeeded(file: File, password: String?, strings: StringResolver): Result<File> {
            return try {
                if (!isEncryptedFile(file)) {
                    return Result.success(file)
                }
                
                if (password.isNullOrBlank()) {
                    return Result.failure(Exception(strings.get(R.string.backup_password_required)))
                }
                
                // 创建临时文件用于存储解密结果
                val decryptedFile = File.createTempFile(
                    "decrypted_${file.nameWithoutExtension}",
                    ".zip",
                    file.parentFile
                )
                
                val result = decryptFile(file, decryptedFile, password, strings)
                
                if (result.isSuccess) {
                    result
                } else {
                    decryptedFile.delete()
                    result
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        
        /**
         * 加密字符串
         * 用于加密敏感配置信息（如 WebDAV 密码）
         * @param plainText 明文字符串
         * @param password 加密密码
         * @return Base64 编码的加密结果（格式：salt:iv:ciphertext）
         */
        internal fun encryptString(plainText: String, password: String, strings: StringResolver): String {
            try {
                // 1. 生成盐值和 IV
                val salt = generateSalt()
                val iv = generateIV()
                
                // 2. 派生密钥
                val key = deriveKey(password, salt)
                
                // 3. 初始化加密器
                val cipher = Cipher.getInstance(ALGORITHM)
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
                
                // 4. 加密数据
                val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                
                // 5. 组合并编码为 Base64
                val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
                val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
                val cipherBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
                
                return "$saltBase64:$ivBase64:$cipherBase64"
            } catch (e: Exception) {
                Log.e(TAG, "String encryption failed", e)
                throw Exception(strings.get(R.string.crypto_message_encrypt_failed, e.message ?: strings.get(R.string.import_data_unknown_error)), e)
            }
        }
        
        /**
         * 解密字符串
         * @param encryptedText Base64 编码的加密文本（格式：salt:iv:ciphertext）
         * @param password 解密密码
         * @return 明文字符串
         */
        internal fun decryptString(encryptedText: String, password: String, strings: StringResolver): String {
            try {
                // 1. 分割并解码 Base64
                val parts = encryptedText.split(":")
                if (parts.size != 3) {
                    throw Exception(strings.get(R.string.crypto_message_invalid_text))
                }
                
                val salt = Base64.decode(parts[0], Base64.NO_WRAP)
                val iv = Base64.decode(parts[1], Base64.NO_WRAP)
                val encryptedBytes = Base64.decode(parts[2], Base64.NO_WRAP)
                
                // 2. 派生密钥
                val key = deriveKey(password, salt)
                
                // 3. 初始化解密器
                val cipher = Cipher.getInstance(ALGORITHM)
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
                
                // 4. 解密数据
                val decryptedBytes = cipher.doFinal(encryptedBytes)
                
                return String(decryptedBytes, Charsets.UTF_8)
            } catch (e: javax.crypto.AEADBadTagException) {
                Log.e(TAG, "String decryption failed - wrong password", e)
                throw Exception(strings.get(R.string.crypto_message_password_or_corrupt), e)
            } catch (e: Exception) {
                Log.e(TAG, "String decryption failed", e)
                throw Exception(strings.get(R.string.crypto_message_decrypt_failed, e.message ?: strings.get(R.string.import_data_unknown_error)), e)
            }
        }
    }
}
