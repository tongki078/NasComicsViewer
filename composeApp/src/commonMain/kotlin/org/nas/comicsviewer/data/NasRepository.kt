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

@Serializable
data class ScanResult(
    val total_items: Int,
    val page: Int,
    val page_size: Int,
    val items: List<NasFile>
)

interface NasRepository {
    suspend fun listFiles(path: String): List<NasFile>
    suspend fun getFileContent(path: String): ByteArray
    suspend fun scanComicFolders(path: String, page: Int, pageSize: Int): ScanResult
}

expect fun provideNasRepository(): NasRepository
