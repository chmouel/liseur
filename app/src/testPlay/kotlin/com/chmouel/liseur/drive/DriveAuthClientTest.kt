package com.chmouel.liseur.drive

import android.app.Application
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DriveAuthClientTest {

    @Test
    fun `authorization request asks strictly for drive file and drive appdata with no server client id`() {
        val request = PlayServicesDriveAuthClient.buildAuthorizationRequest()

        val scopeUris = request.requestedScopes.map { it.scopeUri }
        assertEquals(
            listOf(DriveAuthClient.SCOPE_DRIVE_FILE, DriveAuthClient.SCOPE_DRIVE_APPDATA),
            scopeUris,
        )
        assertNull(request.serverClientId)
        assertFalse(request.isOfflineAccessRequested)
        assertNull(request.account)
        assertEquals(AuthorizationRequest.Prompt.NOT_SET, request.prompt)
    }

    @Test
    fun `authorization request targets known account when refreshing and forces chooser when switching`() {
        val refresh = PlayServicesDriveAuthClient.buildAuthorizationRequest(
            accountEmail = "reader@example.com",
            selectAccount = false,
        )
        assertEquals("reader@example.com", refresh.account?.name)
        assertEquals(DriveAuthClient.ANDROID_GOOGLE_ACCOUNT_TYPE, refresh.account?.type)
        assertEquals(AuthorizationRequest.Prompt.NOT_SET, refresh.prompt)

        val switchRequest = PlayServicesDriveAuthClient.buildAuthorizationRequest(
            accountEmail = "reader@example.com",
            selectAccount = true,
        )
        assertNull(switchRequest.account)
        assertEquals(AuthorizationRequest.Prompt.SELECT_ACCOUNT, switchRequest.prompt)
    }

    @Test
    fun `scope validation requires both drive file and drive appdata`() {
        assertTrue(
            DriveAuthClient.hasAllRequiredScopes(
                listOf(DriveAuthClient.SCOPE_DRIVE_FILE, DriveAuthClient.SCOPE_DRIVE_APPDATA),
            ),
        )
        assertFalse(DriveAuthClient.hasAllRequiredScopes(listOf(DriveAuthClient.SCOPE_DRIVE_FILE)))
        assertFalse(DriveAuthClient.hasAllRequiredScopes(listOf(DriveAuthClient.SCOPE_DRIVE_APPDATA)))
        assertFalse(DriveAuthClient.hasAllRequiredScopes(emptyList()))
        assertFalse(DriveAuthClient.hasAllRequiredScopes(null))
    }

    @Test
    fun `mapAuthorizationResult handles authorized, missing scope, empty token, and needs resolution`() {
        val authorized = PlayServicesDriveAuthClient.mapAuthorizationResult(
            AuthorizationResult(
                null,
                "access-tok-123",
                null,
                listOf(DriveAuthClient.SCOPE_DRIVE_FILE, DriveAuthClient.SCOPE_DRIVE_APPDATA),
                null,
                null,
            ),
        )
        assertEquals(
            DriveAuthResult.Authorized(
                accessToken = "access-tok-123",
                grantedScopes = setOf(DriveAuthClient.SCOPE_DRIVE_FILE, DriveAuthClient.SCOPE_DRIVE_APPDATA),
            ),
            authorized,
        )

        val missingScope = PlayServicesDriveAuthClient.mapAuthorizationResult(
            AuthorizationResult(
                null,
                "access-tok-123",
                null,
                listOf(DriveAuthClient.SCOPE_DRIVE_FILE),
                null,
                null,
            ),
        )
        assertTrue(missingScope is DriveAuthResult.Failed && missingScope.requiresReconnect)

        val emptyToken = PlayServicesDriveAuthClient.mapAuthorizationResult(
            AuthorizationResult(
                null,
                "",
                null,
                listOf(DriveAuthClient.SCOPE_DRIVE_FILE, DriveAuthClient.SCOPE_DRIVE_APPDATA),
                null,
                null,
            ),
        )
        assertTrue(emptyToken is DriveAuthResult.Failed && emptyToken.requiresReconnect)
    }

    @Test
    fun `mapApiException distinguishes reconnect-required status codes from transient failures`() {
        val signInRequired = PlayServicesDriveAuthClient.mapApiException(
            ApiException(Status(CommonStatusCodes.SIGN_IN_REQUIRED)),
        )
        assertTrue(signInRequired is DriveAuthResult.Failed && signInRequired.requiresReconnect)

        val networkError = PlayServicesDriveAuthClient.mapApiException(
            ApiException(Status(CommonStatusCodes.NETWORK_ERROR)),
        )
        assertTrue(networkError is DriveAuthResult.Failed && !networkError.requiresReconnect)
    }
}
