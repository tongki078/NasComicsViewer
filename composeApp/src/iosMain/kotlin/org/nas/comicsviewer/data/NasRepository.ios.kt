package org.nas.comicsviewer.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

actual fun provideNasRepository(): NasRepository = IosNasRepository.getInstance()

class IosNasRepository private constructor() : NasRepository {
    private val baseUrl = "http://192.168.1.100:5555"

    private val client = HttpClient(Darwin) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }

    companion object {
        @Volatile private var instance: IosNasRepository? = null
        fun getInstance() = instance ?: synchronized(this) { instance ?: IosNasRepository().also { instance = it } }
    }

    override fun setCredentials(u: String, p: String) {}
    override fun getCredentials() = Pair("", "")

    override suspend fun listFiles(url: String): List<NasFile> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = "$baseUrl/files/$url"
            client.get(fullUrl).body<List<NasFile>>().sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        } catch (e: Exception) {
            println("DEBUG_NAS: Error listing $url: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getFileContent(url: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            val fullUrl = "$baseUrl/download/$url"
            client.get(fullUrl).body()
        } catch (e: Exception) {
            println("DEBUG_NAS: Error getting content from $url: ${e.message}")
            ByteArray(0)
        }
    }

    override suspend fun downloadFile(url: String, path: String, onProgress: (Float) -> Unit) {}
    override fun getTempFilePath(name: String) = ""

    override fun scanComicFolders(url: String, maxDepth: Int): Flow<NasFile> = flow {
        try {
            val fullUrl = "$baseUrl/scan/$url?maxDepth=$maxDepth"
            val comicFolders = client.get(fullUrl).body<List<NasFile>>()
            comicFolders.forEach { emit(it) }
        } catch (e: Exception) {
            println("DEBUG_NAS: Error scanning comics in $url: ${e.message}")
        }
    }.flowOn(Dispatchers.IO)
}
