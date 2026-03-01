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
        
        // 화보 감상에 적합하면서도 메모리 OOM을 방지하는 최적 사이즈
        // 로딩 속도를 위해 1200x1800 수준으로 유지
        options.inSampleSize = calculateInSampleSize(options, 1200, 1800)
        options.inJustDecodeBounds = false
        
        // 화보 모드에서 로딩 속도와 메모리 효율을 극대화하기 위해 RGB_565 사용
        // ARGB_8888 대비 메모리 사용량이 절반이며 디코딩 속도가 빠름
        options.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        // 디코딩 성능 향상을 위한 옵션
        options.inMutable = false
        
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