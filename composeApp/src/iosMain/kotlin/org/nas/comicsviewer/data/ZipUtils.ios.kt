package org.nas.comicsviewer.data

import org.nas.comicsviewer.domain.model.ComicInfo

actual fun provideZipManager(): ZipManager = IosZipManager()

class IosZipManager : ZipManager {
    override suspend fun listImagesInZip(filePath: String): List<String> {
        // iOS implementation (placeholder or existing logic)
        return emptyList()
    }

    override suspend fun extractImage(zipPath: String, imageName: String): ByteArray? {
        return null
    }

    override fun getComicInfo(zipPath: String): ComicInfo? {
        return null
    }
}
