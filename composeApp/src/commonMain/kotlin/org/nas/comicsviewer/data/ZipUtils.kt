package org.nas.comicsviewer.data

interface ZipManager {
    fun listImagesInZip(filePath: String): List<String>
    fun extractImage(zipPath: String, imageName: String): ByteArray?
}

expect fun provideZipManager(): ZipManager