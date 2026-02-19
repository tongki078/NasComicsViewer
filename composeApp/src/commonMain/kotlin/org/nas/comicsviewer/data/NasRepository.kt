package org.nas.comicsviewer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class NasFile(
    val name: String,
    val isDirectory: Boolean,
    val path: String,
    val metadata: ComicMetadata? = null
)

interface NasRepository {
    suspend fun listFiles(path: String): List<NasFile>
    suspend fun getFileContent(path: String): ByteArray
    fun scanComicFolders(path: String): Flow<NasFile>
}

expect fun provideNasRepository(): NasRepository
