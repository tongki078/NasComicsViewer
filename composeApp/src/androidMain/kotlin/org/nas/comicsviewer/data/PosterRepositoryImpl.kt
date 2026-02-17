package org.nas.comicsviewer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

actual fun providePosterRepository(): PosterRepository = AndroidPosterRepository()

class AndroidPosterRepository : PosterRepository {
    private val apiUrl = "https://graphql.anilist.co"

    override suspend fun searchPoster(title: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Remove weird chars
                val cleanQuery = title.trim()
                if (cleanQuery.length < 2) return@withContext null

                val query = """
                    query {
                        Media(search: "$cleanQuery", type: MANGA, sort: SEARCH_MATCH) {
                            coverImage {
                                extraLarge
                            }
                        }
                    }
                """.trimIndent().replace("\n", " ").replace("\"", "\\\"")

                val jsonBody = "{\"query\": \"$query\"}"
                
                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.doInput = true

                conn.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode == 200) {
                    val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(response)
                    val data = json.optJSONObject("data")
                    val media = data?.optJSONObject("Media")
                    val cover = media?.optJSONObject("coverImage")
                    cover?.optString("extraLarge")
                } else {
                    null
                }
            } catch (e: Exception) {
                // e.printStackTrace() // Silent fail to not spam logs
                null
            }
        }
    }

    override suspend fun downloadImageFromUrl(url: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val imageUrl = URL(url)
                val conn = imageUrl.openConnection() as HttpURLConnection
                conn.doInput = true
                conn.connect()
                
                if (conn.responseCode == 200) {
                    conn.inputStream.use { it.readBytes() }
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}