package org.nas.comicsviewer.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.datetime.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.CC_MD5_DIGEST_LENGTH
import platform.posix.CC_MD5
import platform.posix.u_char

actual fun providePosterRepository(): PosterRepository = IosPosterRepository.getInstance()

@OptIn(ExperimentalForeignApi::class)
class IosPosterRepository private constructor() : PosterRepository {
    private var database: ComicDatabase? = null
    private val client = HttpClient(Darwin) {
        install(ContentNegotiation) {
            json(Json { isLenient = true; ignoreUnknownKeys = true })
        }
    }
    private val baseUrl = "http://192.168.1.100:5555"
    private val NOT_FOUND = "NOT_FOUND"

    companion object {
        @Volatile private var instance: IosPosterRepository? = null
        fun getInstance() = instance ?: synchronized(this) { instance ?: IosPosterRepository().also { instance = it } }
    }

    override fun setDatabase(database: ComicDatabase) {
        this.database = database
    }

    private val cacheDir: String by lazy {
        val cacheDir = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val posterCacheDir = "$cacheDir/poster_cache"
        if (!NSFileManager.defaultManager.fileExistsAtPath(posterCacheDir)) {
            NSFileManager.defaultManager.createDirectoryAtPath(posterCacheDir, true, null, null)
        }
        posterCacheDir
    }

    override suspend fun getMetadata(path: String): ComicMetadata = withContext(Dispatchers.Default) {
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
                cached_at = Clock.System.now().toEpochMilliseconds()
            )
            
            metadata
        } catch (e: Exception) {
            println("Error fetching metadata for $path: ${e.message}")
            ComicMetadata()
        }
    }

    override suspend fun insertRecentSearch(query: String) {}
    override suspend fun getRecentSearches(): List<String> = emptyList()
    override suspend fun clearRecentSearches() {}

    override suspend fun downloadImageFromUrl(url: String): ByteArray? {
        if (url.isBlank() || url == NOT_FOUND) return null
        val urlHash = md5(url)
        val cacheFile = "$cacheDir/img_$urlHash"

        if (NSFileManager.defaultManager.fileExistsAtPath(cacheFile)) {
            return NSFileManager.defaultManager.dataWithContentsOfFile(cacheFile)?.toByteArray()
        }

        return withContext(Dispatchers.Default) {
            try {
                val bytes: ByteArray = when {
                    url.startsWith("http") -> client.get(url).body()
                    url.startsWith("api_zip_thumb://") -> {
                        val apiUrl = url.replace("api_zip_thumb://", "$baseUrl/download_zip_entry/")
                        client.get(apiUrl).body()
                    }
                    else -> client.get("$baseUrl/download/$url").body()
                }
                if (bytes.isNotEmpty()) {
                    bytes.usePinned { pinned ->
                        NSFileManager.defaultManager.createFileContentsAtPath(cacheFile, platform.Foundation.NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong()), null)
                    }
                }
                bytes
            } catch (e: Exception) {
                println("Error downloading image from $url: ${e.message}")
                null
            }
        }
    }

    private fun md5(str: String): String {
        val data = str.encodeToByteArray()
        val digest = ByteArray(CC_MD5_DIGEST_LENGTH)
        data.usePinned { pinned ->
            CC_MD5(pinned.addressOf(0), data.size.toUInt(), digest.refTo(0).getPointer(memScoped {})
            )
        }
        return digest.joinToString("") { "${it.toUByte().toString(16).padStart(2, '0')}" }
    }

    private fun ByteArray.toByteArray(): ByteArray = this
}
