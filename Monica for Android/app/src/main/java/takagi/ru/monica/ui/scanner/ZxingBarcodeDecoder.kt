package takagi.ru.monica.ui.scanner

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import java.nio.ByteBuffer

/**
 * zxing 条码解码器：负责扫码栈里原本由 ML Kit 承担的识别职责，
 * 避免为内置条码模型在每个 ABI 上多打包约 3MB 的原生库。
 * 相机帧（YUV_420_888）与相册位图共用同一读取器配置，码制集合由调用方给定。
 *
 * ML Kit 有三项隐性能力在替换时容易丢失，这里显式补齐：
 * 1. 反色二维码（暗底亮码，如深色主题下的登录码）——旧 zxing 方案的"正反混合扫描"覆盖的正是这个场景；
 * 2. 远距离小码——在抽稀解码面之外保留全分辨率兜底；
 * 3. 空闲帧的处理成本——复用亮度缓冲，每三帧执行一次单极性的全分辨率深扫，
 *    正反色交替且不叠加抽稀阶梯，降低 GC 压力与单帧峰值。
 */
internal class ZxingBarcodeDecoder(
    private val formats: Collection<BarcodeFormat>,
    private val preserveWhitespace: Boolean = false,
) {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to formats.distinct(),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )
    private val qrReader = QRCodeMultiReader()
    private val otherFormats = formats.filterNot { it == BarcodeFormat.QR_CODE }.distinct()
    private val otherHints = hints + (DecodeHintType.POSSIBLE_FORMATS to otherFormats)
    private val reader = MultiFormatReader().apply {
        setHints(otherHints)
    }
    private val multipleReader = GenericMultipleBarcodeReader(reader)
    private var frameNumber = 0
    private var decimatedLuminance = ByteArray(0)
    private var fullLuminance = ByteArray(0)
    private var rowBuffer = ByteArray(0)

    /**
     * 解析一帧相机画面；返回所有候选码值，本帧无可读码返回空列表，
     * 只有真正的异常错误才向上抛出。
     * 仅在单帧分析线程上调用，读取器内部状态因此无需加锁。
     */
    fun decodeFrame(imageProxy: ImageProxy): List<String> {
        val plane = imageProxy.planes[0]
        val crop = imageProxy.cropRect
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= imageProxy.width && crop.bottom <= imageProxy.height)
        val width = crop.width()
        val height = crop.height()
        require(width > 0 && height > 0)
        val buffer = plane.buffer.duplicate()
        val origin = buffer.position() + crop.top * plane.rowStride + crop.left * plane.pixelStride
        val rotationQuarterTurns =
            ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360 / 90

        frameNumber = (frameNumber + 1) % (SWEEP_INTERVAL * 2)
        // Full-resolution sweeps also run after unrelated hits. Each sweep replaces
        // the regular ladder and alternates polarity, so costly attempts cannot pile
        // up on a single frame and trigger the session's stall watchdog.
        if (frameNumber % SWEEP_INTERVAL == 0) {
            fullLuminance = copyLuminance(
                buffer, origin, width, height, plane.rowStride, plane.pixelStride, 1, fullLuminance
            )
            val fullSource = rotated(
                PlanarYUVLuminanceSource(fullLuminance, width, height, 0, 0, width, height, false),
                rotationQuarterTurns
            )
            return decodeFullResolution(fullSource, inverted = frameNumber == 0)
        }

        // 常规帧同时检查正反色，保留 Hybrid 与 GlobalHistogram 对不同码制的覆盖。
        val outWidth = (width + DECIMATION - 1) / DECIMATION
        val outHeight = (height + DECIMATION - 1) / DECIMATION
        decimatedLuminance = copyLuminance(
            buffer, origin, width, height, plane.rowStride, plane.pixelStride, DECIMATION, decimatedLuminance
        )
        val decimatedSource = rotated(
            PlanarYUVLuminanceSource(decimatedLuminance, outWidth, outHeight, 0, 0, outWidth, outHeight, false),
            rotationQuarterTurns
        )
        return decodeWithFallback(decimatedSource)
    }

    /**
     * 从 Y 平面拷贝出紧凑亮度矩阵；step > 1 时按行/列抽稀。
     * buffer 需在调用期间保持有效（imageProxy 尚未 close）。
     */
    private fun copyLuminance(
        buffer: ByteBuffer,
        origin: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        step: Int,
        reusable: ByteArray
    ): ByteArray {
        val outWidth = (width + step - 1) / step
        val outHeight = (height + step - 1) / step
        require(rowStride > 0 && pixelStride > 0 && origin >= 0)
        val lastPixel = origin.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride
        require(lastPixel < buffer.limit()) { "Incomplete camera luminance plane" }
        val luminance = reusable.takeIf { it.size == outWidth * outHeight } ?: ByteArray(outWidth * outHeight)
        if (rowBuffer.size < width) rowBuffer = ByteArray(width)
        var destinationRow = 0
        if (pixelStride == 1) {
            for (row in 0 until height step step) {
                buffer.position(origin + row * rowStride)
                buffer.get(rowBuffer, 0, width)
                var destination = destinationRow * outWidth
                for (column in 0 until width step step) {
                    luminance[destination++] = rowBuffer[column]
                }
                destinationRow++
            }
        } else {
            for (row in 0 until height step step) {
                var destination = destinationRow * outWidth
                for (column in 0 until width step step) {
                    luminance[destination++] = buffer.get(origin + row * rowStride + column * pixelStride)
                }
                destinationRow++
            }
        }
        return luminance
    }

    /** 解析相册位图；zxing 不读 EXIF 方向，依次尝试 4 个旋转角。 */
    @Synchronized
    fun decodeBitmap(bitmap: Bitmap): List<String> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var source: LuminanceSource = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        repeat(ROTATION_ATTEMPTS) {
            val candidates = decodeWithFallback(source)
            if (candidates.isNotEmpty()) return candidates
            source = rotateSourceCounterClockwise(source)
        }
        return emptyList()
    }

    /**
     * 解码阶梯：Hybrid 二值化（正色 → 反色）→ GlobalHistogram 兜底（正色 → 反色）。
     * 反色扫描覆盖暗底亮码；GlobalHistogram 对小模块和一维码与 Hybrid 互补。
     */
    internal fun decodeWithFallback(source: LuminanceSource): List<String> {
        val candidates = linkedSetOf<String>()
        for (polarity in listOf(source, source.invert())) {
            val hybrid = decode(BinaryBitmap(HybridBinarizer(polarity)))
            candidates += hybrid
            if (hybrid.isEmpty()) {
                candidates += decode(BinaryBitmap(GlobalHistogramBinarizer(polarity)))
            }
        }
        return candidates.toList()
    }

    /** Full-resolution sweeps only need Hybrid; the decimated path retains GlobalHistogram. */
    private fun decodeFullResolution(source: LuminanceSource, inverted: Boolean): List<String> =
        decode(BinaryBitmap(HybridBinarizer(if (inverted) source.invert() else source)))

    private fun rotated(source: LuminanceSource, quarterTurns: Int): LuminanceSource {
        var result = source
        repeat(quarterTurns) { result = rotateSourceCounterClockwise(result) }
        return result
    }

    /**
     * LuminanceSource.rotateCounterClockwise() 基类直接抛 UnsupportedOperationException
     * （PlanarYUV/RGB 源都不支持旋转），因此在这里手动旋转亮度矩阵。
     */
    internal fun rotateSourceCounterClockwise(source: LuminanceSource): LuminanceSource {
        val oldWidth = source.width
        val oldHeight = source.height
        val src = source.matrix
        val dst = ByteArray(oldWidth * oldHeight)
        val newWidth = oldHeight
        for (y2 in 0 until oldWidth) {
            val dstRow = y2 * newWidth
            val srcColumn = oldWidth - 1 - y2
            for (x2 in 0 until oldHeight) {
                dst[dstRow + x2] = src[x2 * oldWidth + srcColumn]
            }
        }
        return PlanarYUVLuminanceSource(dst, newWidth, oldWidth, 0, 0, newWidth, oldWidth, false)
    }

    private fun decode(bitmap: BinaryBitmap): List<String> {
        val candidates = linkedSetOf<String>()
        if (BarcodeFormat.QR_CODE in formats) {
            try {
                qrReader.decodeMultiple(bitmap, hints).forEach { candidates += it.text }
            } catch (_: ReaderException) {
                // An empty frame is normal; retain a reusable reader for the next frame.
            } finally {
                qrReader.reset()
            }
        }
        if (otherFormats.isNotEmpty()) {
            try {
                multipleReader.decodeMultiple(bitmap, otherHints).forEach { candidates += it.text }
            } catch (_: ReaderException) {
                // Other formats are optional candidates, independent of QR detection.
            } finally {
                reader.reset()
            }
        }
        return candidates.map { if (preserveWhitespace) it else it.trim() }
            .filter(String::isNotBlank).distinct()
    }

    private companion object {
        private const val ROTATION_ATTEMPTS = 4
        private const val DECIMATION = 2
        // The first two frames after startup stay light while the camera warms up.
        private const val SWEEP_INTERVAL = 3
    }
}
