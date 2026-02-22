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
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nas.comicsviewer.toImageBitmap
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

actual fun providePosterRepository(context: Any?): PosterRepository {
    val ctx = requireNotNull(context) as Context
    val database = ComicDatabaseProvider(DatabaseDriverFactory(ctx)).database
    return AndroidPosterRepository(ctx, database)
}

class AndroidPosterRepository(private val context: Context, private val database: ComicDatabase) : PosterRepository {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) { 
            requestTimeoutMillis = 30000 
            connectTimeoutMillis = 10000
        }
    }
    private var baseUrl = "http://192.168.0.2:5555"
    private val queries = database.posterCacheQueries

    private val memoryCache: LruCache<String, ImageBitmap> = LruCache(40 * 1024 * 1024)
    private val diskCacheDir = File(context.cacheDir, "poster_cache").apply { mkdirs() }

    private fun String.toHash(): String {
        return MessageDigest.getInstance("MD5")
            .digest(this.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    
    override fun switchServer(isWebtoon: Boolean) {
        baseUrl = if (isWebtoon) "http://192.168.0.2:5556" else "http://192.168.0.2:5555"
        provideZipManager().switchServer(isWebtoon)
    }

    override suspend fun getImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        
        Log.d("PosterLoader_FINAL", "Attempting to load URL: $url")
        
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

        if (url.startsWith("zip_thumb://")) {
            try {
                val zipPath = url.removePrefix("zip_thumb://")
                val zipManager = provideZipManager()
                val imagesInZip = zipManager.listImagesInZip(zipPath)
                if (imagesInZip.isNotEmpty()) {
                    val coverImage = imagesInZip.first()
                    val bytes = zipManager.extractImage(zipPath, coverImage)
                    if (bytes != null && bytes.isNotEmpty()) {
                        diskFile.writeBytes(bytes)
                        bytes.toImageBitmap()?.let {
                            memoryCache.put(url, it)
                            return@withContext it
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("NasComics", "Failed to extract thumb from zip: $url", e)
            }
            return@withContext null
        }

        try {
            val response: HttpResponse
            
            // 명확한 분기: 외부 URL (서버 주소로 시작하지 않는 http/https)는 앱이 직접 요청
            if (url.startsWith("http") && !(url.startsWith("http://192.168.0.2:") || url.startsWith("https://192.168.0.2:"))) {
                Log.d("PosterLoader_FINAL", "Requesting EXTERNAL URL directly: $url")
                response = client.get(url)
            } else {
                // 2. 서버 내부 URL 또는 상대 경로 요청 (서버 프록시를 통함)
                val downloadUrl: String
                if (url.startsWith("http")) { // 서버 내부 URL (baseUrl로 시작하는 경우)
                    val encodedPath = URLEncoder.encode(url.removePrefix("$baseUrl/download?path="), "UTF-8").replace("+", "%20")
                    downloadUrl = "$baseUrl/download?path=$encodedPath"
                } else { // DB에 저장된 상대 경로
                    val encodedSegments = url.split("/").joinToString("/") { 
                        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                    }
                    downloadUrl = "$baseUrl/download?path=$encodedSegments"
                }
                Log.d("PosterLoader_FINAL", "Requesting download URL via server: $downloadUrl")
                response = client.get(downloadUrl)
            }
            
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

    override suspend fun insertRecentSearch(query: String) = withContext(Dispatchers.IO) {
        queries.insertRecentSearch(query, System.currentTimeMillis())
    }

    override suspend fun getRecentSearches(): List<String> = withContext(Dispatchers.IO) {
        queries.getRecentSearches().executeAsList()
    }

    override suspend fun clearRecentSearches() = withContext(Dispatchers.IO) {
        queries.clearRecentSearches()
    }

    override suspend fun addToRecent(file: NasFile) = withContext(Dispatchers.IO) {
        queries.insertRecentComic(
            path = file.path,
            name = file.name,
            poster_url = file.metadata?.posterUrl, // metadata를 통해 접근
            is_directory = file.isDirectory,
            last_read_at = System.currentTimeMillis()
        )
    }

    override suspend fun getRecentComics(): List<NasFile> = withContext(Dispatchers.IO) {
        queries.getRecentComics().executeAsList().map { dbRow ->
            NasFile(
                name = dbRow.name,
                path = dbRow.path,
                isDirectory = dbRow.is_directory,
                metadata = ComicMetadata(posterUrl = dbRow.poster_url) // DB에서 가져온 값으로 ComicMetadata 생성
            )
        }
    }

    override suspend fun removeFromRecent(path: String) = withContext(Dispatchers.IO) {
        queries.deleteRecentComic(path)
    }
}
