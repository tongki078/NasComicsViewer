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
    private var baseUrl = "http://192.168.0.2:5555"
    private val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)

    private val memoryCache: LruCache<String, ImageBitmap> = LruCache(40 * 1024 * 1024)
    private val diskCacheDir = File(context.cacheDir, "poster_cache").apply { mkdirs() }

    private fun String.toHash(): String {
        return MessageDigest.getInstance("MD5")
            .digest(this.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    
    override fun switchServer(isWebtoon: Boolean) {
        baseUrl = if (isWebtoon) "http://192.168.0.2:5556" else "http://192.168.0.2:5555"
    }

    override suspend fun getImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val cacheKey = url.toHash()
        memoryCache.get(url)?.let { return@withContext it }
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
        try {
            val downloadUrl = if (url.startsWith("http")) {
                url
            } else {
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

    override suspend fun insertRecentSearch(query: String) {
        val current = getRecentSearches().toMutableList()
        current.remove(query)
        current.add(0, query)
        val saved = current.take(10).joinToString("|||")
        prefs.edit().putString("recent_queries", saved).apply()
    }

    override suspend fun getRecentSearches(): List<String> {
        val saved = prefs.getString("recent_queries", "") ?: ""
        return if (saved.isEmpty()) emptyList() else saved.split("|||")
    }

    override suspend fun clearRecentSearches() {
        prefs.edit().remove("recent_queries").apply()
    }
}
