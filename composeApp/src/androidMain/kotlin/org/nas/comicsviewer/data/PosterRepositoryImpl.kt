package org.nas.comicsviewer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

actual fun providePosterRepository(): PosterRepository = AndroidPosterRepository()

class AndroidPosterRepository : PosterRepository {
    private val apiUrl = "https://graphql.anilist.co"
    private var database: ComicDatabase? = null
    
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
        if (cachedResult?.poster_url != null) return cachedResult.poster_url

        // 2. API 검색
        return withContext(Dispatchers.IO) {
            val result = performSearchWithRetry(cleanQuery)
            if (result != null) {
                try {
                    database?.posterCacheQueries?.upsertPoster(
                        title_hash = titleHash,
                        poster_url = result,
                        cached_at = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            result
        }
    }

    private suspend fun performSearchWithRetry(query: String): String? {
        var currentQuery = query
        val words = query.split(" ")
        
        for (i in words.size downTo 1) {
            currentQuery = words.take(i).joinToString(" ").trim()
            if (currentQuery.length < 2) break
            
            val result = executeAniListQuery(currentQuery)
            if (result != null) return result
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
                connectTimeout = 5000
                readTimeout = 5000
            }

            conn.outputStream.use { it.write(jsonBody.toString().toByteArray()) }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val mediaList = JSONObject(response).optJSONObject("data")
                    ?.optJSONObject("Page")?.optJSONArray("media")
                if (mediaList != null && mediaList.length() > 0) {
                    return mediaList.getJSONObject(0).getJSONObject("coverImage").optString("extraLarge")
                }
            } else if (conn.responseCode == 429) {
                // Too many requests - could implement backoff if needed
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? {
        val urlHash = md5(url)
        val cacheFile = File(cacheDir, "img_$urlHash")
        
        if (cacheFile.exists()) {
            try { return cacheFile.readBytes() } catch (e: Exception) {}
        }

        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.apply {
                    connectTimeout = 5000
                    readTimeout = 10000
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
