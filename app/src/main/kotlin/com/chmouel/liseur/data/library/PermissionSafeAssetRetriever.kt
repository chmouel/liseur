package com.chmouel.liseur.data.library

import android.content.ContentResolver
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.asset.DefaultArchiveOpener
import org.readium.r2.shared.util.asset.DefaultFormatSniffer
import org.readium.r2.shared.util.content.ContentResolverError
import org.readium.r2.shared.util.content.ContentResourceFactory
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.file.FileResourceFactory
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpResourceFactory
import org.readium.r2.shared.util.resource.CompositeResourceFactory
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.ResourceFactory

/**
 * Readium's default [AssetRetriever], except that a content:// book we
 * are no longer allowed to read fails like any other unreadable file.
 *
 * Readium 3.4.0 opens the stream of a content URI outside the code that
 * turns a SecurityException into a read error. A grant lost after a
 * reinstall, or a provider app that went away, then throws out of
 * `retrieve()` and out of every later read of the open book, and the
 * caller's `getOrElse` never sees it. Reported here as
 * [ContentResolverError.Forbidden], the error Readium itself gives when
 * it does catch it.
 */
fun permissionSafeAssetRetriever(
    contentResolver: ContentResolver,
    httpClient: HttpClient,
): AssetRetriever = AssetRetriever(
    CompositeResourceFactory(
        FileResourceFactory(),
        PermissionSafeContentResourceFactory(contentResolver),
        HttpResourceFactory(httpClient),
    ),
    DefaultArchiveOpener(),
    DefaultFormatSniffer(),
)

private class PermissionSafeContentResourceFactory(
    contentResolver: ContentResolver,
) : ResourceFactory {
    private val content = ContentResourceFactory(contentResolver)

    override suspend fun create(url: AbsoluteUrl): Try<Resource, ResourceFactory.Error> =
        content.create(url).map { PermissionSafeResource(it) }
}

private class PermissionSafeResource(private val resource: Resource) : Resource by resource {
    override suspend fun properties() = forbiddenAsReadError { resource.properties() }
    override suspend fun length() = forbiddenAsReadError { resource.length() }
    override suspend fun read(range: LongRange?) = forbiddenAsReadError { resource.read(range) }
}

private inline fun <T> forbiddenAsReadError(read: () -> Try<T, ReadError>): Try<T, ReadError> =
    try {
        read()
    } catch (e: SecurityException) {
        Try.failure(ReadError.Access(ContentResolverError.Forbidden(e)))
    }
