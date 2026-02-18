package org.nas.comicsviewer.data

class ComicDatabaseProvider(databaseDriverFactory: DatabaseDriverFactory) {
    val database = ComicDatabase(databaseDriverFactory.createDriver())
}
