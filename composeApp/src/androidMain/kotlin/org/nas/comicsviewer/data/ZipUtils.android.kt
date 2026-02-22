package org.nas.comicsviewer.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nas.comicsviewer.domain.model.ComicInfo
import kotlinx.serialization.json.Json

actual fun provideZipManager(): ZipManager = AndroidZipManager.getInstance()

class AndroidZipManager private constructor() : ZipManager {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { isLenient = true; ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 30000
        }
    }
    private var baseUrl = "http://192.168.0.2:5555"

    companion object {
        @Volatile private var instance: AndroidZipManager? = null
        fun getInstance() = instance ?: synchronized(this) {
            instance ?: AndroidZipManager().also { instance = it }
        }
    }

    override fun switchServer(isWebtoon: Boolean) {
        baseUrl = if (isWebtoon) "http://192.168.0.2:5556" else "http://192.168.0.2:5555"
    }

    override suspend fun listImagesInZip(filePath: String): List<String> = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/zip_entries") { url { parameters.append("path", filePath) } }.body<List<String>>()
        } catch (e: Exception) {
            println("Error listing images in zip $filePath: ${e.message}")
            emptyList()
        }
    }

    override suspend fun extractImage(zipPath: String, imageName: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/download_zip_entry") {
                url {
                    parameters.append("path", zipPath)
                    parameters.append("entry", imageName)
                }
            }.body()
        } catch (e: Exception) {
            println("Error extracting image $imageName from $zipPath: ${e.message}")
            null
        }
    }

    override suspend fun streamAllImages(
        zipPath: String,
        onProgress: (Float) -> Unit,
        onImageExtracted: suspend (String, ByteArray) -> Unit
    ) = withContext(Dispatchers.IO) {
        val imageList = listImagesInZip(zipPath)
        if (imageList.isEmpty()) {
            onProgress(1.0f)
            return@withContext
        }

        imageList.forEachIndexed { index, imageName ->
            val imageBytes = extractImage(zipPath, imageName)
            if (imageBytes != null) {
                onImageExtracted(imageName, imageBytes)
            }
            onProgress((index + 1) / imageList.size.toFloat())
        }
    }

    override fun getComicInfo(zipPath: String): ComicInfo? {
        return null
    }
}
