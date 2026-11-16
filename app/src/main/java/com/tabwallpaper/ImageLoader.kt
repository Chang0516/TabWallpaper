package com.tabwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

object ImageLoader {

    /** 读取照片（会自动按 EXIF 转正），并缩小到最长边约 maxDim，避免占太多内存。 */
    fun load(c: Context, uri: Uri, maxDim: Int): Bitmap? = try {
        val src = ImageDecoder.createSource(c.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            var sample = 1
            while (longest / (sample * 2) >= maxDim) sample *= 2
            decoder.setTargetSampleSize(sample)
            // 壁纸用软件画布绘制，不能用硬件 Bitmap
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        null
    }

    /** 做一张很小的图，放大画出来就是模糊背景（打横时填满照片两边的空位）。 */
    fun blurOf(src: Bitmap): Bitmap {
        val mid = Bitmap.createScaledBitmap(src, 160, max(1, 160 * src.height / src.width), true)
        return Bitmap.createScaledBitmap(mid, 20, max(1, 20 * src.height / src.width), true)
    }

    fun screenMaxDim(c: Context): Int {
        val dm = c.resources.displayMetrics
        return min(4096, (max(dm.widthPixels, dm.heightPixels) * 1.5f).toInt())
    }
}
