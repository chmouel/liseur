package com.chmouel.liseur.insights

import android.util.AtomicFile
import java.io.File
import java.util.UUID

internal data class ReportingChoice(val enabled: Boolean, val installation: String)

internal class ReportingPreference(directory: File) {
    private val file = AtomicFile(File(directory, "usage-reporting"))

    fun read(): ReportingChoice = try {
        val parts = file.openRead().bufferedReader().use { it.readText() }.split('\n')
        require(parts.size == 2 && parts[0] in listOf("true", "false"))
        ReportingChoice(parts[0].toBoolean(), UUID.fromString(parts[1]).toString())
    } catch (_: java.io.FileNotFoundException) {
        try {
            ReportingChoice(true, UUID.randomUUID().toString()).also(::write)
        } catch (_: Exception) {
            ReportingChoice(false, UUID.randomUUID().toString())
        }
    } catch (_: Exception) {
        ReportingChoice(false, UUID.randomUUID().toString())
    }

    fun write(choice: ReportingChoice) {
        val stream = file.startWrite()
        try {
            stream.write("${choice.enabled}\n${choice.installation}".toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
