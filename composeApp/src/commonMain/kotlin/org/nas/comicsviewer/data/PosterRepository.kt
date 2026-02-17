package org.nas.comicsviewer.data

interface PosterRepository {
    // Searches for a manga title and returns the cover image URL
    suspend fun searchPoster(title: String): String?
    
    // Downloads image bytes from a generic HTTP URL
    suspend fun downloadImageFromUrl(url: String): ByteArray?
}

expect fun providePosterRepository(): PosterRepository

// Improved title cleaner for better search results
fun cleanTitle(folderName: String): String {
    var title = folderName

    // 1. Remove content inside brackets [], parenthesis (), curly braces {}
    // Examples: [Author], (Complete), {Group}
    title = title.replace(Regex("\\[.*?\\]|\\(.*?\\)|\\{.*?\\}"), " ")

    // 2. Replace separators like underscores and dots with spaces
    title = title.replace(Regex("[_\\.]"), " ")

    // 3. Remove common keywords and volume/chapter info (Case insensitive)
    // English patterns
    title = title.replace(Regex("(?i)\\b(vol\\.?|volume)\\s*\\d+"), " ")
    title = title.replace(Regex("(?i)\\b(ch\\.?|chapter)\\s*\\d+"), " ")
    title = title.replace(Regex("(?i)\\bv\\d+"), " ") // v01
    title = title.replace(Regex("(?i)\\b(scan|digital|webtoon|complete|completed|end|fin|raw|eng)\\b"), " ")
    
    // Korean patterns (Suffixes mostly)
    title = title.replace(Regex("(\\d+권|\\d+화|완결|미완|연재|단편|eBook)"), " ")

    // 4. Remove extra special characters, keeping only text-relevant ones
    // Keep: Alphanumeric, Korean, Spaces, and minimal punctuation (! ? ' -)
    title = title.replace(Regex("[^a-zA-Z0-9가-힣\\s!?'\\-]"), "")

    // 5. Collapse multiple spaces into one and trim
    title = title.replace(Regex("\\s+"), " ").trim()

    // 6. Fallback: If cleaning resulted in empty string (rare), revert to original
    if (title.isBlank()) return folderName

    return title
}