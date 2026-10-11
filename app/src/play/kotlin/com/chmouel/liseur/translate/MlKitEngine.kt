package com.chmouel.liseur.translate

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What Liseur asks of ML Kit, so the service is tested without the SDK. Codes are ML Kit's. */
internal interface MlKitEngine {
    /** Every language ML Kit translates. */
    fun languages(): Set<String>

    /** The languages whose models are on the phone. */
    suspend fun downloaded(): Set<String>

    /** Fetches the models for both languages, over Wi-Fi only. Throws [MlKitDownloadFailed]. */
    suspend fun download(source: String, target: String)

    suspend fun delete(code: String)

    /** A translator for a pair whose models are on the phone. */
    fun open(source: String, target: String): MlKitTranslator
}

internal interface MlKitTranslator {
    /** Throws [TranslationError]. */
    suspend fun translate(text: String): String

    fun close()
}

internal class MlKitDownloadFailed(val reason: Reason) : Exception("ML Kit could not download a model: $reason") {
    enum class Reason { Network, Space, Other }
}

/**
 * ML Kit itself. Its startup provider is removed from the manifest, so
 * nothing of it runs until the reader picks it and Liseur first asks it
 * something.
 */
internal class SdkMlKitEngine(context: Context) : MlKitEngine {
    private val app = context.applicationContext
    private val executor: Executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var started = false

    private val models: RemoteModelManager
        get() {
            start()
            return RemoteModelManager.getInstance()
        }

    private fun start() {
        if (started) return
        synchronized(this) {
            if (!started) {
                MlKit.initialize(app)
                started = true
            }
        }
    }

    override fun languages(): Set<String> = TranslateLanguage.getAllLanguages().toSet()

    override suspend fun downloaded(): Set<String> = try {
        models.getDownloadedModels(TranslateRemoteModel::class.java).await().mapTo(mutableSetOf()) { it.language }
    } catch (e: MlKitException) {
        throw TranslationError.DeviceFailed("models not listed (${e.errorCode})")
    }

    override suspend fun download(source: String, target: String) {
        val wifi = DownloadConditions.Builder().requireWifi().build()
        try {
            for (code in listOf(source, target)) models.download(model(code), wifi).await()
        } catch (e: MlKitException) {
            throw MlKitDownloadFailed(
                when (e.errorCode) {
                    MlKitException.NOT_ENOUGH_SPACE -> MlKitDownloadFailed.Reason.Space
                    MlKitException.NETWORK_ISSUE, MlKitException.UNAVAILABLE -> MlKitDownloadFailed.Reason.Network
                    else -> MlKitDownloadFailed.Reason.Other
                },
            )
        }
    }

    override suspend fun delete(code: String) {
        try {
            models.deleteDownloadedModel(model(code)).await()
        } catch (e: MlKitException) {
            throw TranslationError.DeviceFailed("model not deleted (${e.errorCode})")
        }
    }

    override fun open(source: String, target: String): MlKitTranslator {
        start()
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).setExecutor(executor).build(),
        )
        return object : MlKitTranslator {
            override suspend fun translate(text: String): String = try {
                translator.translate(text).await()
            } catch (e: MlKitException) {
                throw when (e.errorCode) {
                    MlKitException.NOT_FOUND, MlKitException.FAILED_PRECONDITION -> TranslationError.NotDownloaded()
                    else -> TranslationError.DeviceFailed("ML Kit error ${e.errorCode}")
                }
            }

            override fun close() = translator.close()
        }
    }

    private fun model(code: String) = TranslateRemoteModel.Builder(code).build()

    /** The task's result; cancelling stops the wait, not ML Kit's work, which the SDK finishes or drops. */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener(executor) { task ->
            val error = task.exception
            when {
                error != null -> continuation.resumeWithException(error)
                task.isCanceled -> continuation.cancel()
                else -> continuation.resume(task.result)
            }
        }
    }
}
