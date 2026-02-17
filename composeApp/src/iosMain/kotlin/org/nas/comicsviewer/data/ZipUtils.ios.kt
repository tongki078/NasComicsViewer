package org.nas.comicsviewer.data

actual fun provideZipManager(): ZipManager = IosZipManager()

class IosZipManager : ZipManager {
    override fun listImagesInZip(filePath: String): List<String> {
        // Not implemented
        return emptyList()
    }

    override fun extractImage(zipPath: String, imageName: String): ByteArray? {
        // Not implemented
        return null
    }
}