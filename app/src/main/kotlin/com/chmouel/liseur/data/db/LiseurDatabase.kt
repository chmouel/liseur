package com.chmouel.liseur.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ReadingProgress::class, Book::class, LibraryFolder::class],
    version = 1,
    exportSchema = true,
)
abstract class LiseurDatabase : RoomDatabase() {
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookDao(): BookDao
    abstract fun libraryFolderDao(): LibraryFolderDao
}
