package org.nas.comicsviewer.domain.model

// Represents metadata from ComicInfo.xml
data class ComicInfo(
    val series: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val writer: String? = null,
    val penciller: String? = null,
    val coverArtist: String? = null,
    val publisher: String? = null,
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null
)