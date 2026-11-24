package com.chmouel.liseur.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Result of requesting Google Drive OAuth authorization. */
sealed interface DriveAuthResult {
    /** Valid short-lived OAuth2 access token covering both required Drive scopes. */
    data class Authorized(
        val accessToken: String,
        val grantedScopes: Set<String> = DriveAuthClient.REQUIRED_SCOPES.toSet(),
    ) : DriveAuthResult

    /**
     * Play Services requires interactive user consent or account selection.
     *
     * Interactive UI launches [pendingIntent] with `StartIntentSenderForResult`;
     * background workers pause Drive sync and transition the account to
     * `NEEDS_RECONNECT`.
     */
    data class NeedsResolution(val pendingIntent: PendingIntent?) : DriveAuthResult

    /** Authorization failed; [requiresReconnect] signals revoked/missing consent. */
    data class Failed(
        val reason: String,
        val requiresReconnect: Boolean = false,
        val cause: Throwable? = null,
    ) : DriveAuthResult
}

/**
 * Narrow Google Drive OAuth interface so account management and sync workers
 * can be tested without Google Play Services.
 *
 * Implementations request ONLY [SCOPE_DRIVE_FILE] and [SCOPE_DRIVE_APPDATA]
 * with no client secret and no embedded OAuth Client ID (ADR-0078).
 */
interface DriveAuthClient {
    /**
     * Requests a Google Drive access token.
     *
     * @param accountEmail Optional Google account email to target when refreshing
     *   or reconnecting a known account. Ignored when [selectAccount] is true.
     * @param selectAccount When true, requests Play Services' account chooser so
     *   the reader can select a different Google account.
     */
    suspend fun authorize(
        accountEmail: String? = null,
        selectAccount: Boolean = false,
    ): DriveAuthResult

    /**
     * Attempts silent token acquisition for background sync. Never launches UI;
     * returns [DriveAuthResult.NeedsResolution] when interactive consent is needed.
     */
    suspend fun authorizeSilently(accountEmail: String? = null): DriveAuthResult

    /** Extracts a [DriveAuthResult] from the Activity result returned by Play Services. */
    fun parseAuthorizationResult(data: Intent?): DriveAuthResult

    /** Clears a stale cached access token (for example after an HTTP 401). */
    suspend fun clearCachedToken(accessToken: String)

    companion object {
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val ANDROID_GOOGLE_ACCOUNT_TYPE = "com.google"

        val REQUIRED_SCOPES: List<String> = listOf(
            SCOPE_DRIVE_FILE,
            SCOPE_DRIVE_APPDATA,
        )

        fun hasAllRequiredScopes(grantedScopes: Collection<String>?): Boolean {
            if (grantedScopes.isNullOrEmpty()) return false
            val granted = grantedScopes.toSet()
            return REQUIRED_SCOPES.all { it in granted }
        }
    }
}

/**
 * Play Services [DriveAuthClient] backed by Android's [AuthorizationClient].
 *
 * Relies on Play Services matching the Android OAuth client by
 * `(packageName, SHA-1)` — no client secret and no embedded client ID.
 */
