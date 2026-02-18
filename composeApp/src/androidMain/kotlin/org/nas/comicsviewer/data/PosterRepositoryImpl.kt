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
        println("DEBUG_POSTER: [STEP 1] Start scanning path: $path")
        
        // 1. DB 캐시 확인 및 복구
        try {
            val cached = database?.posterCacheQueries?.getPoster(pathHash)?.executeAsOneOrNull()
            if (cached?.poster_url != null && cached.poster_url != NOT_FOUND) {
                println("DEBUG_POSTER: [CACHE] Found in DB: ${cached.poster_url}")
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
        } catch (e: Exception) {
            println("DEBUG_POSTER: [ERROR] Cache check fail: ${e.message}")
        }

        // 2. 실시간 스캔 및 데이터 수집
        try {
            val files = nasRepository.listFiles(path)
            println("DEBUG_POSTER: [STEP 2] Files in folder: ${files.map { it.name }}")

            var metadata = ComicMetadata()
            var posterPath: String? = null

            // A. kavita.yaml 파싱
            val yamlFile = files.find { it.name.lowercase() == "kavita.yaml" }
            if (yamlFile != null) {
                println("DEBUG_POSTER: [STEP 3] Found kavita.yaml, reading content...")
                val content = String(nasRepository.getFileContent(yamlFile.path), Charsets.UTF_8)
                
                metadata = metadata.copy(
                    title = parseYamlValue(content, "localizedName") ?: parseYamlValue(content, "name"),
                    author = parseYamlValue(content, "writer"),
                    summary = parseYamlValue(content, "summary")
                )
                
                posterPath = parseYamlValue(content, "poster_url") ?: 
                             parseYamlValue(content, "cover")?.let { name -> 
                                 files.find { it.name.equals(name, ignoreCase = true) }?.path 
                             }
                println("DEBUG_POSTER: [YAML RESULT] Title: ${metadata.title}, Author: ${metadata.author}, Poster: $posterPath")
            }

            // B. 폴더 내 이미지 검색 (YAML에 포스터 정보가 없을 때만)
            if (posterPath == null) {
                val common = listOf("poster", "cover", "folder", "thumbnail")
                posterPath = files.find { f ->
                    val nameLow = f.name.lowercase().substringBeforeLast(".")
                    common.contains(nameLow) && (f.name.lowercase().endsWith(".jpg") || f.name.lowercase().endsWith(".png"))
                }?.path
            }

            // 3. SQLite에 통합 정보 저장 (구분자 ||| 사용)
            val combinedData = "${posterPath ?: ""}|||${metadata.title ?: ""}|||${metadata.author ?: ""}|||${metadata.summary ?: ""}"
            database?.posterCacheQueries?.upsertPoster(
                title_hash = pathHash,
                poster_url = combinedData,
                cached_at = System.currentTimeMillis()
            )
            println("DEBUG_POSTER: [STEP 4] Metadata saved to SQLite")

            return@withContext metadata.copy(posterUrl = posterPath)
        } catch (e: Exception) {
            println("DEBUG_POSTER: [FATAL] Global scan error: ${e.message}")
            ComicMetadata()
        }
    }

    private fun parseYamlValue(content: String, key: String): String? {
        val regex = Regex("""^\s*$key\s*:\s*(.*)$""", RegexOption.MULTILINE)
        val value = regex.find(content)?.groupValues?.get(1)?.trim()?.removeSurrounding("'")?.removeSurrounding("\"")
        return if (value.isNullOrBlank()) null else value
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? {
        if (url.isBlank()) return null
        val urlHash = md5(url)
        val cacheFile = File(cacheDir, "img_$urlHash")
        if (cacheFile.exists()) return cacheFile.readBytes()

        return withContext(Dispatchers.IO) {
            try {
                if (url.startsWith("http")) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.apply { connectTimeout = 8000; readTimeout = 8000 }
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
            } catch (e: Exception) { 
                println("DEBUG_POSTER: [IMAGE ERROR] Download failed for $url: ${e.message}")
                null 
            }
        }
    }

    private fun md5(str: String): String = java.security.MessageDigest.getInstance("MD5")
        .digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
}
