package org.nas.comicsviewer.data

import android.content.Context
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nas.comicsviewer.toImageBitmap
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

actual fun providePosterRepository(context: Any?): PosterRepository {
    return AndroidPosterRepository(requireNotNull(context) as Context)
}

class AndroidPosterRepository(private val context: Context) : PosterRepository {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) { 
            requestTimeoutMillis = 30000 
            connectTimeoutMillis = 10000
        }
    }
    private val baseUrl = "http://192.168.0.2:5555"

    // 메모리 캐시는 키로 URL 그대로 사용
    private val memoryCache: LruCache<String, ImageBitmap> = LruCache(40 * 1024 * 1024)
    private val diskCacheDir = File(context.cacheDir, "poster_cache").apply { mkdirs() }

    // 긴 URL을 안전한 짧은 파일명으로 변환하는 함수
    private fun String.toHash(): String {
        return MessageDigest.getInstance("MD5")
            .digest(this.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    override suspend fun getImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null

        val cacheKey = url.toHash()

        // 1. 메모리 캐시 확인
        memoryCache.get(url)?.let { return@withContext it }

        // 2. 디스크 캐시 확인
        val diskFile = File(diskCacheDir, cacheKey)
        if (diskFile.exists()) {
            try {
                val bytes = diskFile.readBytes()
                bytes.toImageBitmap()?.let {
                    memoryCache.put(url, it)
                    return@withContext it
                }
            } catch (e: Exception) {
                diskFile.delete()
            }
        }

        // 3. 네트워크 다운로드
        try {
            val downloadUrl = if (url.startsWith("http")) {
                url
            } else {
                // 경로에 특수문자가 있을 수 있으므로 인코딩 처리
                val encodedPath = URLEncoder.encode(url, "UTF-8").replace("+", "%20")
                "$baseUrl/download?path=$encodedPath"
            }
            
            val response = client.get(downloadUrl)
            val bytes: ByteArray = response.body()
            
            if (bytes.isNotEmpty()) {
                diskFile.writeBytes(bytes)
                bytes.toImageBitmap()?.let {
                    memoryCache.put(url, it)
                    return@withContext it
                }
            }
        } catch (e: Exception) {
            Log.e("NasComics", "Download failed: $url", e)
        }

        null
    }

    override suspend fun insertRecentSearch(query: String) {}
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}
}
