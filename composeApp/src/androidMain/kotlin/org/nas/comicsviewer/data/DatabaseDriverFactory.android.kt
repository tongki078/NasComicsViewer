package org.nas.comicsviewer.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = ComicDatabase.Schema,
            context = context,
            name = "comic_v3.db", // 스키마 변경 반영을 위해 DB 이름 변경
            callback = object : AndroidSqliteDriver.Callback(ComicDatabase.Schema) {}
        )
    }
}
