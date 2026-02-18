package org.nas.comicsviewer.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = ComicDatabase.Schema,
            context = context,
            name = "comic_v2.db", // DB 이름 변경으로 강제 재생성 유도
            callback = object : AndroidSqliteDriver.Callback(ComicDatabase.Schema) {}
        )
    }
}