internal class PlayServicesDriveAuthClient(
    context: Context,
    private val clientProvider: () -> AuthorizationClient = {
        Identity.getAuthorizationClient(context.applicationContext)
    },
    private val executor: Executor = Executors.newSingleThreadExecutor(),
) : DriveAuthClient {

    override suspend fun authorize(
        accountEmail: String?,
        selectAccount: Boolean,
    ): DriveAuthResult = try {
        val request = buildAuthorizationRequest(accountEmail = accountEmail, selectAccount = selectAccount)
        val result = clientProvider().authorize(request).await(executor)
        mapAuthorizationResult(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        mapApiException(e)
    } catch (e: RuntimeException) {
        DriveAuthResult.Failed(
            reason = "Google Play Services authorization unavailable: ${e.message ?: e.javaClass.simpleName}",
            requiresReconnect = false,
            cause = e,
        )
    } catch (e: LinkageError) {
        DriveAuthResult.Failed(
            reason = "Google Play Services unavailable: ${e.javaClass.simpleName}",
            requiresReconnect = false,
            cause = e,
        )
    }

    override suspend fun authorizeSilently(accountEmail: String?): DriveAuthResult =
        authorize(accountEmail = accountEmail, selectAccount = false)

    override fun parseAuthorizationResult(data: Intent?): DriveAuthResult {
        if (data == null) {
            return DriveAuthResult.Failed(
                reason = "Authorization cancelled",
                requiresReconnect = false,
            )
        }
        return try {
            val result = clientProvider().getAuthorizationResultFromIntent(data)
            mapAuthorizationResult(result)
        } catch (e: ApiException) {
            mapApiException(e)
        } catch (e: RuntimeException) {
            DriveAuthResult.Failed(
                reason = "Could not read authorization result: ${e.message ?: e.javaClass.simpleName}",
                requiresReconnect = false,
                cause = e,
            )
        }
    }

    override suspend fun clearCachedToken(accessToken: String) {
        if (accessToken.isBlank()) return
        try {
            val request = ClearTokenRequest.builder().setToken(accessToken).build()
            clientProvider().clearToken(request).await(executor)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Clearing a stale token is best-effort; the subsequent authorize call decides the outcome.
        } catch (_: LinkageError) {
            // Play Services unavailable.
        }
    }

    internal companion object {
        /**
         * Builds the Play Services [AuthorizationRequest] with strictly the two
         * non-sensitive Drive scopes and no embedded server client ID.
         */
        fun buildAuthorizationRequest(
            accountEmail: String? = null,
            selectAccount: Boolean = false,
        ): AuthorizationRequest {
            val builder = AuthorizationRequest.builder()
                .setRequestedScopes(DriveAuthClient.REQUIRED_SCOPES.map(::Scope))
            if (selectAccount) {
                builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            } else if (!accountEmail.isNullOrBlank()) {
                builder.setAccount(Account(accountEmail.trim(), DriveAuthClient.ANDROID_GOOGLE_ACCOUNT_TYPE))
            }
            return builder.build()
        }

        internal fun mapAuthorizationResult(result: AuthorizationResult): DriveAuthResult {
            if (result.hasResolution()) {
                return DriveAuthResult.NeedsResolution(result.pendingIntent)
            }
            val token = result.accessToken?.trim().orEmpty()
            val granted = result.grantedScopes.toSet()
            if (token.isEmpty()) {
                return DriveAuthResult.Failed(
                    reason = "Google Play Services returned an empty access token",
                    requiresReconnect = true,
                )
            }
            if (!DriveAuthClient.hasAllRequiredScopes(granted)) {
                return DriveAuthResult.Failed(
                    reason = "Required Google Drive permissions were not granted",
                    requiresReconnect = true,
                )
            }
            return DriveAuthResult.Authorized(
                accessToken = token,
                grantedScopes = granted,
            )
        }

        internal fun mapApiException(exception: ApiException): DriveAuthResult {
            val requiresReconnect = when (exception.statusCode) {
                CommonStatusCodes.SIGN_IN_REQUIRED,
                CommonStatusCodes.RESOLUTION_REQUIRED,
                CommonStatusCodes.INVALID_ACCOUNT,
                -> true
                else -> false
            }
            val statusMessage = CommonStatusCodes.getStatusCodeString(exception.statusCode)
            return DriveAuthResult.Failed(
                reason = "Google authorization error ($statusMessage)",
                requiresReconnect = requiresReconnect,
                cause = exception,
            )
        }
    }
}

private suspend fun <T> Task<T>.await(executor: Executor): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(executor) { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.resumeWithException(
                IllegalStateException("Google Play Services authorization task was cancelled"),
            )
            else -> continuation.resume(task.result)
        }
    }
}
