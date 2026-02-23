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
        // SSL/TLS 인증서 검증 문제를 해결하기 위해 기본 엔진 설정 유지
        engine {
            https {
                // 특정 안드로이드 버전에서 발생하는 호스트네임 검증 오류 방지 로직은 
                // Ktor CIO의 기본 설정을 따르되 네트워크 안정성을 위해 타임아웃만 조정
            }
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
        
        Log.d("PosterDebug", ">>> [START] Requesting Image URL: $url")
        
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
                Log.e("PosterDebug", "ZIP Extraction Failed", e)
            }
            return@withContext null
        }

        try {
            // [핵심 수정] SSL 에러가 발생하는 외부 URL은 서버의 Proxy 기능을 다시 사용하여 우회합니다.
            // 서버가 이미지를 대신 받아서 앱에 전달하면 앱은 인증서 걱정 없이 이미지를 받을 수 있습니다.
            val downloadUrl = if (url.startsWith("http") && !url.startsWith(baseUrl)) {
                val encodedUrl = URLEncoder.encode(url, "UTF-8")
                "$baseUrl/download?path=$encodedUrl"
            } else if (url.startsWith("http")) {
                url
            } else {
                val encodedPath = url.split("/").joinToString("/") { 
                    URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                }
                "$baseUrl/download?path=$encodedPath"
            }

            Log.d("PosterDebug", "Requesting via URL: $downloadUrl")
            val response: HttpResponse = client.get(downloadUrl)
            val bytes: ByteArray = response.body()
            
            if (bytes.isNotEmpty()) {
                val bitmap = bytes.toImageBitmap()
                if (bitmap != null) {
                    diskFile.writeBytes(bytes)
                    memoryCache.put(url, bitmap)
                    return@withContext bitmap
                }
            }
        } catch (e: Exception) {
            Log.e("PosterDebug", "Download failed for $url: ${e.message}")
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
