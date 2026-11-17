package com.chmouel.liseur.data.liseursync

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EvaluationWriterIdentity(context: Context) {
    // Restored databases must author new edits in a different namespace from the original phone.
    private val file = AtomicFile(File(context.noBackupFilesDir, "evaluation-writer-id"))

    suspend fun current(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                file.readFully().toString(Charsets.UTF_8)
            } catch (_: FileNotFoundException) {
                val id = UUID.randomUUID().toString()
                val output = file.startWrite()
                try {
                    output.write(id.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    throw e
                }
                id
            }
        }
    }

    private companion object {
        val lock = Mutex()
    }
}
