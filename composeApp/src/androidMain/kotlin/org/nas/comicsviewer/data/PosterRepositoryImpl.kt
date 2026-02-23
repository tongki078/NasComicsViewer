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
            val downloadUrl: String
            if (url.startsWith("http")) {
                if (url.startsWith(baseUrl)) {
                    downloadUrl = url
                } else {
                    // 외부 주소는 서버 Proxy를 거치도록 인코딩하여 전달
                    val encodedUrl = URLEncoder.encode(url, "UTF-8").replace("+", "%20")
                    downloadUrl = "$baseUrl/download?path=$encodedUrl"
                }
            } else {
                // 상대 경로 처리
                val encodedSegments = url.split("/").joinToString("/") { 
                    URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                }
                downloadUrl = "$baseUrl/download?path=$encodedSegments"
            }

            Log.d("PosterLoader_FINAL", "Requesting URL via Server Proxy: $downloadUrl")
            val response: HttpResponse = client.get(downloadUrl)
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
            poster_url = file.metadata?.posterUrl,
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
                metadata = ComicMetadata(posterUrl = dbRow.poster_url)
            )
        }
    }

    override suspend fun removeFromRecent(path: String) = withContext(Dispatchers.IO) {
        queries.deleteRecentComic(path)
    }
}
