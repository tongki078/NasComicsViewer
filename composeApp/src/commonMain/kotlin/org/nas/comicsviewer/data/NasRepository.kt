package org.nas.comicsviewer.data

import kotlinx.coroutines.flow.Flow

data class NasFile(
    val name: String,
    val isDirectory: Boolean,
    val path: String
)

interface NasRepository {
    fun setCredentials(username: String, password: String)
    suspend fun listFiles(url: String): List<NasFile>
    suspend fun getFileContent(url: String): ByteArray
    suspend fun downloadFile(url: String, destinationPath: String, onProgress: (Float) -> Unit)
    fun getTempFilePath(fileName: String): String
    fun scanComicFolders(url: String, maxDepth: Int = 3): Flow<NasFile>
    
    // Add method to get credentials for other managers
    fun getCredentials(): Pair<String, String>
}

expect fun provideNasRepository(): NasRepository