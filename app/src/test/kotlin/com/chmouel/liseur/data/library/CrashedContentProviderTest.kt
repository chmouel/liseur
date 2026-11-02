package com.chmouel.liseur.data.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.toDebugDescription
import org.readium.r2.shared.util.content.ContentResolverError
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A content:// book whose provider answers `openFile` with an exception
 * of its own choosing. Readium 3.4.0 opens the stream outside the code
 * that turns a provider failure into a read error, so this used to throw
 * out of `AssetRetriever.retrieve()` and killed the reader on open.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class CrashedContentProviderTest {

    @Test
    fun `opening a book from a provider that fails is a read error`() = runTest {
        Robolectric.buildContentProvider(Crashed::class.java).create(AUTHORITY)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val retriever = permissionSafeAssetRetriever(context.contentResolver, DefaultHttpClient())

        val failure = retriever.retrieve(AbsoluteUrl(BOOK_URL)!!).failureOrNull()

        assertNotNull(failure)
        assertTrue(
            "expected a content provider error, got ${failure!!.toDebugDescription()}",
            failure.causes().any { it is ContentResolverError.NotAvailable },
        )
    }

    private fun Error.causes(): Sequence<Error> =
        generateSequence(this) { it.cause }

    class Crashed : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            throw IllegalStateException("Provider is in a bad state for $uri")
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            args: Array<out String>?, sort: String?,
        ): Cursor? = null
        // No type, so Readium reads the bytes to sniff the format.
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, args: Array<out String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "com.chmouel.liseur.test.crashed"
        const val BOOK_URL = "content://$AUTHORITY/book.epub"
    }
}
