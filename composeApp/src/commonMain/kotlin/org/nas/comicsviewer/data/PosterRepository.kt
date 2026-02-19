package org.nas.comicsviewer.data

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ComicMetadata(
    val title: String? = null,
    val author: String? = null,
    val summary: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null
)

interface PosterRepository {
    suspend fun getImage(url: String): ImageBitmap?
    suspend fun insertRecentSearch(query: String)
    suspend fun getRecentSearches(): List<String>
    suspend fun clearRecentSearches()
}

// Context를 필요로 하는 Android와 필요 없는 iOS를 모두 지원하기 위해 Any? 타입 사용
expect fun providePosterRepository(context: Any?): PosterRepository

fun cleanTitle(folderName: String): String {
    // ... (rest of the file is unchanged)
    var title = folderName
    val extensions = listOf(".zip", ".cbz", ".rar", ".pdf")
    var cleanName = title
    extensions.forEach { cleanName = cleanName.removeSuffix(it) }
    title = cleanName

    title = title.replace(Regex("""\[[^\]]*\]"""), " ")
    title = title.replace(Regex("""\{[^}]*\}"""), " ")
    title = title.replace(Regex("""\s*\([^)]*\d{4}[^)]*\)"""), " ")

    val metaKeywords = listOf("Digital", "Scan", "c2c", "RAW", "ENG", "KOR", "JPN", "Complete", "Ongoing", "한글", "번역", "정발", "스캔", "완결", "단편")
    val metaPattern = metaKeywords.joinToString("|")
    title = title.replace(Regex("""\s*\((?:$metaPattern)[^)]*\)""", RegexOption.IGNORE_CASE), " ")

    title = title.replace(Regex("""[._\-\u2010-\u2015]"""), " ")

    val volKeywords = listOf("vol", "volume", "ch", "chapter", "v", "ver", "권", "화", "부")
    val volPattern = volKeywords.joinToString("|")
    title = title.replace(Regex("""(?i)\b(?:$volPattern)\s*\d+.*$"""), " ")
    
    title = title.replace(Regex("""\s+\d+\s*$"""), " ")

    title = title.replace(Regex("""[!?@#$%^&*+=\\/|:;~]"""), " ")

    return title.replace(Regex("""\s+"""), " ").trim()
}