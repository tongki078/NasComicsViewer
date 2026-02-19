package org.nas.comicsviewer.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
        // DB 저장 순서 보장: posterUrl|||title|||author|||summary
        val data = "${metadata.posterUrl ?: ""}|||${metadata.title ?: ""}|||${metadata.author ?: ""}|||${metadata.summary ?: ""}"
        database?.posterCacheQueries?.upsertPoster(hash, data, System.currentTimeMillis())
    }

    override suspend fun getMetadata(path: String): ComicMetadata = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext ComicMetadata()
        val hash = md5(nfc(path))
        
        // 1. DB 캐시 확인
        val cached = database?.posterCacheQueries?.getPoster(hash)?.executeAsOneOrNull()
        if (cached?.poster_url != null) {
            val p = cached.poster_url.split("|||")
            val posterUrl = p.getOrNull(0)
            val title = p.getOrNull(1)
            if (!title.isNullOrBlank() && !title.lowercase().endsWith(".jpg")) {
                return@withContext ComicMetadata(title, p.getOrNull(2), p.getOrNull(3), posterUrl)
            }
        }

        // 2. 서버 요청
        try {
            val m = client.get("$baseUrl/metadata") { url { parameters.append("path", path) } }.body<ComicMetadata>()
            // 제목 보정 (파일명 유입 차단)
            val finalTitle = if (m.title.isNullOrBlank() || m.title!!.lowercase().endsWith(".jpg")) {
                path.substringAfterLast("/").substringBeforeLast(".")
            } else m.title
            val fixedMeta = m.copy(title = finalTitle)
            cacheMetadata(path, fixedMeta)
            fixedMeta
        } catch (e: Exception) { 
            ComicMetadata(title = path.substringAfterLast("/").substringBeforeLast(".")) 
        }
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            if (url.startsWith("http")) {
                // RIDIBOOKS 등 외부 링크 직접 다운로드 (User-Agent 추가로 차단 방지)
                client.get(url) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                }.body()
            } else {
                client.get("$baseUrl/download") { url { parameters.append("path", url) } }.body()
            }
        } catch (e: Exception) { null }
    }

    override suspend fun insertRecentSearch(query: String) {}
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}
}
