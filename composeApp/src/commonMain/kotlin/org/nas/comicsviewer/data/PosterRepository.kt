package org.nas.comicsviewer.data

import org.nas.comicsviewer.data.ComicDatabase

interface PosterRepository {
    suspend fun searchPoster(title: String): String?
    suspend fun downloadImageFromUrl(url: String): ByteArray?
    fun setDatabase(database: ComicDatabase)
}

expect fun providePosterRepository(): PosterRepository

fun cleanTitle(folderName: String): String {
    var title = folderName

    // 1. 기초 청소: 확장자 제거
    val extensions = listOf(".zip", ".cbz", ".rar", ".pdf")
    var cleanName = title
    extensions.forEach { cleanName = cleanName.removeSuffix(it) }
    title = cleanName
    
    // 2. 대괄호 [], 중괄호 {} 내부 내용 무조건 제거 (작가, 그룹 등 메타데이터)
    title = title.replace(Regex("""\[[^\]]*\]"""), " ")
    title = title.replace(Regex("""\{[^}]*\}"""), " ")

    // 3. 소괄호 () 처리: 연도나 특정 키워드가 포함된 경우 제거
    title = title.replace(Regex("""\s*\([^)]*\d{4}[^)]*\)"""), " ") // 연도 포함 (2023)
    val metaKeywords = listOf(
        "Digital", "Scan", "c2c", "RAW", "ENG", "KOR", "JPN", "Complete", "Ongoing", 
        "re-edited", "한글", "번역", "정발", "스캔", "완결", "단편", "애니", "만화", "애니메이션"
    )
    val metaPattern = metaKeywords.joinToString("|")
    title = title.replace(Regex("""\s*\((?:$metaPattern)[^)]*\)""", RegexOption.IGNORE_CASE), " ")
    
    // 4. 불필요한 구분 기호 제거 및 공백화
    title = title.replace(Regex("""[._\-\u2010-\u2015]"""), " ")

    // 5. 권수/화수 정보 제거 (v01, 1권, 01화 등)
    val volKeywords = listOf(
        "vol", "volume", "ch", "chapter", "episode", "ep", "season", "part",
        "v", "ver", "권", "화", "부", "시즌", "회"
    )
    val volPattern = volKeywords.joinToString("|")
    title = title.replace(Regex("""(?i)\b(?:$volPattern)\s*\d+.*$"""), " ")
    
    // 6. 제목 끝에 붙는 단순 숫자 제거
    title = title.replace(Regex("""\s+\d+\s*$"""), " ")

    // 7. 검색 방해 특수 문자 제거
    title = title.replace(Regex("""[!?@#$%^&*+=\\/|:;~]"""), " ")

    // 8. 공백 정리
    title = title.replace(Regex("""\s+"""), " ").trim()

    // 9. 결과가 너무 짧으면 안전장치 작동
    if (title.length < 2) {
        val fallback = folderName.replace(Regex("""\[[^\]]*\]"""), "").replace(Regex("""\s+"""), " ").trim()
        return if (fallback.length >= 2) fallback else folderName
    }

    return title
}
