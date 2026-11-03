package takagi.ru.monica.util

import takagi.ru.monica.utils.AppLocaleStringResolver

import takagi.ru.monica.R

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import android.content.pm.PackageManager
import android.Manifest
import android.content.DialogInterface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * 照片选择助手类
 * 使用传统Intent方式处理图片拍照和从相册选择，完全避免requestCode问题
 * 
 * Update: 修复Context泄露问题和Bitmap OOM问题
 */
object PhotoPickerHelper {
    const val REQUEST_CODE_CAMERA = 2001
    const val REQUEST_CODE_GALLERY = 2002
    const val PERMISSION_REQUEST_CAMERA = 2003
    
    // 当前标签，用于区分正面和背面
    var currentTag: String = ""
    
    // 回调接口
    interface PhotoPickerCallback {
        fun onPhotoSelected(imagePath: String?)
        fun onError(error: String)
    }
    
    // 当前回调实例
    private var currentCallback: PhotoPickerCallback? = null
    private lateinit var strings: AppLocaleStringResolver
    
    // Use WeakReference to avoid memory leaks
    private var weakContext: WeakReference<Context>? = null
    private var weakPendingActivity: WeakReference<Activity>? = null
    
    private var pendingAction: (() -> Unit)? = null
    
    /**
     * 设置回调
     */
    fun setCallback(context: Context, callback: PhotoPickerCallback) {
        this.weakContext = WeakReference(context)
        this.strings = AppLocaleStringResolver(context)
        this.currentCallback = callback
    }
    
