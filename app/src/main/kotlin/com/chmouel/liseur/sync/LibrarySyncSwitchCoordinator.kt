package com.chmouel.liseur.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes automatic library-sync backend switches across the main
 * `RemoteServer` (`id = 1`) and any flavor-registered [LibrarySyncBackend].
 *
 * The two sides persist state in separate databases, so without a shared
 * lock an overlapping server publish and external-backend activation could
 * both inspect/clear the other before either commits, leaving both active.
 */
class LibrarySyncSwitchCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withSwitchLock(block: suspend () -> T): T = mutex.withLock { block() }
}
