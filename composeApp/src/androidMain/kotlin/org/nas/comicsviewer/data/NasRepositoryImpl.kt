package org.nas.comicsviewer.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

actual fun provideNasRepository(): NasRepository = AndroidNasRepository.getInstance()

class AndroidNasRepository private constructor() : NasRepository {
    private val baseUrl = "http://192.168.0.2:5555"
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

    override fun scanComicFolders(path: String): Flow<NasFile> = flow {
        try {
            val response = client.get("$baseUrl/scan") { url { parameters.append("path", path) } }.body<List<NasFile>>()
            response.forEach { emit(it) }
        } catch (e: Exception) {
            println("DEBUG_NAS: Scan error: ${e.message}")
        }
    }.flowOn(Dispatchers.IO)
}
