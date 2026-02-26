package org.nas.comicsviewer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun ByteArray.toImageBitmap(): ImageBitmap? {
    return try {
        // Skia의 Image.makeFromEncoded는 대용량 이미지 처리 시
        // OOM(메모리 초과)나 Skia Native Crash를 발생시킬 위험이 높습니다.
        // 대신 UIKit(iOS 네이티브)을 통해 이미지를 다운샘플링/디코딩 후 Skia로 넘겨줍니다.
        
        val nsData = this.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
        }
        
        var uiImage = UIImage(data = nsData)
        if (uiImage == null) return null

        // 화보나 잡지와 같은 초고해상도 이미지일 경우 리사이즈(Downsampling)
        // 화면 크기를 초과하는 너무 큰 텍스처(폭 2000 이상 등)를 로드하면 Skia에서 터짐
        val maxDim = 2000.0
        
        var width = 0.0
        var height = 0.0
        
        uiImage.size.useContents {
            width = this.width
            height = this.height
        }

        if (width > maxDim || height > maxDim) {
            val ratio = maxDim / maxOf(width, height)
            val newWidth = width * ratio
            val newHeight = height * ratio
            
            // CoreGraphics를 활용한 리사이징
            platform.UIKit.UIGraphicsBeginImageContextWithOptions(
                CGSizeMake(newWidth, newHeight), 
                false, 
                1.0
            )
            uiImage.drawInRect(CGRectMake(0.0, 0.0, newWidth, newHeight))
            val resizedImage = platform.UIKit.UIGraphicsGetImageFromCurrentImageContext()
            platform.UIKit.UIGraphicsEndImageContext()
            
            if (resizedImage != null) {
                uiImage = resizedImage
            }
        }
        
        // JPEG 포맷(품질 0.8)으로 변환하여 다시 ByteArray로 추출
        val jpegData = UIImageJPEGRepresentation(uiImage, 0.8)
        if (jpegData == null) return null

        val jpegBytes = ByteArray(jpegData.length.toInt()).apply {
            usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), jpegData.bytes, jpegData.length)
            }
        }

        Image.makeFromEncoded(jpegBytes).toComposeImageBitmap()
    } catch (e: Exception) {
        println("Image decode error: ${e.message}")
        null
    }
}
