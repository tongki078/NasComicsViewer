package org.nas.comicsviewer

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform