package org.nas.comicsviewer.data

interface PosterRepository {
    // Searches for a manga title and returns the cover image URL
    suspend fun searchPoster(title: String): String?
    
    // Downloads image bytes from a generic HTTP URL
    suspend fun downloadImageFromUrl(url: String): ByteArray?
}

expect fun providePosterRepository(): PosterRepository

// Helper to clean folder names for better search results
fun cleanTitle(folderName: String): String {
    // Remove content inside brackets [] and parenthesis ()
    var title = folderName.replace(Regex("\\[.*?\\]"), "")
    title = title.replace(Regex("\\(.*?\\)"), "")
    // Remove specialized terms often found in filenames
    title = title.replace(Regex("(?i)(v\\d+|ch\\d+|scan|complete|완결)"), "")
    return title.trim()
}