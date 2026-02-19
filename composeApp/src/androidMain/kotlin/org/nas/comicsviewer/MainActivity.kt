package org.nas.comicsviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import org.nas.comicsviewer.data.ComicDatabase
import org.nas.comicsviewer.data.DatabaseDriverFactory
import org.nas.comicsviewer.data.provideNasRepository
import org.nas.comicsviewer.data.providePosterRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val databaseDriverFactory = DatabaseDriverFactory(this)
        val database = ComicDatabase(databaseDriverFactory.createDriver())
        val nasRepository = provideNasRepository()
        val posterRepository = providePosterRepository(this)

        setContent {
            App(database, nasRepository, posterRepository)
        }
    }
}
