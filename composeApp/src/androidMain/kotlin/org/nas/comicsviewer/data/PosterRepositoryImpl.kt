package org.nas.comicsviewer.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Normalizer

// 서버 응답과 매칭되는 데이터 클래스 (필드명 poster_url 사용)
@Serializable
data class ServerMetadata(
    val title: String? = null,
    val author: String? = null,
    val summary: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null
)

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
        // 새로운 구분자 사용
        val data = "${metadata.title ?: ""}:::META:::${metadata.author ?: ""}:::META:::${metadata.summary ?: ""}:::META:::${metadata.posterUrl ?: ""}"
        database?.posterCacheQueries?.upsertPoster(hash, data, System.currentTimeMillis())
    }

    override suspend fun getMetadata(path: String): ComicMetadata = withContext(Dispatchers.IO) {
        val hash = md5(nfc(path))
        val cached = database?.posterCacheQueries?.getPoster(hash)?.executeAsOneOrNull()
        
        if (cached?.poster_url != null) {
            val raw = cached.poster_url
            val p = if (raw.contains(":::META:::")) {
                raw.split(":::META:::")
            } else {
                // 기존 ||| 방식 지원 (하위 호환성)
                raw.split("|||")
            }
            
            // 기존 캐시의 경우 순서가 (poster, title, author, summary)였을 수 있음
            // 신규 캐시의 경우 순서가 (title, author, summary, poster)
            if (raw.contains(":::META:::")) {
                return@withContext ComicMetadata(p.getOrNull(0), p.getOrNull(1), p.getOrNull(2), p.getOrNull(3))
            } else if (p.size >= 4) {
                // 기존 방식 추측 복구 (포스터가 http나 zip_thumb으로 시작하는 경우)
                val first = p.getOrNull(0) ?: ""
                if (first.startsWith("http") || first.startsWith("zip_thumb")) {
                     return@withContext ComicMetadata(p.getOrNull(1), p.getOrNull(2), p.getOrNull(3), p.getOrNull(0))
                }
            }
        }

        try {
            val m = client.get("$baseUrl/metadata") { url { parameters.append("path", path) } }.body<ServerMetadata>()
            val metadata = ComicMetadata(m.title, m.author, m.summary, m.posterUrl)
            cacheMetadata(path, metadata)
            metadata
        } catch (e: Exception) { 
            ComicMetadata(title = path.substringAfterLast("/")) 
        }
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            if (url.startsWith("http")) {
                client.get(url).body()
            } else {
                client.get("$baseUrl/download") { url { parameters.append("path", url) } }.body()
            }
        } catch (e: Exception) { null }
    }

    override suspend fun insertRecentSearch(query: String) {}
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}
}
