package org.nas.comicsviewer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

actual fun providePosterRepository(): PosterRepository = AndroidPosterRepository.getInstance()

class AndroidPosterRepository private constructor() : PosterRepository {
    private val apiUrl = "https://graphql.anilist.co"
    private var database: ComicDatabase? = null
    private val EMPTY_URL = "NOT_FOUND"

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

    override suspend fun searchPoster(title: String): String? {
        val cleanQuery = title.trim()
        if (cleanQuery.length < 2) return null
        
        val titleHash = md5(cleanQuery)
        
        // 1. DB 캐시 확인
        val cachedResult = database?.posterCacheQueries?.getPoster(titleHash)?.executeAsOneOrNull()
        if (cachedResult?.poster_url != null) {
            return if (cachedResult.poster_url == EMPTY_URL) null else cachedResult.poster_url
        }

        // 2. API 검색
        return withContext(Dispatchers.IO) {
            val result = performSearchWithRetry(cleanQuery)
            
            // 결과 저장 (검색 실패 시에도 EMPTY_URL 저장하여 반복 검색 방지)
            try {
                database?.posterCacheQueries?.upsertPoster(
                    title_hash = titleHash,
                    poster_url = result ?: EMPTY_URL,
                    cached_at = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            result
        }
    }

    private suspend fun performSearchWithRetry(query: String): String? {
        // 시도 1: 전체 제목
        var result = executeAniListQuery(query)
        if (result != null) return result

        // 시도 2: 너무 긴 경우 잘라내어 시도 (앞에서부터 3단어 정도)
        val words = query.split(" ")
        if (words.size > 2) {
            val shortened = words.take(3).joinToString(" ")
            if (shortened != query) {
                delay(100) // API 매너
                result = executeAniListQuery(shortened)
                if (result != null) return result
            }
        }
        
        return null
    }

    private fun executeAniListQuery(search: String): String? {
        try {
            val query = """
                query (${'$'}search: String) {
                    Page(page: 1, perPage: 1) {
                        media(search: ${'$'}search, type: MANGA, isAdult: false, sort: SEARCH_MATCH) {
                            coverImage { extraLarge }
                        }
                    }
                }
            """.trimIndent()

            val jsonBody = JSONObject().apply {
                put("query", query)
                put("variables", JSONObject().put("search", search))
            }

            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
            }

            conn.outputStream.use { it.write(jsonBody.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val mediaList = JSONObject(response).optJSONObject("data")
                    ?.optJSONObject("Page")?.optJSONArray("media")
                if (mediaList != null && mediaList.length() > 0) {
                    return mediaList.getJSONObject(0).getJSONObject("coverImage").optString("extraLarge")
                }
            } else if (responseCode == 429) {
                // Rate limit hit
                System.err.println("AniList Rate Limit Hit!")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? {
        if (url == EMPTY_URL) return null
        val urlHash = md5(url)
        val cacheFile = File(cacheDir, "img_$urlHash")
        
        if (cacheFile.exists()) {
            try { return cacheFile.readBytes() } catch (e: Exception) {}
        }

        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                }
                
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        try { cacheFile.writeBytes(bytes) } catch (e: Exception) {}
                    }
                    bytes
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun md5(str: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
