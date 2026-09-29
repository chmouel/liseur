package com.chmouel.liseur.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A saved catalog's downloaded books moving to another connection to the same account. */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BrowseServerHandoffTest {

    private lateinit var db: LiseurDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a moved book keeps its url and the id it was saved under stays taken`() = runTest {
        val books = db.bookDao()
        books.upsert(
            Book(
                url = "browse:7:calibre:42",
                title = "Kept",
                author = null,
                coverPath = null,
                source = null,
                addedAt = 0L,
                lastOpenedAt = null,
                localUri = "file:///books/kept.epub",
                remoteUuid = "calibre:42",
                browseServerId = 7L,
                coverUrl = "https://books.example.com/cover/42#liseur-browse=old",
            ),
        )

        books.moveToBrowseServer("browse:7:calibre:42", 3L, "https://books.example.com/cover/42#liseur-browse=new")

        val moved = books.getByUrl("browse:7:calibre:42")!!
        assertEquals(3L, moved.browseServerId)
        assertEquals("calibre:42", moved.remoteUuid)
        assertEquals("https://books.example.com/cover/42#liseur-browse=new", moved.coverUrl)
        assertEquals(8L, db.remoteServerDao().nextBrowseId())
    }
}
