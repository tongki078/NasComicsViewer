package org.nas.comicsviewer.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

actual fun provideNasRepository(): NasRepository = AndroidNasRepository.getInstance()

class AndroidNasRepository private constructor() : NasRepository {
    private var baseUrl = "http://192.168.0.2:5555"
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 90000 }
    }

    companion object {
        @Volatile private var instance: AndroidNasRepository? = null
        fun getInstance() = instance ?: synchronized(this) { 
            instance ?: AndroidNasRepository().also { instance = it } 
        }
    }
    
    override fun switchServer(isWebtoon: Boolean) {
        baseUrl = if (isWebtoon) "http://192.168.0.2:5556" else "http://192.168.0.2:5555"
    }

    override suspend fun listFiles(path: String): List<NasFile> = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/files") { url { parameters.append("path", path) } }.body()
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getFileContent(path: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/download") { url { parameters.append("path", path) } }.body()
        } catch (e: Exception) { ByteArray(0) }
    }

    override suspend fun scanComicFolders(path: String, page: Int, pageSize: Int): ScanResult = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/scan") {
                url {
                    parameters.append("path", path)
                    parameters.append("page", page.toString())
                    parameters.append("page_size", pageSize.toString())
                }
            }.body()
        } catch (e: Exception) {
            println("DEBUG_NAS: Scan error: ${e.message}")
            ScanResult(0, 0, 0, emptyList()) 
        }
    }

    override suspend fun searchComics(query: String, page: Int, pageSize: Int): ScanResult = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/search") {
                url {
                    parameters.append("query", query)
                    parameters.append("page", page.toString())
                    parameters.append("page_size", pageSize.toString())
                }
            }.body()
        } catch (e: Exception) {
            println("DEBUG_NAS: Search error: ${e.message}")
            ScanResult(0, 0, 0, emptyList())
        }
    }

    override suspend fun getMetadata(path: String): ComicMetadata? = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/metadata") { url { parameters.append("path", path) } }.body()
        } catch (e: Exception) {
            println("DEBUG_NAS: Metadata error: ${e.message}")
            null
        }
    }
}
