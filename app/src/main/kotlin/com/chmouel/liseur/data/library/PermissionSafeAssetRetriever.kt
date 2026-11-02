package com.chmouel.liseur.data.library

import android.content.ContentResolver
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
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
 * Readium's default [AssetRetriever], except that a content:// book the
 * provider cannot serve fails like any other unreadable file.
 *
 * Readium 3.4.0 opens the stream of a content URI outside the code that
 * turns a provider exception into a read error. A grant lost after a
 * reinstall, a provider that went away, or one that answers `openFile`
 * with an `IllegalStateException`, then throws out of `retrieve()` and
 * out of every later read of the open book, and the caller's `getOrElse`
 * never sees it. Reported here with the [ContentResolverError] Readium
 * itself gives when it does catch the same failure, so the reader shows
 * an open failure instead of dying.
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
    override suspend fun properties() = providerFailureAsReadError { resource.properties() }
    override suspend fun length() = providerFailureAsReadError { resource.length() }
    override suspend fun read(range: LongRange?) = providerFailureAsReadError { resource.read(range) }
}

private inline fun <T> providerFailureAsReadError(read: () -> Try<T, ReadError>): Try<T, ReadError> =
    try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        Try.failure(ReadError.Access(ContentResolverError.Forbidden(e)))
    } catch (e: FileNotFoundException) {
        Try.failure(ReadError.Access(ContentResolverError.FileNotFound(e)))
    } catch (e: Exception) {
        // A provider that crashed, or handed back a broken descriptor,
        // says so with an exception of its own choosing.
        Try.failure(ReadError.Access(ContentResolverError.NotAvailable(e)))
    }
