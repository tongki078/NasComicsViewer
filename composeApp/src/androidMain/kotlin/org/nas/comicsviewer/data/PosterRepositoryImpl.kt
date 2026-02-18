package org.nas.comicsviewer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

actual fun providePosterRepository(): PosterRepository = AndroidPosterRepository.getInstance()

class AndroidPosterRepository private constructor() : PosterRepository {
    private var database: ComicDatabase? = null
    private val nasRepository: NasRepository by lazy { provideNasRepository() }
    private val zipManager: ZipManager by lazy { provideZipManager() }
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
        
        // 1. DB 캐시 확인
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
        } catch (e: Exception) { }

        // 2. 실시간 스캔
        try {
            val files = nasRepository.listFiles(path)
            if (files.isEmpty()) return@withContext ComicMetadata()

            var metadata = ComicMetadata()
            var posterPath: String? = null

            // A. kavita.yaml 파싱
            val yamlFile = files.find { it.name.lowercase() == "kavita.yaml" }
            if (yamlFile != null) {
                val bytes = nasRepository.getFileContent(yamlFile.path)
                if (bytes.isNotEmpty()) {
                    val content = decodeText(bytes)
                    metadata = metadata.copy(
                        title = parseAggressive(content, "localizedName") ?: parseAggressive(content, "name"),
                        author = parseAggressive(content, "writer") ?: parseAggressive(content, "author"),
                        summary = parseAggressive(content, "summary")
                    )
                    posterPath = parseAggressive(content, "poster_url") ?: 
                                 parseAggressive(content, "cover")?.let { name -> 
                                     files.find { it.name.equals(name, ignoreCase = true) }?.path 
                                 }
                }
            }

            // B. 폴백 1: 폴더 내 이미지 파일 직접 찾기
            if (posterPath == null) {
                val common = listOf("poster", "cover", "folder", "thumbnail")
                posterPath = files.find { f ->
                    val nameLow = f.name.lowercase().substringBeforeLast(".")
                    common.contains(nameLow) && (f.name.lowercase().endsWith(".jpg") || f.name.lowercase().endsWith(".png"))
                }?.path
            }

            // C. 폴백 2 (핵심): 압축 파일 내부의 첫 번째 이미지 추출
            if (posterPath == null) {
                val firstZip = files.find { it.name.lowercase().let { n -> n.endsWith(".zip") || n.endsWith(".cbz") } }
                if (firstZip != null) {
                    posterPath = "zip_thumb://${firstZip.path}"
                }
            }

            // 3. SQLite 통합 저장
            val combinedData = "${posterPath ?: ""}|||${metadata.title ?: ""}|||${metadata.author ?: ""}|||${metadata.summary ?: ""}"
            database?.posterCacheQueries?.upsertPoster(
                title_hash = pathHash,
                poster_url = combinedData,
                cached_at = System.currentTimeMillis()
            )

            return@withContext metadata.copy(posterUrl = posterPath)
        } catch (e: Exception) {
            ComicMetadata()
        }
    }

    // --- 검색 관련 메서드 구현 ---
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
    // -------------------------

    private fun parseAggressive(content: String, key: String): String? {
        val regex = Regex("""(?i)$key\s*:\s*["']?([^"'\r\n]+)""", RegexOption.MULTILINE)
        return regex.find(content)?.groupValues?.get(1)?.trim()?.let { 
            val clean = it.split(" #")[0].trim().removeSurrounding("\"").removeSurrounding("'")
            if (clean == "null" || clean.isBlank()) null else clean 
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        val encodings = listOf("UTF-8", "UTF-16", "CP949")
        for (enc in encodings) {
            try {
                val text = String(bytes, Charset.forName(enc))
                if (text.contains(":")) return if (text.startsWith("\uFEFF")) text.substring(1) else text
            } catch (e: Exception) {}
        }
        return String(bytes, Charsets.UTF_8)
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? {
        if (url.isBlank() || url == NOT_FOUND) return null
        val urlHash = md5(url)
        val cacheFile = File(cacheDir, "img_$urlHash")
        if (cacheFile.exists()) return cacheFile.readBytes()

        return withContext(Dispatchers.IO) {
            try {
                var bytes: ByteArray? = null
                if (url.startsWith("http")) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000; conn.readTimeout = 15000
                    if (conn.responseCode == 200) {
                        bytes = conn.inputStream.use { it.readBytes() }
                    }
                } else if (url.startsWith("zip_thumb://")) {
                    val zipPath = url.substring(12)
                    try {
                        // 첫 장만 읽고 예외를 던져 중단시키는 방식 (효율성)
                        zipManager.streamAllImages(zipPath, {}) { _, b ->
                            bytes = b
                            throw Exception("STOP_READING")
                        }
                    } catch (e: Exception) { 
                        if (e.message != "STOP_READING") e.printStackTrace() 
                    }
                } else {
                    bytes = nasRepository.getFileContent(url)
                }
                
                if (bytes != null && bytes!!.isNotEmpty()) {
                    cacheFile.writeBytes(bytes!!)
                    bytes
                } else null
            } catch (e: Exception) { null }
        }
    }

    private fun md5(str: String): String = java.security.MessageDigest.getInstance("MD5")
        .digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
}
