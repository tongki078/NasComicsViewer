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

actual fun providePosterRepository(): PosterRepository = AndroidPosterRepository.getInstance()

class AndroidPosterRepository private constructor() : PosterRepository {
    private var database: ComicDatabase? = null
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { isLenient = true; ignoreUnknownKeys = true })
        }
    }
    private val baseUrl = "http://192.168.1.100:5555"
    private val NOT_FOUND = "NOT_FOUND"

    companion object {
        @Volatile private var instance: AndroidPosterRepository? = null
        fun getInstance() = instance ?: synchronized(this) { instance ?: AndroidPosterRepository().also { instance = it } }
    }
    
    override fun setDatabase(database: ComicDatabase) {
        this.database = database
    }

    private val cacheDir: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "poster_cache")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    override suspend fun getMetadata(path: String): ComicMetadata = withContext(Dispatchers.IO) {
        val pathHash = md5(path)
        
        try {
            val cached = database?.posterCacheQueries?.getPoster(pathHash)?.executeAsOneOrNull()
            if (cached?.poster_url != null && cached.poster_url != NOT_FOUND && cached.poster_url.contains("|||")) {
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

        try {
            val metadata = client.get("$baseUrl/metadata/$path").body<ComicMetadata>()
            
            val combinedData = "${metadata.posterUrl ?: ""}|||${metadata.title ?: ""}|||${metadata.author ?: ""}|||${metadata.summary ?: ""}"
            database?.posterCacheQueries?.upsertPoster(
                title_hash = pathHash,
                poster_url = combinedData,
                cached_at = System.currentTimeMillis()
            )
            
            metadata
        } catch (e: Exception) {
            println("Error fetching metadata for $path: ${e.message}")
            ComicMetadata()
        }
    }

    override suspend fun insertRecentSearch(query: String) {
        withContext(Dispatchers.IO) {
            try {
                database?.posterCacheQueries?.insertRecentSearch(query, System.currentTimeMillis())
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override suspend fun getRecentSearches(): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                database?.posterCacheQueries?.getRecentSearches()?.executeAsList() ?: emptyList()
            } catch (e: Exception) { 
                e.printStackTrace()
                emptyList() 
            }
        }
    }

    override suspend fun clearRecentSearches() {
        withContext(Dispatchers.IO) {
            try {
                database?.posterCacheQueries?.clearRecentSearches()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? {
        if (url.isBlank() || url == NOT_FOUND) return null
        val urlHash = md5(url)
        val cacheFile = File(cacheDir, "img_$urlHash")
        if (cacheFile.exists()) return cacheFile.readBytes()

        return withContext(Dispatchers.IO) {
            try {
                val bytes: ByteArray = when {
                    url.startsWith("http") -> {
                        client.get(url).body()
                    }
                    url.startsWith("api_zip_thumb://") -> {
                        val apiUrl = url.replace("api_zip_thumb://", "$baseUrl/download_zip_entry/")
                        client.get(apiUrl).body()
                    }
                    else -> {
                        client.get("$baseUrl/download/$url").body()
                    }
                }
                
                if (bytes.isNotEmpty()) {
                    cacheFile.writeBytes(bytes)
                }
                
                bytes
            } catch (e: Exception) {
                println("Error downloading image from $url: ${e.message}")
                null
            }
        }
    }

    private fun md5(str: String): String = java.security.MessageDigest.getInstance("MD5")
        .digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
}
