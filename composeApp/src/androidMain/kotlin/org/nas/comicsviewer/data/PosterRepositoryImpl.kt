package org.nas.comicsviewer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

actual fun providePosterRepository(): PosterRepository = AndroidPosterRepository.getInstance()

class AndroidPosterRepository private constructor() : PosterRepository {
    private var database: ComicDatabase? = null
    private val nasRepository: NasRepository by lazy { provideNasRepository() }

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
        try {
            // 폴더가 아닌 파일(ZIP) 경로가 들어온 경우 부모 폴더 경로를 사용
            val targetPath = if (path.lowercase().endsWith(".zip") || path.lowercase().endsWith(".cbz")) {
                path.substringBeforeLast("/") + "/"
            } else path

            val files = nasRepository.listFiles(targetPath)
            if (files.isEmpty()) return@withContext ComicMetadata()

            var metadata = ComicMetadata()
            
            // 1. kavita.yaml 찾기
            val yamlFile = files.find { it.name.lowercase() == "kavita.yaml" }
            if (yamlFile != null) {
                val content = String(nasRepository.getFileContent(yamlFile.path))
                metadata = metadata.copy(
                    title = parseYamlValue(content, "localizedName") ?: parseYamlValue(content, "name"),
                    author = parseYamlValue(content, "writer"),
                    summary = parseYamlValue(content, "summary")
                )
                
                // 지정된 커버 이미지 찾기
                val coverFileName = parseYamlValue(content, "cover")
                if (coverFileName != null) {
                    val coverFile = files.find { it.name.equals(coverFileName, ignoreCase = true) }
                    if (coverFile != null) {
                        return@withContext metadata.copy(posterUrl = coverFile.path)
                    }
                }
            }

            // 2. YAML에 커버 지정이 없거나 YAML이 없는 경우: 폴더 내 공통 이미지 찾기
            val commonPosterNames = listOf("poster.jpg", "poster.png", "cover.jpg", "cover.png", "folder.jpg")
            val posterFile = files.find { f -> commonPosterNames.any { it.equals(f.name, ignoreCase = true) } }
            
            if (posterFile != null) {
                return@withContext metadata.copy(posterUrl = posterFile.path)
            }

            // 3. 마지막 수단: 폴더 내 첫 번째 이미지 파일 사용 (파일명 오름차순)
            val firstImage = files.filter { !it.isDirectory }
                .sortedBy { it.name }
                .find { it.name.lowercase().let { n -> n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") } }
                
            return@withContext metadata.copy(posterUrl = firstImage?.path)
        } catch (e: Exception) {
            ComicMetadata()
        }
    }

    private fun parseYamlValue(content: String, key: String): String? {
        // "key: value" 또는 "key: 'value'" 형태 대응
        val regex = Regex("""^$key\s*:\s*["']?([^"'\n\r]+)["']?""", RegexOption.MULTILINE)
        return regex.find(content)?.groupValues?.get(1)?.trim()
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? {
        val urlHash = md5(url)
        val cacheFile = File(cacheDir, "img_$urlHash")
        
        if (cacheFile.exists()) {
            try { return cacheFile.readBytes() } catch (e: Exception) {}
        }

        return withContext(Dispatchers.IO) {
            try {
                val bytes = nasRepository.getFileContent(url)
                if (bytes.isNotEmpty()) {
                    cacheFile.writeBytes(bytes)
                }
                bytes
            } catch (e: Exception) { null }
        }
    }

    private fun md5(str: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
