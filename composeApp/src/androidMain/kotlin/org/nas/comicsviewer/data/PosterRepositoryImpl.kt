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
import android.util.LruCache
import java.net.URLEncoder

actual fun providePosterRepository(): PosterRepository = AndroidPosterRepository.getInstance()

class AndroidPosterRepository private constructor() : PosterRepository {
    private var database: ComicDatabase? = null
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    }
    private val baseUrl = "http://192.168.0.2:5555"

    // 메모리 캐시: URL을 키로 사용, 20MB 제한
    private val memoryCache = object : LruCache<String, ByteArray>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    companion object {
        @Volatile private var instance: AndroidPosterRepository? = null
        fun getInstance() = instance ?: synchronized(this) { instance ?: AndroidPosterRepository().also { instance = it } }
    }

    override fun setDatabase(database: ComicDatabase) { this.database = database }

    private fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
    private fun md5(s: String): String = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    override suspend fun cacheMetadata(path: String, metadata: ComicMetadata) {
        val hash = md5(nfc(path))
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
            
            // 수정: posterUrl이 실제로 존재할 때만 캐시를 사용함.
            // 빈 문자열("")인 경우 이전에 못 찾았다는 뜻이지만, 서버 로직이 개선되었을 수 있으므로 재시도하게 함.
            if (!posterUrl.isNullOrBlank() && !title.isNullOrBlank() && !title.lowercase().endsWith(".jpg")) {
                return@withContext ComicMetadata(title, p.getOrNull(2), p.getOrNull(3), posterUrl)
            }
        }

        // 2. 서버 요청
        try {
            // URL 인코딩 명시적 적용 (특수문자 # 등 처리 위함)
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            // parameters.append를 쓰면 중복 인코딩 될 수 있으므로 직접 쿼리 스트링 구성
            val m = client.get("$baseUrl/metadata?path=$encodedPath").body<ComicMetadata>()
            
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
        
        // 1. 메모리 캐시 확인
        memoryCache.get(url)?.let { return@withContext it }

        // 2. 네트워크 요청
        try {
            val bytes = if (url.startsWith("http")) {
                client.get(url) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                }.body<ByteArray>()
            } else {
                // 다운로드 요청 시에도 인코딩 적용
                val encodedPath = URLEncoder.encode(url, "UTF-8")
                client.get("$baseUrl/download?path=$encodedPath").body<ByteArray>()
            }
            
            if (bytes != null && bytes.isNotEmpty()) {
                memoryCache.put(url, bytes)
            }
            bytes
        } catch (e: Exception) { null }
    }

    override suspend fun insertRecentSearch(query: String) {}
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}
}
