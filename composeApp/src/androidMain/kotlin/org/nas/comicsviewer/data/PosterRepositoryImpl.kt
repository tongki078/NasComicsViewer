package org.nas.comicsviewer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

actual fun providePosterRepository(): PosterRepository = AndroidPosterRepository.getInstance()

class AndroidPosterRepository private constructor() : PosterRepository {
    private var database: ComicDatabase? = null
    private val nasRepository: NasRepository by lazy { provideNasRepository() }
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
        
        // 1. SQLite DB 캐시 확인
        try {
            val cached = database?.posterCacheQueries?.getPoster(pathHash)?.executeAsOneOrNull()
            if (cached?.poster_url != null) {
                // 이전에 이미지를 못 찾았더라도 한 번 더 시도해볼 가치가 있음 (업데이트 대응)
                if (cached.poster_url != NOT_FOUND) {
                    return@withContext ComicMetadata(posterUrl = cached.poster_url)
                }
            }
        } catch (e: Exception) { }

        // 2. 실시간 스캔
        try {
            // [획기적 개선] 경로 정규화: 폴더면 반드시 슬래시로 끝나야 함
            val targetPath = if (path.lowercase().let { it.endsWith(".zip") || it.endsWith(".cbz") }) {
                path.substringBeforeLast("/") + "/"
            } else if (!path.endsWith("/")) {
                "$path/"
            } else path

            val files = nasRepository.listFiles(targetPath)
            var foundUrl: String? = null
            var metadata = ComicMetadata()

            // A. kavita.yaml 파싱
            val yamlFile = files.find { it.name.lowercase() == "kavita.yaml" }
            if (yamlFile != null) {
                try {
                    val bytes = nasRepository.getFileContent(yamlFile.path)
                    val content = String(bytes, Charsets.UTF_8)
                    metadata = metadata.copy(
                        title = parseYamlValue(content, "localizedName") ?: parseYamlValue(content, "name"),
                        author = parseYamlValue(content, "writer")
                    )
                    
                    foundUrl = parseYamlValue(content, "poster_url") ?: 
                               parseYamlValue(content, "cover")?.let { name -> 
                                   files.find { it.name.equals(name, ignoreCase = true) }?.path 
                               }
                } catch (e: Exception) { }
            }

            // B. 일반적인 이미지 검색
            if (foundUrl == null) {
                val commonNames = listOf("poster", "cover", "folder", "thumbnail", "1")
                foundUrl = files.find { f ->
                    val nameLow = f.name.lowercase().substringBeforeLast(".")
                    !f.isDirectory && commonNames.any { it == nameLow } && isImagePath(f.name)
                }?.path
            }

            // C. 폴더 내 첫 번째 이미지
            if (foundUrl == null) {
                foundUrl = files.filter { !it.isDirectory && isImagePath(it.name) }
                    .sortedBy { it.name }.firstOrNull()?.path
            }

            // 결과 저장 (NOT_FOUND 상태라도 업데이트 함)
            database?.posterCacheQueries?.upsertPoster(
                title_hash = pathHash,
                poster_url = foundUrl ?: NOT_FOUND,
                cached_at = System.currentTimeMillis()
            )

            return@withContext metadata.copy(posterUrl = foundUrl)
        } catch (e: Exception) {
            ComicMetadata()
        }
    }

    private fun isImagePath(name: String): Boolean {
        val low = name.lowercase()
        return low.endsWith(".jpg") || low.endsWith(".png") || low.endsWith(".jpeg") || low.endsWith(".webp")
    }

    private fun parseYamlValue(content: String, key: String): String? {
        val regex = Regex("""^\s*$key\s*:\s*["']?([^"'\n\r]+)["']?""", RegexOption.MULTILINE)
        return regex.find(content)?.groupValues?.get(1)?.trim()?.removeSurrounding("'")?.removeSurrounding("\"")
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? {
        if (url == NOT_FOUND) return null
        val urlHash = md5(url)
        val cacheFile = File(cacheDir, "img_$urlHash")
        if (cacheFile.exists()) return try { cacheFile.readBytes() } catch (e: Exception) { null }

        return withContext(Dispatchers.IO) {
            try {
                if (url.startsWith("http")) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.isNotEmpty()) cacheFile.writeBytes(bytes)
                        bytes
                    } else null
                } else {
                    val bytes = nasRepository.getFileContent(url)
                    if (bytes.isNotEmpty()) cacheFile.writeBytes(bytes)
                    bytes
                }
            } catch (e: Exception) { null }
        }
    }

    private fun md5(str: String): String = java.security.MessageDigest.getInstance("MD5")
        .digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
}
