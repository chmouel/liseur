package com.chmouel.liseur

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.chmouel.liseur.data.calibre.CalibreAuthInterceptor
import com.chmouel.liseur.sync.PositionSyncWorker

class LiseurApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PositionSyncWorker.syncNow(this)
        PositionSyncWorker.schedulePeriodic(this)
    }

    /**
     * Covers come straight from calibre-web, which wants the login on
     * every request, and are kept on disk so the library scrolls without
     * going back to the server each time.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            CalibreAuthInterceptor.imageLoaderClient(
                                container.calibreAccount::credentialsForUrl,
                            )
                        },
                    ),
                )
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("covers"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
