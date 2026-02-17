package org.nas.comicsviewer

import androidx.compose.ui.graphics.ImageBitmap

actual fun ByteArray.toImageBitmap(): ImageBitmap? {
    // TODO: Implement image decoding for iOS (e.g. using Skia or UIImage)
    return null
}