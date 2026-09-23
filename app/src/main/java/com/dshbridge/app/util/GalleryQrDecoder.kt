package com.dshbridge.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 从相册图片里识别二维码。
 *
 * 相机扫码用 zxing-android-embedded 的实时解码，**相册图片它与本库都不支持**，
 * 所以这里直接用 ZXing core 自己解：Bitmap → RGB 像素 → LuminanceSource → BinaryBitmap → 解码。
 *
 * 两个实现要点：
 *  1. **先降采样**（长边不超过 [MAX_SIDE]）：手机拍的照片动辄 4000×3000，直接喂给 ZXing
 *     既慢又可能 OOM；二维码识别不需要那么高的分辨率。
 *  2. **多角度重试**：`BitmapFactory` 不会自动应用 EXIF 旋转，相册里竖拍的照片可能是躺着存的。
 *     二维码本身有方向信息、多数情况下正反都能认，但横竖颠倒的图仍可能失败，
 *     所以按 0/90/180/270 依次重试（代价很低，全部在后台线程）。
 */
object GalleryQrDecoder {

    private const val MAX_SIDE = 1600

    fun decode(context: Context, uri: Uri): String? {
        val bitmap = loadDownscaled(context, uri) ?: return null
        return try {
            decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    // ---- 内部 ----

    private fun loadDownscaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    private fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val reader = MultiFormatReader()
        reader.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            )
        )

        try {
            for (degrees in intArrayOf(0, 90, 180, 270)) {
                val frame = if (degrees == 0) IntArray(0) else rotate(pixels, width, height, degrees)
                val sourcePixels: IntArray
                val sourceWidth: Int
                val sourceHeight: Int

                if (degrees == 0) {
                    sourcePixels = pixels; sourceWidth = width; sourceHeight = height
                } else {
                    sourcePixels = frame
                    val swapped = degrees % 180 != 0
                    sourceWidth = if (swapped) height else width
                    sourceHeight = if (swapped) width else height
                }

                try {
                    val source = RGBLuminanceSource(sourceWidth, sourceHeight, sourcePixels)
                    val binary = BinaryBitmap(HybridBinarizer(source))
                    return reader.decodeWithState(binary).text
                } catch (_: NotFoundException) {
                    // 换下一个角度继续试
                } finally {
                    reader.reset()
                }
            }
        } catch (_: Throwable) {
            return null
        }
        return null
    }

    /** 把 ARGB 像素数组旋转 [degrees] 度，返回新数组（尺寸随 90/270 交换）。 */
    private fun rotate(src: IntArray, width: Int, height: Int, degrees: Int): IntArray {
        val dstWidth = if (degrees % 180 == 0) width else height
        val dst = IntArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx: Int
                val ny: Int
                when (degrees) {
                    90 -> { nx = height - 1 - y; ny = x }
                    180 -> { nx = width - 1 - x; ny = height - 1 - y }
                    else -> { nx = y; ny = width - 1 - x }
                }
                dst[ny * dstWidth + nx] = src[y * width + x]
            }
        }
        return dst
    }
}
