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
        // 구분자를 :::META:::로 변경하여 구 버전 캐시를 무효화함
        val data = "${metadata.title ?: ""}:::META:::${metadata.author ?: ""}:::META:::${metadata.summary ?: ""}:::META:::${metadata.posterUrl ?: ""}"
        database?.posterCacheQueries?.upsertPoster(hash, data, System.currentTimeMillis())
    }

    override suspend fun getMetadata(path: String): ComicMetadata = withContext(Dispatchers.IO) {
        val hash = md5(nfc(path))
        val cached = database?.posterCacheQueries?.getPoster(hash)?.executeAsOneOrNull()
        
        // 새로운 구분자가 포함된 경우에만 캐시 데이터 사용
        if (cached?.poster_url != null && cached.poster_url.contains(":::META:::")) {
            val p = cached.poster_url.split(":::META:::")
            return@withContext ComicMetadata(
                title = p.getOrNull(0),
                author = p.getOrNull(1),
                summary = p.getOrNull(2),
                posterUrl = p.getOrNull(3)
            )
        }
        
        // 캐시가 없거나 구 버전인 경우 서버에서 새로 가져옴
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
            client.get("$baseUrl/download") { url { parameters.append("path", url) } }.body()
        } catch (e: Exception) { null }
    }

    override suspend fun insertRecentSearch(query: String) {}
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}
}
