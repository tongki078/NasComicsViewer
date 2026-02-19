package org.nas.comicsviewer.data

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nas.comicsviewer.toImageBitmap
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import java.io.File
import java.net.URLEncoder

// iOS에서는 Context가 필요 없으므로 context 파라미터를 무시합니다.
actual fun providePosterRepository(context: Any?): PosterRepository {
    return IosPosterRepository.getInstance()
}

class IosPosterRepository private constructor() : PosterRepository {

    private val client = HttpClient(Darwin) {}

    // iOS에는 LruCache가 없으므로, 간단한 MutableMap으로 메모리 캐시 구현
    private val memoryCache = mutableMapOf<String, ImageBitmap>()

    private val diskCacheDir: String by lazy {
        val cacheDir = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val posterCacheDir = "$cacheDir/poster_cache"
        if (!NSFileManager.defaultManager.fileExistsAtPath(posterCacheDir)) {
            NSFileManager.defaultManager.createDirectoryAtPath(posterCacheDir, true, null, null)
        }
        posterCacheDir
    }

    companion object {
        @Volatile private var instance: IosPosterRepository? = null
        fun getInstance() = instance ?: synchronized(this) { instance ?: IosPosterRepository().also { instance = it } }
    }

    override suspend fun getImage(url: String): ImageBitmap? = withContext(Dispatchers.Default) {
        if (url.isBlank()) return@withContext null
        val key = URLEncoder.encode(url, "UTF-8")

        memoryCache[key]?.let { return@withContext it }

        val diskFile = "$diskCacheDir/$key"
        if (NSFileManager.defaultManager.fileExistsAtPath(diskFile)) {
            try {
                val bytes = NSFileManager.defaultManager.dataWithContentsOfFile(diskFile)?.toByteArray()
                bytes?.toImageBitmap()?.let {
                    if (memoryCache.size > 20 * 1024 * 1024) memoryCache.clear() // 간단한 캐시 관리
                    memoryCache[key] = it
                    return@withContext it
                }
            } catch (e: Exception) { /* Fall through */ }
        }

        try {
            val bytes: ByteArray = client.get(url).body()
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                     NSFileManager.defaultManager.createFileContentsAtPath(diskFile, platform.Foundation.NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong()), null)
                }
                bytes.toImageBitmap()?.let {
                    if (memoryCache.size > 20 * 1024 * 1024) memoryCache.clear()
                    memoryCache[key] = it
                    return@withContext it
                }
            }
        } catch (e: Exception) { /* Fall through */ }
        
        null
    }

    override suspend fun insertRecentSearch(query: String) {}
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}

    private fun ByteArray.toByteArray(): ByteArray = this
}
