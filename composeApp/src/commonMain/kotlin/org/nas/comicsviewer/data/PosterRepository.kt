package org.nas.comicsviewer.data

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
    suspend fun downloadImageFromUrl(url: String): ByteArray?
    fun setDatabase(database: ComicDatabase)
    suspend fun insertRecentSearch(query: String)
    suspend fun getRecentSearches(): List<String>
    suspend fun clearRecentSearches()
}

expect fun providePosterRepository(): PosterRepository

fun cleanTitle(folderName: String): String {
    // 1. 확장자 제거
    var title = folderName
    val extensions = listOf(".zip", ".cbz", ".rar", ".pdf")
    var cleanName = title
    extensions.forEach { cleanName = cleanName.removeSuffix(it) }
    title = cleanName

    // 2. 대괄호/중괄호 내용 제거 (ex: [작가명], {출판사})
    title = title.replace(Regex("""\[[^\]]*\]"""), " ")
    title = title.replace(Regex("""\{[^}]*\}"""), " ")

    // 3. 연도 패턴 제거 (ex: (2021))
    title = title.replace(Regex("""\s*\([^)]*\d{4}[^)]*\)"""), " ")

    // 4. 메타데이터 키워드 제거 (Digital, Scan, 완결 등)
    val metaKeywords = listOf("Digital", "Scan", "c2c", "RAW", "ENG", "KOR", "JPN", "Complete", "Ongoing", "한글", "번역", "정발", "스캔", "완결", "단편")
    val metaPattern = metaKeywords.joinToString("|")
    title = title.replace(Regex("""\s*\((?:$metaPattern)[^)]*\)""", RegexOption.IGNORE_CASE), " ")

    // 5. 특수문자 및 구분자 공백 처리
    title = title.replace(Regex("""[._\-\u2010-\u2015]"""), " ")

    // 6. 권수/화수 패턴 제거 (가장 마지막에 오는 권수만 제거)
    // "나루토 1권" -> "나루토", "원피스 100화" -> "원피스"
    val volKeywords = listOf("vol", "volume", "ch", "chapter", "v", "ver", "권", "화", "부")
    val volPattern = volKeywords.joinToString("|")
    title = title.replace(Regex("""(?i)\b(?:$volPattern)\s*\d+.*$"""), " ")
    
    // 단순 숫자만 있는 경우 제거 (ex: "나루토 01" -> "나루토")
    title = title.replace(Regex("""\s+\d+\s*$"""), " ")

    // 7. 남은 특수문자 정리
    title = title.replace(Regex("""[!?@#$%^&*+=\\/|:;~]"""), " ")

    // 8. 다중 공백 -> 단일 공백 및 앞뒤 공백 제거
    return title.replace(Regex("""\s+"""), " ").trim()
}
