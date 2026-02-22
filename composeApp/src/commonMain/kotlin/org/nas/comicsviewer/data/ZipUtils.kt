package org.nas.comicsviewer.data

import org.nas.comicsviewer.domain.model.ComicInfo

interface ZipManager {
    suspend fun listImagesInZip(filePath: String): List<String>
    suspend fun extractImage(zipPath: String, imageName: String): ByteArray?
    
    // 진행률(0.0~1.0)과 이미지 데이터를 동시에 스트리밍
    suspend fun streamAllImages(
        zipPath: String, 
        onProgress: (Float) -> Unit,
        onImageExtracted: suspend (String, ByteArray) -> Unit
    )

    fun getComicInfo(zipPath: String): ComicInfo?
    fun switchServer(isWebtoon: Boolean)
}

expect fun provideZipManager(): ZipManager