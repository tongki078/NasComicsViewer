package org.nas.comicsviewer.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nas.comicsviewer.domain.model.ComicInfo
import kotlinx.serialization.json.Json

actual fun provideZipManager(): ZipManager = AndroidZipManager()

class AndroidZipManager : ZipManager {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { isLenient = true; ignoreUnknownKeys = true })
        }
    }
    private val baseUrl = "http://192.168.1.100:5555"

    override suspend fun listImagesInZip(filePath: String): List<String> = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/zip_entries/$filePath").body<List<String>>()
        } catch (e: Exception) {
            println("Error listing images in zip $filePath: ${e.message}")
            emptyList()
        }
    }

    override suspend fun extractImage(zipPath: String, imageName: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val encodedImageName = java.net.URLEncoder.encode(imageName, "UTF-8")
            client.get("$baseUrl/download_zip_entry/$zipPath?entry=$encodedImageName").body<ByteArray>()
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
        // 이 기능은 서버 사이드 구현이 필요하며, 현재는 지원되지 않습니다.
        return null
    }
}
