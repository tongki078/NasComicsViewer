package org.nas.comicsviewer

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun ByteArray.toImageBitmap(): ImageBitmap? {
    if (this.isEmpty()) return null
    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(this, 0, this.size, options)
        
        // 화보 감상에 적합하면서도 메모리 OOM을 방지하는 최적 사이즈 (약 1.5MP)
        // 기존 1200x1800에서 1000x1500으로 하향 조정하여 로딩 속도 및 메모리 안정성 확보
        options.inSampleSize = calculateInSampleSize(options, 1000, 1500)
        options.inJustDecodeBounds = false
        // 메모리 절약을 위해 RGB_565 사용 고려 가능하나 화질을 위해 기본값 유지 (필요시 변경)
        options.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        
        val bitmap = BitmapFactory.decodeByteArray(this, 0, this.size, options)
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        println("Bitmap decoding failed: ${e.message}")
        null
    } catch (e: OutOfMemoryError) {
        System.gc()
        println("OOM during bitmap decoding - triggering GC")
        null
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}