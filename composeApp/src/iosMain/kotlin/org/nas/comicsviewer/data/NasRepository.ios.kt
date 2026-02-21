package org.nas.comicsviewer.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

actual fun provideNasRepository(): NasRepository = IosNasRepository.getInstance()

class IosNasRepository private constructor() : NasRepository {
    private val baseUrl = "http://192.168.0.2:5555"

    private val client = HttpClient(Darwin) {
        install(ContentNegotiation) {
            json(Json { isLenient = true; ignoreUnknownKeys = true })
        }
    }

    companion object {
        @Volatile private var instance: IosNasRepository? = null
        fun getInstance() = instance ?: synchronized(this) { instance ?: IosNasRepository().also { instance = it } }
    }

    override suspend fun listFiles(path: String): List<NasFile> = withContext(Dispatchers.Default) {
        try {
            client.get("$baseUrl/files") { url { parameters.append("path", path) } }.body<List<NasFile>>().sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        } catch (e: Exception) {
            println("DEBUG_NAS: Error listing $path: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getFileContent(path: String): ByteArray = withContext(Dispatchers.Default) {
        try {
            client.get("$baseUrl/download") { url { parameters.append("path", path) } }.body()
        } catch (e: Exception) {
            println("DEBUG_NAS: Error getting content from $path: ${e.message}")
            ByteArray(0)
        }
    }

    override suspend fun scanComicFolders(path: String, page: Int, pageSize: Int): ScanResult = withContext(Dispatchers.Default) {
        try {
            client.get("$baseUrl/scan") {
                url {
                    parameters.append("path", path)
                    parameters.append("page", page.toString())
                    parameters.append("page_size", pageSize.toString())
                }
            }.body()
        } catch (e: Exception) {
            println("DEBUG_NAS: Error scanning comics in $path: ${e.message}")
            ScanResult(0, 0, 0, emptyList())
        }
    }

    override suspend fun getMetadata(path: String): ComicMetadata? = withContext(Dispatchers.Default) {
        try {
            client.get("$baseUrl/metadata") { url { parameters.append("path", path) } }.body<ComicMetadata>()
        } catch (e: Exception) {
            println("DEBUG_NAS: Metadata error: ${e.message}")
            null
        }
    }
}
