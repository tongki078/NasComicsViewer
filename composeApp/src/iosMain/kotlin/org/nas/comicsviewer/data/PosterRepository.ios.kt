package org.nas.comicsviewer.data

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.http.encodeURLPathPart
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nas.comicsviewer.toImageBitmap
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

actual fun providePosterRepository(context: Any?): PosterRepository {
    return IosPosterRepository
}

@OptIn(ExperimentalForeignApi::class)
object IosPosterRepository : PosterRepository {

    private val client = HttpClient(Darwin) {}
    private var baseUrl = "http://192.168.0.2:5555"
    private val RECENT_SEARCHES_KEY = "recent_queries"

    private val memoryCache = mutableMapOf<String, ImageBitmap>()
    private val diskCacheDir: String by lazy {
        val cacheDir = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val posterCacheDir = "$cacheDir/poster_cache"
        if (!NSFileManager.defaultManager.fileExistsAtPath(posterCacheDir)) {
            NSFileManager.defaultManager.createDirectoryAtPath(posterCacheDir, true, null, null)
        }
        posterCacheDir
    }

    override fun switchServer(isWebtoon: Boolean) {
        baseUrl = if (isWebtoon) "http://192.168.0.2:5556" else "http://192.168.0.2:5555"
    }

    override suspend fun getImage(url: String): ImageBitmap? = withContext(Dispatchers.Default) {
        if (url.isBlank()) return@withContext null
        val key = url.encodeURLPathPart()

        memoryCache[key]?.let { return@withContext it }

        val diskFile = "$diskCacheDir/$key"
        if (NSFileManager.defaultManager.fileExistsAtPath(diskFile)) {
            try {
                val data = NSData.dataWithContentsOfFile(diskFile)
                val bytes = data?.toByteArray()
                bytes?.toImageBitmap()?.let {
                    if (memoryCache.size > 20 * 1024 * 1024) memoryCache.clear()
                    memoryCache[key] = it
                    return@withContext it
                }
            } catch (e: Exception) { /* Fall through */ }
        }

        try {
            val downloadUrl = if (url.startsWith("http")) {
                url
            } else {
                "$baseUrl/download?path=${url.encodeURLPathPart()}"
            }
            val response = client.get(downloadUrl)
            val bytes: ByteArray = response.body()
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    val data = NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
                    data?.writeToFile(diskFile, true)
                }
                bytes.toImageBitmap()?.let {
                    if (memoryCache.size > 20 * 1024 * 1024) memoryCache.clear()
                    memoryCache[key] = it
                    return@withContext it
                }
            }
        } catch (e: Exception) {
            println("Error downloading image: ${e.message}")
        }

        null
    }

    override suspend fun insertRecentSearch(query: String) {
        val current = getRecentSearches().toMutableList()
        current.remove(query)
        current.add(0, query)
        val saved = current.take(10).joinToString("|||")
        NSUserDefaults.standardUserDefaults.setObject(saved, RECENT_SEARCHES_KEY)
    }

    override suspend fun getRecentSearches(): List<String> {
        val saved = NSUserDefaults.standardUserDefaults.stringForKey(RECENT_SEARCHES_KEY) ?: ""
        return if (saved.isEmpty()) emptyList() else saved.split("|||")
    }

    override suspend fun clearRecentSearches() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(RECENT_SEARCHES_KEY)
    }

    // iOS용 임시 구현 (추후 DB 연결 가능)
    override suspend fun addToRecent(file: NasFile) {}
    override suspend fun getRecentComics(): List<NasFile> = emptyList()
    override suspend fun removeFromRecent(path: String) {}

    private fun NSData.toByteArray(): ByteArray {
        return ByteArray(this.length.toInt()).apply {
            usePinned { pinned ->
                memcpy(pinned.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
            }
        }
    }
}
