package org.nas.comicsviewer.data

import android.content.Context
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

actual fun providePosterRepository(context: Any?): PosterRepository {
    return AndroidPosterRepository(requireNotNull(context) as Context)
}

class AndroidPosterRepository(private val context: Context) : PosterRepository {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 20000 }
    }
    private val baseUrl = "http://192.168.0.2:5555"

    private val memoryCache: LruCache<String, ImageBitmap> = LruCache(30 * 1024 * 1024)
    private val diskCacheDir = File(context.cacheDir, "poster_cache").apply { mkdirs() }

    override suspend fun getImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null

        val key = URLEncoder.encode(url, "UTF-8")

        // 1. 메모리 캐시 확인
        memoryCache.get(key)?.let { return@withContext it }

        // 2. 디스크 캐시 확인
        val diskFile = File(diskCacheDir, key)
        if (diskFile.exists()) {
            try {
                diskFile.readBytes().toImageBitmap()?.let {
                    memoryCache.put(key, it)
                    return@withContext it
                }
            } catch (e: Exception) { /* Fall through */ }
        }

        // 3. 네트워크에서 다운로드 (수정된 부분)
        try {
            val downloadUrl = if (url.startsWith("http")) {
                url
            } else {
                "$baseUrl/download?path=${URLEncoder.encode(url, "UTF-8")}"
            }
            
            val bytes: ByteArray = client.get(downloadUrl).body()
            
            if (bytes.isNotEmpty()) {
                diskFile.writeBytes(bytes)
                bytes.toImageBitmap()?.let {
                    memoryCache.put(key, it)
                    return@withContext it
                }
            }
        } catch (e: Exception) { /* Fall through */ }

        null
    }

    override suspend fun insertRecentSearch(query: String) {}
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}
}
