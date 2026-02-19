package org.nas.comicsviewer.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Normalizer

actual fun providePosterRepository(): PosterRepository = AndroidPosterRepository.getInstance()

class AndroidPosterRepository private constructor() : PosterRepository {
    private var database: ComicDatabase? = null
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    }
    private val baseUrl = "http://192.168.0.2:5555"

    companion object {
        @Volatile private var instance: AndroidPosterRepository? = null
        fun getInstance() = instance ?: synchronized(this) { instance ?: AndroidPosterRepository().also { instance = it } }
    }

    override fun setDatabase(database: ComicDatabase) { this.database = database }

    private fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
    private fun md5(s: String): String = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    override suspend fun cacheMetadata(path: String, metadata: ComicMetadata) {
        val hash = md5(nfc(path))
        // DB 저장 형식: posterUrl|||title|||author|||summary
        val data = "${metadata.posterUrl ?: ""}|||${metadata.title ?: ""}|||${metadata.author ?: ""}|||${metadata.summary ?: ""}"
        database?.posterCacheQueries?.upsertPoster(hash, data, System.currentTimeMillis())
    }

    override suspend fun getMetadata(path: String): ComicMetadata = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext ComicMetadata()
        val hash = md5(nfc(path))
        
        // 1. DB 캐시 우선 조회
        val cached = database?.posterCacheQueries?.getPoster(hash)?.executeAsOneOrNull()
        if (cached?.poster_url != null) {
            val p = cached.poster_url.split("|||")
            // 생성자 순서: title, author, summary, posterUrl
            // 저장 순서: 0:posterUrl, 1:title, 2:author, 3:summary
            val title = p.getOrNull(1)
            val author = p.getOrNull(2)
            val summary = p.getOrNull(3)
            val posterUrl = p.getOrNull(0)
            
            // 캐시 데이터가 정상이면 반환
            if (!title.isNullOrBlank()) {
                return@withContext ComicMetadata(title, author, summary, posterUrl)
            }
        }

        // 2. 캐시 없으면 서버 요청
        try {
            val m = client.get("$baseUrl/metadata") { url { parameters.append("path", path) } }.body<ComicMetadata>()
            cacheMetadata(path, m)
            m
        } catch (e: Exception) { 
            ComicMetadata(title = path.substringAfterLast("/")) 
        }
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            if (url.startsWith("http")) {
                // 외부 URL은 직접 다운로드
                client.get(url).body()
            } else {
                // 내부 경로는 서버 download 엔드포인트 사용
                client.get("$baseUrl/download") { url { parameters.append("path", url) } }.body()
            }
        } catch (e: Exception) { null }
    }

    override suspend fun insertRecentSearch(query: String) {}
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}
}
