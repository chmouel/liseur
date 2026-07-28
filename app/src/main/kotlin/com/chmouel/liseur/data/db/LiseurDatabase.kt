package com.chmouel.liseur.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [ReadingProgress::class, Book::class, LibraryFolder::class],
    version = 2,
    exportSchema = true,
)
abstract class LiseurDatabase : RoomDatabase() {
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookDao(): BookDao
    abstract fun libraryFolderDao(): LibraryFolderDao

    companion object {
        /** Adds the measured reading speed used for time-left estimates. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE reading_progress ADD COLUMN reading_speed REAL",
                )
            }
        }
    }
}
