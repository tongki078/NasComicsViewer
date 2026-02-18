package org.nas.comicsviewer.data

import org.nas.comicsviewer.domain.model.ComicInfo

interface ZipManager {
    suspend fun listImagesInZip(filePath: String): List<String>
    suspend fun extractImage(zipPath: String, imageName: String): ByteArray?
    
    // 획기적 개선: ZIP을 한 번만 읽으면서 모든 이미지를 순차적으로 콜백 전달
    suspend fun streamAllImages(zipPath: String, onImageExtracted: suspend (String, ByteArray) -> Unit)

    fun getComicInfo(zipPath: String): ComicInfo?
}

expect fun provideZipManager(): ZipManager