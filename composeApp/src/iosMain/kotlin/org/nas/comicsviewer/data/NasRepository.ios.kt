package org.nas.comicsviewer.data

actual fun provideNasRepository(): NasRepository = IosNasRepository()

class IosNasRepository : NasRepository {
    override fun setCredentials(username: String, password: String) {
        // Not implemented
    }

    override suspend fun listFiles(url: String): List<NasFile> {
        return emptyList()
    }

    override suspend fun getFileContent(url: String): ByteArray {
        return ByteArray(0)
    }

    override suspend fun downloadFile(url: String, destinationPath: String) {
        // Not implemented
    }

    override fun getTempFilePath(fileName: String): String {
        return fileName // Placeholder
    }
}