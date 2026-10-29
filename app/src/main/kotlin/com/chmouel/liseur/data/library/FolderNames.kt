package com.chmouel.liseur.data.library

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The name the document provider gives a watched folder tree, or null
 * when the provider is gone or the permission was revoked.
 */
suspend fun folderDisplayName(context: Context, treeUri: String): String? = withContext(Dispatchers.IO) {
    try {
        val tree = treeUri.toUri()
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(
            folder,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
