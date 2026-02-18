package org.nas.comicsviewer.data

import org.nas.comicsviewer.domain.model.ComicInfo

interface ZipManager {
    fun listImagesInZip(filePath: String): List<String>
    fun extractImage(zipPath: String, imageName: String): ByteArray?
    fun getComicInfo(zipPath: String): ComicInfo?
}

expect fun provideZipManager(): ZipManager