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
        install(ContentNegotiation) {
            json(Json { isLenient = true; ignoreUnknownKeys = true })
        }
    }
    private val baseUrl = "http://192.168.0.2:5555"

    companion object {
        @Volatile private var instance: AndroidPosterRepository? = null
        fun getInstance() = instance ?: synchronized(this) { instance ?: AndroidPosterRepository().also { instance = it } }
    }

    override fun setDatabase(database: ComicDatabase) { this.database = database }

    private val cacheDir: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "poster_cache")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    override suspend fun getMetadata(path: String): ComicMetadata = withContext(Dispatchers.IO) {
        val nPath = normalize(path)
        val pathHash = md5(nPath)

        // 1. DB 캐시 확인
        try {
            val cached = database?.posterCacheQueries?.getPoster(pathHash)?.executeAsOneOrNull()
            if (cached?.poster_url != null && cached.poster_url.contains("|||")) {
                val parts = cached.poster_url.split("|||")
                if (parts.size >= 4) {
                    return@withContext ComicMetadata(
                        posterUrl = parts[0].ifBlank { null },
                        title = parts[1].ifBlank { null },
                        author = parts[2].ifBlank { null },
                        summary = parts[3].ifBlank { null }
                    )
                }
            }
        } catch (e: Exception) { /* ignore */ }

        // 2. 서버에서 새로 가져오기
        try {
            val metadata = client.get("$baseUrl/metadata") { url { parameters.append("path", nPath) } }.body<ComicMetadata>()
            val combined = "${metadata.posterUrl ?: ""}|||${metadata.title ?: ""}|||${metadata.author ?: ""}|||${metadata.summary ?: ""}"
            database?.posterCacheQueries?.upsertPoster(pathHash, combined, System.currentTimeMillis())
            metadata
        } catch (e: Exception) {
            println("Error metadata for $nPath: ${e.message}")
            ComicMetadata(title = nPath.substringAfterLast("/"))
        }
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? {
        if (url.isBlank()) return null
        val nUrl = normalize(url)
        val urlHash = md5(nUrl)
        val cacheFile = File(cacheDir, "img_$urlHash")
        if (cacheFile.exists()) return cacheFile.readBytes()

        return withContext(Dispatchers.IO) {
            try {
                val bytes: ByteArray = when {
                    nUrl.startsWith("http") -> client.get(nUrl).body()
                    nUrl.startsWith("zip_thumb://") -> {
                        val (zipPath, entry) = nUrl.substringAfter("zip_thumb://").split("|||")
                        client.get("$baseUrl/download_zip_entry") {
                            url {
                                parameters.append("path", zipPath)
                                parameters.append("entry", entry)
                            }
                        }.body()
                    }
                    else -> client.get("$baseUrl/download") { url { parameters.append("path", nUrl) } }.body()
                }
                if (bytes.isNotEmpty()) cacheFile.writeBytes(bytes)
                bytes
            } catch (e: Exception) { null }
        }
    }

    override suspend fun insertRecentSearch(q: String) { /* 로직 유지 */ }
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}
    private fun md5(str: String): String = java.security.MessageDigest.getInstance("MD5").digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
}