    /**
     * 检查并请求相机权限
     */
    private fun checkCameraPermission(activity: Activity, onPermissionGranted: () -> Unit) {
        val cameraPermission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(activity, cameraPermission) == PackageManager.PERMISSION_GRANTED) {
            // 权限已授予，直接执行操作
            onPermissionGranted()
        } else {
            // 请求权限
            weakPendingActivity = WeakReference(activity)
            pendingAction = onPermissionGranted
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(cameraPermission),
                PERMISSION_REQUEST_CAMERA
            )
        }
    }
    
    /**
     * 处理权限请求结果
     */
    fun handlePermissionResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
        if (requestCode == PERMISSION_REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予，执行待处理的操作
                pendingAction?.invoke()
            } else {
                // 权限被拒绝
                currentCallback?.onError(strings.get(R.string.camera_permission_needed))
            }
            pendingAction = null
            weakPendingActivity = null
            return true
        }
        return false
    }
    
    /**
     * 拍照
     * @param activity 调用的Activity
     */
    fun takePhoto(activity: Activity) {
        checkCameraPermission(activity) {
            try {
                // 保存context引用
                this.weakContext = WeakReference(activity)
                
                // 创建临时文件
                val photoFile = createTempPhotoFile(activity)
                tempPhotoFile = photoFile
                
                // 创建URI
                val photoURI = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    photoFile
                )
                
                // 创建拍照Intent
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                
                // 直接启动相机，不进行复杂的检查
                try {
                    activity.startActivityForResult(intent, REQUEST_CODE_CAMERA)
                } catch (e: Exception) {
                    // 如果启动相机失败，给出明确的错误提示，但不自动切换到图库
                    currentCallback?.onError(strings.get(R.string.photo_message_camera_failed, e.message ?: strings.get(R.string.import_data_unknown_error)))
                }
            } catch (e: Exception) {
                currentCallback?.onError(strings.get(R.string.photo_message_camera_failed, e.message ?: strings.get(R.string.import_data_unknown_error)))
            }
        }
    }
    
    /**
     * 从相册选择照片
     * @param activity 调用的Activity
     */
    internal fun galleryIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "image/*"
        addCategory(Intent.CATEGORY_OPENABLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun pickFromGallery(activity: Activity) {
        weakContext = WeakReference(activity)
        try {
            // Launch the system chooser directly: package visibility must not hide it.
            // The returned URI grants access to the selected image only.
            activity.startActivityForResult(galleryIntent(), REQUEST_CODE_GALLERY)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                activity.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, REQUEST_CODE_GALLERY)
            } catch (_: Exception) {
                currentCallback?.onError(strings.get(R.string.photo_message_gallery_missing))
            }
        } catch (e: Exception) {
            currentCallback?.onError(strings.get(R.string.photo_message_gallery_failed,
                e.message ?: strings.get(R.string.import_data_unknown_error)))
        }
    }

    /**
     * 创建临时照片文件
     */
    private fun createTempPhotoFile(context: Context): File {
        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val imageFileName = "JPEG_${timeStamp}_"
        // 确保使用正确的目录，与file_paths.xml中的配置匹配
        val storageDir = File(context.cacheDir, "temp_share")
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }
    
    /**
     * 处理拍照结果
     * @param requestCode 请求码
     * @param resultCode 结果码
     * @param data 返回数据
     */
    fun handleCameraResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_CODE_CAMERA) {
            if (resultCode == Activity.RESULT_OK) {
                tempPhotoFile?.let { file ->
                    // 检查文件是否存在且不为空
                    if (file.exists() && file.length() > 0) {
                        currentCallback?.onPhotoSelected(file.absolutePath)
                    } else {
                        // 等待一段时间再检查（相机可能还在写入文件）
                        try {
                            Thread.sleep(1000) // 等待1秒
                        } catch (e: InterruptedException) {
                            // 忽略中断异常
                        }
                        
                        if (file.exists() && file.length() > 0) {
                            currentCallback?.onPhotoSelected(file.absolutePath)
                        } else {
                            // 尝试从Intent中获取数据
                            val bitmap = data?.extras?.get("data") as? Bitmap
                            if (bitmap != null) {
                                // 将Bitmap保存到文件
                                try {
                                    val outputStream = FileOutputStream(file)
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                                    outputStream.close()
                                    currentCallback?.onPhotoSelected(file.absolutePath)
                                } catch (e: Exception) {
                                    currentCallback?.onError(strings.get(R.string.photo_message_data_failed, e.message ?: strings.get(R.string.import_data_unknown_error)))
                                }
                            } else {
                                currentCallback?.onError(strings.get(R.string.photo_message_file_missing))
                            }
                        }
                    }
                } ?: run {
                    // 如果没有临时文件，尝试从Intent中获取数据
                    val bitmap = data?.extras?.get("data") as? Bitmap
                    if (bitmap != null) {
                        // 创建新文件并保存Bitmap
                         weakContext?.get()?.let { ctx ->
                            try {
                                val photoFile = createTempPhotoFile(ctx)
                                val outputStream = FileOutputStream(photoFile)
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                                outputStream.close()
                                currentCallback?.onPhotoSelected(photoFile.absolutePath)
                            } catch (e: Exception) {
                                currentCallback?.onError(strings.get(R.string.photo_message_data_failed, e.message ?: strings.get(R.string.import_data_unknown_error)))
                            }
                        }
                    } else {
                        currentCallback?.onError(strings.get(R.string.photo_message_file_missing))
                    }
                }
            } else {
                currentCallback?.onPhotoSelected(null)
            }
            tempPhotoFile = null
            return true
        }
        return false
    }
    
    /**
     * 处理相册选择结果
     * @param requestCode 请求码
     * @param resultCode 结果码
     * @param data 返回数据
     */
    fun handleGalleryResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_CODE_GALLERY) {
            if (resultCode == Activity.RESULT_OK) {
                val uri: Uri? = data?.data
                if (uri != null) {
                    // 将URI转换为文件路径 (使用流复制而不是解码Bitmap)
                    val imagePath = copyImageToFile(uri)
                    if (imagePath != null) {
                        currentCallback?.onPhotoSelected(imagePath)
                    } else {
                        currentCallback?.onError(strings.get(R.string.photo_save_failed))
                    }
                } else {
                    currentCallback?.onError(strings.get(R.string.photo_message_not_selected))
                }
            } else {
                currentCallback?.onPhotoSelected(null)
            }
            return true
        }
        return false
    }
    
    /**
     * 将URI复制到临时文件 (FIX: Don't decode to Bitmap, just copy stream)
     */
    private fun copyImageToFile(uri: Uri): String? {
        return try {
            weakContext?.get()?.let { ctx ->
                // 创建临时文件
                val tempFile = createTempPhotoFile(ctx)
                
                // 从URI读取输入流
                val inputStream: InputStream = ctx.contentResolver.openInputStream(uri) ?: return null
                val outputStream = FileOutputStream(tempFile)
                
                // 直接复制流，避免OOM
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                
                // 返回临时文件路径
                tempFile.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // 临时照片文件
    private var tempPhotoFile: File? = null
}
