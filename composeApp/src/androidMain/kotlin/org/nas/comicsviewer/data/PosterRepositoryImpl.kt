package org.nas.comicsviewer.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import android.util.LruCache
import java.net.URLEncoder

actual fun providePosterRepository(): PosterRepository = AndroidPosterRepository.getInstance()

class AndroidPosterRepository private constructor() : PosterRepository {
    private var database: ComicDatabase? = null
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        install(HttpTimeout) { 
            requestTimeoutMillis = 30000 
            connectTimeoutMillis = 10000 
            socketTimeoutMillis = 30000
        }
    }
    private val baseUrl = "http://192.168.0.2:5555"

    // 메모리 캐시: URL을 키로 사용, 50MB 제한 (기존 20MB -> 50MB 증량)
    private val memoryCache = object : LruCache<String, ByteArray>(50 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    companion object {
        @Volatile private var instance: AndroidPosterRepository? = null
        fun getInstance() = instance ?: synchronized(this) { instance ?: AndroidPosterRepository().also { instance = it } }
    }

    override fun setDatabase(database: ComicDatabase) { this.database = database }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        
        // 1. 메모리 캐시 확인
        memoryCache.get(url)?.let { return@withContext it }

        // 2. 네트워크 요청
        try {
            val bytes = if (url.startsWith("http")) {
                client.get(url) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                }.body<ByteArray>()
            } else {
                val encodedPath = URLEncoder.encode(url, "UTF-8")
                client.get("$baseUrl/download?path=$encodedPath").body<ByteArray>()
            }
            
            if (bytes.isNotEmpty()) {
                memoryCache.put(url, bytes)
            }
            bytes
        } catch (e: Exception) { 
            println("Download failed for $url: ${e.message}") // 상세 에러 로그 출력
            e.printStackTrace()
            null 
        }
    }

    override suspend fun insertRecentSearch(query: String) {}
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}
}
