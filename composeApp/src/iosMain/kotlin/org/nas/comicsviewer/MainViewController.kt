package org.nas.comicsviewer

import androidx.compose.ui.window.ComposeUIViewController
import org.nas.comicsviewer.data.ComicDatabase
import org.nas.comicsviewer.data.DatabaseDriverFactory
import org.nas.comicsviewer.data.provideNasRepository
import org.nas.comicsviewer.data.providePosterRepository

fun MainViewController() = ComposeUIViewController { 
    val databaseDriverFactory = DatabaseDriverFactory()
    val database = ComicDatabase(databaseDriverFactory.createDriver())
    val nasRepository = provideNasRepository()
    val posterRepository = providePosterRepository(null) // iOS에서는 Context가 필요 없음

    App(database, nasRepository, posterRepository) 
}